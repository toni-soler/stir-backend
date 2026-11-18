-- Agreement fulfilment (stir-doc/AGREEMENT_FULFILMENT.md): per-obligation fulfilment with typed,
-- append-only confirmations; amendments that chain to the snapshot they modify; persisted and
-- classified economic execution attempts; opt-in contextual fulfilment history.
-- The Agreement snapshot is never touched: obligations are derived from it and point at it.

CREATE TABLE stir.agreement_amendment (
  id                 uuid PRIMARY KEY,
  tenant_id          uuid NOT NULL,
  agreement_id       uuid NOT NULL REFERENCES stir.agreement(id),
  proposer_user_id   uuid NOT NULL,
  base_digest        char(64) NOT NULL,
  proposal_json      text NOT NULL,
  reason             varchar(2000) NOT NULL,
  valid_until        timestamptz,
  status             varchar(16) NOT NULL DEFAULT 'PROPOSED' CHECK (status IN ('PROPOSED','ACCEPTED','REJECTED')),
  decided_by_user_id uuid,
  decided_at         timestamptz,
  sequence_number    integer,
  canonical_json     text,
  digest_sha256      char(64),
  created_at         timestamptz NOT NULL,
  CHECK ((status = 'ACCEPTED') = (canonical_json IS NOT NULL AND digest_sha256 IS NOT NULL AND sequence_number IS NOT NULL)),
  CHECK ((status = 'PROPOSED') = (decided_at IS NULL AND decided_by_user_id IS NULL))
);
-- Exactly one accepted amendment may build on any given base: the database's own answer to two
-- amendments racing on the same base is the 409 the losing party sees.
CREATE UNIQUE INDEX agreement_amendment_one_per_base ON stir.agreement_amendment (agreement_id, base_digest) WHERE status = 'ACCEPTED';
CREATE UNIQUE INDEX agreement_amendment_sequence ON stir.agreement_amendment (agreement_id, sequence_number) WHERE status = 'ACCEPTED';
CREATE INDEX agreement_amendment_agreement ON stir.agreement_amendment (tenant_id, agreement_id, created_at);

CREATE TABLE stir.agreement_obligation (
  id                         uuid PRIMARY KEY,
  tenant_id                  uuid NOT NULL,
  agreement_id               uuid NOT NULL REFERENCES stir.agreement(id),
  kind                       varchar(24) NOT NULL CHECK (kind IN ('OBJECT','OWNER_TASKS','INITIATOR_TASKS','ECONOMIC_EXCHANGE')),
  origin                     varchar(16) NOT NULL CHECK (origin IN ('SNAPSHOT','AMENDMENT')),
  amendment_id               uuid REFERENCES stir.agreement_amendment(id),
  obligor_user_id            uuid NOT NULL,
  beneficiary_user_id        uuid NOT NULL,
  description                varchar(4000),
  fulfil_by                  date,
  fulfilment_mode            varchar(24),
  status                     varchar(16) NOT NULL DEFAULT 'PENDING'
                             CHECK (status IN ('PENDING','DECLARED','PARTIAL','DONE','IN_DISAGREEMENT','FAILED','SUPERSEDED')),
  superseded_by_amendment_id uuid REFERENCES stir.agreement_amendment(id),
  created_at                 timestamptz NOT NULL,
  updated_at                 timestamptz NOT NULL,
  CHECK ((origin = 'AMENDMENT') = (amendment_id IS NOT NULL)),
  CHECK ((status = 'SUPERSEDED') = (superseded_by_amendment_id IS NOT NULL)),
  CHECK (obligor_user_id <> beneficiary_user_id),
  CHECK (kind <> 'ECONOMIC_EXCHANGE' OR origin = 'SNAPSHOT')
);
-- Derivation from the snapshot happens at most once per kind, however many readers race to it.
CREATE UNIQUE INDEX agreement_obligation_snapshot_kind ON stir.agreement_obligation (agreement_id, kind) WHERE origin = 'SNAPSHOT';
CREATE INDEX agreement_obligation_agreement ON stir.agreement_obligation (tenant_id, agreement_id);
CREATE INDEX agreement_obligation_history ON stir.agreement_obligation (tenant_id, obligor_user_id, status);

