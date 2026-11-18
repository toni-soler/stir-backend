-- Agreement cases (stir-doc/AGREEMENT_CASES.md): reviewing an unfulfilled obligation without
-- judging the person. The dispute, the finding about the effect, the attribution of conduct, a
-- sanction and an appeal are separate objects. Containment is scoped to the Agreement's execution,
-- never to a person or a balance. Remedies on the economic exchange are adjudicated osTRIS
-- REVERSALs authorized by the community resolution authority, never an ordinary EXCHANGE.
-- Nothing here is ever triggered automatically: every decision has a named human author.

-- Who reviews. A STIR-owned roster, never derived from platform roles (ORDINARY_GOVERNANCE.md).
CREATE TABLE stir.case_reviewer (
  id                 uuid PRIMARY KEY,
  tenant_id          uuid NOT NULL,
  user_id            uuid NOT NULL,
  added_by_user_id   uuid NOT NULL,
  added_at           timestamptz NOT NULL,
  removed_by_user_id uuid,
  removed_at         timestamptz,
  CHECK ((removed_at IS NULL) = (removed_by_user_id IS NULL))
);
CREATE UNIQUE INDEX case_reviewer_active ON stir.case_reviewer (tenant_id, user_id) WHERE removed_at IS NULL;

CREATE TABLE stir.case_review_settings (
  tenant_id                 uuid PRIMARY KEY,
  appeal_window_hours       integer NOT NULL CHECK (appeal_window_hours BETWEEN 1 AND 720),
  authority_threshold       integer NOT NULL CHECK (authority_threshold >= 1),
  ostris_authority_id       uuid NOT NULL,
  ostris_authority_version  integer NOT NULL CHECK (ostris_authority_version >= 1),
  updated_by_user_id        uuid NOT NULL,
  updated_at                timestamptz NOT NULL
);

CREATE TABLE stir.agreement_case (
  id                  uuid PRIMARY KEY,
  tenant_id           uuid NOT NULL,
  agreement_id        uuid NOT NULL REFERENCES stir.agreement(id),
  obligation_id       uuid NOT NULL REFERENCES stir.agreement_obligation(id),
  claimant_user_id    uuid NOT NULL,
  respondent_user_id  uuid NOT NULL,
  claim               varchar(24) NOT NULL CHECK (claim IN ('NON_DELIVERY','NON_CONFORMITY','PARTIAL_DELIVERY')),
  statement           varchar(4000) NOT NULL,
  status              varchar(20) NOT NULL CHECK (status IN ('OPEN','UNDER_REVIEW','FINDING_ISSUED','APPEALED','RESOLVED','WITHDRAWN')),
  reviewer_user_id    uuid,
  created_at          timestamptz NOT NULL,
  updated_at          timestamptz NOT NULL,
  CHECK (claimant_user_id <> respondent_user_id),
  CHECK (reviewer_user_id IS NULL OR reviewer_user_id NOT IN (claimant_user_id, respondent_user_id)),
  CHECK (status IN ('OPEN','WITHDRAWN') OR reviewer_user_id IS NOT NULL)
);
-- One live case per obligation; a resolved or withdrawn one leaves room for a new claim.
CREATE UNIQUE INDEX agreement_case_one_live ON stir.agreement_case (obligation_id) WHERE status NOT IN ('RESOLVED','WITHDRAWN');
CREATE INDEX agreement_case_agreement ON stir.agreement_case (tenant_id, agreement_id, created_at);
CREATE INDEX agreement_case_status ON stir.agreement_case (tenant_id, status, created_at);

