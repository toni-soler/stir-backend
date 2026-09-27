-- Consent/Retention MVP. Distinguishes: having a datum, having permission to use it, still being
-- allowed to retain it, and still being eligible as current evidence. See CONSENT_RETENTION.md.

-- Purpose-specific consent per party per observation. Grant/decline is captured automatically at
-- Agreement acceptance time (the existing bilateral shareReferenceObservation flow); withdraw is the
-- only action a party takes later, through the new self-service consent endpoint. Never a generic
-- "accept everything" consent - purpose is explicit and, today, a single real value.
CREATE TABLE stir.reference_consent (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, observation_id uuid NOT NULL, definition_id uuid NOT NULL,
 party_user_id uuid NOT NULL, purpose varchar(60) NOT NULL CHECK(purpose IN ('REFERENCE_EVIDENCE_CONTRIBUTION')),
 notice_version int NOT NULL CHECK(notice_version > 0), created_at timestamptz NOT NULL,
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,observation_id,party_user_id,purpose),
 FOREIGN KEY(tenant_id,observation_id) REFERENCES stir.reference_observation(tenant_id,id),
 FOREIGN KEY(tenant_id,definition_id) REFERENCES stir.reference_definition(tenant_id,id)
);
-- Append-only lifecycle: current status is the latest event by sequence. GRANT/DECLINE is recorded
-- once at acceptance time; WITHDRAW is the only transition a party can add later, and only once it
-- is meaningful (idempotent at the service layer - a repeated withdraw is a no-op, not a new row).
CREATE TABLE stir.reference_consent_event (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, consent_id uuid NOT NULL, sequence int NOT NULL,
 action varchar(10) NOT NULL CHECK(action IN ('GRANT','DECLINE','WITHDRAW')),
 recorded_by uuid NOT NULL, reason varchar(2000), recorded_at timestamptz NOT NULL,
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,consent_id,sequence),
 FOREIGN KEY(tenant_id,consent_id) REFERENCES stir.reference_consent(tenant_id,id)
);

-- Versioned, community-scoped retention policy. Deliberately not a legal engine: only what STIR's
-- own observation lifecycle needs. retention_period_days has a code-level floor (RetentionService.
-- MINIMUM_RETENTION_PERIOD_DAYS) - deliberately NOT wired into the Seven Keys constitution (that
-- sacred schema stays untouched); the floor is simply not configurable below it, the same fail-closed
-- style as REPLACE_CONTROLLER, not a Seven Keys amendment path.
CREATE TABLE stir.retention_policy (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, community_id uuid NOT NULL, version int NOT NULL CHECK(version > 0),
 retention_period_days int NOT NULL CHECK(retention_period_days >= 90),
 basis varchar(60) NOT NULL CHECK(basis IN ('COMMUNITY_REFERENCE_AND_INTEGRITY_HISTORY')),
 explanation varchar(2000) NOT NULL, created_by uuid NOT NULL, created_at timestamptz NOT NULL,
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,community_id,version)
);

-- Immutable audit of the one lifecycle transition that actually mutates reference_observation.
CREATE TABLE stir.retention_lifecycle_event (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, observation_id uuid NOT NULL,
 action varchar(20) NOT NULL CHECK(action IN ('ANONYMIZED')),
 actor_id uuid NOT NULL, reason varchar(2000) NOT NULL, recorded_at timestamptz NOT NULL,
 UNIQUE(tenant_id,id),
 FOREIGN KEY(tenant_id,observation_id) REFERENCES stir.reference_observation(tenant_id,id)
);

DO $$ DECLARE tab text; BEGIN
 FOREACH tab IN ARRAY ARRAY['reference_consent','reference_consent_event','retention_policy','retention_lifecycle_event'] LOOP
  EXECUTE format('ALTER TABLE stir.%I ENABLE ROW LEVEL SECURITY',tab);
  EXECUTE format('ALTER TABLE stir.%I FORCE ROW LEVEL SECURITY',tab);
  EXECUTE format('CREATE POLICY %I ON stir.%I FOR ALL TO idax_app,idax_admin USING (tenant_id = NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid) WITH CHECK (tenant_id = NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid)',tab||'_tenant',tab);
  EXECUTE format('GRANT SELECT,INSERT ON stir.%I TO idax_app,idax_admin',tab);
  EXECUTE format('CREATE TRIGGER immutable_history BEFORE UPDATE OR DELETE ON stir.%I FOR EACH ROW EXECUTE FUNCTION stir.reject_reference_mutation()',tab);
 END LOOP;
