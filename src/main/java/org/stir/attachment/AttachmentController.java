package org.stir.attachment;

import es.idynamicsax.idax.security.CurrentUser;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.stir.ratelimit.RateLimited;
import static org.springframework.http.HttpStatus.CREATED;

@RestController @RequestMapping("/api/stir/tenants/{tenantId}")
public class AttachmentController {
    private final AttachmentService service;
    public AttachmentController(AttachmentService service) { this.service = service; }
    @ModelAttribute
    public void requireTenant(@PathVariable UUID tenantId) {
        var context = es.idynamicsax.idax.tenant.TenantContext.get();
        if (context == null || !tenantId.equals(context.getTenantId()))
            throw new org.springframework.security.access.AccessDeniedException("Tenant context mismatch");
    }

    @PostMapping(value = "/listings/{listingId}/photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(CREATED) @PreAuthorize("@permissionService.hasPermission('stir.listings.update')")
    @RateLimited(key = "uploadPhoto", limit = 30, windowSeconds = 60)
    public AttachmentService.AttachmentView uploadListingPhoto(@PathVariable UUID listingId,
            @RequestParam("file") MultipartFile file, @AuthenticationPrincipal CurrentUser user) {
        return service.uploadListingPhoto(user, listingId, file);
    }

    @GetMapping("/listings/{listingId}/photos") @PreAuthorize("@permissionService.hasPermission('stir.listings.read')")
    public List<AttachmentService.AttachmentView> listingPhotos(@PathVariable UUID listingId) {
        return service.listingPhotos(listingId);
    }

    @PostMapping(value = "/participants/me/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@permissionService.hasPermission('stir.participants.update')")
    @RateLimited(key = "uploadAvatar", limit = 10, windowSeconds = 60)
    public AttachmentService.AttachmentView uploadAvatar(@RequestParam("file") MultipartFile file, @AuthenticationPrincipal CurrentUser user) {
        return service.uploadAvatar(user, file);
    }

    @DeleteMapping("/attachments/{id}") @PreAuthorize("@permissionService.hasPermission('stir.listings.update')")
    public ResponseEntity<Void> delete(@PathVariable UUID id, @AuthenticationPrincipal CurrentUser user) {
        service.delete(user, id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/attachments/{id}/content") @PreAuthorize("@permissionService.hasPermission('stir.listings.read')")
    public ResponseEntity<InputStreamResource> content(@PathVariable UUID id, @AuthenticationPrincipal CurrentUser user) {
        var result = service.readContent(user, id);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(result.mediaType()))
            .contentLength(result.size())
            .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
            .body(new InputStreamResource(result.stream()));
    }
}