-- The timeline. visibility=REVIEWERS rows are shown to reviewers and to their own author only.
CREATE TABLE stir.agreement_case_event (
  id            uuid PRIMARY KEY,
  tenant_id     uuid NOT NULL,
  case_id       uuid NOT NULL REFERENCES stir.agreement_case(id),
  actor_user_id uuid NOT NULL,
  actor_role    varchar(16) NOT NULL CHECK (actor_role IN ('CLAIMANT','RESPONDENT','REVIEWER')),
  type          varchar(32) NOT NULL CHECK (type IN ('OPENED','EVIDENCE_ADDED','REVIEW_TAKEN','CONTAINMENT_APPLIED',
                  'CONTAINMENT_LIFTED','FINDING_ISSUED','ATTRIBUTION_ISSUED','SANCTION_ISSUED','APPEAL_WAIVED',
                  'APPEAL_FILED','APPEAL_DECIDED','DECISION_FINAL','REMEDY_PREPARED','REMEDY_SIGNED','REMEDY_EXECUTED',
                  'REMEDY_ATTEMPT_FAILED','WITHDRAWN')),
  visibility    varchar(16) NOT NULL DEFAULT 'PARTIES' CHECK (visibility IN ('PARTIES','REVIEWERS')),
  ref_id        uuid,
  note          varchar(4000),
  created_at    timestamptz NOT NULL
);
CREATE INDEX agreement_case_event_case ON stir.agreement_case_event (tenant_id, case_id, created_at, id);

CREATE TABLE stir.agreement_case_evidence (
  id                 uuid PRIMARY KEY,
  tenant_id          uuid NOT NULL,
  case_id            uuid NOT NULL REFERENCES stir.agreement_case(id),
  submitted_by       uuid NOT NULL,
  submitter_role     varchar(16) NOT NULL CHECK (submitter_role IN ('CLAIMANT','RESPONDENT','REVIEWER')),
  visibility         varchar(24) NOT NULL CHECK (visibility IN ('PARTIES_AND_REVIEWERS','REVIEWERS_ONLY')),
  description        varchar(4000) NOT NULL,
  external_reference varchar(500),
  created_at         timestamptz NOT NULL
);
CREATE INDEX agreement_case_evidence_case ON stir.agreement_case_evidence (tenant_id, case_id, created_at, id);

-- Finding, attribution and sanction: three kinds of decision, each its own row and its own appeal
-- window, never derived from one another automatically.
CREATE TABLE stir.agreement_case_decision (
  id                    uuid PRIMARY KEY,
  tenant_id             uuid NOT NULL,
  case_id               uuid NOT NULL REFERENCES stir.agreement_case(id),
  decision_type         varchar(16) NOT NULL CHECK (decision_type IN ('FINDING','ATTRIBUTION','SANCTION')),
  issued_by_user_id     uuid NOT NULL,
  reasoning             varchar(4000) NOT NULL,
  status                varchar(16) NOT NULL CHECK (status IN ('ISSUED','APPEALED','FINAL','OVERTURNED')),
  appeal_deadline       timestamptz NOT NULL,
  final_at              timestamptz,
  -- FINDING: what happened to the obligation, and which repair of the effect follows.
  effect                varchar(24) CHECK (effect IN ('ESTABLISHED','PARTIALLY_ESTABLISHED','NOT_ESTABLISHED')),
  economic_correction        varchar(16) CHECK (economic_correction IN ('NONE','FULL_REVERSAL')),
  performance_remedy    varchar(2000),
  ostris_basis_id       uuid,
  -- ATTRIBUTION: whether someone's conduct caused the effect. A good-faith third party is a valid answer.
  subject_role          varchar(16) CHECK (subject_role IN ('OBLIGOR','BENEFICIARY','THIRD_PARTY','NONE')),
  assessment            varchar(24) CHECK (assessment IN ('NO_FAULT','GOOD_FAITH_THIRD_PARTY','GOOD_FAITH_ERROR','BREACH')),
  third_party_note      varchar(1000),
  -- SANCTION: only ever on a FINAL attribution of BREACH, decided explicitly.
  subject_user_id       uuid,
  sanction_kind         varchar(16) CHECK (sanction_kind IN ('WARNING')),
  basis_decision_id     uuid REFERENCES stir.agreement_case_decision(id),
  created_at            timestamptz NOT NULL,
  CHECK ((status = 'FINAL') = (final_at IS NOT NULL)),
  CHECK (decision_type <> 'FINDING' OR (effect IS NOT NULL AND economic_correction IS NOT NULL)),
  CHECK (decision_type <> 'FINDING' OR economic_correction = 'NONE' OR effect <> 'NOT_ESTABLISHED'),
  CHECK ((decision_type = 'FINDING' AND economic_correction = 'FULL_REVERSAL') = (ostris_basis_id IS NOT NULL)),
  CHECK (decision_type <> 'ATTRIBUTION' OR (subject_role IS NOT NULL AND assessment IS NOT NULL AND basis_decision_id IS NOT NULL)),
  CHECK (decision_type <> 'ATTRIBUTION' OR assessment <> 'BREACH' OR subject_role IN ('OBLIGOR','BENEFICIARY')),
  CHECK (decision_type <> 'SANCTION' OR (subject_user_id IS NOT NULL AND sanction_kind IS NOT NULL AND basis_decision_id IS NOT NULL)),
  CHECK (decision_type = 'FINDING' OR (effect IS NULL AND economic_correction IS NULL AND performance_remedy IS NULL AND ostris_basis_id IS NULL)),
  CHECK (decision_type = 'ATTRIBUTION' OR (subject_role IS NULL AND assessment IS NULL AND third_party_note IS NULL)),
  CHECK (decision_type = 'SANCTION' OR (subject_user_id IS NULL AND sanction_kind IS NULL))
);
CREATE INDEX agreement_case_decision_case ON stir.agreement_case_decision (tenant_id, case_id, created_at);
-- One standing (not overturned) decision of each type per case.
CREATE UNIQUE INDEX agreement_case_decision_standing ON stir.agreement_case_decision (case_id, decision_type) WHERE status <> 'OVERTURNED';

