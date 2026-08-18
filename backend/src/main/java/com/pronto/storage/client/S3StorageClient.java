package com.pronto.storage.client;

import jakarta.annotation.PreDestroy;
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
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.time.Duration;

/**
 * Real AWS S3 implementation ({@code pronto.storage.mode=s3}), per
 * {@code docs/architecture/api-contract-issues.md} §3.2. Uses AWS SDK v2 and the default
 * credential provider chain ({@code AWS_ACCESS_KEY_ID}/{@code AWS_SECRET_ACCESS_KEY} env
 * vars, an EC2/ECS instance role, etc. — never a hardcoded key). Activated by setting
 * {@code pronto.storage.mode=s3} plus real credentials/bucket/region.
 *
 * <p>The bucket blocks all public access (default SSE-S3 encryption, no public-read policy),
 * so {@link #presignUrl} does NOT return a raw, unsigned S3 URL — that would always 403.
 * Instead it uses {@link S3Presigner}/{@link GetObjectPresignRequest} to mint a real,
 * time-limited AWS presigned GET URL that points directly at S3.
 *
 * <p><b>Deliberate reversal of an earlier decision — not a silent contradiction.</b> This
 * class previously stated (and implemented) that "every image fetch is backend-proxied, never
 * a direct-to-S3 redirect or a pre-signed URL (a deliberate decision, not a placeholder
 * pending one)" — the controller/service layer would download bytes from S3 via
 * {@link #download} and stream them back through this backend's own
 * {@code GET /api/storage/images/**}. That decision is explicitly reversed by backend MS9
 * ({@code docs/architecture/backend-ms9-presigned-image-urls-design.md} §5), because
 * backend-proxying required every {@code <img src>} consumer to attach a JWT
 * {@code Authorization} header — something a plain HTML {@code <img>} tag cannot do — which
 * made every image request fail with {@code net::ERR_BLOCKED_BY_ORB}. Presigned URLs replace
 * backend-proxying for both storage modes; the private bucket stays private (this class never
 * makes the bucket or its objects publicly readable), authorization simply moves earlier —
 * decided once, by {@code storage.service.StorageService}, at the moment a presigned URL is
 * minted, rather than re-checked on every byte-streaming request.
 */
@Component
@ConditionalOnProperty(prefix = "pronto.storage", name = "mode", havingValue = "s3")
public class S3StorageClient implements StorageClient {

    private final S3Client s3Client;
    private final S3Presigner presigner;
    private final String bucket;
    private final Duration defaultPresignedUrlTtl;

    public S3StorageClient(
            @Value("${pronto.storage.bucket}") String bucket,
            @Value("${pronto.storage.region}") String region,
            @Value("${pronto.storage.presigned-url-ttl-seconds}") long presignedUrlTtlSeconds) {
        this.bucket = bucket;
        this.defaultPresignedUrlTtl = Duration.ofSeconds(presignedUrlTtlSeconds);
        Region awsRegion = Region.of(region);
        this.s3Client = S3Client.builder()
                .region(awsRegion)
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
        this.presigner = S3Presigner.builder()
                .region(awsRegion)
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
            return new StoredObject(key, presignUrl(key, defaultPresignedUrlTtl), contentType, content.length);
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
    public String presignUrl(String key, Duration expiry) {
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(expiry)
                .getObjectRequest(getObjectRequest)
                .build();
        return presigner.presignGetObject(presignRequest).url().toString();
    }

    /** Resource hygiene — releases the presigner's resources on application shutdown. */
    @PreDestroy
    public void close() {
        presigner.close();
    }
}
