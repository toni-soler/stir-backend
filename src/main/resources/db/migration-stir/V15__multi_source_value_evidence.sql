-- Multi-Source Value Evidence MVP: LISTING/WANTED/COMMUNITY_SEED. See MULTI_SOURCE_VALUE_EVIDENCE.md.
-- OBSERVATION != REFERENCE != AGREEMENT, and now also LISTING != WANTED != PROPOSAL != AGREEMENT !=
-- COMMUNITY_SEED - reference_observation.source already allowed all five values since V5; this
-- increment is the first to actually populate LISTING/WANTED/COMMUNITY_SEED.

-- A listing may volunteer an indicative price for its own OFFER/WANTED direction - opt-in, mirroring
-- Offer's own proposedAmount/proposedUnitRef/shareReferenceObservation fields exactly. This is the
-- owner's own ask, never "what the market accepts" - EvidenceAnalysis keeps it in its own separate
-- descriptive bucket, never blended into the AGREEMENT median.
ALTER TABLE stir.listing ADD COLUMN indicative_amount numeric(18,2);
ALTER TABLE stir.listing ADD COLUMN indicative_quantity numeric(18,4);
ALTER TABLE stir.listing ADD COLUMN indicative_unit_label varchar(40);
ALTER TABLE stir.listing ADD COLUMN indicative_unit_ref varchar(60);
ALTER TABLE stir.listing ADD COLUMN share_reference_observation boolean NOT NULL DEFAULT false;
-- stir.listing only ever had a single-column PK (id) - add the (tenant_id,id) composite so
-- listing_revision below can reference it the same way every reference_* table already does.
ALTER TABLE stir.listing ADD CONSTRAINT listing_tenant_id_unique UNIQUE(tenant_id,id);

-- Immutable point-in-time snapshot, created on every listing create/update. reference_observation
-- for a LISTING/WANTED source points here, never at stir.listing.id directly - editing a listing
-- must never rewrite what an earlier observation saw (the same freeze-at-creation convention as
-- Offer/Agreement, applied to Listing for the first time).
CREATE TABLE stir.listing_revision (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, listing_id uuid NOT NULL, revision_number int NOT NULL CHECK(revision_number > 0),
 owner_id uuid NOT NULL, direction varchar(10) NOT NULL, title varchar(160) NOT NULL,
 indicative_amount numeric(18,2), indicative_quantity numeric(18,4), indicative_unit_label varchar(40), indicative_unit_ref varchar(60),
 reference_definition_id uuid, share_reference_observation boolean NOT NULL,
 canonical_json text NOT NULL, digest_sha256 varchar(64) NOT NULL, created_at timestamptz NOT NULL,
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,listing_id,revision_number),
 FOREIGN KEY(tenant_id,listing_id) REFERENCES stir.listing(tenant_id,id),
 FOREIGN KEY(tenant_id,reference_definition_id) REFERENCES stir.reference_definition(tenant_id,id)
);
CREATE INDEX listing_revision_listing ON stir.listing_revision(tenant_id,listing_id);

-- Economic lineage: the minimal correlation-group concept so one economic process (a listing, any
-- proposal/counterproposal negotiated on it, and its eventual Agreement) never counts as several
-- independent voices. NULL for COMMUNITY_SEED, which is not an economic process at all. Populated
-- as the originating stir.listing.id for every source (LISTING/WANTED observations use their own
-- listing directly; PROPOSAL/AGREEMENT observations use negotiation.listingId) - no new UUID
-- generation needed, and every observation that ever traces back to the same listing automatically
-- shares the same lineage.
ALTER TABLE stir.reference_observation ADD COLUMN economic_lineage_id uuid;
CREATE INDEX reference_observation_lineage ON stir.reference_observation(tenant_id,definition_id,economic_lineage_id);

-- Widen the anonymization-only trigger (V14) so economic_lineage_id is also required to stay
-- byte-identical - a column added after that trigger was written must not become an accidental
-- loophole in its own immutability guarantee.
CREATE OR REPLACE FUNCTION stir.reject_reference_observation_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
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
    OR NEW.aggregate_consent <> OLD.aggregate_consent OR NEW.observed_at <> OLD.observed_at
    OR NEW.economic_lineage_id IS DISTINCT FROM OLD.economic_lineage_id THEN
  RAISE EXCEPTION 'Only anonymizing participant_a/participant_b is permitted on reference_observation';
 END IF;
 RETURN NEW;
END $$;

-- Per-definition source eligibility: which non-AGREEMENT sources may contribute at all. Nullable-
-- with-default false, so every existing definition/policy keeps behaving exactly as before -
-- LISTING/WANTED contribute nothing until a publisher/community explicitly opts in. This is an
-- ordinary policy parameter (REFERENCE_POLICY_CHANGE already covers it), not constitutional -
-- widening the input surface never disables provenance/independence/history invariants, which
-- every added observation still has to pass exactly like an AGREEMENT does.
ALTER TABLE stir.reference_policy ADD COLUMN listing_source_enabled boolean NOT NULL DEFAULT false;
ALTER TABLE stir.reference_policy ADD COLUMN wanted_source_enabled boolean NOT NULL DEFAULT false;