CREATE TABLE stir.agreement_case_appeal (
  id                 uuid PRIMARY KEY,
  tenant_id          uuid NOT NULL,
  case_id            uuid NOT NULL REFERENCES stir.agreement_case(id),
  decision_id        uuid NOT NULL REFERENCES stir.agreement_case_decision(id),
  filed_by_user_id   uuid NOT NULL,
  grounds            varchar(4000) NOT NULL,
  status             varchar(16) NOT NULL CHECK (status IN ('OPEN','UPHELD','OVERTURNED')),
  decided_by_user_id uuid,
  decision_reasoning varchar(4000),
  decided_at         timestamptz,
  created_at         timestamptz NOT NULL,
  CHECK ((status = 'OPEN') = (decided_at IS NULL AND decided_by_user_id IS NULL AND decision_reasoning IS NULL)),
  CHECK (decided_by_user_id IS NULL OR decided_by_user_id <> filed_by_user_id)
);
CREATE UNIQUE INDEX agreement_case_appeal_one_per_decision ON stir.agreement_case_appeal (decision_id);

-- Containment pauses the Agreement's pending execution and amendments while a case is reviewed.
-- It never touches a person, an account or a balance.
CREATE TABLE stir.agreement_case_containment (
  id                 uuid PRIMARY KEY,
  tenant_id          uuid NOT NULL,
  case_id            uuid NOT NULL REFERENCES stir.agreement_case(id),
  agreement_id       uuid NOT NULL REFERENCES stir.agreement(id),
  scope              varchar(24) NOT NULL CHECK (scope IN ('AGREEMENT_EXECUTION')),
  reason             varchar(2000) NOT NULL,
  applied_by_user_id uuid NOT NULL,
  applied_at         timestamptz NOT NULL,
  until_at           timestamptz NOT NULL,
  lifted_by_user_id  uuid,
  lifted_at          timestamptz,
  CHECK (until_at > applied_at),
  CHECK ((lifted_at IS NULL) = (lifted_by_user_id IS NULL))
);
CREATE INDEX agreement_case_containment_agreement ON stir.agreement_case_containment (tenant_id, agreement_id);

