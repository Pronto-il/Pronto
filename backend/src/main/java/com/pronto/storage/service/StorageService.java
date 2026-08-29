package com.pronto.storage.service;

import com.pronto.common.dto.FieldError;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.common.security.AuthenticatedUser;
import com.pronto.common.security.UploadOwner;
import com.pronto.storage.DocumentContentType;
import com.pronto.storage.ImageContentType;
import com.pronto.storage.ImageKeyUtils;
import com.pronto.storage.client.LocalHmacUrlSigner;
import com.pronto.storage.client.StorageClient;
import com.pronto.storage.client.StorageException;
import com.pronto.storage.client.StoredObject;
import com.pronto.storage.dto.ImageUploadResponse;
import com.pronto.storage.dto.PresignedImageUrlEntry;
import com.pronto.storage.dto.RetrievedImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code POST}/{@code GET /api/storage/images}, per
 * {@code docs/architecture/api-contract-issues.md} §2.3-2.4. Image retrieval was reworked in
 * backend MS9 ({@code docs/architecture/backend-ms9-presigned-image-urls-design.md}) from a
 * JWT-gated backend proxy to presigned/signed URLs — see {@link #getPresignedUrl},
 * {@link #getPresignedUrlAssumingCallerAuthorized}, {@link #retrieveBySignedUrl}.
 */
@Service
public class StorageService {

    private static final Logger log = LoggerFactory.getLogger(StorageService.class);

    /** §2.3's recommended 8 MB cap. Spring's multipart limit (application.yml) is the
     *  first line of defense (rejects earlier, before bytes are even fully read into this
     *  service); this is a defense-in-depth re-check against the same number. */
    static final long MAX_SIZE_BYTES = 8L * 1024 * 1024;

    /** §12.2's batch-size cap for {@link #getPresignedUrls} — comfortably above
     *  {@code PhotoUploader}'s own {@code maxCount} default of 6. */
    static final int MAX_BATCH_SIZE = 20;

    /**
     * The key namespace {@code auth.service.AuthService#register} writes a professional's
     * verification document into, and the only namespace
     * {@link #getVerificationDocumentUrlForOperator} will unlock.
     */
    private static final String VERIFICATION_DOCUMENT_PREFIX = "verification-documents/";

    private final StorageClient storageClient;
    private final Optional<LocalHmacUrlSigner> localHmacUrlSigner;
    private final Duration presignedUrlTtl;

    public StorageService(StorageClient storageClient,
                           Optional<LocalHmacUrlSigner> localHmacUrlSigner,
                           @Value("${pronto.storage.presigned-url-ttl-seconds}") long presignedUrlTtlSeconds) {
        this.storageClient = storageClient;
        this.localHmacUrlSigner = localHmacUrlSigner;
        this.presignedUrlTtl = Duration.ofSeconds(presignedUrlTtlSeconds);
    }

    /**
     * How long a URL minted by this service stays valid. Exposed so a caller can tell its own
     * client ({@code VerificationDocumentUrlResponse}) when to stop relying on one, rather than
     * duplicating the {@code pronto.storage.presigned-url-ttl-seconds} property.
     */
    public long getPresignedUrlTtlSeconds() {
        return presignedUrlTtl.toSeconds();
    }

    /**
     * The issue-photo upload, for either kind of owner. <b>One flow, one set of rules.</b>
     *
     * <p>Everything that decides whether an upload is allowed and what it becomes — the
     * {@code image/jpeg}/{@code png}/{@code webp} allow-list, the 8 MB cap, the
     * {@code .../issues/temp/{uuid}.{ext}} key template, the presigned URL the response carries and
     * its TTL — is shared verbatim between an authenticated customer and a guest. The single thing
     * {@code owner} changes is which owner segment the generated key is namespaced under, via
     * {@link ImageKeyUtils#issueTempKeyPrefix}. There is deliberately no guest-specific validator,
     * limit or policy anywhere in this package: a guest photo is an issue photo.
     */
    public ImageUploadResponse upload(UploadOwner owner, MultipartFile file) {
        ImageContentType type = validateAndResolveType(file);
        String key = ImageKeyUtils.issueTempKeyPrefix(owner) + UUID.randomUUID() + "." + type.extension();
        StoredObject stored = uploadWithKey(key, file);
        return new ImageUploadResponse(stored.key(), stored.url(), stored.contentType(), stored.sizeBytes());
    }

    /** Convenience overload for call sites that already hold an {@link AuthenticatedUser}. */
    public ImageUploadResponse upload(AuthenticatedUser caller, MultipartFile file) {
        return upload(UploadOwner.customer(caller.id()), file);
    }

    /**
     * Generic "validate type/size, delegate to {@link StorageClient#upload}, wrap exceptions"
     * logic, extracted so other packages needing their own image-upload endpoint (e.g.
     * {@code professionals.service.ProfessionalsService#uploadProfileImage}) don't duplicate
     * it. {@code key} is fully caller-supplied (including extension) — the caller resolves
     * its own {@link ImageContentType} first (via {@link #validateAndResolveType}, or its own
     * equivalent lookup) to build a key template appropriate to its own domain, then hands
     * the finished key here. {@link #upload} above is now a thin wrapper: it builds its own
     * {@code customers/{callerId}/issues/temp/{uuid}.{ext}} key and calls straight through to
     * this method — behavior for issue images is unchanged.
     */
    public StoredObject uploadWithKey(String key, MultipartFile file) {
        ImageContentType type = validateAndResolveType(file);
        byte[] content = readBytes(file);
        try {
            return storageClient.upload(key, content, type.contentType());
        } catch (StorageException e) {
            throw new ApiException(ErrorCode.STORAGE_SERVICE_ERROR, "Failed to store uploaded image.");
        }
    }

    /**
     * Document counterpart to {@link #uploadWithKey} — same "caller resolves its own
     * key template, hand the finished key here" contract, but validated against
     * {@link DocumentContentType} (PDF/image) instead of {@link ImageContentType}.
     * Used by {@code auth.service.AuthService} for a Professional registration's
     * required verification document (backend registration flow separation task §12).
     */
    public StoredObject uploadDocumentWithKey(String key, MultipartFile file) {
        DocumentContentType type = validateAndResolveDocumentType(file);
        byte[] content = readBytes(file);
        try {
            return storageClient.upload(key, content, type.contentType());
        } catch (StorageException e) {
            throw new ApiException(ErrorCode.STORAGE_SERVICE_ERROR, "Failed to store uploaded document.");
        }
    }

    /**
     * General-purpose presigned-URL path. {@code 403 FORBIDDEN} if {@code callerId} may not
     * view {@code key} — reuses the exact {@code isPubliclyReadable}/{@code belongsTo} check
     * the old {@code retrieve()} method had (see {@link #authorize}). Use for every call site
     * EXCEPT the one narrow exception documented on
     * {@link #getPresignedUrlAssumingCallerAuthorized}.
     */
    public String getPresignedUrl(UploadOwner owner, String key) {
        authorize(owner, key);
        return storageClient.presignUrl(key, presignedUrlTtl);
    }

    /** Convenience overload for the (still overwhelmingly common) authenticated-customer case. */
    public String getPresignedUrl(Long callerId, String key) {
        return getPresignedUrl(UploadOwner.customer(callerId), key);
    }

    /** Convenience overload for call sites that already hold an {@link AuthenticatedUser}. */
    public String getPresignedUrl(AuthenticatedUser caller, String key) {
        return getPresignedUrl(caller.id(), key);
    }

    /**
     * Promotes a guest-owned object onto {@code newOwnerId}'s own namespace, returning the new key.
     *
     * <p><b>What this is for.</b> A guest attaches photos, then registers, then commits a booking.
     * At that commit an account finally exists, and the images have to become that account's — not
     * by a database pointer but by living where every existing read path already knows to look for
     * a customer's image. Without this, {@code issue_images} would permanently hold
     * {@code guests/{uuid}/...} keys whose owner is a session that expires within a day, and
     * {@code getPresignedUrl}'s general ownership check could never authorise them again.
     *
     * <p><b>The destination filename is the source filename, on purpose.</b> Promotion is therefore
     * idempotent: a booking commit that fails after the copy and is retried copies onto the same
     * destination rather than accumulating a fresh orphan per attempt.
     *
     * <p><b>The ownership check is re-run here</b>, against the same {@link #authorize} every other
     * path uses, even though the caller ({@code issues.service.IssuesService}) has already run it.
     * This method takes a caller-supplied key and creates an object under another id's namespace;
     * that is precisely the operation that must not rely on an upstream check staying correct.
     *
     * @throws ApiException {@code 403 FORBIDDEN} if {@code owner} does not own {@code guestKey}.
     */
    public String promoteGuestImage(UploadOwner owner, String guestKey, Long newOwnerId) {
        authorize(owner, guestKey);
        String fileName = guestKey.substring(guestKey.lastIndexOf('/') + 1);
        String destination = ImageKeyUtils.issueTempKeyPrefix(UploadOwner.customer(newOwnerId)) + fileName;
        try {
            storageClient.copy(guestKey, destination);
        } catch (StorageException e) {
            throw new ApiException(ErrorCode.STORAGE_SERVICE_ERROR, "Failed to attach an uploaded image.");
        }
        return destination;
    }

    /**
     * Drops the guest-namespace original of an already-promoted object. Best effort by design: the
     * copy is what the issue now depends on, so a failure to tidy up the source must never fail a
     * booking that has already committed. Anything missed here is reclaimed by the
     * {@code guests/} S3 lifecycle rule (infra/terraform/storage.tf).
     */
    public void discardPromotedGuestImage(String guestKey) {
        try {
            storageClient.delete(guestKey);
        } catch (StorageException e) {
            log.warn("storage.guest.promoted-original.delete-failed key={}", guestKey);
        }
    }

    /**
     * Issues a presigned URL for {@code key} WITHOUT any ownership/visibility check of its
     * own. Callable ONLY by code that has already independently established, via an
     * equal-or-broader authorization rule, that the current caller may view {@code key}. The
     * sole approved caller today is {@code issues.service.IssuesService#getById} — see
     * {@code docs/architecture/backend-ms9-presigned-image-urls-design.md} §9.4.2 for the full
     * reasoning. Do not add a second caller without re-justifying this exemption to
     * pronto-lead; this method exists to avoid over-broadening the general-purpose
     * {@link #authorize} check above, not as a general escape hatch.
     */
    public String getPresignedUrlAssumingCallerAuthorized(String key) {
        return storageClient.presignUrl(key, presignedUrlTtl);
    }

    /**
     * <b>MS1 (D-F): the operator's read of a professional's verification document.</b>
     *
     * <p>This is a second, narrower exemption from {@link #authorize}, and it is deliberately not
     * a second caller of {@link #getPresignedUrlAssumingCallerAuthorized} — whose Javadoc forbids
     * exactly that without re-justification. Here is the justification, and the alternative that
     * was rejected.
     *
     * <p>{@link #authorize} resolves ownership out of the key itself: a
     * {@code verification-documents/{userId}/...} key is readable only by that {@code userId}. An
     * operator reviewing a professional is by construction <em>not</em> that user, so the general
     * rule refuses them — correctly. The obvious fix, teaching {@link #authorize} that ADMINs may
     * read anything, was rejected: it would silently widen access to every private key in the
     * system, including customers' issue photos, on the strength of a role check made in a class
     * that has no idea what it is being asked to unlock. This method instead stays narrow in
     * three independent ways:
     *
     * <ul>
     *   <li><b>Prefix-locked.</b> Only {@code verification-documents/} keys. It cannot be turned
     *       into a general read primitive for {@code customers/} issue images even by a caller
     *       who can choose the key, because it refuses everything else outright.</li>
     *   <li><b>Reachable only from the ADMIN route.</b> Its sole caller is
     *       {@code professionals.service.ProfessionalApprovalService}, behind
     *       {@code /api/admin/professionals/**}, which {@code RoleRequiredInterceptor} gates on
     *       {@code ADMIN} before argument resolution.</li>
     *   <li><b>Key never client-supplied.</b> That caller reads the key off the
     *       {@code professionals} row it just loaded by id. No request field reaches this
     *       parameter.</li>
     * </ul>
     *
     * <p><b>The returned URL is a bearer capability</b> valid for
     * {@code pronto.storage.presigned-url-ttl-seconds} (300 by default): anyone holding it can
     * fetch a private compliance document without authenticating. It must never be logged, cached
     * in a shared store, or included in an error message — and neither must {@code key}. Nothing
     * in this method or its caller logs either, which is why neither does any logging at all.
     *
     * <p>Do not add a third caller without the same kind of justification.
     *
     * @throws ApiException {@code 403 FORBIDDEN} for any key outside the verification-document
     *         namespace
     */
    public String getVerificationDocumentUrlForOperator(String key) {
        if (key == null || !key.startsWith(VERIFICATION_DOCUMENT_PREFIX)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "This document is not an operator-reviewable document.");
        }
        return storageClient.presignUrl(key, presignedUrlTtl);
    }

    /**
     * Batch counterpart to {@link #getPresignedUrl} — used exclusively by
     * {@code POST /api/storage/images/presigned-urls} (§12.2), which re-resolves a paused
     * booking draft's photo keys into fresh presigned URLs on resume. Never fails the whole
     * batch because of one bad key: a per-key {@link #authorize} failure is caught and that
     * key is simply omitted from the result (defense-in-depth against a corrupted/tampered
     * {@code localStorage} draft — a legitimate caller's own draft should never actually hit
     * this branch, since every key in it was authorized at upload time).
     *
     * @throws ApiException {@code VALIDATION_ERROR} if {@code keys.size() > MAX_BATCH_SIZE}.
     */
    public List<PresignedImageUrlEntry> getPresignedUrls(UploadOwner owner, List<String> keys) {
        if (keys.size() > MAX_BATCH_SIZE) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request body failed validation.",
                    List.of(new FieldError("imageKeys", "must not contain more than " + MAX_BATCH_SIZE + " keys")));
        }
        List<PresignedImageUrlEntry> result = new ArrayList<>();
        for (String key : keys) {
            try {
                authorize(owner, key);
                result.add(new PresignedImageUrlEntry(key, storageClient.presignUrl(key, presignedUrlTtl)));
            } catch (ApiException e) {
                // Ownership mismatch on a key inside this caller's OWN draft should never
                // legitimately happen (every key was authorized at upload time) — this branch
                // exists purely as defense-in-depth against a corrupted/tampered localStorage
                // draft. Skip, don't fail the whole batch — see design doc §12.5.
            }
        }
        return result;
    }

    /** Convenience overload for the authenticated-customer case. */
    public List<PresignedImageUrlEntry> getPresignedUrls(Long callerId, List<String> keys) {
        return getPresignedUrls(UploadOwner.customer(callerId), keys);
    }

    /**
     * Verifies the local-mode HMAC signature + expiry (§3), then streams the bytes. In s3
     * mode, {@link #localHmacUrlSigner} is empty (no bean exists — see
     * {@link LocalHmacUrlSigner}'s Javadoc), so this always rejects with
     * {@code 401 UNAUTHORIZED} — correct, since s3-mode image URLs never point at this
     * backend at all (they're real S3 presigned URLs). Any failure (missing/unparseable
     * params, expired, mismatched signature) produces the identical
     * {@code 401 UNAUTHORIZED} outcome, deliberately indistinguishable — same
     * anti-enumeration spirit as the existing "always 403, never 404, on an ownership
     * mismatch" rule elsewhere in this package.
     */
    public RetrievedImage retrieveBySignedUrl(String key, Long expiresEpochSeconds, String signature) {
        LocalHmacUrlSigner signer = localHmacUrlSigner.orElseThrow(this::unauthorized);
        if (expiresEpochSeconds == null || signature == null) {
            throw unauthorized();
        }
        if (Instant.now().getEpochSecond() > expiresEpochSeconds) {
            throw unauthorized();
        }
        if (!signer.isValid(key, expiresEpochSeconds, signature)) {
            throw unauthorized();
        }

        byte[] content;
        try {
            content = storageClient.download(key);
        } catch (StorageException e) {
            throw new ApiException(ErrorCode.STORAGE_SERVICE_ERROR, "Failed to read image.");
        }

        String contentType = ImageKeyUtils.extractExtension(key)
                .flatMap(ImageContentType::fromExtension)
                .map(ImageContentType::contentType)
                .orElse(MediaType.APPLICATION_OCTET_STREAM_VALUE);

        return new RetrievedImage(content, contentType);
    }

    private ApiException unauthorized() {
        return new ApiException(ErrorCode.UNAUTHORIZED, "Invalid or expired image URL.");
    }

    /**
     * §2.4 steps 2-4. Ownership mismatch (including an unparseable key) is always
     * {@code 403 FORBIDDEN} — never {@code 404} — so a caller can't distinguish "not yours"
     * from "doesn't exist" by probing (§2.4's explicit anti-enumeration requirement).
     *
     * <p>{@code professionals/}-prefixed keys (profile images) skip the ownership check
     * entirely — see {@link ImageKeyUtils#isPubliclyReadable}'s javadoc for why. Every other
     * key format, in particular {@code customers/}-prefixed issue images, still goes through
     * {@link ImageKeyUtils#belongsTo}'s exact original per-caller ownership check, unchanged.
     *
     * <p><b>No eager {@code exists()} check</b> — deliberate, per
     * {@code docs/architecture/backend-ms9-presigned-image-urls-design.md} §2's recommendation:
     * presigning is a local/offline signing operation (S3 mode) or a cheap disk stat (local
     * mode) with no byte-streaming benefit either way, so an eager existence check here would
     * be a pure latency/cost add with no correctness benefit — a genuinely nonexistent key
     * (should not happen in normal operation) simply surfaces as a broken {@code <img>} when
     * the browser follows the URL, not as an API-level {@code 404}.
     *
     * <p><b>Guest ownership goes through this same check</b>, via
     * {@link ImageKeyUtils#belongsTo(String, UploadOwner)} — a {@code guests/{uuid}/...} key is
     * authorised by exactly the rule a {@code customers/{id}/...} key is, against an owner segment
     * this backend minted and the caller proved possession of. There is no second authorisation
     * path for guests, and in particular no branch anywhere that trusts a key merely because it
     * has the guest prefix.
     */
    private void authorize(UploadOwner owner, String key) {
        if (!ImageKeyUtils.isPubliclyReadable(key) && !ImageKeyUtils.belongsTo(key, owner)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "You do not have access to this image.");
        }
    }

    private DocumentContentType validateAndResolveDocumentType(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request body failed validation.",
                    List.of(new FieldError("verificationDocument", "is required")));
        }

        DocumentContentType type = DocumentContentType.fromContentType(file.getContentType())
                .orElseThrow(() -> new ApiException(ErrorCode.UNSUPPORTED_DOCUMENT_TYPE,
                        "Unsupported document content type: " + file.getContentType()));

        if (file.getSize() > MAX_SIZE_BYTES) {
            throw new ApiException(ErrorCode.IMAGE_TOO_LARGE, "File exceeds the maximum allowed size of 8 MB.");
        }
        return type;
    }

    private ImageContentType validateAndResolveType(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Request body failed validation.",
                    List.of(new FieldError("file", "is required")));
        }

        ImageContentType type = ImageContentType.fromContentType(file.getContentType())
                .orElseThrow(() -> new ApiException(ErrorCode.UNSUPPORTED_IMAGE_TYPE,
                        "Unsupported image content type: " + file.getContentType()));

        if (file.getSize() > MAX_SIZE_BYTES) {
            throw new ApiException(ErrorCode.IMAGE_TOO_LARGE, "File exceeds the maximum allowed size of 8 MB.");
        }
        return type;
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new ApiException(ErrorCode.STORAGE_SERVICE_ERROR, "Failed to read uploaded file.");
        }
    }
}
