package com.pronto.storage.controller;

import com.pronto.auth.security.GuestSessionTokenService;
import com.pronto.auth.security.UploadOwnerResolver;
import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.common.security.AuthenticatedUser;
import com.pronto.common.security.UploadOwner;
import com.pronto.storage.dto.GuestUploadSessionResponse;
import com.pronto.storage.dto.ImageUploadResponse;
import com.pronto.storage.dto.PresignedImageUrlEntry;
import com.pronto.storage.dto.PresignedImageUrlsRequest;
import com.pronto.storage.dto.PresignedImageUrlsResponse;
import com.pronto.storage.dto.RetrievedImage;
import com.pronto.storage.service.StorageService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.HandlerMapping;

import java.util.List;

/**
 * {@code /api/storage/images} — image upload (backend-proxied, §2.3) and retrieval (presigned/
 * signed URLs as of backend MS9,
 * {@code docs/architecture/backend-ms9-presigned-image-urls-design.md}). {@code POST /images}
 * (upload) requires {@code role = CUSTOMER} (§0.1), enforced by
 * {@code storage.config.StorageWebConfig}'s {@code RoleRequiredInterceptor} registration —
 * not in this method's body, see that class's javadoc for why (it must run before
 * {@code @RequestParam}/multipart resolution below).
 *
 * <p>{@code GET /images/**} (retrieval) no longer requires a JWT at all — MS9 §4 makes it
 * {@code permitAll()} at {@code auth.config.SecurityConfig}, since a plain {@code <img src>}
 * cannot attach an {@code Authorization} header. Authorization moved from "valid JWT +
 * per-key ownership check" to "a valid, unexpired {@code expires}/{@code sig} HMAC pair in
 * local mode" (s3-mode presigned URLs never reach this route at all — they point straight at
 * S3). See {@code storage.service.StorageService#retrieveBySignedUrl}.
 *
 * <p>{@code POST /images/presigned-urls} (§12.2) is the new batch key-to-presigned-URL lookup
 * used by a resumed booking draft to re-resolve its photos' keys — any authenticated caller,
 * per-key ownership enforced the same way as the general single-key presign path.
 */
@RestController
@RequestMapping("/api/storage")
public class StorageController {

    private static final String IMAGES_PATH_PREFIX = "/api/storage/images/";

    private final StorageService storageService;
    private final GuestSessionTokenService guestSessionTokenService;
    private final UploadOwnerResolver uploadOwnerResolver;

    public StorageController(StorageService storageService,
                              GuestSessionTokenService guestSessionTokenService,
                              UploadOwnerResolver uploadOwnerResolver) {
        this.storageService = storageService;
        this.guestSessionTokenService = guestSessionTokenService;
        this.uploadOwnerResolver = uploadOwnerResolver;
    }

    /**
     * Mints a guest upload session — the only way to obtain an upload namespace without an
     * account, and the only new endpoint this feature adds.
     *
     * <p>Public, and per-IP rate limited in {@code storage.config.StorageWebConfig} for the same
     * reason {@code POST /api/issues/classify} is: it is reachable by anyone, and what it hands
     * out has a cost attached (here, the right to write objects into the uploads bucket). What it
     * hands out is also deliberately inert on its own — a session with no uploads consumes
     * nothing, creates no row, and expires by itself.
     */
    @PostMapping("/guest-sessions")
    public ResponseEntity<GuestUploadSessionResponse> createGuestSession() {
        GuestSessionTokenService.GuestSession session = guestSessionTokenService.issue();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new GuestUploadSessionResponse(session.token(), session.expiresInSeconds()));
    }

    /**
     * Unchanged for an authenticated customer, and now also reachable by a guest holding a valid
     * session token.
     *
     * <p><b>{@code principal} may be {@code null} here, and that does not make this endpoint
     * anonymous.</b> {@code auth.config.SecurityConfig} no longer 401s this route at the filter
     * layer, because a guest legitimately has no JWT — but
     * {@link UploadOwnerResolver#requireIdentified} refuses a caller who proved neither identity,
     * so an upload is still authorised on every single request. This is the same relocation of
     * authorization backend MS9 performed for {@code GET /images/**} (filter chain → issuance
     * time), applied to the one other route that now has two legitimate kinds of caller. The
     * {@code CUSTOMER} role restriction is untouched for anyone who <em>does</em> present a JWT —
     * see {@code storage.config.StorageWebConfig}.
     */
    @PostMapping(value = "/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImageUploadResponse> upload(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestHeader(value = GuestSessionTokenService.HEADER, required = false) String guestSessionToken,
            @RequestParam("file") MultipartFile file) {
        UploadOwner owner = uploadOwnerResolver.requireIdentified(principal, guestSessionToken);
        ImageUploadResponse response = storageService.upload(owner, file);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * The draft-resume batch lookup, now serving a guest's paused draft as well as a customer's.
     *
     * <p>A guest who leaves and comes back has exactly the problem this endpoint was built for: the
     * draft persists bare {@code imageKey}s, never URLs, and the presigned URLs minted at upload
     * time are long expired. Per-key authorization is the same {@code authorize()} call as before,
     * against whichever identities the caller proved.
     */
    @PostMapping("/images/presigned-urls")
    public ResponseEntity<PresignedImageUrlsResponse> presignedUrls(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestHeader(value = GuestSessionTokenService.HEADER, required = false) String guestSessionToken,
            @RequestBody PresignedImageUrlsRequest request) {
        UploadOwner owner = uploadOwnerResolver.requireIdentified(principal, guestSessionToken);
        List<PresignedImageUrlEntry> images = storageService.getPresignedUrls(owner, request.imageKeys());
        return ResponseEntity.ok(new PresignedImageUrlsResponse(images));
    }

    /**
     * Mapped with a trailing wildcard, not a single {@code {key}} path variable, since the
     * key itself contains {@code /} characters — the exact gotcha called out in
     * {@code api-contract-issues.md} §2.4's implementation note. No
     * {@code @AuthenticationPrincipal} — this route is {@code permitAll()} at the Spring
     * Security layer (MS9 §4); {@code expires}/{@code sig} are the sole authorization
     * mechanism, verified by {@link StorageService#retrieveBySignedUrl}.
     */
    @GetMapping("/images/**")
    public ResponseEntity<byte[]> retrieve(HttpServletRequest request,
                                            @RequestParam(required = false) Long expires,
                                            @RequestParam(required = false) String sig) {
        String key = extractKey(request);
        RetrievedImage image = storageService.retrieveBySignedUrl(key, expires, sig);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.contentType()))
                .body(image.content());
    }

    private String extractKey(HttpServletRequest request) {
        Object attribute = request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
        String fullPath = attribute == null ? request.getRequestURI() : attribute.toString();
        if (!fullPath.startsWith(IMAGES_PATH_PREFIX)) {
            throw new ApiException(ErrorCode.FORBIDDEN, "Invalid image key.");
        }
        return fullPath.substring(IMAGES_PATH_PREFIX.length());
    }
}
