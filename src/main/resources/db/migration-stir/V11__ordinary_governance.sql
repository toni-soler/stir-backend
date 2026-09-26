-- Ordinary community governance: a real, quorum-based, non-constitutional decision process,
-- explicitly separate from Seven Keys. Opt-in per community (community_governance_settings) so
-- every existing publisher-delegated flow keeps working unchanged unless a community explicitly
-- turns this on. See PARTICIPANT_INDEPENDENCE.md's sibling doc, ORDINARY_GOVERNANCE.md, for the
-- full design and why platform SuperAdmin/Guardian/Seven-Keys seats do not vote by virtue of that
-- role alone (ReferenceService.requireCommunityAuthority already refuses SuperAdmin; membership
-- here is a wholly separate, explicit STIR-owned electorate, never derived from IDAX permissions).

CREATE TABLE stir.community_governance_settings (
 tenant_id uuid NOT NULL, community_id uuid NOT NULL,
 ordinary_governance_enabled boolean NOT NULL DEFAULT false,
 updated_at timestamptz NOT NULL, updated_by uuid NOT NULL,
 PRIMARY KEY(tenant_id,community_id)
);

-- Current-state electorate roll. Not append-only by design (a membership list is a live roster,
-- not evidence history) - but every vote/proposal freezes an explicit copy of this roll at that
-- instant (ordinary_proposal_electorate below), so later additions/removals never rewrite a past
-- vote's eligibility.
CREATE TABLE stir.community_governance_member (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, community_id uuid NOT NULL, user_id uuid NOT NULL,
 added_at timestamptz NOT NULL, added_by uuid NOT NULL,
 removed_at timestamptz, removed_by uuid,
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,community_id,user_id)
);

-- Versioned, immutable, community-set voting rules (v0.1: one policy shape, not a DSL). Numeric
-- floors below are STIR's own documented sanity bounds for what "quorum"/"majority" can even mean
-- - not a constitutional floor (Seven Keys is untouched by this table entirely) - so the community
-- can tune them within a sane range without needing a 7-of-7 amendment for its own voting rules.
CREATE TABLE stir.ordinary_governance_policy (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, community_id uuid NOT NULL, version int NOT NULL CHECK(version > 0),
 quorum_numerator int NOT NULL CHECK(quorum_numerator > 0), quorum_denominator int NOT NULL CHECK(quorum_denominator > 0 AND quorum_numerator <= quorum_denominator),
 approval_numerator int NOT NULL CHECK(approval_numerator > 0), approval_denominator int NOT NULL CHECK(approval_denominator > 0 AND approval_numerator <= approval_denominator),
 -- approval fraction must still mean "majority" (>50%), otherwise "quorum" alone could approve
 -- anything - this is the one numeric floor enforced here, deliberately not configurable to <=50%.
 CHECK(approval_numerator * 2 > approval_denominator),
 voting_window_hours int NOT NULL CHECK(voting_window_hours BETWEEN 1 AND 2160),
 abstention_rule varchar(40) NOT NULL CHECK(abstention_rule IN ('COUNTS_TOWARD_QUORUM_NOT_APPROVAL','DOES_NOT_COUNT_TOWARD_QUORUM')),
 explanation varchar(2000) NOT NULL, canonical_json text NOT NULL, digest_sha256 varchar(64) NOT NULL,
 created_by uuid NOT NULL, created_at timestamptz NOT NULL,
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,community_id,version)
);

