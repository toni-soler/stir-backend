package org.stir.listing;

import es.idynamicsax.idax.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import static org.springframework.http.HttpStatus.CREATED;

@RestController @RequestMapping("/api/stir/tenants/{tenantId}/listings") @Validated
public class ListingController {
    private final ListingService service;
    public ListingController(ListingService service) { this.service=service; }
    @ModelAttribute
    public void requireTenant(@PathVariable UUID tenantId) {
        var context=es.idynamicsax.idax.tenant.TenantContext.get();
        if(context==null || !tenantId.equals(context.getTenantId()))
            throw new org.springframework.security.access.AccessDeniedException("Tenant context mismatch");
    }
    @GetMapping @PreAuthorize("@permissionService.hasPermission('stir.listings.read')")
    public Page<Listing> search(@AuthenticationPrincipal CurrentUser user,
        @RequestParam(required=false) @Size(max=160) String q,
        @RequestParam(required=false) @Pattern(regexp="OFFER|WANTED") String direction,
        @RequestParam(required=false) @Size(max=40) String category,
        @RequestParam(required=false) @Size(max=40) String resourceKind,
        @RequestParam(defaultValue="ACTIVE") @Pattern(regexp="ACTIVE|CLOSED") String status,
        @RequestParam(defaultValue="false") boolean mine,
        @RequestParam(defaultValue="0") @Min(0) int page,
        @RequestParam(defaultValue="20") @Min(1) @Max(100) int size) {
        return service.search(user,q,direction,category,resourceKind,status,mine,page,size);
    }
    @GetMapping("/catalogs") @PreAuthorize("@permissionService.hasPermission('stir.listings.read')")
    public Map<String,List<String>> catalogs() { return service.catalogs(); }
    @GetMapping("/{id}") @PreAuthorize("@permissionService.hasPermission('stir.listings.read')")
    public Listing read(@PathVariable UUID id) { return service.read(id); }
    @PostMapping @ResponseStatus(CREATED) @PreAuthorize("@permissionService.hasPermission('stir.listings.create')")
    public Listing create(@AuthenticationPrincipal CurrentUser user,@Valid @RequestBody ListingRequest request) { return service.create(user,request); }
    @PutMapping("/{id}") @PreAuthorize("@permissionService.hasPermission('stir.listings.update')")
    public Listing update(@PathVariable UUID id,@AuthenticationPrincipal CurrentUser user,@Valid @RequestBody ListingRequest request) { return service.update(id,user,request); }
    @PostMapping("/{id}/close") @PreAuthorize("@permissionService.hasPermission('stir.listings.update')")
    public Listing close(@PathVariable UUID id,@AuthenticationPrincipal CurrentUser user,@Valid @RequestBody CloseRequest request) { return service.close(id,user,request.version()); }
    public record CloseRequest(@NotNull @PositiveOrZero Long version) {}
}
