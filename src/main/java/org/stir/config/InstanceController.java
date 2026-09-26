package org.stir.config;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.stir.negotiation.AgreementSnapshotService;

/**
 * Public, unauthenticated instance branding (section 18 of the 0.4 brief): nothing here is
 * tenant- or user-specific, so a real stir.es deployment is purely a matter of environment
 * configuration - no code path is hardcoded to any one hostname/brand.
 *
 * The three version fields are a minimal, honest compatibility surface for external
 * distributions (see COMMUNITY_EXTENSION_GUIDE.md) - not a capability-registry framework.
 * They expose only versions STIR actually enforces: its own build (bump by hand alongside
 * pom.xml/package.json), the catalog consumer contract, and the Agreement snapshot schema
 * that carries a distribution's opaque external contract commitment. STIR promises nothing
 * beyond "this is the version deployed right now" - a distribution's own lock/compatibility
 * record decides what minimum/tested version it requires.
 */
@RestController
public class InstanceController {
    // Must match CATALOG_CONTRACT_VERSION in stir-frontend/src/catalog-client.js - bump both
    // together, and only when the change is additive-safe or a distribution can detect it.
    public static final int CATALOG_CONTRACT_VERSION = 1;
    @Value("${stir.instance.version}") String stirVersion;
    @Value("${stir.instance.site-name}") String siteName;
    @Value("${stir.instance.public-base-url}") String publicBaseUrl;
    @Value("${stir.instance.support-contact}") String supportContact;
    @Value("${stir.instance.default-locale}") String defaultLocale;
    @Value("${stir.instance.available-locales}") String availableLocalesCsv;
    @Value("${stir.instance.marketplace-description}") String marketplaceDescription;
    @Value("${stir.instance.logo-url}") String logoUrl;

    @GetMapping("/api/stir/instance")
    public InstanceView instance() {
        List<String> locales = availableLocalesCsv.isBlank() ? List.of() : List.of(availableLocalesCsv.split(","));
        return new InstanceView(siteName, publicBaseUrl, supportContact, defaultLocale, locales, marketplaceDescription, logoUrl,
            stirVersion, CATALOG_CONTRACT_VERSION, AgreementSnapshotService.SCHEMA_VERSION);
    }

    public record InstanceView(String siteName, String publicBaseUrl, String supportContact,
        String defaultLocale, List<String> availableLocales, String marketplaceDescription, String logoUrl,
        String stirVersion, int catalogContractVersion, int externalContractSchemaVersion) {}
}
