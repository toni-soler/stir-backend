-- TAMPER-EVIDENT GOVERNED STATE AUDIT MVP (Phase 1) - GOVERNED_STATE_AUDIT_ARCHITECTURE.md,
-- CLAUDE_GOVERNED_STATE_AUDIT_ORDERS.md. Additive, on top of V17 (idax_admin already has zero
-- STIR DML). Does NOT close AUD-012: idax_app/idax_backend keep every write privilege they had
-- before this migration - this migration adds a DB-owned, runtime-unmodifiable witness of every
-- governed-state DML, not a preventive gate. A runtime SQL actor can still write a self-consistent
-- fabricated history in MI/Ordinary/Consent; the verifier (separate Java process, not part of this
-- migration) must report that as PASS_STRUCTURE_ONLY, never PASS_AUTHORIZED.
--
-- Trust model this migration encodes:
--   postgres          - migrator/infrastructure owner, unchanged, can always bypass (superuser)
--   stir_audit_owner  - NOLOGIN, owns stir_audit.* schema/tables/functions; nothing else
--   stir_auditor      - LOGIN, separate secret from idax_backend; SELECT-only on stir.* (own RLS
--                        policy, cross-tenant), SELECT on stir_audit.*, INSERT/UPDATE only on its
--                        own cursor/verdict/incident/anchor-outbox rows. Never idax_app/idax_admin
--                        member. Password is set out-of-band by stir-main's provision-audit.sh,
--                        never inline in this migration.
--   idax_app/idax_admin/idax_backend - UNCHANGED privileges on stir.*; explicitly granted NOTHING
--                        on stir_audit.* (no GRANT statement at all - default-deny plus an
--                        explicit REVOKE ALL FROM PUBLIC on the schema).

-- ===== Roles =====
DO $do_roles$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'stir_audit_owner') THEN
    CREATE ROLE stir_audit_owner NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS NOREPLICATION;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'stir_auditor') THEN
    -- LOGIN with no password yet (password NULL => cannot authenticate until provision-audit.sh
    -- runs ALTER ROLE ... PASSWORD, mirroring exactly how idax_backend itself gets its password
    -- set by provision-runtime.sh after core-migrations, never inline in a Flyway migration).
    CREATE ROLE stir_auditor LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS NOREPLICATION;
  END IF;
END $do_roles$;

-- Defensive, explicit non-membership statement (documentation-as-code, not a functional no-op:
-- if a future migration or manual operation ever granted membership, this comment is the place a
-- reviewer checks first). idax_app, idax_admin, idax_backend, idax_service_auth, idax_capability_owner
-- and PUBLIC must NEVER be members of stir_audit_owner or stir_auditor. No GRANT ... TO statement
-- exists anywhere in this file that adds such membership - verified by StirAuditRolePostgresTest.

-- ===== Schema, owned by stir_audit_owner, unreachable by PUBLIC/runtime by default =====
CREATE SCHEMA IF NOT EXISTS stir_audit AUTHORIZATION stir_audit_owner;
REVOKE ALL ON SCHEMA stir_audit FROM PUBLIC;
GRANT USAGE ON SCHEMA stir_audit TO stir_auditor;
-- idax_app/idax_admin/idax_backend get no USAGE - even if they somehow had SELECT on a stir_audit
-- object, they could never even qualify a name to reach it without schema USAGE.

CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA stir_audit;

SET ROLE stir_audit_owner;

-- ===== coverage_registry: static, migrator-only, tells the trigger and the verifier which
-- domain/PK-columns a table belongs to. Never written by any runtime role. A table present in
-- pg_tables("stir") with no row here is a CI-failing condition (StirAuditCoverageRegistryTest). =====
CREATE TABLE stir_audit.coverage_registry (
  table_oid            oid PRIMARY KEY,
  schema_name          text NOT NULL,
  table_name           text NOT NULL,
  domain               text,              -- NULL unless coverage_status = 'COVERED'
  pk_columns           text[],            -- ordinal-ordered; NULL unless coverage_status = 'COVERED'
  coverage_status      text NOT NULL CHECK (coverage_status IN ('COVERED','EXCLUDED_JUSTIFIED','NO_PRIVILEGE_CATALOG')),
  justification        text NOT NULL,
  digest_profile_version text,            -- NULL unless coverage_status = 'COVERED'
  registered_at         timestamptz NOT NULL DEFAULT now(),
  UNIQUE (schema_name, table_name),
  CHECK ((coverage_status <> 'COVERED') OR (domain IS NOT NULL AND pk_columns IS NOT NULL AND digest_profile_version IS NOT NULL))
);
REVOKE ALL ON TABLE stir_audit.coverage_registry FROM PUBLIC;
GRANT SELECT ON TABLE stir_audit.coverage_registry TO stir_auditor;
-- No RLS: this is schema metadata, not tenant data, and only the owner (this migration) ever
-- writes it. idax_app/idax_admin/idax_backend get no GRANT at all, so ownership alone is the
-- write boundary; nothing here depends on RLS to stop them.