-- Purpose-specific consent, generalized beyond the single REFERENCE_EVIDENCE_CONTRIBUTION purpose
-- Consent/Retention shipped with: a listing/wanted post has exactly one party behind it (never
-- bilateral), so it needs its own distinct purpose, never reusing Agreement's.
DO $$ DECLARE conname text; BEGIN
 SELECT c.conname INTO conname FROM pg_constraint c JOIN pg_class t ON t.oid=c.conrelid JOIN pg_namespace n ON n.oid=t.relnamespace
  WHERE n.nspname='stir' AND t.relname='reference_consent' AND c.contype='c' AND pg_get_constraintdef(c.oid) LIKE '%purpose%';
 EXECUTE format('ALTER TABLE stir.reference_consent DROP CONSTRAINT %I',conname);
END $$;
ALTER TABLE stir.reference_consent ADD CONSTRAINT reference_consent_purpose_check
 CHECK(purpose IN ('REFERENCE_EVIDENCE_CONTRIBUTION','LISTING_EVIDENCE_CONTRIBUTION'));

-- Community Seed: an explicit, always-governed normative orientation - never reachable through a
-- delegated-publisher direct path (unlike PUBLISH_REFERENCE/REFERENCE_POLICY_CHANGE, there is no
-- publishSeedDirect() bypass anywhere in this codebase; the only writer is
-- OrdinaryGovernanceService.execute() for an approved COMMUNITY_SEED_PUBLICATION proposal). Fully
-- immutable and versioned exactly like community_reference - a later seed is a new row, never a
-- rewrite of an earlier one.
CREATE TABLE stir.community_seed (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, definition_id uuid NOT NULL, proposal_id uuid NOT NULL,
 version int NOT NULL CHECK(version > 0), kind varchar(20) NOT NULL CHECK(kind IN ('VALUE','BAND','QUALITATIVE')),
 lower_value numeric(18,2), upper_value numeric(18,2), rationale varchar(2000) NOT NULL, basis varchar(100) NOT NULL,
 valid_from timestamptz NOT NULL, valid_until timestamptz NOT NULL CHECK(valid_until > valid_from),
 created_by uuid NOT NULL, created_at timestamptz NOT NULL,
 CHECK((kind='QUALITATIVE' AND lower_value IS NULL AND upper_value IS NULL) OR
       (kind<>'QUALITATIVE' AND lower_value IS NOT NULL AND upper_value IS NOT NULL AND upper_value >= lower_value)),
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,proposal_id), UNIQUE(tenant_id,definition_id,version),
 FOREIGN KEY(tenant_id,definition_id) REFERENCES stir.reference_definition(tenant_id,id),
 FOREIGN KEY(tenant_id,proposal_id) REFERENCES stir.ordinary_proposal(tenant_id,id)
);

DO $$ DECLARE conname text; BEGIN
 SELECT c.conname INTO conname FROM pg_constraint c JOIN pg_class t ON t.oid=c.conrelid JOIN pg_namespace n ON n.oid=t.relnamespace
  WHERE n.nspname='stir' AND t.relname='ordinary_proposal' AND c.contype='c' AND pg_get_constraintdef(c.oid) LIKE '%proposal_type%';
 EXECUTE format('ALTER TABLE stir.ordinary_proposal DROP CONSTRAINT %I',conname);
END $$;
ALTER TABLE stir.ordinary_proposal ADD CONSTRAINT ordinary_proposal_proposal_type_check
 CHECK(proposal_type IN ('PUBLISH_REFERENCE','REFERENCE_POLICY_CHANGE','RETENTION_POLICY_CHANGE','COMMUNITY_SEED_PUBLICATION'));

DO $$ DECLARE tab text; BEGIN
 FOREACH tab IN ARRAY ARRAY['listing_revision','community_seed'] LOOP
  EXECUTE format('ALTER TABLE stir.%I ENABLE ROW LEVEL SECURITY',tab);
  EXECUTE format('ALTER TABLE stir.%I FORCE ROW LEVEL SECURITY',tab);
  EXECUTE format('CREATE POLICY %I ON stir.%I FOR ALL TO idax_app,idax_admin USING (tenant_id = NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid) WITH CHECK (tenant_id = NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid)',tab||'_tenant',tab);
  EXECUTE format('GRANT SELECT,INSERT ON stir.%I TO idax_app,idax_admin',tab);
  EXECUTE format('CREATE TRIGGER immutable_history BEFORE UPDATE OR DELETE ON stir.%I FOR EACH ROW EXECUTE FUNCTION stir.reject_reference_mutation()',tab);
 END LOOP;
END $$;