CREATE TABLE stir.agreement_obligation_event (
  id             uuid PRIMARY KEY,
  tenant_id      uuid NOT NULL,
  agreement_id   uuid NOT NULL REFERENCES stir.agreement(id),
  obligation_id  uuid NOT NULL REFERENCES stir.agreement_obligation(id),
  actor_user_id  uuid NOT NULL,
  actor_role     varchar(16) NOT NULL CHECK (actor_role IN ('OBLIGOR','BENEFICIARY')),
  type           varchar(32) NOT NULL CHECK (type IN ('PERFORMED','PARTIALLY_PERFORMED','NOT_PERFORMED',
                   'CONFORMITY_CONFIRMED','PARTIAL_RECEIVED','NONCONFORMITY_REPORTED','NOT_RECEIVED')),
  note           varchar(2000),
  created_at     timestamptz NOT NULL,
  CHECK ((actor_role = 'OBLIGOR') = (type IN ('PERFORMED','PARTIALLY_PERFORMED','NOT_PERFORMED'))),
  CHECK (type NOT IN ('PARTIALLY_PERFORMED','PARTIAL_RECEIVED') OR length(btrim(coalesce(note,''))) > 0)
);
CREATE INDEX agreement_obligation_event_obligation ON stir.agreement_obligation_event (tenant_id, obligation_id, created_at, id);

CREATE TABLE stir.economic_execution_attempt (
  id                 uuid PRIMARY KEY,
  tenant_id          uuid NOT NULL,
  agreement_id       uuid NOT NULL REFERENCES stir.agreement(id),
  trade_id           uuid NOT NULL REFERENCES stir.trade(id),
  actor_user_id      uuid NOT NULL,
  outcome            varchar(32) NOT NULL CHECK (outcome IN ('COMMITTED','EXPOSURE_REFUSAL','AUTHORISATION_INCOMPLETE',
                       'INVALID_DESCRIPTION','IMPLEMENTATION_FAULT','UNKNOWN')),
  ostris_code        varchar(80),
  http_status        integer,
  detail             varchar(500),
  committed_sequence bigint,
  created_at         timestamptz NOT NULL
);
CREATE INDEX economic_execution_attempt_agreement ON stir.economic_execution_attempt (tenant_id, agreement_id, created_at, id);

ALTER TABLE stir.participant_profile ADD COLUMN share_fulfilment_history boolean NOT NULL DEFAULT false;

-- The counterparty hears about fulfilment declarations and amendment proposals/decisions.
ALTER TABLE stir.notification DROP CONSTRAINT notification_type_check;
ALTER TABLE stir.notification ADD CONSTRAINT notification_type_check CHECK (type IN
  ('OFFER_RECEIVED','COUNTEROFFER_RECEIVED','OFFER_ACCEPTED','OFFER_DECLINED',
   'SIGNATURE_NEEDED','COUNTERPARTY_SIGNED','TRADE_COMMITTED','TRADE_REJECTED',
   'OBLIGATION_UPDATED','AMENDMENT_PROPOSED','AMENDMENT_ACCEPTED','AMENDMENT_REJECTED'));

-- Append-only history: events and attempts are never edited or removed, by any role.
CREATE FUNCTION stir.reject_append_only_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'STIR_APPEND_ONLY: % rows are never updated or deleted', TG_TABLE_NAME;
END $$;
CREATE TRIGGER agreement_obligation_event_append_only BEFORE UPDATE OR DELETE ON stir.agreement_obligation_event
  FOR EACH ROW EXECUTE FUNCTION stir.reject_append_only_mutation();
CREATE TRIGGER economic_execution_attempt_append_only BEFORE UPDATE OR DELETE ON stir.economic_execution_attempt
  FOR EACH ROW EXECUTE FUNCTION stir.reject_append_only_mutation();

-- An obligation's identity and terms are frozen; only its derived status (and the amendment that
-- superseded it) may change. A superseded obligation never comes back.
CREATE FUNCTION stir.guard_agreement_obligation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'STIR_OBLIGATION_IMMUTABLE: obligations are never deleted'; END IF;
  IF (NEW.id, NEW.tenant_id, NEW.agreement_id, NEW.kind, NEW.origin, NEW.amendment_id, NEW.obligor_user_id,
      NEW.beneficiary_user_id, NEW.description, NEW.fulfil_by, NEW.fulfilment_mode, NEW.created_at)
     IS DISTINCT FROM
     (OLD.id, OLD.tenant_id, OLD.agreement_id, OLD.kind, OLD.origin, OLD.amendment_id, OLD.obligor_user_id,
      OLD.beneficiary_user_id, OLD.description, OLD.fulfil_by, OLD.fulfilment_mode, OLD.created_at) THEN
    RAISE EXCEPTION 'STIR_OBLIGATION_IMMUTABLE: only the derived status of an obligation may change';
  END IF;
  IF OLD.status = 'SUPERSEDED' AND (NEW.status <> 'SUPERSEDED' OR NEW.superseded_by_amendment_id IS DISTINCT FROM OLD.superseded_by_amendment_id) THEN
    RAISE EXCEPTION 'STIR_OBLIGATION_IMMUTABLE: a superseded obligation stays superseded';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER agreement_obligation_guard BEFORE UPDATE OR DELETE ON stir.agreement_obligation
  FOR EACH ROW EXECUTE FUNCTION stir.guard_agreement_obligation();

