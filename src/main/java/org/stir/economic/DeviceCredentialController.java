package org.stir.economic;

import es.idynamicsax.idax.security.CurrentUser;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import static org.springframework.http.HttpStatus.CREATED;

@RestController @RequestMapping("/api/stir/tenants/{tenantId}/economic/devices")
public class DeviceCredentialController {
    private final DeviceCredentialService service;
    public DeviceCredentialController(DeviceCredentialService service) { this.service = service; }
    @ModelAttribute
    public void requireTenant(@PathVariable UUID tenantId) {
        var context = es.idynamicsax.idax.tenant.TenantContext.get();
        if (context == null || !tenantId.equals(context.getTenantId()))
            throw new org.springframework.security.access.AccessDeniedException("Tenant context mismatch");
    }

    @GetMapping @PreAuthorize("@permissionService.hasPermission('stir.economic.read')")
    public List<DeviceCredentialService.DeviceView> mine(@AuthenticationPrincipal CurrentUser user) { return service.myDevices(user); }

    @PostMapping @ResponseStatus(CREATED) @PreAuthorize("@permissionService.hasPermission('stir.economic.manage')")
    public DeviceCredentialService.DeviceView add(@AuthenticationPrincipal CurrentUser user, @RequestBody AddDeviceRequest request) {
        return service.addDevice(user, request.label(), request.publicKeyBase64url());
    }

    @PostMapping("/{credentialId}/revoke") @PreAuthorize("@permissionService.hasPermission('stir.economic.manage')")
    public void revoke(@PathVariable UUID credentialId, @AuthenticationPrincipal CurrentUser user) {
        service.revokeDevice(user, credentialId);
    }

    public record AddDeviceRequest(@NotBlank @Size(max = 80) String label, @NotBlank String publicKeyBase64url) {}
}
