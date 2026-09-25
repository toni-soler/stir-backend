package org.stir.reference;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import org.stir.negotiation.CanonicalJson;

/** The same Ed25519 raw-key, unpadded-base64url and RFC 8785 wire conventions as osTRIS. */
final class SevenKeysCrypto {
    static final String DOMAIN="STIR:MARKET:CONSTITUTION:V1";
    static final String GUARDIAN_DOMAIN="STIR:MARKET:GUARDIAN:V1";
    static final String POSSESSION_DOMAIN="STIR:MARKET:POSSESSION:V1";
    static final String BOOTSTRAP_DOMAIN="STIR:MARKET:BOOTSTRAP:V1";
    private static final byte[] PREFIX=HexFormat.of().parseHex("302a300506032b6570032100");
    private SevenKeysCrypto() {}
    static byte[] message(String domain,Map<String,Object> payload) {
        byte[] a=domain.getBytes(StandardCharsets.US_ASCII), b=CanonicalJson.canonicalBytes(payload);
        byte[] out=Arrays.copyOf(a,a.length+1+b.length);
        System.arraycopy(b,0,out,a.length+1,b.length);
        return out;
    }
    static void requireSignature(String publicKey,String signature,byte[] message) {
        try {
            byte[] raw=decode(publicKey,32), sig=decode(signature,64);
            byte[] key=Arrays.copyOf(PREFIX,PREFIX.length+raw.length);
            System.arraycopy(raw,0,key,PREFIX.length,raw.length);
            var verifier=Signature.getInstance("Ed25519");
            verifier.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(key)));
            verifier.update(message);
            if(!verifier.verify(sig)) throw new IllegalArgumentException("Invalid Ed25519 signature");
        } catch(GeneralSecurityException e) { throw new IllegalArgumentException("Invalid Ed25519 key/signature",e); }
    }
    private static byte[] decode(String value,int length) {
        if(value==null || value.contains("=") || !value.matches("[A-Za-z0-9_-]+"))
            throw new IllegalArgumentException("Non-canonical base64url");
        byte[] bytes;
        try { bytes=Base64.getUrlDecoder().decode(value); }
        catch(IllegalArgumentException e) { throw new IllegalArgumentException("Invalid base64url",e); }
        if(bytes.length!=length || !Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(value))
            throw new IllegalArgumentException("Wrong base64url length or encoding");
        return bytes;
    }
}