-- ===== activation_marker / baseline_import: P1-R2-002 (second Codex reaudit) moved these here
-- from what was originally a separate V19, specifically so table baseline capture and this table's
-- own trigger activation happen inside ONE migration transaction, with the LOCK TABLE this DO block
-- takes below (see "activation ceremony" comment further down) closing the window an ordinary
-- idax_app writer could otherwise land in between "trigger not yet active" and "baseline captured".
-- V18/V19 were never published/deployed anywhere before this change - see this commit's message and
-- `git log -p` on this file for the exact before/after Codex asked for, rather than a parallel V20.
--
-- SEMANTICS, STATED EXPLICITLY (Codex's second reaudit: the first round's language overclaimed):
-- a `baseline_import` row means ONLY "this (table, key) was present in stir.* at the moment this
-- activation ceremony captured it" - it is NOT evidence of legitimate origin, authorization, or
-- audited history. The row's creation was never itself observed by this audit trail. Equivalent
-- explicit semantics: LEGACY_UNVERIFIED. Never read/write/log/document this as VALID, AUTHORIZED,
-- or AUDITED - a row can be in baseline_import precisely because nothing ever verified it.
-- `postgres`/DB owner/host root remain entirely outside Phase 1's runtime trust boundary: an owner
-- who disables a trigger and inserts a row before this ceremony runs produces a row this migration
-- cannot distinguish from genuine pre-activation legacy data. Phase 1 defends against idax_app/
-- idax_admin/idax_backend (the runtime credential surface), never against the database owner - see
-- GOVERNED_STATE_AUDIT_ARCHITECTURE.md's own trust boundary statement.
CREATE TABLE stir_audit.activation_marker (
  activation_batch  uuid PRIMARY KEY,
  activated_at       timestamptz NOT NULL DEFAULT now(),
  migration_version  text NOT NULL
);
REVOKE ALL ON TABLE stir_audit.activation_marker FROM PUBLIC;
GRANT SELECT ON TABLE stir_audit.activation_marker TO stir_auditor;
-- Durable, queryable record of exactly when "audit protection active" became true for a given
-- activation ceremony - the boundary marker the operational ceremony diagram (see V18's DO block
-- below and VALIDATION_GOVERNED_STATE_AUDIT_MVP.md) refers to as "establish audit activation
-- marker". One row per ceremony; Phase 1 populates exactly one (this migration's own run).

CREATE TABLE stir_audit.baseline_import (
  table_oid             oid NOT NULL,
  entity_key_canonical  text NOT NULL,
  tenant_id             uuid,                 -- best-effort, for observability/filtering only
  imported_at           timestamptz NOT NULL DEFAULT now(),
  import_batch          uuid NOT NULL REFERENCES stir_audit.activation_marker(activation_batch),
  PRIMARY KEY (table_oid, entity_key_canonical)
);
REVOKE ALL ON TABLE stir_audit.baseline_import FROM PUBLIC;
GRANT SELECT ON TABLE stir_audit.baseline_import TO stir_auditor;
-- No RLS, same reasoning as coverage_registry: migrator-only writer, schema metadata rather than
-- tenant business data, read cross-tenant by the auditor by design.

-- ===== stream_head: one row per (tenant, domain), the serialization point for that stream. =====
CREATE TABLE stir_audit.stream_head (
  tenant_id   uuid NOT NULL,
  domain      text NOT NULL,
  sequence    bigint NOT NULL DEFAULT 0,
  head_hash   bytea NOT NULL,
  updated_at  timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, domain)
);
ALTER TABLE stir_audit.stream_head ENABLE ROW LEVEL SECURITY;
ALTER TABLE stir_audit.stream_head FORCE ROW LEVEL SECURITY;
CREATE POLICY stream_head_owner_write ON stir_audit.stream_head FOR ALL TO stir_audit_owner USING (true) WITH CHECK (true);
CREATE POLICY stream_head_auditor_read ON stir_audit.stream_head FOR SELECT TO stir_auditor USING (true);
GRANT SELECT ON TABLE stir_audit.stream_head TO stir_auditor;
-- RLS policies alone are not sufficient in PostgreSQL: the underlying table-level GRANT is a
-- separate, mandatory precondition checked before any RLS policy is even evaluated. Every
-- CREATE POLICY ... TO stir_auditor below has a matching GRANT for exactly this reason - found by
-- actually running the packaged verifier against this migration and getting "permission denied
-- for table verifier_cursor" despite the FOR ALL policy already existing (see
-- VALIDATION_GOVERNED_STATE_AUDIT_MVP.md).
-- No policy at all for any other role/command: FORCE RLS + zero matching policy = zero visible/
-- writable rows for idax_app/idax_admin/idax_backend even if they somehow acquired a GRANT later.

