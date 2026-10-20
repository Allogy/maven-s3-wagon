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

import org.apache.maven.wagon.AbstractWagon;
import org.apache.maven.wagon.ConnectionException;
import org.apache.maven.wagon.ResourceDoesNotExistException;
import org.apache.maven.wagon.TransferFailedException;
import org.apache.maven.wagon.authentication.AuthenticationException;
import org.apache.maven.wagon.authorization.AuthorizationException;
import org.apache.maven.wagon.events.TransferEvent;
import org.apache.maven.wagon.resource.Resource;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Maven {@link org.apache.maven.wagon.Wagon} backed by Amazon S3 via AWS SDK v2.
 *
 * <p>Credentials are resolved exclusively through the AWS default credential
 * chain ({@link DefaultCredentialsProvider}), which covers environment
 * variables, SSO, profile {@code role_arn}/{@code source_profile} assumed
 * roles, web-identity/IRSA, ECS, and EC2 instance profiles. The region is also
 * resolved from the default chain ({@code AWS_REGION} / profile / IMDS), so the
 * same artifact works unchanged in commercial and GovCloud regions.</p>
 *
 * <p>The {@code s3://bucket/base/path} repository URL maps the host to the S3
 * bucket and the base directory to a key prefix.</p>
 */
public class S3Wagon extends AbstractWagon
{
    private S3Client s3;

    @Override
    protected void openConnectionInternal() throws ConnectionException, AuthenticationException
    {
        this.s3 = createS3Client();
    }

    @Override
    protected void closeConnection() throws ConnectionException
    {
        if (s3 != null)
        {
            s3.close();
            s3 = null;
        }
    }

    /**
     * Builds the production {@link S3Client}. Overridable test seam.
     *
     * <p>No region or endpoint is set: both come from the AWS default chain.
     * The HTTP client is pinned explicitly so the transport does not depend on
     * ServiceLoader discovery surviving the shade relocation.</p>
     */
    protected S3Client createS3Client()
    {
        return S3Client.builder()
                .credentialsProvider(DefaultCredentialsProvider.create())
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }

    @Override
    public void get(String resourceName, File destination)
            throws TransferFailedException, ResourceDoesNotExistException, AuthorizationException
    {
        Resource resource = new Resource(resourceName);
        fireGetInitiated(resource, destination);

        String key = toS3Key(resourceName);

        // Stage into a unique, non-existent sibling path and move it onto the
        // destination only after a complete download, so a failed transfer
        // leaves any pre-existing destination untouched. The body is streamed
        // through AbstractWagon.getTransfer, which runs the read loop and fires
        // fireTransferProgress for every chunk: maven-resolver computes the
        // download checksum from those progress events (not from the file on
        // disk), so writing straight to a file would make it checksum an empty
        // stream and fail under a strict checksum policy.
        Path stage = destination.toPath().resolveSibling(destination.getName() + ".part-" + UUID.randomUUID());

        createParentDirectories(destination);
        fireGetStarted(resource, destination);
        try
        {
            ResponseInputStream<GetObjectResponse> body =
                    s3.getObject(b -> b.bucket(bucket()).key(key), ResponseTransformer.toInputStream());

            GetObjectResponse response = body.response();
            if (response.contentLength() != null)
            {
                // sizes the transfer buffer and lets listeners report progress
                resource.setContentLength(response.contentLength());
            }
            if (response.lastModified() != null)
            {
                resource.setLastModified(response.lastModified().toEpochMilli());
            }

            // getTransfer runs the read loop firing fireTransferProgress for each
            // chunk and closes the body; we own the staging OutputStream so the
            // get lifecycle events stay keyed to the real destination.
            try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(stage)))
            {
                getTransfer(resource, out, body);
            }

            moveIntoPlace(stage, destination);
        }
        catch (SdkException e)
        {
            fireTransferError(resource, e, TransferEvent.REQUEST_GET);
            throw translateGet(key, e);
        }
        catch (IOException e)
        {
            fireTransferError(resource, e, TransferEvent.REQUEST_GET);
            throw new TransferFailedException("Failed to store " + key + " to " + destination, e);
        }
        finally
        {
            // A mid-stream read failure surfaces from getTransfer as a
            // TransferFailedException, which bypasses both catches above, so
            // staging cleanup must live here to guarantee no .part-* is left
            // behind on any failure path. After a successful moveIntoPlace the
            // stage no longer exists, so this is a harmless no-op on success.
            deleteQuietly(stage);
        }

        fireGetCompleted(resource, destination);
    }

    @Override
    public boolean getIfNewer(String resourceName, File destination, long timestamp)
            throws TransferFailedException, ResourceDoesNotExistException, AuthorizationException
    {
        String key = toS3Key(resourceName);

        Instant lastModified;
        try
        {
            HeadObjectResponse head = s3.headObject(b -> b.bucket(bucket()).key(key));
            lastModified = head.lastModified();
        }
        catch (SdkException e)
        {
            throw translateGet(key, e);
        }

        // S3 lastModified is second-granular; use a strict greater-than so an
        // object stamped at exactly `timestamp` is treated as not newer.
        if (lastModified != null && lastModified.toEpochMilli() > timestamp)
        {
            get(resourceName, destination);
            return true;
        }
        return false;
    }

    @Override
    public void put(File source, String destination)
            throws TransferFailedException, ResourceDoesNotExistException, AuthorizationException
    {
        Resource resource = new Resource(destination);
        resource.setContentLength(source.length());
        resource.setLastModified(source.lastModified());

        firePutInitiated(resource, source);

        String key = toS3Key(destination);

        firePutStarted(resource, source);
        try
        {
            // No .acl(...): objects inherit bucket defaults, which is correct for
            // both ACL-disabled "Bucket owner enforced" and Block-Public-Access
            // buckets. The legacy forced-Private canned ACL is intentionally dropped.
            s3.putObject(b -> b.bucket(bucket()).key(key), RequestBody.fromFile(source));
        }
        catch (SdkException e)
        {
            fireTransferError(resource, e, TransferEvent.REQUEST_PUT);
            throw translatePut(key, e);
        }

        firePutCompleted(resource, source);
    }

    @Override
    public boolean resourceExists(String resourceName)
            throws TransferFailedException, AuthorizationException
    {
        String key = toS3Key(resourceName);
        try
        {
            s3.headObject(b -> b.bucket(bucket()).key(key));
            return true;
        }
        catch (SdkException e)
        {
            // Real S3 answers a missing key on HEAD with a bare 404 rather than a
            // typed NoSuchKeyException (which LocalStack does surface), so treat
            // both as "does not exist".
            if (isNotFound(e))
            {
                return false;
            }
            if (isAuthFailure(e))
            {
                throw new AuthorizationException("Access denied checking " + key, e);
            }
            throw new TransferFailedException("Failed to check existence of " + key, e);
        }
    }

    @Override
    public List<String> getFileList(String directory)
            throws TransferFailedException, ResourceDoesNotExistException, AuthorizationException
    {
        String prefix = toS3Key(directory);
        if (!prefix.isEmpty() && !prefix.endsWith("/"))
        {
            prefix = prefix + "/";
        }

        List<String> files = new ArrayList<>();
        String continuationToken = null;
        try
        {
            do
            {
                final String token = continuationToken;
                final String listPrefix = prefix;
                ListObjectsV2Request request = ListObjectsV2Request.builder()
                        .bucket(bucket())
                        .prefix(listPrefix)
                        .delimiter("/")
                        .continuationToken(token)
                        .build();

                ListObjectsV2Response response = s3.listObjectsV2(request);

                // CommonPrefixes are "directories"; return them with a trailing
                // slash, relative to the requested directory.
                for (CommonPrefix commonPrefix : response.commonPrefixes())
                {
                    files.add(relativize(commonPrefix.prefix(), listPrefix));
                }
                // Contents are files, relative to the requested directory. Skip a
                // key equal to the prefix itself (a zero-length "directory marker").
                for (S3Object object : response.contents())
                {
                    String relative = relativize(object.key(), listPrefix);
                    if (!relative.isEmpty())
                    {
                        files.add(relative);
                    }
                }

                continuationToken = Boolean.TRUE.equals(response.isTruncated())
                        ? response.nextContinuationToken()
                        : null;
            }
            while (continuationToken != null);
        }
        catch (SdkException e)
        {
            if (isAuthFailure(e))
            {
                throw new AuthorizationException("Access denied listing " + prefix, e);
            }
            throw new TransferFailedException("Failed to list " + prefix, e);
        }

        if (files.isEmpty())
        {
            throw new ResourceDoesNotExistException("No files found under " + prefix);
        }
        return files;
    }

    @Override
    public boolean supportsDirectoryCopy()
    {
        return false;
    }

    // --- helpers --------------------------------------------------------------

    private String bucket()
    {
        return getRepository().getHost();
    }

    /**
     * Maps a wagon resource name to an S3 object key by prepending the
     * repository base directory, normalizing separators, collapsing repeated
     * slashes, and stripping any leading slash.
     */
    String toS3Key(String resourceName)
    {
        String base = getRepository().getBasedir();
        String combined = (base == null ? "" : base) + "/" + (resourceName == null ? "" : resourceName);
        combined = combined.replace('\\', '/');
        combined = combined.replaceAll("/+", "/");
        if (combined.startsWith("/"))
        {
            combined = combined.substring(1);
        }
        return combined;
    }

    private static String relativize(String key, String prefix)
    {
        return key.startsWith(prefix) ? key.substring(prefix.length()) : key;
    }

    private void moveIntoPlace(Path stage, File destination) throws IOException
    {
        Path target = destination.toPath();
        try
        {
            Files.move(stage, target, StandardCopyOption.ATOMIC_MOVE);
        }
        catch (AtomicMoveNotSupportedException e)
        {
            Files.move(stage, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteQuietly(Path path)
    {
        try
        {
            Files.deleteIfExists(path);
        }
        catch (IOException ignored)
        {
            // best effort; the stage file never overwrites the destination
        }
    }

    private static boolean isNotFound(SdkException e)
    {
        if (e instanceof NoSuchKeyException)
        {
            return true;
        }
        if (e instanceof S3Exception)
        {
            S3Exception s3e = (S3Exception) e;
            if (s3e.statusCode() == 404)
            {
                return true;
            }
            String code = errorCode(s3e);
            return "NoSuchKey".equals(code) || "NoSuchBucket".equals(code);
        }
        return false;
    }

    private static boolean isAuthFailure(SdkException e)
    {
        if (e instanceof S3Exception)
        {
            S3Exception s3e = (S3Exception) e;
            int status = s3e.statusCode();
            if (status == 401 || status == 403)
            {
                return true;
            }
            String code = errorCode(s3e);
            return "AccessDenied".equals(code)
                    || "InvalidAccessKeyId".equals(code)
                    || "SignatureDoesNotMatch".equals(code)
                    || "ExpiredToken".equals(code)
                    || "InvalidToken".equals(code);
        }
        return false;
    }

    private static String errorCode(S3Exception e)
    {
        return e.awsErrorDetails() == null ? null : e.awsErrorDetails().errorCode();
    }

    private TransferFailedException translatePut(String key, SdkException e)
            throws AuthorizationException
    {
        if (isAuthFailure(e))
        {
            throw new AuthorizationException("Access denied writing " + key, e);
        }
        return new TransferFailedException("Failed to upload " + key, e);
    }

    private TransferFailedException translateGet(String key, SdkException e)
            throws ResourceDoesNotExistException, AuthorizationException
    {
        if (isNotFound(e))
        {
            throw new ResourceDoesNotExistException("Resource does not exist: " + key, e);
        }
        if (isAuthFailure(e))
        {
            throw new AuthorizationException("Access denied reading " + key, e);
        }
        return new TransferFailedException("Failed to download " + key, e);
    }
}
