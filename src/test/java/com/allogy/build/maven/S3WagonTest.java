/*
 * Copyright (c) 2013 Allogy Interactive.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.allogy.build.maven;

import org.apache.maven.wagon.ResourceDoesNotExistException;
import org.apache.maven.wagon.TransferFailedException;
import org.apache.maven.wagon.authorization.AuthorizationException;
import org.apache.maven.wagon.events.TransferEvent;
import org.apache.maven.wagon.events.TransferListener;
import org.apache.maven.wagon.repository.Repository;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.ArgumentCaptor;

import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class S3WagonTest
{
    private static final String URL = "s3://my-bucket/base/path";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private S3Client s3;

    @Before
    public void setUp()
    {
        s3 = mock(S3Client.class);
    }

    /** Test seam: inject the mock S3 client instead of the default-chain one. */
    private static final class TestableS3Wagon extends S3Wagon
    {
        private final S3Client injected;

        TestableS3Wagon(S3Client injected)
        {
            this.injected = injected;
        }

        @Override
        protected S3Client createS3Client()
        {
            return injected;
        }
    }

    private TestableS3Wagon connect(String url) throws Exception
    {
        TestableS3Wagon wagon = new TestableS3Wagon(s3);
        wagon.connect(new Repository("test", url));
        return wagon;
    }

    private static S3Exception s3Exception(int status, String errorCode)
    {
        return (S3Exception) S3Exception.builder()
                .statusCode(status)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode(errorCode).build())
                .message(errorCode)
                .build();
    }

    // --- toS3Key -------------------------------------------------------------

    @Test
    public void toS3Key_prepends_basedir_and_strips_leading_slash() throws Exception
    {
        TestableS3Wagon wagon = connect(URL);
        assertThat(wagon.toS3Key("com/foo/bar-1.0.jar"), is("base/path/com/foo/bar-1.0.jar"));
    }

    @Test
    public void toS3Key_handles_leading_slash_on_resource() throws Exception
    {
        TestableS3Wagon wagon = connect(URL);
        assertThat(wagon.toS3Key("/com/foo/bar.jar"), is("base/path/com/foo/bar.jar"));
    }

    @Test
    public void toS3Key_collapses_duplicate_slashes() throws Exception
    {
        TestableS3Wagon wagon = connect(URL);
        assertThat(wagon.toS3Key("com//foo///bar.jar"), is("base/path/com/foo/bar.jar"));
    }

    @Test
    public void toS3Key_normalizes_backslashes() throws Exception
    {
        TestableS3Wagon wagon = connect(URL);
        assertThat(wagon.toS3Key("com\\foo\\bar.jar"), is("base/path/com/foo/bar.jar"));
    }

    @Test
    public void toS3Key_with_bucket_root_basedir() throws Exception
    {
        TestableS3Wagon wagon = connect("s3://my-bucket");
        assertThat(wagon.toS3Key("com/foo/bar.jar"), is("com/foo/bar.jar"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void toS3Key_rejects_parent_traversal_escaping_prefix() throws Exception
    {
        TestableS3Wagon wagon = connect(URL);
        wagon.toS3Key("../../other/secret.jar");
    }

    @Test(expected = IllegalArgumentException.class)
    public void toS3Key_rejects_embedded_parent_traversal() throws Exception
    {
        TestableS3Wagon wagon = connect(URL);
        wagon.toS3Key("com/foo/../../../../secret.jar");
    }

    @Test(expected = IllegalArgumentException.class)
    public void toS3Key_rejects_backslash_parent_traversal() throws Exception
    {
        // Backslashes are normalized to '/' first, so a Windows-style traversal
        // must be caught just the same.
        TestableS3Wagon wagon = connect(URL);
        wagon.toS3Key("..\\..\\secret.jar");
    }

    @Test
    public void toS3Key_allows_double_dot_inside_a_filename() throws Exception
    {
        // ".." must only be rejected as a whole path segment; a filename that
        // merely contains ".." is a legitimate key and must pass through.
        TestableS3Wagon wagon = connect(URL);
        assertThat(wagon.toS3Key("com/foo/bar..baz-1.0.jar"), is("base/path/com/foo/bar..baz-1.0.jar"));
    }

    // --- put -----------------------------------------------------------------

    @Test
    public void put_omits_acl_and_maps_key() throws Exception
    {
        TestableS3Wagon wagon = connect(URL);
        File source = tmp.newFile("artifact.jar");
        Files.write(source.toPath(), "payload".getBytes(StandardCharsets.UTF_8));

        wagon.put(source, "com/foo/artifact-1.0.jar");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<PutObjectRequest.Builder>> captor = ArgumentCaptor.forClass(Consumer.class);
        verify(s3).putObject(captor.capture(), any(RequestBody.class));

        PutObjectRequest.Builder builder = PutObjectRequest.builder();
        captor.getValue().accept(builder);
        PutObjectRequest request = builder.build();

        assertThat(request.acl(), nullValue());
        assertThat(request.aclAsString(), nullValue());
        assertThat(request.bucket(), is("my-bucket"));
        assertThat(request.key(), is("base/path/com/foo/artifact-1.0.jar"));
    }

    @Test(expected = AuthorizationException.class)
    public void put_maps_403_to_AuthorizationException() throws Exception
    {
        TestableS3Wagon wagon = connect(URL);
        File source = tmp.newFile("artifact.jar");
        when(s3.putObject(any(Consumer.class), any(RequestBody.class)))
                .thenThrow(s3Exception(403, "AccessDenied"));

        wagon.put(source, "com/foo/artifact-1.0.jar");
    }

    @Test
    public void put_fires_transfer_progress_for_uploaded_bytes() throws Exception
    {
        TestableS3Wagon wagon = connect(URL);
        byte[] payload = "the-uploaded-artifact-bytes".getBytes(StandardCharsets.UTF_8);
        File source = tmp.newFile("artifact.jar");
        Files.write(source.toPath(), payload);

        // The real S3 client drains the request body; the mock must do the same
        // so the progress-firing stream is actually read.
        when(s3.putObject(any(Consumer.class), any(RequestBody.class))).thenAnswer(invocation -> {
            RequestBody body = invocation.getArgument(1);
            try (InputStream in = body.contentStreamProvider().newStream())
            {
                byte[] buffer = new byte[8];
                while (in.read(buffer) != -1)
                {
                    // discard; reading is what drives fireTransferProgress
                }
            }
            return null;
        });

        AtomicLong progressBytes = new AtomicLong();
        wagon.addTransferListener(new ProgressRecordingListener(progressBytes));

        wagon.put(source, "com/foo/artifact-1.0.jar");

        assertThat(progressBytes.get(), is((long) payload.length));
    }

    /** Sums the byte counts reported to {@code transferProgress}. */
    private static final class ProgressRecordingListener implements TransferListener
    {
        private final AtomicLong total;

        ProgressRecordingListener(AtomicLong total)
        {
            this.total = total;
        }

        @Override
        public void transferProgress(TransferEvent transferEvent, byte[] buffer, int length)
        {
            total.addAndGet(length);
        }

        @Override public void transferInitiated(TransferEvent transferEvent) { }
        @Override public void transferStarted(TransferEvent transferEvent) { }
        @Override public void transferCompleted(TransferEvent transferEvent) { }
        @Override public void transferError(TransferEvent transferEvent) { }
        @Override public void debug(String message) { }
    }

    // --- resourceExists ------------------------------------------------------

    @Test
    public void resourceExists_true_when_head_succeeds() throws Exception
    {
        TestableS3Wagon wagon = connect(URL);
        when(s3.headObject(any(Consumer.class))).thenReturn(HeadObjectResponse.builder().build());

        assertThat(wagon.resourceExists("com/foo/bar.jar"), is(true));
    }

    @Test
    public void resourceExists_false_on_typed_NoSuchKey() throws Exception
    {
        TestableS3Wagon wagon = connect(URL);
        when(s3.headObject(any(Consumer.class)))
                .thenThrow(NoSuchKeyException.builder().statusCode(404).message("NoSuchKey").build());

        assertThat(wagon.resourceExists("com/foo/bar.jar"), is(false));
    }

    @Test
    public void resourceExists_false_on_bare_404() throws Exception
    {
        // Real S3 returns a bare 404 S3Exception on HEAD, not a typed NoSuchKeyException.
        TestableS3Wagon wagon = connect(URL);
        when(s3.headObject(any(Consumer.class))).thenThrow(s3Exception(404, null));

        assertThat(wagon.resourceExists("com/foo/bar.jar"), is(false));
    }

    @Test(expected = AuthorizationException.class)
    public void resourceExists_403_maps_to_AuthorizationException() throws Exception
    {
        TestableS3Wagon wagon = connect(URL);
        when(s3.headObject(any(Consumer.class))).thenThrow(s3Exception(403, "AccessDenied"));

        wagon.resourceExists("com/foo/bar.jar");
    }

    // --- getFileList ---------------------------------------------------------

    @Test
    public void getFileList_returns_relative_files_and_trailing_slash_dirs() throws Exception
    {
        TestableS3Wagon wagon = connect(URL);
        ListObjectsV2Response response = ListObjectsV2Response.builder()
                .commonPrefixes(CommonPrefix.builder().prefix("base/path/com/1.0/").build())
                .contents(
                        // the prefix marker itself must be filtered out
                        S3Object.builder().key("base/path/com/").build(),
                        S3Object.builder().key("base/path/com/maven-metadata.xml").build())
                .isTruncated(false)
                .build();
        when(s3.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(response);

        List<String> files = wagon.getFileList("com");

        assertThat(files, hasItem("1.0/"));
        assertThat(files, hasItem("maven-metadata.xml"));
        assertThat(files, not(hasItem("")));
    }

    @Test
    public void getFileList_paginates_truncated_results() throws Exception
    {
        TestableS3Wagon wagon = connect(URL);
        ListObjectsV2Response page1 = ListObjectsV2Response.builder()
                .contents(S3Object.builder().key("base/path/com/a.jar").build())
                .isTruncated(true)
                .nextContinuationToken("TOKEN")
                .build();
        ListObjectsV2Response page2 = ListObjectsV2Response.builder()
                .contents(S3Object.builder().key("base/path/com/b.jar").build())
                .isTruncated(false)
                .build();
        when(s3.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(page1, page2);

        List<String> files = wagon.getFileList("com");

        assertThat(files, hasItem("a.jar"));
        assertThat(files, hasItem("b.jar"));
    }

    @Test(expected = ResourceDoesNotExistException.class)
    public void getFileList_missing_directory_throws_ResourceDoesNotExist() throws Exception
    {
        // No children and no directory marker: the prefix does not exist.
        TestableS3Wagon wagon = connect(URL);
        when(s3.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(ListObjectsV2Response.builder().isTruncated(false).build());

        wagon.getFileList("com");
    }

    @Test
    public void getFileList_existing_but_empty_directory_returns_empty_list() throws Exception
    {
        // A directory marker (zero-length object keyed exactly at the prefix,
        // with its trailing slash) proves the directory exists even with no
        // children. The Wagon contract says return an empty list here, NOT throw.
        TestableS3Wagon wagon = connect(URL);
        ListObjectsV2Response response = ListObjectsV2Response.builder()
                .contents(S3Object.builder().key("base/path/com/").build())
                .isTruncated(false)
                .build();
        when(s3.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(response);

        List<String> files = wagon.getFileList("com");

        assertThat(files.isEmpty(), is(true));
    }

    @Test
    public void getFileList_directory_marker_is_not_listed_as_a_child() throws Exception
    {
        // The marker proves existence but must not appear as a file entry, while
        // genuine children alongside it are still returned.
        TestableS3Wagon wagon = connect(URL);
        ListObjectsV2Response response = ListObjectsV2Response.builder()
                .contents(
                        S3Object.builder().key("base/path/com/").build(),
                        S3Object.builder().key("base/path/com/child.jar").build())
                .isTruncated(false)
                .build();
        when(s3.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(response);

        List<String> files = wagon.getFileList("com");

        assertThat(files, hasItem("child.jar"));
        assertThat(files, not(hasItem("")));
    }

    @Test
    public void getFileList_appends_trailing_slash_to_prefix() throws Exception
    {
        // The requested directory is listed without a trailing slash; the wagon
        // must query S3 with the slash-terminated prefix so the delimiter groups
        // the right level. Capture the request and assert the prefix.
        TestableS3Wagon wagon = connect(URL);
        ListObjectsV2Response response = ListObjectsV2Response.builder()
                .contents(S3Object.builder().key("base/path/com/a.jar").build())
                .isTruncated(false)
                .build();
        when(s3.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(response);

        wagon.getFileList("com");

        ArgumentCaptor<ListObjectsV2Request> captor = ArgumentCaptor.forClass(ListObjectsV2Request.class);
        verify(s3).listObjectsV2(captor.capture());
        assertThat(captor.getValue().prefix(), is("base/path/com/"));
    }

    // --- get -----------------------------------------------------------------

    @Test
    public void get_writes_destination_and_populates_resource_metadata() throws Exception
    {
        TestableS3Wagon wagon = connect(URL);
        byte[] body = "downloaded-bytes".getBytes(StandardCharsets.UTF_8);

        when(s3.getObject(any(Consumer.class), any(ResponseTransformer.class))).thenAnswer(invocation -> {
            ResponseTransformer<GetObjectResponse, GetObjectResponse> transformer = invocation.getArgument(1);
            GetObjectResponse response = GetObjectResponse.builder()
                    .contentLength((long) body.length)
                    .lastModified(Instant.ofEpochMilli(1_700_000_000_000L))
                    .build();
            return transformer.transform(response, AbortableInputStream.create(new ByteArrayInputStream(body)));
        });

        File dest = new File(tmp.getRoot(), "out/bar.jar");
        wagon.get("com/foo/bar.jar", dest);

        assertThat(dest.exists(), is(true));
        assertThat(new String(Files.readAllBytes(dest.toPath()), StandardCharsets.UTF_8), is("downloaded-bytes"));
        // no staging leftovers
        assertThat(dest.getParentFile().list().length, is(1));
    }

    @Test
    public void get_failure_preserves_existing_destination_and_cleans_stage() throws Exception
    {
        TestableS3Wagon wagon = connect(URL);
        File dest = tmp.newFile("existing.jar");
        Files.write(dest.toPath(), "ORIGINAL".getBytes(StandardCharsets.UTF_8));

        when(s3.getObject(any(Consumer.class), any(ResponseTransformer.class))).thenAnswer(invocation -> {
            // Write the stage file (simulating a partial transfer) then fail hard.
            ResponseTransformer<GetObjectResponse, GetObjectResponse> transformer = invocation.getArgument(1);
            transformer.transform(GetObjectResponse.builder().build(),
                    AbortableInputStream.create(new ByteArrayInputStream("PARTIAL".getBytes(StandardCharsets.UTF_8))));
            throw s3Exception(500, "InternalError");
        });

        try
        {
            wagon.get("com/foo/existing.jar", dest);
            throw new AssertionError("expected TransferFailedException");
        }
        catch (TransferFailedException expected)
        {
            // expected
        }

        assertThat(new String(Files.readAllBytes(dest.toPath()), StandardCharsets.UTF_8), is("ORIGINAL"));
        // only the preserved destination remains; the .part- stage was cleaned up
        assertThat(dest.getParentFile().list().length, is(1));
    }

    @Test
    public void get_midstream_failure_cleans_stage_and_leaves_no_destination() throws Exception
    {
        // A failure DURING streaming (not before it) surfaces from
        // AbstractWagon.getTransfer as a TransferFailedException, which bypasses
        // both catch blocks in get(); the staging cleanup therefore has to run
        // in a finally. This reproduces the leak: deliver a few bytes so the
        // stage file is opened and written, then throw mid-stream.
        TestableS3Wagon wagon = connect(URL);

        InputStream failing = new InputStream()
        {
            private int reads = 0;

            @Override
            public int read()
            {
                return 'X';
            }

            @Override
            public int read(byte[] b, int off, int len) throws java.io.IOException
            {
                // First bulk read delivers data (creating/writing the stage),
                // the next one fails - an interrupted download.
                if (reads++ == 0)
                {
                    int n = Math.min(4, len);
                    for (int i = 0; i < n; i++)
                    {
                        b[off + i] = 'X';
                    }
                    return n;
                }
                throw new java.io.IOException("simulated mid-stream read failure");
            }
        };

        when(s3.getObject(any(Consumer.class), any(ResponseTransformer.class))).thenAnswer(invocation -> {
            ResponseTransformer<GetObjectResponse, GetObjectResponse> transformer = invocation.getArgument(1);
            return transformer.transform(
                    GetObjectResponse.builder().contentLength(1_000L).build(),
                    AbortableInputStream.create(failing));
        });

        File dest = new File(tmp.getRoot(), "fresh/interrupted.jar");
        try
        {
            wagon.get("com/foo/interrupted.jar", dest);
            throw new AssertionError("expected TransferFailedException");
        }
        catch (TransferFailedException expected)
        {
            // expected: getTransfer wraps the mid-stream IOException
        }

        assertThat(dest.exists(), is(false));
        // the .part-* stage must have been cleaned up, leaving the dir empty
        assertThat(dest.getParentFile().list().length, is(0));
    }

    @Test(expected = ResourceDoesNotExistException.class)
    public void get_missing_key_throws_ResourceDoesNotExist() throws Exception
    {
        TestableS3Wagon wagon = connect(URL);
        when(s3.getObject(any(Consumer.class), any(ResponseTransformer.class)))
                .thenThrow(NoSuchKeyException.builder().statusCode(404).message("NoSuchKey").build());

        wagon.get("com/foo/missing.jar", new File(tmp.getRoot(), "missing.jar"));
    }

    // --- getIfNewer ----------------------------------------------------------

    @Test
    public void getIfNewer_downloads_when_remote_is_newer() throws Exception
    {
        TestableS3Wagon wagon = connect(URL);
        when(s3.headObject(any(Consumer.class)))
                .thenReturn(HeadObjectResponse.builder().lastModified(Instant.ofEpochMilli(2_000L)).build());
        byte[] body = "newer".getBytes(StandardCharsets.UTF_8);
        when(s3.getObject(any(Consumer.class), any(ResponseTransformer.class))).thenAnswer(invocation -> {
            ResponseTransformer<GetObjectResponse, GetObjectResponse> transformer = invocation.getArgument(1);
            return transformer.transform(GetObjectResponse.builder().contentLength((long) body.length).build(),
                    AbortableInputStream.create(new ByteArrayInputStream(body)));
        });

        File dest = new File(tmp.getRoot(), "newer.jar");
        boolean downloaded = wagon.getIfNewer("com/foo/newer.jar", dest, 1_000L);

        assertThat(downloaded, is(true));
        assertThat(dest.exists(), is(true));
    }

    @Test
    public void getIfNewer_skips_when_remote_is_not_newer() throws Exception
    {
        TestableS3Wagon wagon = connect(URL);
        when(s3.headObject(any(Consumer.class)))
                .thenReturn(HeadObjectResponse.builder().lastModified(Instant.ofEpochMilli(1_000L)).build());

        File dest = new File(tmp.getRoot(), "older.jar");
        boolean downloaded = wagon.getIfNewer("com/foo/older.jar", dest, 1_000L);

        assertThat(downloaded, is(false));
        assertThat(dest.exists(), is(false));
        verify(s3, never()).getObject(any(Consumer.class), any(ResponseTransformer.class));
    }
}
