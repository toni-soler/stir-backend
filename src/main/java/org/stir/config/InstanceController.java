package org.stir.config;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public, unauthenticated instance branding (section 18 of the 0.4 brief): nothing here is
 * tenant- or user-specific, so a real stir.es deployment is purely a matter of environment
 * configuration - no code path is hardcoded to any one hostname/brand.
 */
@RestController
public class InstanceController {
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
        return new InstanceView(siteName, publicBaseUrl, supportContact, defaultLocale, locales, marketplaceDescription, logoUrl);
    }

    public record InstanceView(String siteName, String publicBaseUrl, String supportContact,
        String defaultLocale, List<String> availableLocales, String marketplaceDescription, String logoUrl) {}
}