-- ===== mutation_event: append-only witness. INSERT only via the trigger function running AS
-- stir_audit_owner (SECURITY DEFINER); never UPDATE/DELETE by anyone, including the owner itself
-- (no UPDATE/DELETE policy exists for any role). =====
CREATE TABLE stir_audit.mutation_event (
  audit_event_id        uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id              uuid NOT NULL,
  domain                 text NOT NULL,
  sequence               bigint NOT NULL,
  db_time                timestamptz NOT NULL DEFAULT clock_timestamp(),
  txid                   xid8 NOT NULL DEFAULT pg_current_xact_id(),
  table_oid              oid NOT NULL,
  table_name             text NOT NULL,
  operation              text NOT NULL CHECK (operation IN ('INSERT','UPDATE','DELETE')),
  entity_key             jsonb NOT NULL,        -- queryable convenience view of the PK
  entity_key_canonical   text NOT NULL,         -- exact bytes hashed - "col=value;col2=value2", pk_columns order
  community_id           uuid,                  -- claimed only when the row itself has a community_id column; never joined
  old_row_digest         bytea,                 -- 32-byte sha256(to_jsonb(OLD)::text), NULL on INSERT
  new_row_digest         bytea,                 -- 32-byte sha256(to_jsonb(NEW)::text), NULL on DELETE
  row_digest_profile     text NOT NULL,
  session_user_name      text NOT NULL,         -- session_user - the real login role, not SECURITY DEFINER's current_user
  session_role_reported  text,                  -- current_setting('role') as reported by the session, a claim
  application_name       text,
  client_addr            inet,
  request_id             text,                  -- claim from app.request_id GUC, if set
  correlation_id         text,                  -- claim from app.correlation_id GUC, if set
  authorization_id       text,                  -- claim from app.authorization_id GUC, if set
  proposal_id            text,                  -- claim from app.proposal_id GUC, if set
  previous_hash          bytea NOT NULL,
  current_hash           bytea NOT NULL,
  event_format_version   text NOT NULL,
  UNIQUE (tenant_id, domain, sequence)
);
CREATE INDEX mutation_event_reconcile_idx ON stir_audit.mutation_event (table_oid, entity_key_canonical, sequence DESC);
CREATE INDEX mutation_event_stream_idx ON stir_audit.mutation_event (tenant_id, domain, sequence);
ALTER TABLE stir_audit.mutation_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE stir_audit.mutation_event FORCE ROW LEVEL SECURITY;
CREATE POLICY mutation_event_owner_insert ON stir_audit.mutation_event FOR INSERT TO stir_audit_owner WITH CHECK (true);
CREATE POLICY mutation_event_auditor_read ON stir_audit.mutation_event FOR SELECT TO stir_auditor USING (true);
GRANT SELECT ON TABLE stir_audit.mutation_event TO stir_auditor;

