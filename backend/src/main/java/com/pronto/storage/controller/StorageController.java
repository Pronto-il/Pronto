package com.pronto.storage.controller;

import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.common.security.AuthenticatedUser;
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

    public StorageController(StorageService storageService) {
        this.storageService = storageService;
    }

    @PostMapping(value = "/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImageUploadResponse> upload(@AuthenticationPrincipal AuthenticatedUser principal,
                                                        @RequestParam("file") MultipartFile file) {
        ImageUploadResponse response = storageService.upload(principal, file);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/images/presigned-urls")
    public ResponseEntity<PresignedImageUrlsResponse> presignedUrls(@AuthenticationPrincipal AuthenticatedUser principal,
                                                                      @RequestBody PresignedImageUrlsRequest request) {
        List<PresignedImageUrlEntry> images = storageService.getPresignedUrls(principal.id(), request.imageKeys());
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
