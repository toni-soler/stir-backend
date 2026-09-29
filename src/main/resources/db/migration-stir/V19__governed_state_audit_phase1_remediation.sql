-- TAMPER-EVIDENT GOVERNED STATE AUDIT MVP — Phase 1 remediation, per Codex's independent
-- adversarial reaudit (REVALIDATION_GOVERNED_STATE_AUDIT_PHASE1.md). Findings addressed here at
-- the schema level: P1-RA-002 (incident dedup + atomicity substrate), P1-RA-003 (baseline import,
-- so a real upgrade does not manufacture false CRITICAL noise from pre-existing rows),
-- P1-RA-005 (a new, versioned hash profile that additionally commits audit_event_id, db_time,
-- txid, event_format_version, client/correlation metadata - V18's original profile/events are
-- NEVER retroactively reinterpreted), P1-RA-007 (a durable checkpoint table for periodic full-
-- chain reconciliation, separate from incremental per-event processing). P1-RA-001, P1-RA-004 and
-- P1-RA-006 are primarily Java/test-side fixes (audit-verifier's polling query, IntegrityRule, and
-- a new stir-backend-side coverage test) and do not need schema changes beyond what is here.
--
-- Does NOT close AUD-012. Does NOT touch Seven Keys/Guardian/Ordinary Governance/osTRIS/Agreement
-- semantics. Does NOT add external anchoring or a consumption gate. Additive only, on top of V18;
-- no V18 statement is edited or reordered.

SET ROLE stir_audit_owner;

-- ===== P1-RA-003: baseline import. A snapshot, taken once at this migration's apply time, of
-- every PK that already existed in a COVERED table before the audit trigger could have witnessed
-- its creation. NEVER an event - no mutation_event row is inserted for these, and no hash chain
-- entry is fabricated. Reconciler consults this table to distinguish "legitimately pre-existing,
-- never audited because audit did not exist yet" from "a new row that skipped the trigger", which
-- is the only distinction CLAUDE_GOVERNED_STATE_AUDIT_ORDERS.md's remediation actually asks for. =====
CREATE TABLE stir_audit.baseline_import (
  table_oid             oid NOT NULL,
  entity_key_canonical  text NOT NULL,
  tenant_id             uuid,                 -- best-effort, for observability/filtering only
  imported_at           timestamptz NOT NULL DEFAULT now(),
  import_batch          uuid NOT NULL,        -- groups every row from the same migration/import run
  PRIMARY KEY (table_oid, entity_key_canonical)
);
REVOKE ALL ON TABLE stir_audit.baseline_import FROM PUBLIC;
GRANT SELECT ON TABLE stir_audit.baseline_import TO stir_auditor;
-- No RLS, same reasoning as coverage_registry: migrator-only writer, schema metadata rather than
-- tenant business data, read cross-tenant by the auditor by design.

RESET ROLE;
-- Back to postgres/migrator for this block: it SELECTs FROM stir.* tables to snapshot existing
-- PKs, and stir_audit_owner deliberately has no grant on stir.* at all (only stir_auditor does,
-- per V18) - found by actually running this migration, same "permission denied" pattern as V18's
-- own findings, not by inspection.

DO $do_baseline$
DECLARE
  r record;
  v_batch uuid := gen_random_uuid();
  v_count bigint;