-- ===== verifier_cursor / verification_result: the auditor's own narrow, idempotent progress and
-- verdict storage. stir_auditor may INSERT/UPDATE only these two tables (plus security_incident
-- INSERT and anchor_outbox INSERT/UPDATE below) - never mutation_event/stream_head. =====
CREATE TABLE stir_audit.verifier_cursor (
  cursor_name          text PRIMARY KEY,
  last_audit_event_id  uuid,
  last_sequence_seen    jsonb NOT NULL DEFAULT '{}'::jsonb, -- {"tenant:domain": last_sequence}
  updated_at            timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE stir_audit.verifier_cursor ENABLE ROW LEVEL SECURITY;
ALTER TABLE stir_audit.verifier_cursor FORCE ROW LEVEL SECURITY;
CREATE POLICY verifier_cursor_auditor_all ON stir_audit.verifier_cursor FOR ALL TO stir_auditor USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE ON TABLE stir_audit.verifier_cursor TO stir_auditor;

CREATE TABLE stir_audit.verification_result (
  audit_event_id        uuid NOT NULL,
  verifier_rule_version  text NOT NULL,
  result                 text NOT NULL CHECK (result IN ('PASS_CRYPTO','PASS_STRUCTURE_ONLY','INDETERMINATE','NEEDS_BASELINE','VIOLATION')),
  reason                 text,
  verified_at            timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (audit_event_id, verifier_rule_version)
);
ALTER TABLE stir_audit.verification_result ENABLE ROW LEVEL SECURITY;
ALTER TABLE stir_audit.verification_result FORCE ROW LEVEL SECURITY;
CREATE POLICY verification_result_auditor_all ON stir_audit.verification_result FOR ALL TO stir_auditor USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE ON TABLE stir_audit.verification_result TO stir_auditor;
-- UPSERT-shaped (ON CONFLICT DO UPDATE) is intentional here: a crash between computing a verdict
-- and persisting it must re-run idempotently without duplicating a security_incident row, keyed by
-- (audit_event_id, verifier_rule_version) exactly as CLAUDE_GOVERNED_STATE_AUDIT_ORDERS.md #7 asks.

-- ===== security_incident: append-only. stir_auditor may only INSERT; no UPDATE/DELETE policy for
-- anyone, including stir_auditor itself - acknowledgement is a human/runbook action performed by
-- an infrastructure owner outside this role, per AUDIT_RUNBOOK.md, never by the auditor process. =====
CREATE TABLE stir_audit.security_incident (
  incident_id      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  detected_at       timestamptz NOT NULL DEFAULT now(),
  tenant_id         uuid,
  domain            text,
  audit_event_id    uuid,
  reason_code       text NOT NULL,
  evidence          jsonb NOT NULL,
  acknowledged_at   timestamptz,
  acknowledged_by   text
);
ALTER TABLE stir_audit.security_incident ENABLE ROW LEVEL SECURITY;
ALTER TABLE stir_audit.security_incident FORCE ROW LEVEL SECURITY;
CREATE POLICY security_incident_auditor_insert ON stir_audit.security_incident FOR INSERT TO stir_auditor WITH CHECK (true);
CREATE POLICY security_incident_auditor_read ON stir_audit.security_incident FOR SELECT TO stir_auditor USING (true);
GRANT SELECT, INSERT ON TABLE stir_audit.security_incident TO stir_auditor;

-- ===== anchor_outbox: Phase 1 interface stub only. No row is ever produced by Phase 1 code -
-- status stays 'NOT_IMPLEMENTED_PHASE1' for every possible row. This exists only so Phase 2 (per
-- CLAUDE_GOVERNED_STATE_AUDIT_ORDERS.md's own phase boundary) has a stable table shape to extend,
-- without Phase 1 calling IDAX Ledger or claiming any anchoring occurred. =====
CREATE TABLE stir_audit.anchor_outbox (
  anchor_batch_id  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id         uuid NOT NULL,
  domain            text NOT NULL,
  sequence          bigint NOT NULL,
  head_hash         bytea NOT NULL,
  profile_version   text NOT NULL,
  status            text NOT NULL DEFAULT 'NOT_IMPLEMENTED_PHASE1'
                       CHECK (status IN ('NOT_IMPLEMENTED_PHASE1','PENDING_ANCHOR','VALIDATED_MATCH','NOT_VALIDATED','ANCHOR_MISMATCH','PROVIDER_UNAVAILABLE')),
  created_at        timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE stir_audit.anchor_outbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE stir_audit.anchor_outbox FORCE ROW LEVEL SECURITY;
CREATE POLICY anchor_outbox_auditor_all ON stir_audit.anchor_outbox FOR ALL TO stir_auditor USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE ON TABLE stir_audit.anchor_outbox TO stir_auditor;

-- Still SET ROLE stir_audit_owner: every helper function and the trigger function itself below
-- must also be OWNED by stir_audit_owner, not the postgres migrator, or the SECURITY DEFINER
-- trigger function (which runs with current_user = stir_audit_owner) will get "permission denied"
-- calling its own helper functions - found by actually running this migration and the first
-- forged-INSERT reproduction, not by inspection (see VALIDATION_GOVERNED_STATE_AUDIT_MVP.md).

-- ===== Canonicalization and hashing, versioned and shared with the Java verifier's test vectors
-- (StirAuditHashVectorTest / AuditHashCanonicalizerTest). Every helper is IMMUTABLE/STABLE pure
-- SQL - no table access, no side effects - so a test can call it directly without a trigger. =====

CREATE OR REPLACE FUNCTION stir_audit.lp_bytes(v bytea) RETURNS bytea
LANGUAGE sql IMMUTABLE SET search_path = pg_catalog AS $$
  SELECT int4send(length(v)) || v
$$;

CREATE OR REPLACE FUNCTION stir_audit.lp_text(v text) RETURNS bytea
LANGUAGE sql IMMUTABLE SET search_path = pg_catalog AS $$
  SELECT stir_audit.lp_bytes(convert_to(v, 'UTF8'))
$$;

CREATE OR REPLACE FUNCTION stir_audit.nullable_lp_bytes(v bytea) RETURNS bytea
LANGUAGE sql IMMUTABLE SET search_path = pg_catalog AS $$
  SELECT CASE WHEN v IS NULL THEN '\x00'::bytea ELSE '\x01'::bytea || stir_audit.lp_bytes(v) END
$$;

CREATE OR REPLACE FUNCTION stir_audit.nullable_lp_text(v text) RETURNS bytea
LANGUAGE sql IMMUTABLE SET search_path = pg_catalog AS $$
  SELECT CASE WHEN v IS NULL THEN '\x00'::bytea ELSE '\x01'::bytea || stir_audit.lp_text(v) END
$$;

-- H[0] per stream: fixed, deterministic, never stored bytes from elsewhere.
CREATE OR REPLACE FUNCTION stir_audit.genesis_hash(p_tenant uuid, p_domain text) RETURNS bytea
LANGUAGE sql IMMUTABLE SET search_path = pg_catalog, stir_audit AS $$
  SELECT digest(
    convert_to('STIR-AUDIT-GENESIS-V1', 'UTF8') || stir_audit.lp_text(p_tenant::text) || stir_audit.lp_text(p_domain),
    'sha256')
$$;

-- The row digest profile PG17_JSONB_TEXT_SHA256_V1: sha256(to_jsonb(row)::text) computed by
-- PostgreSQL itself server-side. Only ever called from the trigger with OLD/NEW as ROW arguments;
-- exposed as its own function purely so a test can assert the profile name/behavior directly.
CREATE OR REPLACE FUNCTION stir_audit.row_digest_pg17_jsonb_text_sha256_v1(p_row jsonb) RETURNS bytea
LANGUAGE sql IMMUTABLE SET search_path = pg_catalog, stir_audit AS $$
  SELECT digest(convert_to(p_row::text, 'UTF8'), 'sha256')
$$;

-- H[n] = SHA256( "STIR-AUDIT-EVENT-V1" || H[n-1] || U64(sequence) || LP(tenant_id) || LP(domain)
--   || U32(table_oid) || LP(table_name) || LP(operation) || LP(entity_key_canonical)
--   || NULLABLE_LP(old_row_digest) || NULLABLE_LP(new_row_digest) || LP(row_digest_profile)
--   || LP(session_user_name) || NULLABLE_LP(community_id::text) )
CREATE OR REPLACE FUNCTION stir_audit.compute_event_hash(
  p_previous_hash bytea, p_sequence bigint, p_tenant uuid, p_domain text,
  p_table_oid oid, p_table_name text, p_operation text, p_entity_key_canonical text,
  p_old_row_digest bytea, p_new_row_digest bytea, p_row_digest_profile text,
  p_session_user_name text, p_community_id uuid
) RETURNS bytea
LANGUAGE sql IMMUTABLE SET search_path = pg_catalog, stir_audit AS $$
  SELECT digest(
      convert_to('STIR-AUDIT-EVENT-V1', 'UTF8')
      || p_previous_hash
      || int8send(p_sequence)
      || stir_audit.lp_text(p_tenant::text)
      || stir_audit.lp_text(p_domain)
      || int4send(p_table_oid::int4)
      || stir_audit.lp_text(p_table_name)
      || stir_audit.lp_text(p_operation)
      || stir_audit.lp_text(p_entity_key_canonical)
      || stir_audit.nullable_lp_bytes(p_old_row_digest)
      || stir_audit.nullable_lp_bytes(p_new_row_digest)
      || stir_audit.lp_text(p_row_digest_profile)
      || stir_audit.lp_text(p_session_user_name)
      || stir_audit.nullable_lp_text(p_community_id::text),
      'sha256')
$$;

REVOKE EXECUTE ON FUNCTION stir_audit.lp_bytes(bytea) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION stir_audit.lp_text(text) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION stir_audit.nullable_lp_bytes(bytea) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION stir_audit.nullable_lp_text(text) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION stir_audit.genesis_hash(uuid, text) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION stir_audit.row_digest_pg17_jsonb_text_sha256_v1(jsonb) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION stir_audit.compute_event_hash(bytea,bigint,uuid,text,oid,text,text,text,bytea,bytea,text,text,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION stir_audit.lp_bytes(bytea) TO stir_auditor;
GRANT EXECUTE ON FUNCTION stir_audit.lp_text(text) TO stir_auditor;
GRANT EXECUTE ON FUNCTION stir_audit.nullable_lp_bytes(bytea) TO stir_auditor;
GRANT EXECUTE ON FUNCTION stir_audit.nullable_lp_text(text) TO stir_auditor;
GRANT EXECUTE ON FUNCTION stir_audit.genesis_hash(uuid, text) TO stir_auditor;
GRANT EXECUTE ON FUNCTION stir_audit.row_digest_pg17_jsonb_text_sha256_v1(jsonb) TO stir_auditor;
GRANT EXECUTE ON FUNCTION stir_audit.compute_event_hash(bytea,bigint,uuid,text,oid,text,text,text,bytea,bytea,text,text,uuid) TO stir_auditor;
-- These SQL functions never touch a table, so granting stir_auditor EXECUTE lets the verifier
-- recompute hashes/canonical bytes identically to the trigger without duplicating the formula in
-- two places that could silently drift; it grants no write capability whatsoever.

-- ===== The trigger function itself: SECURITY DEFINER, owned by stir_audit_owner, closed
-- search_path, qualified references only, OLD/NEW from the engine (never a JSON argument from
-- Java/HTTP), WHEN OTHERS is never caught - persistence failure aborts the governed mutation in
-- the same transaction (no swallowed exceptions, unlike idax_core.audit_log's own trigger). =====
CREATE OR REPLACE FUNCTION stir_audit.emit_mutation_event() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, stir_audit AS $$
DECLARE
  v_domain               text;
  v_pk_columns            text[];
  v_digest_profile        text;
  v_tenant                uuid;
  v_community              uuid;
  v_row_jsonb              jsonb;
  v_entity_key             jsonb;
  v_entity_key_canonical   text;
  v_old_digest             bytea;
  v_new_digest             bytea;
  v_head                   bytea;
  v_seq                    bigint;
  v_new_head                bytea;
  v_session_user            text := session_user;
  v_session_role_reported   text := current_setting('role', true);
  v_application_name        text := current_setting('application_name', true);
  v_request_id               text := current_setting('app.request_id', true);
  v_correlation_id            text := current_setting('app.correlation_id', true);
  v_authorization_id           text := current_setting('app.authorization_id', true);
  v_proposal_id                  text := current_setting('app.proposal_id', true);
BEGIN
  SELECT domain, pk_columns, digest_profile_version
    INTO v_domain, v_pk_columns, v_digest_profile
    FROM stir_audit.coverage_registry
    WHERE table_oid = TG_RELID AND coverage_status = 'COVERED';

  IF v_domain IS NULL THEN
    RAISE EXCEPTION 'stir_audit: table % (oid %) fired the audit trigger but has no COVERED coverage_registry row - fail closed', TG_TABLE_NAME, TG_RELID
      USING ERRCODE = 'raise_exception';
  END IF;

  v_row_jsonb := to_jsonb(COALESCE(NEW, OLD));
  v_tenant := (v_row_jsonb ->> 'tenant_id')::uuid;
  IF v_tenant IS NULL THEN
    RAISE EXCEPTION 'stir_audit: table % row has NULL tenant_id - cannot assign a stream, fail closed', TG_TABLE_NAME
      USING ERRCODE = 'raise_exception';
  END IF;
  -- community_id is claimed only when the row itself carries that column - never derived via a
  -- JOIN to a possibly-forged or absent related row (GOVERNED_STATE_AUDIT_ARCHITECTURE.md).
  v_community := NULLIF(v_row_jsonb ->> 'community_id', '')::uuid;

  v_entity_key := (SELECT jsonb_object_agg(kv.key, kv.value) FROM jsonb_each(v_row_jsonb) kv WHERE kv.key = ANY(v_pk_columns));
  SELECT string_agg(u.col || '=' || COALESCE(v_row_jsonb ->> u.col, E'\\N'), ';' ORDER BY u.ord)
    INTO v_entity_key_canonical
    FROM unnest(v_pk_columns) WITH ORDINALITY AS u(col, ord);

  IF TG_OP IN ('UPDATE','DELETE') THEN
    v_old_digest := stir_audit.row_digest_pg17_jsonb_text_sha256_v1(to_jsonb(OLD));
  END IF;
  IF TG_OP IN ('INSERT','UPDATE') THEN
    v_new_digest := stir_audit.row_digest_pg17_jsonb_text_sha256_v1(to_jsonb(NEW));
  END IF;

  INSERT INTO stir_audit.stream_head (tenant_id, domain, sequence, head_hash)
    VALUES (v_tenant, v_domain, 0, stir_audit.genesis_hash(v_tenant, v_domain))
    ON CONFLICT (tenant_id, domain) DO NOTHING;

  SELECT sequence, head_hash INTO v_seq, v_head
    FROM stir_audit.stream_head WHERE tenant_id = v_tenant AND domain = v_domain
    FOR UPDATE;

  v_seq := v_seq + 1;
  v_new_head := stir_audit.compute_event_hash(v_head, v_seq, v_tenant, v_domain, TG_RELID, TG_TABLE_NAME, TG_OP,
    v_entity_key_canonical, v_old_digest, v_new_digest, v_digest_profile, v_session_user, v_community);

  UPDATE stir_audit.stream_head SET sequence = v_seq, head_hash = v_new_head, updated_at = now()
    WHERE tenant_id = v_tenant AND domain = v_domain;

  INSERT INTO stir_audit.mutation_event (
    tenant_id, domain, sequence, table_oid, table_name, operation, entity_key, entity_key_canonical,
    community_id, old_row_digest, new_row_digest, row_digest_profile, session_user_name,
    session_role_reported, application_name, client_addr, request_id, correlation_id,
    authorization_id, proposal_id, previous_hash, current_hash, event_format_version
  ) VALUES (
    v_tenant, v_domain, v_seq, TG_RELID, TG_TABLE_NAME, TG_OP, v_entity_key, v_entity_key_canonical,
    v_community, v_old_digest, v_new_digest, v_digest_profile, v_session_user,
    v_session_role_reported, v_application_name, inet_client_addr(), v_request_id, v_correlation_id,
    v_authorization_id, v_proposal_id, v_head, v_new_head, 'STIR_AUDIT_EVENT_V1'
  );

  RETURN COALESCE(NEW, OLD);
END;
$$;
REVOKE EXECUTE ON FUNCTION stir_audit.emit_mutation_event() FROM PUBLIC;
-- No EXECUTE grant to anyone at all: this function is invoked exclusively by the trigger
-- mechanism, never called directly. A callable "prepared event" entry point does not exist here.

RESET ROLE;
-- Back to the postgres migrator for CREATE TRIGGER on stir.* tables below: stir_audit_owner does
-- not own those tables and must not be granted TRIGGER privilege on them (it would be an
-- unnecessary, unused permission - the trigger's own SECURITY DEFINER function already runs as
-- stir_audit_owner regardless of which role issued the CREATE TRIGGER DDL).

-- ===== coverage_registry population: computed from real information_schema, not hand-typed table
-- names, to minimize transcription risk (CLAUDE_GOVERNED_STATE_AUDIT_ORDERS.md #1's "no inventes
-- contratos"). One explicit VALUES list assigns each real STIR table to a domain or an excluded/
-- catalog classification; any table in stir.* not named here makes this DO block fail the
-- migration itself (fail-closed at migration time, in addition to StirAuditCoverageRegistryTest's
-- own CI check against a running database). =====
DO $do_registry$
DECLARE
  v_classification jsonb := '{
    "market_constitution": "CONSTITUTION", "constitutional_proposal": "CONSTITUTION",
    "constitutional_signature": "CONSTITUTION", "market_governance_event": "CONSTITUTION",
    "constitutional_authority": "CONSTITUTION", "constitutional_seat": "CONSTITUTION",
    "constitutional_credential_history": "CONSTITUTION", "constitutional_webauthn_credential": "CONSTITUTION",
    "constitutional_webauthn_challenge": "CONSTITUTION",
    "market_integrity_case": "INTEGRITY", "market_integrity_case_event": "INTEGRITY",
    "community_governance_settings": "ORDINARY", "community_governance_member": "ORDINARY",
    "ordinary_governance_policy": "ORDINARY", "ordinary_proposal": "ORDINARY",
    "ordinary_proposal_electorate": "ORDINARY", "ordinary_vote": "ORDINARY",
    "ordinary_proposal_execution": "ORDINARY", "community_seed": "ORDINARY",
    "community_reference": "REFERENCE", "reference_policy": "REFERENCE",
    "reference_snapshot": "REFERENCE", "reference_proposal": "REFERENCE",
    "reference_context_snapshot": "REFERENCE", "reference_definition": "REFERENCE",
    "reference_consent": "CONSENT_RETENTION", "reference_consent_event": "CONSENT_RETENTION",
    "retention_policy": "CONSENT_RETENTION", "retention_lifecycle_event": "CONSENT_RETENTION",
    "reference_observation": "CONSENT_RETENTION",
    "agreement": "AGREEMENT_ECONOMIC", "agreement_snapshot": "AGREEMENT_ECONOMIC",
    "offer": "AGREEMENT_ECONOMIC", "negotiation": "AGREEMENT_ECONOMIC", "trade": "AGREEMENT_ECONOMIC",
    "marketplace_economic_binding": "AGREEMENT_ECONOMIC", "participant_economic_binding": "AGREEMENT_ECONOMIC",
    "listing": "MARKETPLACE_IDENTITY", "listing_revision": "MARKETPLACE_IDENTITY",
    "participant_independence_projection": "MARKETPLACE_IDENTITY"
  }'::jsonb;
  -- Operational tables deliberately deferred out of Phase 1 coverage, each with its own
  -- justification (CLAUDE_GOVERNED_STATE_AUDIT_ORDERS.md #3's "justificarlo"): none of these is a
  -- governance/authority/decision table; each already has its own narrower lifecycle contract.
  v_excluded jsonb := '{
    "attachment": "File/photo lifecycle (upload, owner or moderation delete) - not a governance decision; already has its own narrower operational DELETE contract distinct from the append-only governed-state tables this MVP protects.",
    "participant_device_credential": "Browser-device WebAuthn convenience bookkeeping - the constitutional credential itself (constitutional_webauthn_credential) is covered separately; this table is a UX mapping, not a governed authority decision.",
    "content_report": "Moderation report intake - the report itself is a claim/triage input, not an authority decision; any resulting mutation lands on listing/attachment, which are classified separately.",
    "notification": "Pure UI notification record with no governance semantics.",
    "participant_profile": "Basic profile fields - not a governed decision surface."
  }'::jsonb;
  r record;
  v_domain text;
  v_pk_columns text[];
  v_justification text;
  -- P1-R2-002 activation ceremony: one batch id ties every baseline_import row created by this
  -- migration run to exactly one activation_marker row, inserted FIRST (baseline_import.import_batch
  -- has a real FK to activation_marker.activation_batch) - the operational ceremony diagram in
  -- VALIDATION_GOVERNED_STATE_AUDIT_MVP.md describes "capture baseline" as conceptually preceding
  -- "establish activation marker", but within this one already-atomic migration transaction nothing
  -- outside it can observe either statement until BOTH commit together, so the FK's own ordering
  -- requirement (marker row must exist first) changes nothing about the guarantee actually being
  -- documented; enforcing the FK is worth more than a diagram-literal statement order.
  v_activation_batch uuid := gen_random_uuid();
  v_baseline_count bigint;
BEGIN
  INSERT INTO stir_audit.activation_marker (activation_batch, migration_version) VALUES (v_activation_batch, 'V18');

  FOR r IN
    SELECT c.oid AS table_oid, c.relname AS table_name
    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'stir' AND c.relkind = 'r' AND c.relname <> 'flyway_schema_history'
  LOOP
    IF v_classification ? r.table_name THEN
      v_domain := v_classification ->> r.table_name;
      SELECT array_agg(kcu.column_name ORDER BY kcu.ordinal_position) INTO v_pk_columns
        FROM information_schema.table_constraints tc
        JOIN information_schema.key_column_usage kcu
          ON kcu.constraint_name = tc.constraint_name AND kcu.table_schema = tc.table_schema
        WHERE tc.table_schema = 'stir' AND tc.table_name = r.table_name AND tc.constraint_type = 'PRIMARY KEY';
      IF v_pk_columns IS NULL THEN
        RAISE EXCEPTION 'stir_audit: table % is classified as COVERED but has no primary key to key events by', r.table_name;
      END IF;
      INSERT INTO stir_audit.coverage_registry (table_oid, schema_name, table_name, domain, pk_columns, coverage_status, justification, digest_profile_version)
        VALUES (r.table_oid, 'stir', r.table_name, v_domain, v_pk_columns, 'COVERED',
          'Governed state per GOVERNED_STATE_AUDIT_ARCHITECTURE.md domain classification.', 'PG17_JSONB_TEXT_SHA256_V1');

      -- ===== P1-R2-002 activation ceremony, per-table, INSIDE this migration's own transaction:
      -- LOCK this table ACCESS EXCLUSIVE FIRST (before reading a single row of it), capture its
      -- baseline snapshot next, and only THEN create the trigger. PostgreSQL holds an ACCESS
      -- EXCLUSIVE lock until this whole migration transaction commits or rolls back - so from the
      -- instant this LOCK statement returns, no session (including idax_app) can INSERT/UPDATE/
      -- DELETE this table until the entire V18 migration finishes, meaning there is no statement-
      -- level gap in which an ordinary runtime write could land between "baseline captured" and
      -- "trigger active" for this table. This is what closes the "quiesce application writers"
      -- ceremony step technically, for the ordinary-writer threat model, rather than only as an
      -- operational runbook instruction: a superuser bypassing this lock (e.g. via a second
      -- superuser session force-disabling the trigger afterward) remains explicitly outside Phase
      -- 1's trust boundary, per P1-R2-002 - this migration does not and cannot defend against that. =====
      EXECUTE format('LOCK TABLE stir.%I IN ACCESS EXCLUSIVE MODE', r.table_name);
      EXECUTE format($f$
        INSERT INTO stir_audit.baseline_import (table_oid, entity_key_canonical, tenant_id, import_batch)
        SELECT %L::oid,
               (SELECT string_agg(u.col || '=' || COALESCE(to_jsonb(t)::jsonb ->> u.col, E'\\N'), ';' ORDER BY u.ord)
                  FROM unnest(%L::text[]) WITH ORDINALITY AS u(col, ord)),
               NULLIF(to_jsonb(t)::jsonb ->> 'tenant_id', '')::uuid,
               %L::uuid
        FROM stir.%I t
        ON CONFLICT (table_oid, entity_key_canonical) DO NOTHING
      $f$, r.table_oid, v_pk_columns, v_activation_batch, r.table_name);
      GET DIAGNOSTICS v_baseline_count = ROW_COUNT;
      RAISE NOTICE 'stir_audit: activation ceremony captured % LEGACY_UNVERIFIED baseline rows for stir.% before activating its trigger', v_baseline_count, r.table_name;

      EXECUTE format('CREATE TRIGGER audit_emit_mutation_event AFTER INSERT OR UPDATE OR DELETE ON stir.%I FOR EACH ROW EXECUTE FUNCTION stir_audit.emit_mutation_event()', r.table_name);
    ELSIF v_excluded ? r.table_name THEN
      v_justification := v_excluded ->> r.table_name;
      INSERT INTO stir_audit.coverage_registry (table_oid, schema_name, table_name, coverage_status, justification)
        VALUES (r.table_oid, 'stir', r.table_name, 'EXCLUDED_JUSTIFIED', v_justification);
    ELSIF NOT (has_table_privilege('idax_app', format('stir.%I', r.table_name), 'INSERT')
            OR has_table_privilege('idax_app', format('stir.%I', r.table_name), 'UPDATE')
            OR has_table_privilege('idax_app', format('stir.%I', r.table_name), 'DELETE')) THEN
      -- category/resource_kind today: no runtime role can ever mutate them via ANY of INSERT,
      -- UPDATE or DELETE, so no trigger is meaningful; re-verified live rather than hardcoded by
      -- name, so a future table that also happens to have no idax_app DML still classifies
      -- correctly without a migration edit. Checking all three verbs (not just INSERT) here directly
      -- is P1-RA-006's own re-verification, done once at classification time; V19 additionally
      -- re-checks this invariant against the schema as it stands after upgrade.
      INSERT INTO stir_audit.coverage_registry (table_oid, schema_name, table_name, coverage_status, justification)
        VALUES (r.table_oid, 'stir', r.table_name, 'NO_PRIVILEGE_CATALOG', 'idax_app has no INSERT, UPDATE or DELETE privilege on this table today - verified live via has_table_privilege at migration time, not assumed.');
    ELSE
      RAISE EXCEPTION 'stir_audit: table stir.% is new, has an unclassified sensitive privilege surface, and is neither COVERED nor EXCLUDED_JUSTIFIED nor a no-privilege catalog - classify it explicitly in V18 (or a later additive migration) before this can proceed. This is the fail-closed CI gate CLAUDE_GOVERNED_STATE_AUDIT_ORDERS.md #3 requires.', r.table_name;
    END IF;
  END LOOP;

  RAISE NOTICE 'stir_audit: activation ceremony complete, activation_batch=%', v_activation_batch;
END $do_registry$;

-- ===== stir_auditor's cross-tenant read of covered STIR tables (GOVERNED_STATE_AUDIT_ARCHITECTURE.md:
-- "esa lectura transversal es privilegio de un secreto separado y no pasa a la API pública"). This
-- grants SELECT only - never INSERT/UPDATE/DELETE - and adds a SECOND, additional permissive RLS
-- policy scoped strictly `TO stir_auditor`; it does not touch, widen or replace the existing
-- tenant-scoped policy idax_app/idax_admin/idax_backend already use (multiple permissive policies
-- are evaluated per-role: stir_auditor is not a member of idax_app, so it only ever matches its
-- own policy, and idax_app continues to see only what app.tenant_id already restricted it to). =====
GRANT USAGE ON SCHEMA stir TO stir_auditor;
-- Schema USAGE is its own mandatory precondition, separate from both the per-table GRANT and any
-- RLS policy - found the same way the missing per-table GRANTs above were found, by actually
-- running the packaged verifier and getting "permission denied for schema stir".

DO $do_auditor_reads$
DECLARE
  r record;
BEGIN
  FOR r IN SELECT table_name FROM stir_audit.coverage_registry WHERE coverage_status = 'COVERED' ORDER BY table_name
  LOOP
    EXECUTE format('GRANT SELECT ON TABLE stir.%I TO stir_auditor', r.table_name);
    EXECUTE format('CREATE POLICY stir_auditor_cross_tenant_read ON stir.%I FOR SELECT TO stir_auditor USING (true)', r.table_name);
  END LOOP;
END $do_auditor_reads$;
