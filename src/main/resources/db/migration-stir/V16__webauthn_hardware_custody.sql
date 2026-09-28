-- WEBAUTHN_HARDWARE_CUSTODY.md: WebAuthn/hardware-backed credentials as an alternative custody
-- path for Seven Keys seats and the Guardian, alongside the existing same-device Ed25519 software
-- credential - never replacing 7-of-7, Guardian separation, or any other constitutional invariant.
-- Existing Ed25519 credentials keep working unchanged; credential_type defaults preserve that.

-- Existing Ed25519 raw keys stay byte-identical (32 raw bytes, base64url); WebAuthn COSE keys need
-- more room (an EC2/P-256 COSE key is roughly 120 base64url chars, RSA considerably more).
ALTER TABLE stir.constitutional_seat ALTER COLUMN public_key TYPE text;
ALTER TABLE stir.constitutional_credential_history ALTER COLUMN public_key TYPE text;
ALTER TABLE stir.constitutional_authority ALTER COLUMN guardian_public_key TYPE text;

ALTER TABLE stir.constitutional_seat ADD COLUMN credential_type varchar(20) NOT NULL DEFAULT 'SOFTWARE_ED25519'
 CHECK (credential_type IN ('SOFTWARE_ED25519','WEBAUTHN'));
ALTER TABLE stir.constitutional_seat ADD COLUMN algorithm varchar(20) NOT NULL DEFAULT 'Ed25519';
ALTER TABLE stir.constitutional_credential_history ADD COLUMN credential_type varchar(20) NOT NULL DEFAULT 'SOFTWARE_ED25519'
 CHECK (credential_type IN ('SOFTWARE_ED25519','WEBAUTHN'));
ALTER TABLE stir.constitutional_credential_history ADD COLUMN algorithm varchar(20) NOT NULL DEFAULT 'Ed25519';
ALTER TABLE stir.constitutional_authority ADD COLUMN guardian_credential_type varchar(20) NOT NULL DEFAULT 'SOFTWARE_ED25519'
 CHECK (guardian_credential_type IN ('SOFTWARE_ED25519','WEBAUTHN'));
ALTER TABLE stir.constitutional_authority ADD COLUMN guardian_algorithm varchar(20) NOT NULL DEFAULT 'Ed25519';

-- Re-grant the seat/authority UPDATE allowlists to cover the two new columns each - idax_app only.
-- V9 deliberately REVOKEd UPDATE on these same columns from idax_admin (administrative read access
-- is not authority to rotate constitutional credentials); granting TO idax_app,idax_admin here
-- again, as the original V6 grant did before V9 existed, would silently undo that restriction.
GRANT UPDATE(controller_id,credential_id,public_key,status,credential_type,algorithm)
 ON stir.constitutional_seat TO idax_app;
GRANT UPDATE(guardian_credential_id,guardian_public_key,guardian_status,guardian_credential_type,guardian_algorithm,next_sequence)
 ON stir.constitutional_authority TO idax_app;

-- A stored seat signature must be independently re-verifiable at activation time exactly like an
-- Ed25519 signature already is, so a WEBAUTHN seat's full assertion envelope (not just the raw
-- signature bytes, which alone cannot be re-verified without the challenge-binding clientDataJSON/
-- authenticatorData it was computed over) is persisted alongside it. Ed25519 rows leave both new
-- columns NULL, unchanged behavior. signature_base64url widens from varchar(90) (sized for a fixed
-- 64-byte Ed25519 signature) to text - an RSA-2048 assertion signature alone is ~342 base64url
-- characters.
ALTER TABLE stir.constitutional_signature ALTER COLUMN signature_base64url TYPE text;
ALTER TABLE stir.constitutional_signature ADD COLUMN client_data_json text;
ALTER TABLE stir.constitutional_signature ADD COLUMN authenticator_data text;

-- WebAuthn-specific material a WEBAUTHN-type credential carries in addition to its generic
-- public_key already on constitutional_seat/constitutional_credential_history above - kept in its
-- own table because sign_count is the one field that must mutate on every real use (native WebAuthn
-- clone/replay detection), a very different mutation cadence than the otherwise-append-only history
-- tables this schema is built around (V5/V6). No attestation statement, transports or AAGUID are
-- stored - none are needed for verification (attestation is deliberately not required by policy),
-- and storing them would be gratuitous authenticator fingerprinting this increment's own privacy
-- rule forbids. authority_id is not a foreign key here: a credential is registered and verified
-- before the authority row exists during a bootstrap ceremony, exactly like credentialId/
-- controllerId are already client-generated UUIDs before bootstrap submits them.
CREATE TABLE stir.constitutional_webauthn_credential (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, authority_id uuid NOT NULL,
 credential_id uuid NOT NULL, webauthn_credential_id text NOT NULL,
 rp_id varchar(255) NOT NULL, sign_count bigint NOT NULL DEFAULT 0,
 user_verification_required boolean NOT NULL DEFAULT true,
 created_at timestamptz NOT NULL,
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,credential_id), UNIQUE(tenant_id,webauthn_credential_id)
);

-- Registration-ceremony challenges: single-use, expiring, bound to actor/authority/purpose/context.
-- Signing (assertion) "challenges" need no separate table at all - they are deterministic
-- base64url(SHA-256(domain || 0x00 || JCS payload)) digests of an already-unique, already
-- domain-separated governance payload (WEBAUTHN_HARDWARE_CUSTODY.md), so their single-use property
-- is inherited from the payload's own uniqueness plus the pre-existing
-- constitutional_signature(proposal_id,seat_ordinal) uniqueness constraint - inventing a second,
-- redundant challenge-tracking mechanism for something already single-use by construction would be
-- exactly the "no inventes abstracción más grande de lo necesario" this increment was told to avoid.
CREATE TABLE stir.constitutional_webauthn_challenge (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, authority_id uuid NOT NULL,
 actor_id uuid NOT NULL, purpose varchar(20) NOT NULL CHECK (purpose IN ('REGISTRATION')),
 context varchar(60) NOT NULL, challenge text NOT NULL,
 expires_at timestamptz NOT NULL, consumed_at timestamptz,
 created_at timestamptz NOT NULL, UNIQUE(tenant_id,id)
);

DO $$ DECLARE tab text; BEGIN
 FOREACH tab IN ARRAY ARRAY['constitutional_webauthn_credential','constitutional_webauthn_challenge'] LOOP
  EXECUTE format('ALTER TABLE stir.%I ENABLE ROW LEVEL SECURITY',tab);
  EXECUTE format('ALTER TABLE stir.%I FORCE ROW LEVEL SECURITY',tab);
  EXECUTE format('CREATE POLICY %I ON stir.%I FOR ALL TO idax_app,idax_admin USING (tenant_id = NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid) WITH CHECK (tenant_id = NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid)',tab||'_tenant',tab);
  EXECUTE format('GRANT SELECT,INSERT ON stir.%I TO idax_app,idax_admin',tab);
 END LOOP;
END $$;
-- The two narrow, deliberate exceptions to "insert-only" in this migration: a signature counter
-- that must advance on every real assertion, and a challenge's own single-use consumption marker.
GRANT UPDATE(sign_count) ON stir.constitutional_webauthn_credential TO idax_app,idax_admin;
GRANT UPDATE(consumed_at) ON stir.constitutional_webauthn_challenge TO idax_app,idax_admin;
