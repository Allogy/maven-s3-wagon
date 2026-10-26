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
import org.apache.maven.wagon.repository.Repository;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.Rule;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

/**
 * LocalStack-backed integration tests exercising the full S3 round-trip through
 * the wagon. These map 1:1 to the CONUS-290 acceptance criteria for object,
 * metadata, checksum, and download behavior.
 *
 * <p>LocalStack cannot emulate STS AssumeRole, so the assumed-role acceptance
 * criterion is covered by a real-S3 role + CI-OIDC run, not by this test.</p>
 *
 * @author Ivan Cayabyab
 */
public class S3WagonIT
{
    private static final String BUCKET = "it-bucket";

    private static LocalStackContainer localstack;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @BeforeClass
    public static void startLocalStack()
    {
        localstack = new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.8"))
                .withServices(LocalStackContainer.Service.S3);
        localstack.start();

        try (S3Client admin = newClient())
        {
            admin.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        }
    }

    @AfterClass
    public static void stopLocalStack()
    {
        if (localstack != null)
        {
            localstack.stop();
        }
    }

    private static S3Client newClient()
    {
        return S3Client.builder()
                .endpointOverride(localstack.getEndpointOverride(LocalStackContainer.Service.S3))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(localstack.getAccessKey(), localstack.getSecretKey())))
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }

    /** Wagon that talks to LocalStack instead of the default credential/region chain. */
    private static final class LocalStackWagon extends S3Wagon
    {
        @Override
        protected S3Client createS3Client()
        {
            return newClient();
        }
    }

    private LocalStackWagon wagon;

    @Before
    public void connect() throws Exception
    {
        wagon = new LocalStackWagon();
        wagon.connect(new Repository("it", "s3://" + BUCKET + "/snapshots"));
    }

    private File file(String name, String content) throws Exception
    {
        File f = tmp.newFile(name);
        Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return f;
    }

    @Test
    public void put_then_get_round_trips_bytes() throws Exception
    {
        wagon.put(file("artifact.jar", "jar-bytes"), "com/foo/app/1.0.0/app-1.0.0.jar");

        File out = new File(tmp.getRoot(), "downloaded.jar");
        wagon.get("com/foo/app/1.0.0/app-1.0.0.jar", out);

        assertThat(new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8), is("jar-bytes"));
    }

    @Test
    public void put_transfers_checksums_and_pom_as_plain_objects() throws Exception
    {
        wagon.put(file("app.pom", "<project/>"), "com/foo/app/1.0.0/app-1.0.0.pom");
        wagon.put(file("app.jar.sha1", "abc123"), "com/foo/app/1.0.0/app-1.0.0.jar.sha1");
        wagon.put(file("app.jar.md5", "def456"), "com/foo/app/1.0.0/app-1.0.0.jar.md5");
        wagon.put(file("app.jar.asc", "-----BEGIN PGP-----"), "com/foo/app/1.0.0/app-1.0.0.jar.asc");

        assertThat(wagon.resourceExists("com/foo/app/1.0.0/app-1.0.0.pom"), is(true));
        assertThat(wagon.resourceExists("com/foo/app/1.0.0/app-1.0.0.jar.sha1"), is(true));
        assertThat(wagon.resourceExists("com/foo/app/1.0.0/app-1.0.0.jar.md5"), is(true));
        assertThat(wagon.resourceExists("com/foo/app/1.0.0/app-1.0.0.jar.asc"), is(true));
    }

    @Test
    public void snapshot_deploy_and_metadata_round_trip() throws Exception
    {
        wagon.put(file("snap.jar", "snap-1"), "com/foo/app/1.0.0-SNAPSHOT/app-1.0.0-20260101.000000-1.jar");
        wagon.put(file("meta.xml", "<metadata/>"), "com/foo/app/1.0.0-SNAPSHOT/maven-metadata.xml");

        File out = new File(tmp.getRoot(), "meta-out.xml");
        wagon.get("com/foo/app/1.0.0-SNAPSHOT/maven-metadata.xml", out);

        assertThat(new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8), is("<metadata/>"));
    }

    @Test
    public void getFileList_lists_versions_as_dirs_and_files() throws Exception
    {
        wagon.put(file("v1.jar", "a"), "com/foo/lib/1.0.0/lib-1.0.0.jar");
        wagon.put(file("v2.jar", "b"), "com/foo/lib/2.0.0/lib-2.0.0.jar");
        wagon.put(file("meta.xml", "<metadata/>"), "com/foo/lib/maven-metadata.xml");

        List<String> files = wagon.getFileList("com/foo/lib");

        assertThat(files, hasItem("1.0.0/"));
        assertThat(files, hasItem("2.0.0/"));
        assertThat(files, hasItem("maven-metadata.xml"));
    }

    @Test
    public void getIfNewer_skips_when_not_newer_and_downloads_when_newer() throws Exception
    {
        wagon.put(file("timed.jar", "payload"), "com/foo/timed/1.0.0/timed-1.0.0.jar");

        File out = new File(tmp.getRoot(), "timed.jar.out");
        // far-future timestamp: remote is not newer -> skip
        assertThat(wagon.getIfNewer("com/foo/timed/1.0.0/timed-1.0.0.jar", out, Long.MAX_VALUE), is(false));
        assertThat(out.exists(), is(false));

        // epoch timestamp: remote is newer -> download
        assertThat(wagon.getIfNewer("com/foo/timed/1.0.0/timed-1.0.0.jar", out, 0L), is(true));
        assertThat(out.exists(), is(true));
    }

    @Test
    public void resourceExists_false_for_missing_key() throws Exception
    {
        assertThat(wagon.resourceExists("com/foo/missing/1.0.0/missing-1.0.0.jar"), is(false));
    }

    @Test(expected = ResourceDoesNotExistException.class)
    public void get_missing_key_throws() throws Exception
    {
        wagon.get("com/foo/missing/1.0.0/missing-1.0.0.jar", new File(tmp.getRoot(), "missing.jar"));
    }
}
