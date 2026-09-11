-- STIR 0.2 marketplace: participant presence, offers, negotiation history, agreements and
-- their immutable contractual snapshot. Additive to V1; empty-database migration must still pass.

CREATE TABLE stir.participant_profile (
 id uuid PRIMARY KEY,
 tenant_id uuid NOT NULL,
 user_id uuid NOT NULL,
 display_name varchar(80) NOT NULL CHECK (length(trim(display_name)) > 0),
 bio varchar(500),
 location varchar(160),
 active boolean NOT NULL DEFAULT true,
 version bigint NOT NULL DEFAULT 0,
 created_at timestamptz NOT NULL,
 updated_at timestamptz NOT NULL,
 UNIQUE (tenant_id, user_id)
);

CREATE TABLE stir.negotiation (
 id uuid PRIMARY KEY,
 tenant_id uuid NOT NULL,
 listing_id uuid NOT NULL REFERENCES stir.listing(id),
 initiator_id uuid NOT NULL,
 owner_id uuid NOT NULL CHECK (owner_id <> initiator_id),
 status varchar(10) NOT NULL CHECK (status IN ('OPEN','ACCEPTED','DECLINED')),
 last_offer_id uuid,
 version bigint NOT NULL DEFAULT 0,
 created_at timestamptz NOT NULL,
 updated_at timestamptz NOT NULL
);
CREATE UNIQUE INDEX negotiation_one_open_thread ON stir.negotiation(tenant_id, listing_id, initiator_id) WHERE status = 'OPEN';
CREATE INDEX negotiation_tenant_initiator ON stir.negotiation(tenant_id, initiator_id, created_at DESC, id);
CREATE INDEX negotiation_tenant_owner ON stir.negotiation(tenant_id, owner_id, created_at DESC, id);
CREATE INDEX negotiation_tenant_listing ON stir.negotiation(tenant_id, listing_id);

CREATE TABLE stir.offer (
 id uuid PRIMARY KEY,
 tenant_id uuid NOT NULL,
 negotiation_id uuid NOT NULL REFERENCES stir.negotiation(id),
 listing_id uuid NOT NULL REFERENCES stir.listing(id),
 sequence_number int NOT NULL CHECK (sequence_number > 0),
 author_id uuid NOT NULL,
 previous_offer_id uuid REFERENCES stir.offer(id),
 message varchar(2000) NOT NULL CHECK (length(trim(message)) > 0),
 quantity numeric(18,4) CHECK (quantity IS NULL OR quantity > 0),
 unit_label varchar(40),
 proposed_amount numeric(18,2) CHECK (proposed_amount IS NULL OR proposed_amount >= 0),
 proposed_unit_ref varchar(60),
 terms varchar(2000),
 status varchar(10) NOT NULL CHECK (status IN ('PROPOSED','SUPERSEDED','ACCEPTED','DECLINED')),
 created_at timestamptz NOT NULL,
 UNIQUE (negotiation_id, sequence_number)
);
CREATE INDEX offer_tenant_negotiation ON stir.offer(tenant_id, negotiation_id, sequence_number);

ALTER TABLE stir.negotiation ADD CONSTRAINT negotiation_last_offer_fk FOREIGN KEY (last_offer_id) REFERENCES stir.offer(id);

CREATE TABLE stir.agreement (
 id uuid PRIMARY KEY,
 tenant_id uuid NOT NULL,
 negotiation_id uuid NOT NULL UNIQUE REFERENCES stir.negotiation(id),
 listing_id uuid NOT NULL REFERENCES stir.listing(id),
 offer_id uuid NOT NULL REFERENCES stir.offer(id),
 initiator_id uuid NOT NULL,
 owner_id uuid NOT NULL,
 economic_phase varchar(40) NOT NULL DEFAULT 'AWAITING_ECONOMIC_EXECUTION' CHECK (economic_phase IN ('AWAITING_ECONOMIC_EXECUTION')),
 version bigint NOT NULL DEFAULT 0,
 created_at timestamptz NOT NULL
);
CREATE INDEX agreement_tenant_initiator ON stir.agreement(tenant_id, initiator_id, created_at DESC, id);
CREATE INDEX agreement_tenant_owner ON stir.agreement(tenant_id, owner_id, created_at DESC, id);

-- Immutable contractual commitment: canonical bytes + private nonce + digest, frozen at accept
-- time and independent of later Listing/ParticipantProfile edits. See AgreementSnapshotService.
CREATE TABLE stir.agreement_snapshot (
 id uuid PRIMARY KEY,
 tenant_id uuid NOT NULL,
 agreement_id uuid NOT NULL UNIQUE REFERENCES stir.agreement(id),
 schema_version int NOT NULL,
 canonical_json text NOT NULL,
 nonce varchar(64) NOT NULL,
 digest_sha256 varchar(64) NOT NULL CHECK (digest_sha256 ~ '^[0-9a-f]{64}$'),
 created_at timestamptz NOT NULL
);

ALTER TABLE stir.participant_profile ENABLE ROW LEVEL SECURITY;
ALTER TABLE stir.participant_profile FORCE ROW LEVEL SECURITY;
ALTER TABLE stir.negotiation ENABLE ROW LEVEL SECURITY;
ALTER TABLE stir.negotiation FORCE ROW LEVEL SECURITY;
ALTER TABLE stir.offer ENABLE ROW LEVEL SECURITY;
ALTER TABLE stir.offer FORCE ROW LEVEL SECURITY;
ALTER TABLE stir.agreement ENABLE ROW LEVEL SECURITY;
ALTER TABLE stir.agreement FORCE ROW LEVEL SECURITY;
ALTER TABLE stir.agreement_snapshot ENABLE ROW LEVEL SECURITY;
ALTER TABLE stir.agreement_snapshot FORCE ROW LEVEL SECURITY;

CREATE POLICY participant_profile_tenant ON stir.participant_profile FOR ALL TO idax_app, idax_admin
 USING (tenant_id = NULLIF(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id',true),'')::uuid);
CREATE POLICY negotiation_tenant ON stir.negotiation FOR ALL TO idax_app, idax_admin
 USING (tenant_id = NULLIF(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id',true),'')::uuid);
CREATE POLICY offer_tenant ON stir.offer FOR ALL TO idax_app, idax_admin
 USING (tenant_id = NULLIF(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id',true),'')::uuid);
CREATE POLICY agreement_tenant ON stir.agreement FOR ALL TO idax_app, idax_admin
 USING (tenant_id = NULLIF(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id',true),'')::uuid);
CREATE POLICY agreement_snapshot_tenant ON stir.agreement_snapshot FOR ALL TO idax_app, idax_admin
 USING (tenant_id = NULLIF(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id',true),'')::uuid);

GRANT SELECT, INSERT, UPDATE ON stir.participant_profile TO idax_app, idax_admin;
GRANT SELECT, INSERT, UPDATE ON stir.negotiation TO idax_app, idax_admin;
GRANT SELECT, INSERT, UPDATE ON stir.offer TO idax_app, idax_admin;
GRANT SELECT, INSERT, UPDATE ON stir.agreement TO idax_app, idax_admin;
GRANT SELECT, INSERT ON stir.agreement_snapshot TO idax_app, idax_admin;
