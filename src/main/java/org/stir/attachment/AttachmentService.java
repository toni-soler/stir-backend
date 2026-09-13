package org.stir.attachment;

import es.idynamicsax.idax.security.CurrentUser;
import es.idynamicsax.idax.tenant.TenantContext;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.stir.listing.ListingRepository;
import org.stir.participant.ParticipantProfileRepository;
import org.stir.storage.ObjectStorageService;
import static org.springframework.http.HttpStatus.*;

/**
 * Attachments (Listing photos, participant avatars): real magic-byte sniffing (never the client's
 * declared Content-Type or filename - object keys are always server-generated, so a malicious
 * filename can never influence storage), server-side ownership/tenant authorization on every read
 * and write, and size/count limits. Bytes live in object storage (ObjectStorageService); this
 * service is the ONLY thing allowed to read or write them - nothing else ever gets bucket access.
 */
@Service
public class AttachmentService {
    private static final Logger log = LoggerFactory.getLogger(AttachmentService.class);
    private static final long MAX_LISTING_PHOTO_BYTES = 8L * 1024 * 1024;
    private static final long MAX_AVATAR_BYTES = 4L * 1024 * 1024;
    private static final int MAX_PHOTOS_PER_LISTING = 6;

    private final AttachmentRepository attachments;
    private final ListingRepository listings;
    private final ParticipantProfileRepository profiles;
    private final ObjectStorageService storage;

    public AttachmentService(AttachmentRepository attachments, ListingRepository listings,
            ParticipantProfileRepository profiles, ObjectStorageService storage) {
        this.attachments = attachments; this.listings = listings; this.profiles = profiles; this.storage = storage;
    }

    private UUID tenant() {
        var context = TenantContext.get();
        if (context == null || context.getTenantId() == null) throw new AccessDeniedException("Tenant required");
        return context.getTenantId();
    }
    private UUID actor(CurrentUser user) {
        if (user == null || user.isService() || user.getUserId() == null) throw new AccessDeniedException("User required");
        return user.getUserId();
    }

    @Transactional
    public AttachmentView uploadListingPhoto(CurrentUser user, UUID listingId, MultipartFile file) {
        UUID tenant = tenant(), actor = actor(user);
        var listing = listings.findByIdAndTenantId(listingId, tenant).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Listing not found"));
        if (!listing.ownerId.equals(actor)) throw new ResponseStatusException(NOT_FOUND, "Listing not found");
        long existing = attachments.countByTenantIdAndListingIdAndStatus(tenant, listingId, "ACTIVE");
        if (existing >= MAX_PHOTOS_PER_LISTING) throw new ResponseStatusException(CONFLICT, "A listing may have at most " + MAX_PHOTOS_PER_LISTING + " photos");
        var sniffed = sniffImage(file, MAX_LISTING_PHOTO_BYTES, tenant, actor);
        var attachment = store(tenant, actor, "LISTING_PHOTO", listingId, (short) existing, sniffed);
        return AttachmentView.of(attachment);
    }

    @Transactional
    public AttachmentView uploadAvatar(CurrentUser user, MultipartFile file) {
        UUID tenant = tenant(), actor = actor(user);
        var profile = profiles.findByTenantIdAndUserId(tenant, actor)
            .orElseThrow(() -> new ResponseStatusException(CONFLICT, "Set up your profile before adding an avatar"));
        var sniffed = sniffImage(file, MAX_AVATAR_BYTES, tenant, actor);
        var attachment = store(tenant, actor, "AVATAR", null, null, sniffed);
        var previousId = profile.avatarAttachmentId;
        profile.avatarAttachmentId = attachment.id;
        profiles.saveAndFlush(profile);
        if (previousId != null) attachments.findByIdAndTenantId(previousId, tenant).ifPresent(prev -> {
            prev.status = "DELETED"; prev.updatedAt = Instant.now(); attachments.saveAndFlush(prev); storage.delete(prev.objectKey);
        });
        return AttachmentView.of(attachment);
    }

    @Transactional
    public void delete(CurrentUser user, UUID attachmentId) {
        UUID tenant = tenant(), actor = actor(user);
        var attachment = attachments.findByIdAndTenantId(attachmentId, tenant).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Attachment not found"));
        if (!attachment.ownerUserId.equals(actor)) throw new ResponseStatusException(NOT_FOUND, "Attachment not found");
        attachment.status = "DELETED"; attachment.updatedAt = Instant.now();
        attachments.saveAndFlush(attachment);
        storage.delete(attachment.objectKey);
    }