CREATE TABLE stir.ordinary_proposal (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, community_id uuid NOT NULL,
 proposal_type varchar(30) NOT NULL CHECK(proposal_type IN ('PUBLISH_REFERENCE','REFERENCE_POLICY_CHANGE')),
 definition_id uuid NOT NULL,
 -- PUBLISH_REFERENCE: the already-immutable reference_proposal being voted on (its own content is
 -- frozen the instant it was created - a changed field there is necessarily a different id, so
 -- "a new proposal is required if content changes" holds for free). REFERENCE_POLICY_CHANGE has no
 -- pre-existing frozen draft to point at, so payload_json/payload_digest freeze it here instead.
 target_reference_proposal_id uuid,
 payload_json text NOT NULL, payload_digest varchar(64) NOT NULL,
 -- Digest of the definition/policy/snapshot state this proposal was created against - re-checked
 -- byte-for-byte at activation (SevenKeysService.activate()'s "Constitution changed; proposal is
 -- stale" pattern, applied here to ordinary governance instead).
 reference_state_digest varchar(64) NOT NULL,
 governance_policy_id uuid NOT NULL, governance_policy_version int NOT NULL,
 electorate_digest varchar(64) NOT NULL,
 voting_opens_at timestamptz NOT NULL, voting_closes_at timestamptz NOT NULL CHECK(voting_closes_at > voting_opens_at),
 -- Staleness (the underlying reference_policy moved on since this vote opened) is a separately
 -- computed fact, never its own persisted status transition - see OrdinaryGovernanceService.isStale().
 status varchar(20) NOT NULL DEFAULT 'OPEN' CHECK(status IN ('OPEN','APPROVED','REJECTED','EXPIRED','EXECUTED')),
 closed_at timestamptz, executed_at timestamptz,
 created_by uuid NOT NULL, created_at timestamptz NOT NULL,
 UNIQUE(tenant_id,id),
 FOREIGN KEY(tenant_id,definition_id) REFERENCES stir.reference_definition(tenant_id,id),
 FOREIGN KEY(tenant_id,target_reference_proposal_id) REFERENCES stir.reference_proposal(tenant_id,id),
 FOREIGN KEY(tenant_id,governance_policy_id) REFERENCES stir.ordinary_governance_policy(tenant_id,id)
);

-- The frozen electorate roll itself - immutable, inserted once at proposal creation. A user removed
-- from community_governance_member after this row exists can still vote (they were eligible when
-- voting opened); a user added afterward is simply not in this table and cannot.
CREATE TABLE stir.ordinary_proposal_electorate (
 tenant_id uuid NOT NULL, proposal_id uuid NOT NULL, user_id uuid NOT NULL,
 PRIMARY KEY(tenant_id,proposal_id,user_id),
 FOREIGN KEY(tenant_id,proposal_id) REFERENCES stir.ordinary_proposal(tenant_id,id)
);

CREATE TABLE stir.ordinary_vote (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, proposal_id uuid NOT NULL, voter_id uuid NOT NULL,
 choice varchar(10) NOT NULL CHECK(choice IN ('APPROVE','REJECT','ABSTAIN')), cast_at timestamptz NOT NULL,
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,proposal_id,voter_id),
 FOREIGN KEY(tenant_id,proposal_id) REFERENCES stir.ordinary_proposal(tenant_id,id)
);

CREATE TABLE stir.ordinary_proposal_execution (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, proposal_id uuid NOT NULL,
 executed_by uuid NOT NULL, executed_at timestamptz NOT NULL, result_digest varchar(64) NOT NULL,
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,proposal_id),
 FOREIGN KEY(tenant_id,proposal_id) REFERENCES stir.ordinary_proposal(tenant_id,id)
);

DO $$ DECLARE tab text; BEGIN
 FOREACH tab IN ARRAY ARRAY['community_governance_settings','community_governance_member','ordinary_governance_policy',
 'ordinary_proposal','ordinary_proposal_electorate','ordinary_vote','ordinary_proposal_execution'] LOOP
  EXECUTE format('ALTER TABLE stir.%I ENABLE ROW LEVEL SECURITY',tab);
  EXECUTE format('ALTER TABLE stir.%I FORCE ROW LEVEL SECURITY',tab);
  EXECUTE format('CREATE POLICY %I ON stir.%I FOR ALL TO idax_app,idax_admin USING (tenant_id = NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid) WITH CHECK (tenant_id = NULLIF(current_setting(''app.tenant_id'',true),'''')::uuid)',tab||'_tenant',tab);
 END LOOP;
 -- Fully immutable (append-only): a proposal's frozen electorate, its votes, and its execution
 -- record can never be edited or deleted, reusing the exact same guarantee V5 already gives
 -- reference_proposal/community_reference.
 FOREACH tab IN ARRAY ARRAY['ordinary_governance_policy','ordinary_proposal_electorate','ordinary_vote','ordinary_proposal_execution'] LOOP
  EXECUTE format('GRANT SELECT,INSERT ON stir.%I TO idax_app,idax_admin',tab);
  EXECUTE format('CREATE TRIGGER immutable_history BEFORE UPDATE OR DELETE ON stir.%I FOR EACH ROW EXECUTE FUNCTION stir.reject_reference_mutation()',tab);
 END LOOP;
END $$;
-- Current-state rosters/settings: ordinary INSERT+UPDATE, no append-only trigger.
GRANT SELECT,INSERT,UPDATE ON stir.community_governance_settings TO idax_app,idax_admin;
GRANT SELECT,INSERT,UPDATE ON stir.community_governance_member TO idax_app,idax_admin;
-- ordinary_proposal: everything is frozen at creation except its own lifecycle state, mirroring
-- constitutional_authority/constitutional_seat's narrow-column-grant pattern from V6 rather than a
-- blanket immutable trigger.
GRANT SELECT,INSERT ON stir.ordinary_proposal TO idax_app,idax_admin;
GRANT UPDATE(status,closed_at,executed_at) ON stir.ordinary_proposal TO idax_app,idax_admin;
