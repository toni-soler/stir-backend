-- STIR-owned context. No osTRIS schema or protocol changes.
CREATE TABLE stir.reference_definition (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, community_id uuid NOT NULL, unit_id uuid NOT NULL,
 name varchar(160) NOT NULL, scope varchar(500) NOT NULL, attributes_json text NOT NULL,
 quantity_basis numeric(18,4) NOT NULL CHECK(quantity_basis > 0), quantity_unit varchar(40) NOT NULL,
 unit_ref varchar(60) NOT NULL, created_by uuid NOT NULL, created_at timestamptz NOT NULL,
 UNIQUE(tenant_id,id)
);
CREATE TABLE stir.reference_policy (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, definition_id uuid NOT NULL,
 version int NOT NULL CHECK(version > 0), window_days int NOT NULL CHECK(window_days BETWEEN 7 AND 365),
 minimum_observations int NOT NULL CHECK(minimum_observations >= 5),
 minimum_participants int NOT NULL CHECK(minimum_participants >= 6),
 maximum_participant_share numeric(5,4) NOT NULL CHECK(maximum_participant_share BETWEEN 0.1 AND 0.5),
 freshness_days int NOT NULL CHECK(freshness_days > 0 AND freshness_days <= window_days),
 explanation varchar(2000) NOT NULL, created_by uuid NOT NULL, created_at timestamptz NOT NULL,
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,definition_id,version),
 FOREIGN KEY(tenant_id,definition_id) REFERENCES stir.reference_definition(tenant_id,id)
);
ALTER TABLE stir.listing ADD COLUMN reference_definition_id uuid;
ALTER TABLE stir.listing ADD CONSTRAINT listing_reference_fk FOREIGN KEY(tenant_id,reference_definition_id)
 REFERENCES stir.reference_definition(tenant_id,id);
ALTER TABLE stir.negotiation ADD COLUMN reference_definition_id uuid;
ALTER TABLE stir.negotiation ADD CONSTRAINT negotiation_reference_fk FOREIGN KEY(tenant_id,reference_definition_id)
 REFERENCES stir.reference_definition(tenant_id,id);
ALTER TABLE stir.offer ADD COLUMN share_reference_observation boolean NOT NULL DEFAULT false;
CREATE TABLE stir.reference_observation (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, definition_id uuid NOT NULL,
 source varchar(20) NOT NULL CHECK(source IN ('LISTING','WANTED','PROPOSAL','AGREEMENT','COMMUNITY_SEED')),
 source_id uuid NOT NULL, participant_a uuid NOT NULL, participant_b uuid,
 amount numeric(18,2), quantity numeric(18,4), quantity_unit varchar(40), unit_ref varchar(60),
 aggregate_consent boolean NOT NULL DEFAULT false, observed_at timestamptz NOT NULL,
 UNIQUE(tenant_id,source,source_id), UNIQUE(tenant_id,id),
 FOREIGN KEY(tenant_id,definition_id) REFERENCES stir.reference_definition(tenant_id,id)
);
CREATE INDEX reference_observation_window ON stir.reference_observation(tenant_id,definition_id,observed_at);
CREATE TABLE stir.reference_snapshot (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, definition_id uuid NOT NULL, policy_id uuid NOT NULL,
 cutoff timestamptz NOT NULL, canonical_json text NOT NULL, digest_sha256 varchar(64) NOT NULL,
 evidence_json text NOT NULL, created_at timestamptz NOT NULL,
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,definition_id,policy_id,cutoff),
 FOREIGN KEY(tenant_id,definition_id) REFERENCES stir.reference_definition(tenant_id,id),
 FOREIGN KEY(tenant_id,policy_id) REFERENCES stir.reference_policy(tenant_id,id)
);
CREATE TABLE stir.reference_proposal (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, definition_id uuid NOT NULL, snapshot_id uuid NOT NULL,
 kind varchar(20) NOT NULL CHECK(kind IN ('VALUE','BAND','CONVENTION','QUALITATIVE')),
 lower_value numeric(18,2), upper_value numeric(18,2), explanation varchar(2000) NOT NULL,
 origin varchar(100) NOT NULL, valid_days int NOT NULL CHECK(valid_days BETWEEN 1 AND 365),
 proposed_by uuid NOT NULL, proposed_at timestamptz NOT NULL,
 CHECK((kind='QUALITATIVE' AND lower_value IS NULL AND upper_value IS NULL) OR
       (kind<>'QUALITATIVE' AND lower_value >= 0 AND upper_value >= lower_value)),
 UNIQUE(tenant_id,id),
 FOREIGN KEY(tenant_id,definition_id) REFERENCES stir.reference_definition(tenant_id,id),
 FOREIGN KEY(tenant_id,snapshot_id) REFERENCES stir.reference_snapshot(tenant_id,id)
);
CREATE TABLE stir.community_reference (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, definition_id uuid NOT NULL, proposal_id uuid NOT NULL,
 version int NOT NULL CHECK(version > 0), published_by uuid NOT NULL, decision varchar(2000) NOT NULL,
 valid_from timestamptz NOT NULL, valid_until timestamptz NOT NULL CHECK(valid_until > valid_from),
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,proposal_id), UNIQUE(tenant_id,definition_id,version),
 FOREIGN KEY(tenant_id,definition_id) REFERENCES stir.reference_definition(tenant_id,id),
 FOREIGN KEY(tenant_id,proposal_id) REFERENCES stir.reference_proposal(tenant_id,id)
);
ALTER TABLE stir.agreement ADD CONSTRAINT agreement_tenant_id_unique UNIQUE(tenant_id,id);
CREATE TABLE stir.reference_context_snapshot (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, agreement_id uuid NOT NULL,
 canonical_json text NOT NULL, digest_sha256 varchar(64) NOT NULL, created_at timestamptz NOT NULL,
 UNIQUE(tenant_id,agreement_id),
 FOREIGN KEY(tenant_id,agreement_id) REFERENCES stir.agreement(tenant_id,id)
);
CREATE FUNCTION stir.reject_reference_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Published reference history is append-only'; END $$;
DO $$ DECLARE tab text; BEGIN
 FOREACH tab IN ARRAY ARRAY['reference_definition','reference_policy','reference_observation','reference_snapshot',
 'reference_proposal','community_reference','reference_context_snapshot'] LOOP
  EXECUTE format('ALTER TABLE stir.%I ENABLE ROW LEVEL SECURITY',tab);
  EXECUTE format('ALTER TABLE stir.%I FORCE ROW LEVEL SECURITY',tab);
  EXECUTE format('CREATE POLICY %I ON stir.%I FOR ALL TO idax_app,idax_admin USING (tenant_id = NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid) WITH CHECK (tenant_id = NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid)',tab||'_tenant',tab);
  EXECUTE format('GRANT SELECT,INSERT ON stir.%I TO idax_app,idax_admin',tab);
  EXECUTE format('CREATE TRIGGER immutable_history BEFORE UPDATE OR DELETE ON stir.%I FOR EACH ROW EXECUTE FUNCTION stir.reject_reference_mutation()',tab);
 END LOOP;
END $$;
