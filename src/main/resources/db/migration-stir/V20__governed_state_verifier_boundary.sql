-- V20: governed-state verifier boundary (Phase 0 option B; closes the constitutional AUD-012 vector).
--
-- Before this migration idax_app and idax_backend could INSERT/UPDATE every constitutional table
-- (market_constitution, constitutional_authority/seat/proposal/signature/credential_history,
-- market_governance_event, WebAuthn credential/challenge), including the active seat key material
-- and Guardian fields. After it, only idax_governed_verifier - a separate login role, not a member
-- of or inherited by any ordinary runtime role - may write those tables. The verifier process is the
-- only component that holds that credential; it authenticates each request with idax-core's JWT
-- verification and checks authority against idax_core.tenant_user before writing.
--
-- Ordinary runtime roles keep SELECT (reads are unchanged). RLS is not weakened: the verifier gets
-- the same tenant-scoped policy shape the ordinary roles already have, nothing broader.

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'idax_governed_verifier') THEN
    CREATE ROLE idax_governed_verifier LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS NOREPLICATION;
  END IF;
END $$;

CREATE TEMP TABLE _governed_tables (name text) ON COMMIT DROP;
INSERT INTO _governed_tables VALUES
  ('market_constitution'), ('constitutional_authority'), ('constitutional_seat'),
  ('constitutional_credential_history'), ('constitutional_proposal'), ('constitutional_signature'),
  ('market_governance_event'), ('constitutional_webauthn_credential'), ('constitutional_webauthn_challenge');

-- 1. Ordinary runtime roles lose every write, table-level and column-level.
DO $$
DECLARE t record; c record;
BEGIN
  FOR t IN SELECT name FROM _governed_tables LOOP
    EXECUTE format('REVOKE INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON stir.%I FROM idax_app, idax_backend', t.name);
    FOR c IN SELECT column_name FROM information_schema.columns WHERE table_schema = 'stir' AND table_name = t.name LOOP
      EXECUTE format('REVOKE INSERT (%I), UPDATE (%I), REFERENCES (%I) ON stir.%I FROM idax_app, idax_backend',
                     c.column_name, c.column_name, c.column_name, t.name);
    END LOOP;
  END LOOP;
END $$;

-- 2. The verifier role: write access only on the governed constitutional tables it operates.
DO $$
DECLARE t record;
BEGIN
  FOR t IN SELECT name FROM _governed_tables LOOP
    EXECUTE format('GRANT SELECT, INSERT, UPDATE ON stir.%I TO idax_governed_verifier', t.name);
    EXECUTE format('CREATE POLICY %I ON stir.%I FOR ALL TO idax_governed_verifier
                    USING (tenant_id = NULLIF(current_setting(''app.tenant_id'', true), '''')::uuid)
                    WITH CHECK (tenant_id = NULLIF(current_setting(''app.tenant_id'', true), '''')::uuid)',
                   t.name || '_governed_verifier', t.name);
  END LOOP;
END $$;

-- 3. Read-only context the verifier needs to check community authority (V20 changes no ordinary
--    authority rule; these are the same tables the ordinary check already reads).
GRANT SELECT ON stir.marketplace_economic_binding TO idax_governed_verifier;
GRANT SELECT ON stir.community_governance_settings TO idax_governed_verifier;
GRANT SELECT ON stir.community_governance_member TO idax_governed_verifier;
CREATE POLICY marketplace_economic_binding_governed_verifier ON stir.marketplace_economic_binding
  FOR SELECT TO idax_governed_verifier
  USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY community_governance_settings_governed_verifier ON stir.community_governance_settings
  FOR SELECT TO idax_governed_verifier
  USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY community_governance_member_governed_verifier ON stir.community_governance_member
  FOR SELECT TO idax_governed_verifier
  USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid);

-- 4. Membership lookup (idax_core owns the authoritative tables; the verifier only reads them).
DO $$
BEGIN
  IF to_regclass('idax_core.tenant_user') IS NOT NULL THEN
    GRANT USAGE ON SCHEMA idax_core TO idax_governed_verifier;
    GRANT SELECT ON idax_core.tenant_user, idax_core.app_user, idax_core.tenant TO idax_governed_verifier;
    IF EXISTS (SELECT 1 FROM pg_class WHERE oid = 'idax_core.tenant_user'::regclass AND relrowsecurity) THEN
      CREATE POLICY p_tenant_user_governed_verifier ON idax_core.tenant_user
        FOR SELECT TO idax_governed_verifier
        USING (idax_core.tenant_match(tenant_id));
    END IF;
  END IF;
END $$;

-- 5. Tenant context functions, named individually (never EXECUTE on the whole schema, so no
--    SECURITY DEFINER helper outside this list becomes reachable from the verifier).
DO $$
DECLARE f text;
BEGIN
  FOREACH f IN ARRAY ARRAY['set_tenant(uuid)', 'current_tenant_id()', 'tenant_match(uuid)'] LOOP
    IF to_regprocedure('idax_core.' || f) IS NOT NULL THEN
      EXECUTE format('GRANT EXECUTE ON FUNCTION idax_core.%s TO idax_governed_verifier', f);
    END IF;
  END LOOP;
END $$;
