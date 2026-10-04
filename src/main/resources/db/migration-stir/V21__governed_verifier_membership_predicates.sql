-- V21: the governed verifier evaluates idax-core's tenant-membership predicates inside its own RLS
-- policies (the same predicates the ordinary runtime uses on these tables). Both functions are read-only
-- (STABLE). is_current_user_member_of_tenant is SECURITY DEFINER, which is how it reads membership without
-- granting the verifier any table privilege beyond V20's. Granted by name, never as a schema wildcard.
DO $$
BEGIN
  IF to_regprocedure('idax_core.is_current_user_member_of_tenant(uuid)') IS NOT NULL THEN
    EXECUTE 'GRANT EXECUTE ON FUNCTION idax_core.is_current_user_member_of_tenant(uuid) TO idax_governed_verifier';
  END IF;
  IF to_regprocedure('idax_core.current_app_user_id()') IS NOT NULL THEN
    EXECUTE 'GRANT EXECUTE ON FUNCTION idax_core.current_app_user_id() TO idax_governed_verifier';
  END IF;
END $$;