-- The repair of the economic effect: an adjudicated osTRIS REVERSAL of the committed exchange.
CREATE TABLE stir.agreement_case_remedy (
  id                       uuid PRIMARY KEY,
  tenant_id                uuid NOT NULL,
  case_id                  uuid NOT NULL REFERENCES stir.agreement_case(id),
  decision_id              uuid NOT NULL REFERENCES stir.agreement_case_decision(id),
  agreement_id             uuid NOT NULL REFERENCES stir.agreement(id),
  kind                     varchar(24) NOT NULL CHECK (kind IN ('ECONOMIC_REVERSAL')),
  status                   varchar(24) NOT NULL CHECK (status IN ('AWAITING_AUTHORITY','EXECUTED')),
  ostris_transaction_id    uuid NOT NULL UNIQUE,
  reverses_transaction_id  uuid NOT NULL,
  ostris_basis_id          uuid NOT NULL,
  ostris_authority_id      uuid NOT NULL,
  ostris_authority_version integer NOT NULL,
  covered_account_ids      text NOT NULL,
  committed_sequence       bigint,
  protocol_digest          varchar(64),
  committed_at             timestamptz,
  last_error_code          varchar(80),
  prepared_by_user_id      uuid NOT NULL,
  created_at               timestamptz NOT NULL,
  updated_at               timestamptz NOT NULL,
  CHECK ((status = 'EXECUTED') = (committed_sequence IS NOT NULL AND committed_at IS NOT NULL))
);
CREATE UNIQUE INDEX agreement_case_remedy_one_per_decision ON stir.agreement_case_remedy (decision_id);

ALTER TABLE stir.notification DROP CONSTRAINT notification_type_check;
ALTER TABLE stir.notification ADD CONSTRAINT notification_type_check CHECK (type IN
  ('OFFER_RECEIVED','COUNTEROFFER_RECEIVED','OFFER_ACCEPTED','OFFER_DECLINED',
   'SIGNATURE_NEEDED','COUNTERPARTY_SIGNED','TRADE_COMMITTED','TRADE_REJECTED',
   'OBLIGATION_UPDATED','AMENDMENT_PROPOSED','AMENDMENT_ACCEPTED','AMENDMENT_REJECTED',
   'CASE_OPENED','CASE_UPDATED'));

CREATE TRIGGER agreement_case_event_append_only BEFORE UPDATE OR DELETE ON stir.agreement_case_event
  FOR EACH ROW EXECUTE FUNCTION stir.reject_append_only_mutation();
CREATE TRIGGER agreement_case_evidence_append_only BEFORE UPDATE OR DELETE ON stir.agreement_case_evidence
  FOR EACH ROW EXECUTE FUNCTION stir.reject_append_only_mutation();

-- A case keeps its parties, obligation and claim; only status and reviewer move.
CREATE FUNCTION stir.guard_agreement_case() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'STIR_CASE_IMMUTABLE: cases are never deleted'; END IF;
  IF (NEW.id, NEW.tenant_id, NEW.agreement_id, NEW.obligation_id, NEW.claimant_user_id, NEW.respondent_user_id,
      NEW.claim, NEW.statement, NEW.created_at)
     IS DISTINCT FROM
     (OLD.id, OLD.tenant_id, OLD.agreement_id, OLD.obligation_id, OLD.claimant_user_id, OLD.respondent_user_id,
      OLD.claim, OLD.statement, OLD.created_at) THEN
    RAISE EXCEPTION 'STIR_CASE_IMMUTABLE: only the status and reviewer of a case may change';
  END IF;
  IF OLD.status IN ('RESOLVED','WITHDRAWN') THEN RAISE EXCEPTION 'STIR_CASE_IMMUTABLE: a closed case stays closed'; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER agreement_case_guard BEFORE UPDATE OR DELETE ON stir.agreement_case
  FOR EACH ROW EXECUTE FUNCTION stir.guard_agreement_case();

