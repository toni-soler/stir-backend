-- osTRIS stays the sole authority for identity/continuity. This table is a minimal, deterministic
-- STIR-side PROJECTION of osTRIS's private continuity decisions, versioned by osTRIS's own
-- community_sequence so a historical reference snapshot stays reconstructible. It is not history
-- and not a second identity database: a publisher can only trigger a refresh (never author the
-- result), the row is overwritten idempotently with whatever osTRIS's own protocol response says,
-- and no raw osTRIS risk_subject_id is stored - only a one-way digest (cluster_ref), just enough to
-- recognize that two participants share a cluster without STIR holding osTRIS's own correlation key.
CREATE TABLE stir.participant_independence_projection (
 tenant_id uuid NOT NULL, user_id uuid NOT NULL, community_id uuid NOT NULL,
 status varchar(32) NOT NULL CHECK(status IN ('NO_OSTRIS_BINDING','INDEPENDENCE_UNKNOWN','IDENTITY_CONTINUITY_PENDING','RELATED_CONTINUITY')),
 cluster_ref varchar(64), community_sequence bigint,
 fetched_at timestamptz NOT NULL, fetched_by uuid NOT NULL,
 PRIMARY KEY(tenant_id,user_id),
 CHECK((status='RELATED_CONTINUITY') = (cluster_ref IS NOT NULL))
);
ALTER TABLE stir.participant_independence_projection ENABLE ROW LEVEL SECURITY;
ALTER TABLE stir.participant_independence_projection FORCE ROW LEVEL SECURITY;
CREATE POLICY participant_independence_projection_tenant ON stir.participant_independence_projection FOR ALL TO idax_app,idax_admin
 USING (tenant_id = NULLIF(current_setting('app.tenant_id',true),'')::uuid) WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id',true),'')::uuid);
-- Not append-only by design: a projection is a current-state cache of osTRIS's latest answer, not
-- an evidence/governance history record, so UPDATE (idempotent upsert on refresh) is legitimate here.
GRANT SELECT,INSERT,UPDATE ON stir.participant_independence_projection TO idax_app,idax_admin;
