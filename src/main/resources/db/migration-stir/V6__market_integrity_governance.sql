-- STIR market semantics stay outside osTRIS. All evidence and governance events are tenant scoped.
CREATE TABLE stir.market_constitution (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, community_id uuid NOT NULL,
 authority_id uuid NOT NULL, version int NOT NULL CHECK(version > 0),
 canonical_json text NOT NULL, digest_sha256 varchar(64) NOT NULL,
 activated_at timestamptz NOT NULL, UNIQUE(tenant_id,id), UNIQUE(tenant_id,community_id,version)
);
CREATE TABLE stir.constitutional_authority (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, community_id uuid NOT NULL,
 threshold int NOT NULL DEFAULT 7 CHECK(threshold = 7),
 guardian_credential_id uuid NOT NULL, guardian_public_key varchar(44) NOT NULL,
 guardian_status varchar(20) NOT NULL CHECK(guardian_status IN ('ACTIVE','REMOVED')),
 next_sequence bigint NOT NULL DEFAULT 1, created_at timestamptz NOT NULL,
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,community_id)
);
CREATE TABLE stir.constitutional_seat (
 tenant_id uuid NOT NULL, authority_id uuid NOT NULL,
 ordinal int NOT NULL CHECK(ordinal BETWEEN 1 AND 7),
 controller_id uuid NOT NULL, credential_id uuid NOT NULL,
 public_key varchar(44) NOT NULL,
 status varchar(40) NOT NULL CHECK(status IN ('ACTIVE','EMERGENCY_SUSPENDED','RECOVERY_PENDING','CONTROLLER_REPLACEMENT_PENDING')),
 PRIMARY KEY(tenant_id,authority_id,ordinal), UNIQUE(tenant_id,authority_id,controller_id),
 UNIQUE(tenant_id,authority_id,credential_id),
 FOREIGN KEY(tenant_id,authority_id) REFERENCES stir.constitutional_authority(tenant_id,id)
);
CREATE TABLE stir.constitutional_credential_history (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, authority_id uuid NOT NULL,
 seat_ordinal int NOT NULL, controller_id uuid NOT NULL, credential_id uuid NOT NULL,
 public_key varchar(44) NOT NULL, status varchar(32) NOT NULL,
 event_sequence bigint NOT NULL, UNIQUE(tenant_id,id),
 FOREIGN KEY(tenant_id,authority_id,seat_ordinal) REFERENCES stir.constitutional_seat(tenant_id,authority_id,ordinal)
);
CREATE TABLE stir.constitutional_proposal (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, authority_id uuid NOT NULL,
 community_id uuid NOT NULL, authority_version int NOT NULL,
 action_type varchar(40) NOT NULL CHECK(action_type IN ('AMEND_CONSTITUTION','REMOVE_GUARDIAN','APPOINT_GUARDIAN','ROTATE_CREDENTIAL','REPLACE_CONTROLLER')),
 before_digest varchar(64) NOT NULL, after_digest varchar(64) NOT NULL,
 after_json text NOT NULL, affected_fields_json text NOT NULL,
 reason varchar(2000) NOT NULL, evidence_refs_json text NOT NULL,
 sequence bigint NOT NULL, payload_json text NOT NULL, payload_digest varchar(64) NOT NULL,
 created_at timestamptz NOT NULL, UNIQUE(tenant_id,id),
 FOREIGN KEY(tenant_id,authority_id) REFERENCES stir.constitutional_authority(tenant_id,id)
);
CREATE TABLE stir.constitutional_signature (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, proposal_id uuid NOT NULL,
 seat_ordinal int NOT NULL, credential_id uuid NOT NULL,
 signature_base64url varchar(90) NOT NULL, signed_at timestamptz NOT NULL,
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,proposal_id,seat_ordinal),
 FOREIGN KEY(tenant_id,proposal_id) REFERENCES stir.constitutional_proposal(tenant_id,id)
);
CREATE TABLE stir.market_governance_event (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, authority_id uuid NOT NULL,
 sequence bigint NOT NULL, event_type varchar(50) NOT NULL,
 payload_json text NOT NULL, previous_digest varchar(64), digest_sha256 varchar(64) NOT NULL,
 recorded_at timestamptz NOT NULL, UNIQUE(tenant_id,id), UNIQUE(tenant_id,authority_id,sequence),
 FOREIGN KEY(tenant_id,authority_id) REFERENCES stir.constitutional_authority(tenant_id,id)
);
CREATE TABLE stir.market_integrity_case (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, definition_id uuid NOT NULL,
 observation_id uuid NOT NULL, signal_code varchar(60) NOT NULL,
 reason varchar(2000) NOT NULL, evidence_refs_json text NOT NULL,
 created_by uuid NOT NULL, created_at timestamptz NOT NULL,
 UNIQUE(tenant_id,id),
 FOREIGN KEY(tenant_id,definition_id) REFERENCES stir.reference_definition(tenant_id,id),
 FOREIGN KEY(tenant_id,observation_id) REFERENCES stir.reference_observation(tenant_id,id)
);
CREATE TABLE stir.market_integrity_case_event (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, case_id uuid NOT NULL,
 status varchar(20) NOT NULL, reason varchar(2000) NOT NULL,
 actor_id uuid NOT NULL, recorded_at timestamptz NOT NULL, UNIQUE(tenant_id,id),
 FOREIGN KEY(tenant_id,case_id) REFERENCES stir.market_integrity_case(tenant_id,id)
);
CREATE FUNCTION stir.reject_market_history_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'Market integrity history is append-only'; END $$;
DO $$ DECLARE tab text; BEGIN
 FOREACH tab IN ARRAY ARRAY['market_constitution','constitutional_authority','constitutional_seat',
 'constitutional_credential_history','constitutional_proposal','constitutional_signature',
 'market_governance_event','market_integrity_case','market_integrity_case_event'] LOOP
  EXECUTE format('ALTER TABLE stir.%I ENABLE ROW LEVEL SECURITY',tab);
  EXECUTE format('ALTER TABLE stir.%I FORCE ROW LEVEL SECURITY',tab);
  EXECUTE format('CREATE POLICY %I ON stir.%I FOR ALL TO idax_app,idax_admin USING (tenant_id = NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid) WITH CHECK (tenant_id = NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid)',tab||'_tenant',tab);
  EXECUTE format('GRANT SELECT,INSERT ON stir.%I TO idax_app,idax_admin',tab);
 END LOOP;
 FOREACH tab IN ARRAY ARRAY['market_constitution','constitutional_credential_history',
 'constitutional_proposal','constitutional_signature','market_governance_event','market_integrity_case',
 'market_integrity_case_event'] LOOP
  EXECUTE format('CREATE TRIGGER immutable_market_history BEFORE UPDATE OR DELETE ON stir.%I FOR EACH ROW EXECUTE FUNCTION stir.reject_market_history_mutation()',tab);
 END LOOP;
END $$;
GRANT UPDATE(next_sequence,guardian_credential_id,guardian_public_key,guardian_status)
 ON stir.constitutional_authority TO idax_app,idax_admin;
GRANT UPDATE(controller_id,credential_id,public_key,status)
 ON stir.constitutional_seat TO idax_app,idax_admin;
