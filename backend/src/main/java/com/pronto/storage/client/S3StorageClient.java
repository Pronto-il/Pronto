package com.pronto.storage.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Real AWS S3 implementation ({@code pronto.storage.mode=s3}), per
 * {@code docs/architecture/api-contract-issues.md} §3.2. Uses AWS SDK v2 and the default
 * credential provider chain ({@code AWS_ACCESS_KEY_ID}/{@code AWS_SECRET_ACCESS_KEY} env
 * vars, an EC2/ECS instance role, etc. — never a hardcoded key). Activated by setting
 * {@code pronto.storage.mode=s3} plus real credentials/bucket/region.
 *
 * <p>The bucket blocks all public access (default SSE-S3 encryption, no public-read
 * policy), so {@link #resolveUrl} does NOT return a raw S3 URL — that would always 403.
 * Instead, same as {@link LocalDiskStorageClient}, it points at this backend's own
 * {@code GET /api/storage/images/**} retrieval endpoint (§2.4), built from the shared
 * {@code pronto.storage.public-base-url}. The controller/service layer downloads the bytes
 * from S3 via {@link #download} and streams them back to the caller — i.e. every image
 * fetch is backend-proxied, never a direct-to-S3 redirect or a pre-signed URL (a deliberate
 * decision, not a placeholder pending one).
 */
@Component
@ConditionalOnProperty(prefix = "pronto.storage", name = "mode", havingValue = "s3")
public class S3StorageClient implements StorageClient {

    private final S3Client s3Client;
    private final String bucket;
    private final String publicBaseUrl;

    public S3StorageClient(
            @Value("${pronto.storage.bucket}") String bucket,
            @Value("${pronto.storage.region}") String region,
            @Value("${pronto.storage.public-base-url}") String publicBaseUrl) {
        this.bucket = bucket;
        this.publicBaseUrl = publicBaseUrl.replaceAll("/+$", "");
        this.s3Client = S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    @Override
    public StoredObject upload(String key, byte[] content, String contentType) {
        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .contentType(contentType)
                    .build();
            s3Client.putObject(request, RequestBody.fromBytes(content));
            return new StoredObject(key, resolveUrl(key), contentType, content.length);
        } catch (SdkException e) {
            throw new StorageException("Failed to upload object to S3: " + key, e);
        }
    }

    @Override
    public byte[] download(String key) {
        try {
            GetObjectRequest request = GetObjectRequest.builder().bucket(bucket).key(key).build();
            return s3Client.getObjectAsBytes(request).asByteArray();
        } catch (SdkException e) {
            throw new StorageException("Failed to download object from S3: " + key, e);
        }
    }

    @Override
    public boolean exists(String key) {
        try {
            s3Client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (SdkException e) {
            throw new StorageException("Failed to check existence of S3 object: " + key, e);
        }
    }

    @Override
    public String resolveUrl(String key) {
        return publicBaseUrl + "/api/storage/images/" + key;
    }
}