END $$;

-- reference_observation itself stays append-only for every column that already existed: consent
-- withdrawal and retention expiry NEVER rewrite the historical fact of what was observed. The one
-- narrow, real exception is anonymization - removing the participant identifiers once retention has
-- genuinely expired, no market-integrity case ever touched this observation, and it has not already
-- been anonymized. Everything else about the row (amount, quantity, observed_at, aggregate_consent,
-- source, id) is byte-identical forever; a past reference_snapshot that already included this
-- observation is never recomputed or invalidated by this.
ALTER TABLE stir.reference_observation ALTER COLUMN participant_a DROP NOT NULL;
ALTER TABLE stir.reference_observation ADD COLUMN anonymized_at timestamptz;
ALTER TABLE stir.reference_observation ADD COLUMN anonymized_by uuid;
DROP TRIGGER immutable_history ON stir.reference_observation;
CREATE FUNCTION stir.reject_reference_observation_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP = 'DELETE' THEN
  RAISE EXCEPTION 'Reference observation history is append-only';
 END IF;
 IF OLD.anonymized_at IS NOT NULL THEN
  RAISE EXCEPTION 'Reference observation is already anonymized';
 END IF;
 IF NEW.participant_a IS NOT NULL OR NEW.anonymized_at IS NULL OR NEW.anonymized_by IS NULL
    OR NEW.id <> OLD.id OR NEW.tenant_id <> OLD.tenant_id OR NEW.definition_id <> OLD.definition_id
    OR NEW.source <> OLD.source OR NEW.source_id <> OLD.source_id
    OR NEW.amount IS DISTINCT FROM OLD.amount OR NEW.quantity IS DISTINCT FROM OLD.quantity
    OR NEW.quantity_unit IS DISTINCT FROM OLD.quantity_unit OR NEW.unit_ref IS DISTINCT FROM OLD.unit_ref
    OR NEW.aggregate_consent <> OLD.aggregate_consent OR NEW.observed_at <> OLD.observed_at THEN
  RAISE EXCEPTION 'Only anonymizing participant_a/participant_b is permitted on reference_observation';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER immutable_history BEFORE UPDATE OR DELETE ON stir.reference_observation
 FOR EACH ROW EXECUTE FUNCTION stir.reject_reference_observation_mutation();
GRANT UPDATE(participant_a,participant_b,anonymized_at,anonymized_by) ON stir.reference_observation TO idax_app,idax_admin;

-- Retention policy is community-scoped, not per-definition (like ordinary_governance_policy/
-- community_governance_member already are) - ordinary_proposal.definition_id was NOT NULL because
-- every proposal type until now was definition-scoped. Widen it rather than force a fake
-- definition_id onto a RETENTION_POLICY_CHANGE proposal; community_id (already NOT NULL on this
-- table) remains the real scoping column for every proposal type.
ALTER TABLE stir.ordinary_proposal ALTER COLUMN definition_id DROP NOT NULL;
DO $$ DECLARE conname text; BEGIN
 SELECT c.conname INTO conname FROM pg_constraint c JOIN pg_class t ON t.oid=c.conrelid JOIN pg_namespace n ON n.oid=t.relnamespace
  WHERE n.nspname='stir' AND t.relname='ordinary_proposal' AND c.contype='c' AND pg_get_constraintdef(c.oid) LIKE '%proposal_type%';
 EXECUTE format('ALTER TABLE stir.ordinary_proposal DROP CONSTRAINT %I',conname);
END $$;
ALTER TABLE stir.ordinary_proposal ADD CONSTRAINT ordinary_proposal_proposal_type_check
 CHECK(proposal_type IN ('PUBLISH_REFERENCE','REFERENCE_POLICY_CHANGE','RETENTION_POLICY_CHANGE'));