BEGIN
  FOR r IN SELECT table_oid, table_name, pk_columns FROM stir_audit.coverage_registry WHERE coverage_status = 'COVERED'
  LOOP
    -- Exact same canonical-key construction the trigger uses (unnest(...) WITH ORDINALITY +
    -- col=value;col2=value2), duplicated deliberately rather than factored into a shared function
    -- called by both: emit_mutation_event() must never change behavior for already-hashed V18
    -- events, and importing this migration-only, run-once snapshot logic into that hot path would
    -- only add risk for zero benefit (P1-RA-005's same "do not silently touch what is already
    -- hashed" discipline, applied here to structural behavior instead of the hash formula itself).
    EXECUTE format($f$
      INSERT INTO stir_audit.baseline_import (table_oid, entity_key_canonical, tenant_id, import_batch)
      SELECT %L::oid,
             (SELECT string_agg(u.col || '=' || COALESCE(to_jsonb(t)::jsonb ->> u.col, E'\\N'), ';' ORDER BY u.ord)
                FROM unnest(%L::text[]) WITH ORDINALITY AS u(col, ord)),
             NULLIF(to_jsonb(t)::jsonb ->> 'tenant_id', '')::uuid,
             %L::uuid
      FROM stir.%I t
      ON CONFLICT (table_oid, entity_key_canonical) DO NOTHING
    $f$, r.table_oid, r.pk_columns, v_batch, r.table_name);
    GET DIAGNOSTICS v_count = ROW_COUNT;
    RAISE NOTICE 'stir_audit: baseline_import seeded % rows for stir.%', v_count, r.table_name;
  END LOOP;
END $do_baseline$;

-- ===== P1-RA-002 / P1-RA-007: security_incident gains a deterministic dedup key so re-detecting
-- the same problem (a crash-recovery reprocessing an event, or a periodic chain scan re-observing
-- an already-reported historical break) can INSERT ... ON CONFLICT DO NOTHING instead of growing
-- an unbounded duplicate trail. Two disjoint key shapes share the column: per-event incidents use
-- "<audit_event_id>:<verifier_rule_version>:<reason_code>"; chain-level incidents (no single event
-- to anchor to, e.g. a deleted link) use "<tenant_id>:<domain>:<reason_code>:<sequence-or-marker>". =====
ALTER TABLE stir_audit.security_incident ADD COLUMN dedup_key text;
UPDATE stir_audit.security_incident SET dedup_key = incident_id::text WHERE dedup_key IS NULL;
-- Backfill for any Phase-1 incident rows from before this column existed (none expected outside a
-- developer's own test databases, since Phase 1 was never deployed) - incident_id is already
-- unique, so this is a safe, non-colliding placeholder rather than a guess.
ALTER TABLE stir_audit.security_incident ALTER COLUMN dedup_key SET NOT NULL;
ALTER TABLE stir_audit.security_incident ADD CONSTRAINT security_incident_dedup_key_unique UNIQUE (dedup_key);

SET ROLE stir_audit_owner;
-- Back to owning-role context for the rest of this migration: chain_reconciliation_checkpoint and
-- both function definitions below must be owned by stir_audit_owner, same reason as V18's own
-- "permission denied calling its own helper functions" finding - a SECURITY DEFINER function can
-- only implicitly call functions its OWNER has execute rights on.

-- ===== P1-RA-007: durable checkpoint for periodic FULL chain reconciliation, kept explicitly
-- separate from stir_audit.verifier_cursor (incremental per-event processing). The verifier
-- (VerifierLoop.runPeriodicChainReconciliation) always re-walks each stream from genesis on every
-- periodic pass - NOT resumed from last_verified_sequence - because resuming was found, during this
-- same remediation, to silently miss a tamper to an interior event once the checkpoint had already
-- advanced past it (see that method's own comment for the full reasoning). This table is therefore
-- pure observability/bookkeeping ("last sequence a full walk verified clean through, as of
-- last_run_at"), never a resume point that skips re-verifying historical links. =====
CREATE TABLE stir_audit.chain_reconciliation_checkpoint (
  tenant_id              uuid NOT NULL,
  domain                 text NOT NULL,
  last_verified_sequence bigint NOT NULL DEFAULT 0,
  last_verified_head     bytea,
  last_run_at            timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, domain)
);
ALTER TABLE stir_audit.chain_reconciliation_checkpoint ENABLE ROW LEVEL SECURITY;
ALTER TABLE stir_audit.chain_reconciliation_checkpoint FORCE ROW LEVEL SECURITY;
CREATE POLICY chain_reconciliation_checkpoint_auditor_all ON stir_audit.chain_reconciliation_checkpoint FOR ALL TO stir_auditor USING (true) WITH CHECK (true);
GRANT SELECT, INSERT, UPDATE ON TABLE stir_audit.chain_reconciliation_checkpoint TO stir_auditor;
-- Read-modify-write of "how far reconciliation has confirmed" is exactly the auditor's own narrow
-- progress bookkeeping, same trust class as verifier_cursor/verification_result - never mutation_
-- event/stream_head themselves, which stay owner-only exactly as V18 established.

-- ===== P1-RA-005: hash profile V2. Commits every field the verifier actually treats as evidence
-- (ordering, event identity, correlation claims, transaction, chronology) that V1 left out. V1's
-- compute_event_hash/genesis_hash/row_digest_* are UNTOUCHED below - existing V18 events keep
-- verifying against the exact formula that produced them; only emit_mutation_event() is replaced,
-- and only to call the new function for events it writes from this migration onward. =====

-- H2[n] = SHA256( "STIR-AUDIT-EVENT-V2" || H[n-1] || U64(sequence) || LP(audit_event_id)
--   || LP(tenant_id) || LP(domain) || U32(table_oid) || LP(table_name) || LP(operation)
--   || LP(entity_key_canonical) || NULLABLE_LP(old_row_digest) || NULLABLE_LP(new_row_digest)
--   || LP(row_digest_profile) || LP(session_user_name) || NULLABLE_LP(session_role_reported)
--   || NULLABLE_LP(application_name) || NULLABLE_LP(client_addr_text) || NULLABLE_LP(request_id)
--   || NULLABLE_LP(correlation_id) || NULLABLE_LP(authorization_id) || NULLABLE_LP(proposal_id)
--   || LP(db_time_text) || LP(txid_text) || LP(event_format_version) || NULLABLE_LP(community_id) )
-- db_time/txid are hashed as their exact canonical text representation
-- (to_char(..,'YYYY-MM-DD"T"HH24:MI:SS.US"Z"') / txid::text) so the verifier's independently
-- fetched values (read back from the same stored columns) reproduce byte-identical input without
-- depending on any driver-specific binary timestamp/xid8 encoding.
CREATE OR REPLACE FUNCTION stir_audit.compute_event_hash_v2(
  p_previous_hash bytea, p_sequence bigint, p_audit_event_id uuid, p_tenant uuid, p_domain text,
  p_table_oid oid, p_table_name text, p_operation text, p_entity_key_canonical text,
  p_old_row_digest bytea, p_new_row_digest bytea, p_row_digest_profile text,
  p_session_user_name text, p_session_role_reported text, p_application_name text,
  p_client_addr_text text, p_request_id text, p_correlation_id text, p_authorization_id text,
  p_proposal_id text, p_db_time_text text, p_txid_text text, p_event_format_version text,
  p_community_id uuid
) RETURNS bytea
LANGUAGE sql IMMUTABLE SET search_path = pg_catalog, stir_audit AS $$
  SELECT digest(
      convert_to('STIR-AUDIT-EVENT-V2', 'UTF8')
      || p_previous_hash
      || int8send(p_sequence)
      || stir_audit.lp_text(p_audit_event_id::text)
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
      || stir_audit.nullable_lp_text(p_session_role_reported)
      || stir_audit.nullable_lp_text(p_application_name)
      || stir_audit.nullable_lp_text(p_client_addr_text)
      || stir_audit.nullable_lp_text(p_request_id)
      || stir_audit.nullable_lp_text(p_correlation_id)
      || stir_audit.nullable_lp_text(p_authorization_id)
      || stir_audit.nullable_lp_text(p_proposal_id)
      || stir_audit.lp_text(p_db_time_text)
      || stir_audit.lp_text(p_txid_text)
      || stir_audit.lp_text(p_event_format_version)
      || stir_audit.nullable_lp_text(p_community_id::text),
      'sha256')
$$;
REVOKE EXECUTE ON FUNCTION stir_audit.compute_event_hash_v2(bytea,bigint,uuid,uuid,text,oid,text,text,text,bytea,bytea,text,text,text,text,text,text,text,text,text,text,text,text,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION stir_audit.compute_event_hash_v2(bytea,bigint,uuid,uuid,text,oid,text,text,text,bytea,bytea,text,text,text,text,text,text,text,text,text,text,text,text,uuid) TO stir_auditor;
-- NOT HASH-PROTECTED / CONTEXT ONLY, documented explicitly rather than silently omitted:
--   - table_oid's live catalog lookup (the STORED value in the event row is hashed and verified;
--     but pg_class.oid is not itself durable across a dump/restore - a restored database may
--     assign a table a different oid than it had originally). This is a restore-time caveat on
--     table_oid's meaning, not a gap in what this profile commits to for the row as originally
--     written; Fase 2 restore validation will need to resolve oid by table_name, not assume it is
--     dump/restore-stable.
--   - PostgreSQL's own MVCC/WAL-level transaction commit timestamp (distinct from the trigger's
--     own clock_timestamp()) is not captured or hashed at all; db_time is this profile's only
--     chronology claim, and it is a claim, not a proof, exactly like every other context field.

-- ===== emit_mutation_event(): CREATE OR REPLACE only, V18's INSERT/UPDATE/DELETE behavior,
-- coverage_registry lookup, entity-key derivation and digest capture are byte-for-byte unchanged;
-- the only difference is explicitly capturing audit_event_id/db_time/txid/client_addr into local
-- variables BEFORE hashing (so the hash input and the stored row are guaranteed to agree - V18
-- let the audit_event_id/db_time/txid columns' own DEFAULT expressions run independently of the
-- hash computation, which was harmless there only because none of those three were hashed at all)
-- and using compute_event_hash_v2/'STIR_AUDIT_EVENT_V2' instead of the V1 call. =====
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
  -- P1-RA-005: captured once, used identically for both the hash input and the stored columns.
  v_audit_event_id       uuid := gen_random_uuid();
  v_db_time               timestamptz := clock_timestamp();
  v_txid                   xid8 := pg_current_xact_id();
  v_client_addr             inet := inet_client_addr();
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
  v_new_head := stir_audit.compute_event_hash_v2(v_head, v_seq, v_audit_event_id, v_tenant, v_domain,
    TG_RELID, TG_TABLE_NAME, TG_OP, v_entity_key_canonical, v_old_digest, v_new_digest, v_digest_profile,
    v_session_user, v_session_role_reported, v_application_name, v_client_addr::text, v_request_id,
    v_correlation_id, v_authorization_id, v_proposal_id,
    to_char(v_db_time AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'), v_txid::text,
    'STIR_AUDIT_EVENT_V2', v_community);

  UPDATE stir_audit.stream_head SET sequence = v_seq, head_hash = v_new_head, updated_at = now()
    WHERE tenant_id = v_tenant AND domain = v_domain;

  INSERT INTO stir_audit.mutation_event (
    audit_event_id, tenant_id, domain, sequence, db_time, txid, table_oid, table_name, operation,
    entity_key, entity_key_canonical, community_id, old_row_digest, new_row_digest,
    row_digest_profile, session_user_name, session_role_reported, application_name, client_addr,
    request_id, correlation_id, authorization_id, proposal_id, previous_hash, current_hash,
    event_format_version
  ) VALUES (
    v_audit_event_id, v_tenant, v_domain, v_seq, v_db_time, v_txid, TG_RELID, TG_TABLE_NAME, TG_OP,
    v_entity_key, v_entity_key_canonical, v_community, v_old_digest, v_new_digest, v_digest_profile,
    v_session_user, v_session_role_reported, v_application_name, v_client_addr, v_request_id,
    v_correlation_id, v_authorization_id, v_proposal_id, v_head, v_new_head, 'STIR_AUDIT_EVENT_V2'
  );

  RETURN COALESCE(NEW, OLD);
END;
$$;
REVOKE EXECUTE ON FUNCTION stir_audit.emit_mutation_event() FROM PUBLIC;

RESET ROLE;

-- P1-RA-001's incremental query (see AuditSql.fetchUnverifiedEvents) is an anti-join between
-- mutation_event and verification_result on audit_event_id - both sides already have that column
-- as (part of) their primary key from V18, so no additional index is needed here.

-- ===== P1-RA-006: V18's coverage_registry population classified a table 'NO_PRIVILEGE_CATALOG'
-- by checking ONLY has_table_privilege('idax_app', ..., 'INSERT') - a table where idax_app has no
-- INSERT but DOES have UPDATE or DELETE would have been wrongly bucketed as "no runtime role can
-- ever mutate them" while in fact idax_app could silently mutate existing rows via UPDATE/DELETE
-- with zero audit trigger coverage. No table in this schema is actually affected today (re-verified
-- below, at every migration run, not just once): stir.category/stir.resource_kind (the only two
-- NO_PRIVILEGE_CATALOG rows V18 produced) have only SELECT granted to idax_app (V1__listings.sql),
-- so INSERT/UPDATE/DELETE are all absent. This block re-verifies that invariant live, for every
-- coverage_registry row of either classification, and fails closed (RAISE EXCEPTION, aborting the
-- migration) if it is ever violated - by a manual grant drift on an existing table, or by a future
-- migration adding a new table whose privilege surface does not match its stored classification.
-- A COVERED table is exempt from this check by construction: it already has a trigger. =====
DO $do_coverage_privilege_reverify$
DECLARE
  r record;
  v_has_insert boolean;
  v_has_update boolean;
  v_has_delete boolean;
BEGIN
  FOR r IN SELECT table_oid, table_name, coverage_status FROM stir_audit.coverage_registry WHERE coverage_status <> 'COVERED'
  LOOP
    v_has_insert := has_table_privilege('idax_app', format('stir.%I', r.table_name), 'INSERT');
    v_has_update := has_table_privilege('idax_app', format('stir.%I', r.table_name), 'UPDATE');
    v_has_delete := has_table_privilege('idax_app', format('stir.%I', r.table_name), 'DELETE');
    IF r.coverage_status = 'NO_PRIVILEGE_CATALOG' AND (v_has_insert OR v_has_update OR v_has_delete) THEN
      RAISE EXCEPTION 'stir_audit: table stir.% is classified NO_PRIVILEGE_CATALOG but idax_app actually has INSERT=% UPDATE=% DELETE=% - reclassify as COVERED or EXCLUDED_JUSTIFIED (P1-RA-006 fail-closed re-verification)', r.table_name, v_has_insert, v_has_update, v_has_delete
        USING ERRCODE = 'raise_exception';
    END IF;
  END LOOP;
END $do_coverage_privilege_reverify$;