-- A decision's content is frozen when issued; only its status moves, and FINAL/OVERTURNED are terminal.
CREATE FUNCTION stir.guard_agreement_case_decision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'STIR_DECISION_IMMUTABLE: decisions are never deleted'; END IF;
  IF (NEW.id, NEW.tenant_id, NEW.case_id, NEW.decision_type, NEW.issued_by_user_id, NEW.reasoning, NEW.appeal_deadline,
      NEW.effect, NEW.economic_correction, NEW.performance_remedy, NEW.ostris_basis_id, NEW.subject_role, NEW.assessment,
      NEW.third_party_note, NEW.subject_user_id, NEW.sanction_kind, NEW.basis_decision_id, NEW.created_at)
     IS DISTINCT FROM
     (OLD.id, OLD.tenant_id, OLD.case_id, OLD.decision_type, OLD.issued_by_user_id, OLD.reasoning, OLD.appeal_deadline,
      OLD.effect, OLD.economic_correction, OLD.performance_remedy, OLD.ostris_basis_id, OLD.subject_role, OLD.assessment,
      OLD.third_party_note, OLD.subject_user_id, OLD.sanction_kind, OLD.basis_decision_id, OLD.created_at) THEN
    RAISE EXCEPTION 'STIR_DECISION_IMMUTABLE: only the status of a decision may change';
  END IF;
  IF OLD.status IN ('FINAL','OVERTURNED') THEN RAISE EXCEPTION 'STIR_DECISION_IMMUTABLE: a final or overturned decision never changes'; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER agreement_case_decision_guard BEFORE UPDATE OR DELETE ON stir.agreement_case_decision
  FOR EACH ROW EXECUTE FUNCTION stir.guard_agreement_case_decision();

CREATE FUNCTION stir.guard_agreement_case_appeal() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'STIR_APPEAL_IMMUTABLE: appeals are never deleted'; END IF;
  IF OLD.status <> 'OPEN' THEN RAISE EXCEPTION 'STIR_APPEAL_IMMUTABLE: an appeal is decided once'; END IF;
  IF (NEW.id, NEW.tenant_id, NEW.case_id, NEW.decision_id, NEW.filed_by_user_id, NEW.grounds, NEW.created_at)
     IS DISTINCT FROM (OLD.id, OLD.tenant_id, OLD.case_id, OLD.decision_id, OLD.filed_by_user_id, OLD.grounds, OLD.created_at) THEN
    RAISE EXCEPTION 'STIR_APPEAL_IMMUTABLE: only the decision may be recorded on an appeal';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER agreement_case_appeal_guard BEFORE UPDATE OR DELETE ON stir.agreement_case_appeal
  FOR EACH ROW EXECUTE FUNCTION stir.guard_agreement_case_appeal();

CREATE FUNCTION stir.guard_agreement_case_containment() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'STIR_CONTAINMENT_IMMUTABLE: containments are never deleted'; END IF;
  IF OLD.lifted_at IS NOT NULL THEN RAISE EXCEPTION 'STIR_CONTAINMENT_IMMUTABLE: a lifted containment stays lifted'; END IF;
  IF (NEW.id, NEW.tenant_id, NEW.case_id, NEW.agreement_id, NEW.scope, NEW.reason, NEW.applied_by_user_id, NEW.applied_at, NEW.until_at)
     IS DISTINCT FROM (OLD.id, OLD.tenant_id, OLD.case_id, OLD.agreement_id, OLD.scope, OLD.reason, OLD.applied_by_user_id, OLD.applied_at, OLD.until_at) THEN
    RAISE EXCEPTION 'STIR_CONTAINMENT_IMMUTABLE: only lifting may be recorded on a containment';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER agreement_case_containment_guard BEFORE UPDATE OR DELETE ON stir.agreement_case_containment
  FOR EACH ROW EXECUTE FUNCTION stir.guard_agreement_case_containment();

CREATE FUNCTION stir.guard_agreement_case_remedy() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'STIR_REMEDY_IMMUTABLE: remedies are never deleted'; END IF;
  IF OLD.status = 'EXECUTED' THEN RAISE EXCEPTION 'STIR_REMEDY_IMMUTABLE: an executed remedy never changes'; END IF;
  IF (NEW.id, NEW.tenant_id, NEW.case_id, NEW.decision_id, NEW.agreement_id, NEW.kind, NEW.ostris_transaction_id,
      NEW.reverses_transaction_id, NEW.ostris_basis_id, NEW.ostris_authority_id, NEW.ostris_authority_version,
      NEW.covered_account_ids, NEW.prepared_by_user_id, NEW.created_at)
     IS DISTINCT FROM
     (OLD.id, OLD.tenant_id, OLD.case_id, OLD.decision_id, OLD.agreement_id, OLD.kind, OLD.ostris_transaction_id,
      OLD.reverses_transaction_id, OLD.ostris_basis_id, OLD.ostris_authority_id, OLD.ostris_authority_version,
      OLD.covered_account_ids, OLD.prepared_by_user_id, OLD.created_at) THEN
    RAISE EXCEPTION 'STIR_REMEDY_IMMUTABLE: a prepared remedy keeps its osTRIS intent';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER agreement_case_remedy_guard BEFORE UPDATE OR DELETE ON stir.agreement_case_remedy
  FOR EACH ROW EXECUTE FUNCTION stir.guard_agreement_case_remedy();

