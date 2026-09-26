package org.stir.config;

import org.junit.jupiter.api.Test;
import org.stir.negotiation.AgreementSnapshotService;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The instance endpoint's version fields are the entire public compatibility surface for
 * external distributions (COMMUNITY_EXTENSION_GUIDE.md). Pin down that they reflect the
 * real, enforced values - not placeholders - and that a distribution never sees null.
 */
class InstanceControllerTest {
    @Test void exposesTheThreeVersionFieldsExternalDistributionsCanCheck() {
        var controller = new InstanceController();
        controller.stirVersion = "0.5.0-rc1";
        controller.siteName = "STIR"; controller.publicBaseUrl = "http://localhost:8089";
        controller.supportContact = ""; controller.defaultLocale = "en"; controller.availableLocalesCsv = "en";
        controller.marketplaceDescription = ""; controller.logoUrl = "";

        var view = controller.instance();

        assertEquals("0.5.0-rc1", view.stirVersion());
        assertEquals(InstanceController.CATALOG_CONTRACT_VERSION, view.catalogContractVersion());
        assertEquals(AgreementSnapshotService.SCHEMA_VERSION, view.externalContractSchemaVersion());
        assertTrue(view.catalogContractVersion() > 0);
        assertTrue(view.externalContractSchemaVersion() > 0);
    }
}
