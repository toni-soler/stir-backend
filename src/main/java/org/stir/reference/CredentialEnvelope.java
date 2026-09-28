package org.stir.reference;

/** The minimal credential/signature abstraction WEBAUTHN_HARDWARE_CUSTODY.md asks for: the
 * governance payload/message a seat or the Guardian signs stays exactly the same logical object
 * (SevenKeysService's domain-separated JCS payloads, unchanged) regardless of which credential
 * produced the proof - only this envelope varies by credential type. Deliberately not a larger
 * polymorphic hierarchy: one flat record covers every credential type this codebase supports today
 * (SOFTWARE_ED25519's plain signature) and the one being added (WEBAUTHN's three-part assertion),
 * with room for a future hardware/offline type to reuse the same shape without a new abstraction.
 *
 * clientDataJson/authenticatorData are null for SOFTWARE_ED25519 - Ed25519 signs the message bytes
 * directly, it has no separate "verification context" the way a WebAuthn assertion does. */
record CredentialEnvelope(String credentialType, String algorithm, String signature,
                           String clientDataJson, String authenticatorData) {
    static CredentialEnvelope ed25519(String signatureBase64url) {
        return new CredentialEnvelope("SOFTWARE_ED25519", "Ed25519", signatureBase64url, null, null);
    }
    /** Normalizes the additive legacy-vs-envelope pair every SevenKeysService input record now
     * carries: an explicit envelope always wins; otherwise the legacy plain-string field (if
     * present) is wrapped as Ed25519 - the same "old wire shape keeps working verbatim" rule as
     * every prior additive constructor in this codebase. */
    static CredentialEnvelope of(String legacySignatureBase64url, CredentialEnvelope envelope) {
        if (envelope != null) return envelope;
        if (legacySignatureBase64url != null) return ed25519(legacySignatureBase64url);
        return null;
    }
}