-- A proposal is decided once; an accepted amendment's content never changes.
CREATE FUNCTION stir.guard_agreement_amendment() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN RAISE EXCEPTION 'STIR_AMENDMENT_IMMUTABLE: amendments are never deleted'; END IF;
  IF OLD.status <> 'PROPOSED' THEN RAISE EXCEPTION 'STIR_AMENDMENT_IMMUTABLE: a decided amendment never changes'; END IF;
  IF (NEW.id, NEW.tenant_id, NEW.agreement_id, NEW.proposer_user_id, NEW.base_digest, NEW.proposal_json, NEW.reason,
      NEW.valid_until, NEW.created_at)
     IS DISTINCT FROM
     (OLD.id, OLD.tenant_id, OLD.agreement_id, OLD.proposer_user_id, OLD.base_digest, OLD.proposal_json, OLD.reason,
      OLD.valid_until, OLD.created_at) THEN
    RAISE EXCEPTION 'STIR_AMENDMENT_IMMUTABLE: only the decision may be recorded on a proposal';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER agreement_amendment_guard BEFORE UPDATE OR DELETE ON stir.agreement_amendment
  FOR EACH ROW EXECUTE FUNCTION stir.guard_agreement_amendment();

ALTER TABLE stir.agreement_amendment ENABLE ROW LEVEL SECURITY;
ALTER TABLE stir.agreement_amendment FORCE ROW LEVEL SECURITY;
ALTER TABLE stir.agreement_obligation ENABLE ROW LEVEL SECURITY;
ALTER TABLE stir.agreement_obligation FORCE ROW LEVEL SECURITY;
ALTER TABLE stir.agreement_obligation_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE stir.agreement_obligation_event FORCE ROW LEVEL SECURITY;
ALTER TABLE stir.economic_execution_attempt ENABLE ROW LEVEL SECURITY;
ALTER TABLE stir.economic_execution_attempt FORCE ROW LEVEL SECURITY;

CREATE POLICY agreement_amendment_tenant ON stir.agreement_amendment FOR ALL TO idax_app, idax_admin
 USING (tenant_id = NULLIF(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id',true),'')::uuid);
CREATE POLICY agreement_obligation_tenant ON stir.agreement_obligation FOR ALL TO idax_app, idax_admin
 USING (tenant_id = NULLIF(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id',true),'')::uuid);
CREATE POLICY agreement_obligation_event_tenant ON stir.agreement_obligation_event FOR ALL TO idax_app, idax_admin
 USING (tenant_id = NULLIF(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id',true),'')::uuid);
CREATE POLICY economic_execution_attempt_tenant ON stir.economic_execution_attempt FOR ALL TO idax_app, idax_admin
 USING (tenant_id = NULLIF(current_setting('app.tenant_id',true),'')::uuid)
 WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id',true),'')::uuid);

-- The runtime credential gets exactly what the service needs. Platform admin gets no DML (V17).
GRANT SELECT, INSERT, UPDATE ON stir.agreement_amendment TO idax_app;
GRANT SELECT, INSERT, UPDATE ON stir.agreement_obligation TO idax_app;
GRANT SELECT, INSERT ON stir.agreement_obligation_event TO idax_app;
GRANT SELECT, INSERT ON stir.economic_execution_attempt TO idax_app;
GRANT SELECT ON stir.agreement_amendment, stir.agreement_obligation, stir.agreement_obligation_event,
  stir.economic_execution_attempt TO idax_admin;

-- Tamper-evident audit (V18/V19): the four new tables join the AGREEMENT_ECONOMIC domain, with the
-- same trigger and the same separate, read-only auditor access as every other covered table.
-- They are new and empty, so there is no baseline to capture.
DO $do_audit$
DECLARE
  t text;
BEGIN
  FOREACH t IN ARRAY ARRAY['agreement_amendment','agreement_obligation','agreement_obligation_event','economic_execution_attempt']
  LOOP
    INSERT INTO stir_audit.coverage_registry (table_oid, schema_name, table_name, domain, pk_columns, coverage_status, justification, digest_profile_version)
      VALUES (format('stir.%I', t)::regclass::oid, 'stir', t, 'AGREEMENT_ECONOMIC', ARRAY['id'], 'COVERED',
        'Agreement fulfilment state per AGREEMENT_FULFILMENT.md (V23).', 'PG17_JSONB_TEXT_SHA256_V1');
    EXECUTE format('CREATE TRIGGER audit_emit_mutation_event AFTER INSERT OR UPDATE OR DELETE ON stir.%I FOR EACH ROW EXECUTE FUNCTION stir_audit.emit_mutation_event()', t);
    EXECUTE format('GRANT SELECT ON TABLE stir.%I TO stir_auditor', t);
    EXECUTE format('CREATE POLICY stir_auditor_cross_tenant_read ON stir.%I FOR SELECT TO stir_auditor USING (true)', t);
  END LOOP;
END $do_audit$;
