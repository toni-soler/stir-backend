package org.stir.reference;

import java.security.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SevenKeysCryptoTest {
    @Test void exactPayloadDomainCommunityAndProposalAreBoundToSignature() throws Exception {
        var key=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String publicKey=Base64.getUrlEncoder().withoutPadding().encodeToString(
            Arrays.copyOfRange(key.getPublic().getEncoded(),key.getPublic().getEncoded().length-32,key.getPublic().getEncoded().length));
        var payload=Map.<String,Object>of("communityId",UUID.randomUUID().toString(),"proposalId",UUID.randomUUID().toString(),
            "beforeDigest","a".repeat(64),"afterDigest","b".repeat(64));
        byte[] message=SevenKeysCrypto.message(SevenKeysCrypto.DOMAIN,payload);
        var signer=Signature.getInstance("Ed25519");signer.initSign(key.getPrivate());signer.update(message);
        String signature=Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
        assertDoesNotThrow(()->SevenKeysCrypto.requireSignature(publicKey,signature,message));
        for(String field:List.of("communityId","proposalId","afterDigest")) {
            var changed=new HashMap<>(payload);changed.put(field,"different");
            assertThrows(IllegalArgumentException.class,()->SevenKeysCrypto.requireSignature(publicKey,signature,
                SevenKeysCrypto.message(SevenKeysCrypto.DOMAIN,changed)));
        }
        assertThrows(IllegalArgumentException.class,()->SevenKeysCrypto.requireSignature(publicKey,signature,
            SevenKeysCrypto.message(SevenKeysCrypto.GUARDIAN_DOMAIN,payload)));
        assertThrows(IllegalArgumentException.class,()->SevenKeysCrypto.requireSignature(publicKey,signature+"=",message));
    }
    // Golden vector shared with stir-frontend's governance-signer.test.mjs: same fixed constitution
    // object, same RFC 8785 canonical bytes and SHA-256 hex on both sides of the JCS boundary.
    @Test void initialConstitutionDigestMatchesTheSharedFrontendVector() {
        String canonical=new String(
            org.stir.negotiation.CanonicalJson.canonicalBytes(SevenKeysService.initialConstitution()),java.nio.charset.StandardCharsets.UTF_8);
        assertEquals("{\"concentrationChecksRequired\":true,\"constitutionalThreshold\":7,\"guardianMayGovern\":false,"+
            "\"historyImmutable\":true,\"independenceChecksRequired\":true,\"maximumParticipantShareCeiling\":\"0.50\","+
            "\"minimumObservationFloor\":5,\"minimumParticipantFloor\":6,\"provenanceRequired\":true,\"schema\":\"STIR-MARKET-CONSTITUTION-1\"}",
            canonical);
        assertEquals("8254b12fba01df8b2527a3583302573fe8a47d6ba30696003731e22200c79da5",
            org.stir.negotiation.CanonicalJson.sha256Hex(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
