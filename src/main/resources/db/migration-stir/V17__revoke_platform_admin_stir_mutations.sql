-- Platform administration is not community authority. Historical migrations granted
-- idax_admin writes to STIR domain tables, including constitutional and FINAL findings.
-- Keep its tenant-scoped read access, but remove every direct STIR mutation privilege.
-- PostgreSQL superusers/table owners remain an explicit infrastructure trust boundary.
DO $$
DECLARE tab record;
DECLARE col record;
BEGIN
 FOR tab IN SELECT tablename FROM pg_tables WHERE schemaname = 'stir' LOOP
  EXECUTE format('REVOKE INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON stir.%I FROM idax_admin', tab.tablename);
  -- Table-level REVOKE does not remove earlier column-level UPDATE grants.
  FOR col IN SELECT column_name FROM information_schema.columns
             WHERE table_schema = 'stir' AND table_name = tab.tablename LOOP
   EXECUTE format('REVOKE INSERT (%I), UPDATE (%I), REFERENCES (%I) ON stir.%I FROM idax_admin',
                  col.column_name, col.column_name, col.column_name, tab.tablename);
  END LOOP;
 END LOOP;
END $$;
