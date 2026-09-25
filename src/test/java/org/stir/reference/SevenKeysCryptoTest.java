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
}