    /** Any tenant member may view: Listings/profiles are already tenant-visible, not per-owner
     * private (matches the existing Listing/profile read model - see AUTHENTICATION_AUDIT.md).
     * @Transactional is required even for this plain read: RlsTransactionAspect only sets the
     * app.tenant_id session variable around @Transactional methods, and stir.attachment FORCES
     * row level security - without it, every row is invisible regardless of tenant match. */
    @Transactional(readOnly = true)
    public ContentResult readContent(CurrentUser user, UUID attachmentId) {
        UUID tenant = tenant(); actor(user);
        var attachment = attachments.findByIdAndTenantId(attachmentId, tenant)
            .filter(a -> "ACTIVE".equals(a.status))
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Attachment not found"));
        return new ContentResult(storage.get(attachment.objectKey), attachment.mediaType, attachment.sizeBytes);
    }

    @Transactional(readOnly = true)
    public List<AttachmentView> listingPhotos(UUID listingId) {
        return attachments.findByTenantIdAndListingIdAndStatusOrderByPosition(tenant(), listingId, "ACTIVE").stream().map(AttachmentView::of).toList();
    }

    private Attachment store(UUID tenant, UUID actor, String purpose, UUID listingId, Short position, SniffedImage sniffed) {
        UUID id = UUID.randomUUID();
        String extension = switch (sniffed.mediaType) {
            case "image/jpeg" -> "jpg"; case "image/png" -> "png"; case "image/webp" -> "webp"; default -> "bin";
        };
        // Server-generated key only - never derived from the client's filename: no path traversal,
        // no collisions, no leaking local filesystem structure.
        String objectKey = "tenants/" + tenant + "/" + purpose.toLowerCase() + "/" + id + "." + extension;
        storage.put(objectKey, sniffed.stream, sniffed.size, sniffed.mediaType);
        var attachment = new Attachment();
        attachment.id = id; attachment.tenantId = tenant; attachment.ownerUserId = actor; attachment.purpose = purpose;
        attachment.listingId = listingId; attachment.position = position; attachment.objectKey = objectKey;
        attachment.mediaType = sniffed.mediaType; attachment.sizeBytes = sniffed.size; attachment.status = "ACTIVE";
        attachment.createdAt = Instant.now(); attachment.updatedAt = attachment.createdAt;
        return attachments.saveAndFlush(attachment);
    }

    /** Never trusts the client-declared Content-Type or filename: sniffs the real magic bytes so a
     * renamed/relabeled file can never pass as an image. */
    private SniffedImage sniffImage(MultipartFile file, long maxBytes, UUID tenant, UUID actor) {
        if (file == null || file.isEmpty()) throw new ResponseStatusException(BAD_REQUEST, "A file is required");
        if (file.getSize() > maxBytes) throw new ResponseStatusException(PAYLOAD_TOO_LARGE, "File exceeds the " + (maxBytes / (1024 * 1024)) + "MB limit");
        byte[] head;
        try (InputStream in = file.getInputStream()) { head = in.readNBytes(12); }
        catch (Exception ex) { throw new ResponseStatusException(BAD_REQUEST, "Unable to read file"); }
        String mediaType = detectImageType(head);
        if (mediaType == null) {
            log.warn("Rejected upload with unrecognized image content, tenant={} actor={}", tenant, actor);
            throw new ResponseStatusException(UNSUPPORTED_MEDIA_TYPE, "Only JPEG, PNG or WEBP images are accepted");
        }
        try { return new SniffedImage(file.getInputStream(), file.getSize(), mediaType); }
        catch (Exception ex) { throw new ResponseStatusException(BAD_REQUEST, "Unable to read file"); }
    }

    private static String detectImageType(byte[] head) {
        if (head.length >= 3 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF) return "image/jpeg";
        if (head.length >= 8 && (head[0] & 0xFF) == 0x89 && head[1] == 'P' && head[2] == 'N' && head[3] == 'G'
            && head[4] == 0x0D && head[5] == 0x0A && head[6] == 0x1A && head[7] == 0x0A) return "image/png";
        if (head.length >= 12 && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
            && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P') return "image/webp";
        return null;
    }

    private record SniffedImage(InputStream stream, long size, String mediaType) {}
    public record ContentResult(InputStream stream, String mediaType, long size) {}
    public record AttachmentView(UUID id, String purpose, String mediaType, long sizeBytes, Instant createdAt) {
        static AttachmentView of(Attachment a) { return new AttachmentView(a.id, a.purpose, a.mediaType, a.sizeBytes, a.createdAt); }
    }
}