CREATE FUNCTION stir.guard_case_reviewer() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'STIR_REVIEWER_HISTORY: reviewer history is never deleted'; END IF;
  IF OLD.removed_at IS NOT NULL OR (NEW.id, NEW.tenant_id, NEW.user_id, NEW.added_by_user_id, NEW.added_at)
     IS DISTINCT FROM (OLD.id, OLD.tenant_id, OLD.user_id, OLD.added_by_user_id, OLD.added_at) THEN
    RAISE EXCEPTION 'STIR_REVIEWER_HISTORY: only a single removal may be recorded';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER case_reviewer_guard BEFORE UPDATE OR DELETE ON stir.case_reviewer
  FOR EACH ROW EXECUTE FUNCTION stir.guard_case_reviewer();

DO $do_rls$
DECLARE
  t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['case_reviewer','case_review_settings','agreement_case','agreement_case_event','agreement_case_evidence',
    'agreement_case_decision','agreement_case_appeal','agreement_case_containment','agreement_case_remedy']
  LOOP
    EXECUTE format('ALTER TABLE stir.%I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE stir.%I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format('CREATE POLICY %I ON stir.%I FOR ALL TO idax_app, idax_admin '
      || 'USING (tenant_id = NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid) '
      || 'WITH CHECK (tenant_id = NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid)', t || '_tenant', t);
    EXECUTE format('GRANT SELECT ON stir.%I TO idax_admin', t);
  END LOOP;
END $do_rls$;

-- The runtime credential gets exactly what the service needs. Platform admin gets no DML (V17).
GRANT SELECT, INSERT, UPDATE ON stir.case_reviewer, stir.agreement_case, stir.agreement_case_decision,
  stir.agreement_case_appeal, stir.agreement_case_containment, stir.agreement_case_remedy TO idax_app;
GRANT SELECT, INSERT, UPDATE ON stir.case_review_settings TO idax_app;
GRANT SELECT, INSERT ON stir.agreement_case_event, stir.agreement_case_evidence TO idax_app;

DO $do_audit$
DECLARE
  t text;
  pk text[];
BEGIN
  FOREACH t IN ARRAY ARRAY['case_reviewer','case_review_settings','agreement_case','agreement_case_event','agreement_case_evidence',
    'agreement_case_decision','agreement_case_appeal','agreement_case_containment','agreement_case_remedy']
  LOOP
    pk := CASE WHEN t = 'case_review_settings' THEN ARRAY['tenant_id'] ELSE ARRAY['id'] END;
    INSERT INTO stir_audit.coverage_registry (table_oid, schema_name, table_name, domain, pk_columns, coverage_status, justification, digest_profile_version)
      VALUES (format('stir.%I', t)::regclass::oid, 'stir', t, 'AGREEMENT_ECONOMIC', pk, 'COVERED',
        'Agreement case review state per AGREEMENT_CASES.md (V24).', 'PG17_JSONB_TEXT_SHA256_V1');
    EXECUTE format('CREATE TRIGGER audit_emit_mutation_event AFTER INSERT OR UPDATE OR DELETE ON stir.%I FOR EACH ROW EXECUTE FUNCTION stir_audit.emit_mutation_event()', t);
    EXECUTE format('GRANT SELECT ON TABLE stir.%I TO stir_auditor', t);
    EXECUTE format('CREATE POLICY stir_auditor_cross_tenant_read ON stir.%I FOR SELECT TO stir_auditor USING (true)', t);
  END LOOP;
END $do_audit$;
