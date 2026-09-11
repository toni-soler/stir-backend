package org.stir.negotiation;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** STIR's own AgreementSnapshot canonicalization test vectors: same input -> same bytes -> same
 * digest, and a modified contractual term -> a different digest. */
class CanonicalJsonTest {
    Map<String,Object> baseline() {
        Map<String,Object> fields=new LinkedHashMap<>();
        fields.put("schemaVersion",1);
        fields.put("agreementId","5c2c6a2e-1111-4a11-9a11-000000000001");
        fields.put("listingDirection","OFFER");
        fields.put("proposedAmount","15.00");
        fields.put("quantity","20.0000");
        fields.put("terms",null);
        fields.put("nonce","fixed-test-nonce");
        return fields;
    }
    @Test void sameInputProducesSameCanonicalBytesAndDigest() {
        var a=CanonicalJson.canonicalBytes(baseline());
        var b=CanonicalJson.canonicalBytes(new LinkedHashMap<>(baseline()));
        assertArrayEquals(a,b);
        assertEquals(CanonicalJson.sha256Hex(a),CanonicalJson.sha256Hex(b));
    }
    @Test void keyInsertionOrderDoesNotAffectCanonicalBytes() {
        Map<String,Object> reordered=new LinkedHashMap<>();
        reordered.put("nonce","fixed-test-nonce");
        reordered.put("terms",null);
        reordered.put("quantity","20.0000");
        reordered.put("proposedAmount","15.00");
        reordered.put("listingDirection","OFFER");
        reordered.put("agreementId","5c2c6a2e-1111-4a11-9a11-000000000001");
        reordered.put("schemaVersion",1);
        assertArrayEquals(CanonicalJson.canonicalBytes(baseline()),CanonicalJson.canonicalBytes(reordered));
    }
    @Test void modifiedContractualTermProducesADifferentDigest() {
        var original=CanonicalJson.sha256Hex(CanonicalJson.canonicalBytes(baseline()));
        var changed=new LinkedHashMap<>(baseline()); changed.put("proposedAmount","15.01");
        var modified=CanonicalJson.sha256Hex(CanonicalJson.canonicalBytes(changed));
        assertNotEquals(original,modified);
    }
    @Test void differentNonceProducesADifferentDigestForOtherwiseIdenticalTerms() {
        var original=CanonicalJson.sha256Hex(CanonicalJson.canonicalBytes(baseline()));
        var changed=new LinkedHashMap<>(baseline()); changed.put("nonce","a-different-nonce");
        var modified=CanonicalJson.sha256Hex(CanonicalJson.canonicalBytes(changed));
        assertNotEquals(original,modified);
    }
    @Test void canonicalBytesAreCompactSortedKeyUtf8Json() {
        var bytes=CanonicalJson.canonicalBytes(baseline());
        var text=new String(bytes,java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(text.startsWith("{\"agreementId\":"));
        assertFalse(text.contains(": "));
        assertFalse(text.contains(", "));
    }
    /** Shared Java/JavaScript vector (see stir-frontend/tests/canonical-json.test.mjs): the exact
     * same field values, independently canonicalized by this module's erdtman/java-json-
     * canonicalization and the frontend's "canonicalize" npm package, produce byte-identical JCS
     * output and this exact digest - real interoperability, not two implementations that merely
     * each pass their own tests. Computed with Node's canonicalize + crypto against this same
     * baseline() map. */
    @Test void matchesTheSharedJavaJavaScriptTestVector() {
        var bytes=CanonicalJson.canonicalBytes(baseline());
        assertEquals("{\"agreementId\":\"5c2c6a2e-1111-4a11-9a11-000000000001\",\"listingDirection\":\"OFFER\","
            + "\"nonce\":\"fixed-test-nonce\",\"proposedAmount\":\"15.00\",\"quantity\":\"20.0000\","
            + "\"schemaVersion\":1,\"terms\":null}", new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("3bde73f31403a61d2c0cdcc31158207935475881981a57e6696b3b44c6dce862",
            CanonicalJson.sha256Hex(bytes));
    }
}
