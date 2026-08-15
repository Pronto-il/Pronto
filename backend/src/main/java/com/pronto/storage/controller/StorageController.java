package com.pronto.storage.controller;

import com.pronto.common.exception.ApiException;
import com.pronto.common.exception.ErrorCode;
import com.pronto.common.security.AuthenticatedUser;
import com.pronto.storage.dto.ImageUploadResponse;
import com.pronto.storage.dto.RetrievedImage;
import com.pronto.storage.service.StorageService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.HandlerMapping;

/**
 * {@code /api/storage/images} — image upload (backend-proxied, §2.3) and retrieval
 * (§2.4, backend-proxied for both storage modes). {@code POST} (upload) requires
 * {@code role = CUSTOMER} (§0.1), enforced by
 * {@code storage.config.StorageWebConfig}'s {@code RoleRequiredInterceptor} registration —
 * not in this method's body, see that class's javadoc for why (it must run before
 * {@code @RequestParam}/multipart resolution below). {@code GET} (retrieval) is either-role
 * (any authenticated caller) at the route level; per-key authorization is enforced in
 * {@code storage.service.StorageService#retrieve} — see that method's javadoc and
 * {@code StorageWebConfig}'s for why. See {@code docs/architecture/api-contract-issues.md}
 * §2.3-2.4.
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

    /**
     * Mapped with a trailing wildcard, not a single {@code {key}} path variable, since the
     * key itself contains {@code /} characters — the exact gotcha called out in
     * {@code api-contract-issues.md} §2.4's implementation note.
     */
    @GetMapping("/images/**")
    public ResponseEntity<byte[]> retrieve(@AuthenticationPrincipal AuthenticatedUser principal,
                                            HttpServletRequest request) {
        String key = extractKey(request);
        RetrievedImage image = storageService.retrieve(principal, key);
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
