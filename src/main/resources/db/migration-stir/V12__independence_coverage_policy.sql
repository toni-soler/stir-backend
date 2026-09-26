-- Refresh coverage integrity: a reference policy MAY require a minimum share of its evidence to
-- have real independence assurance behind it. Nullable and defaulting to no requirement, so every
-- existing policy row is completely unaffected (EvidenceAnalysis.java's own gating is likewise a
-- no-op when this is null - see PARTICIPANT_INDEPENDENCE.md/ORDINARY_GOVERNANCE.md).
ALTER TABLE stir.reference_policy ADD COLUMN minimum_independence_coverage_percent int
 CHECK(minimum_independence_coverage_percent IS NULL OR minimum_independence_coverage_percent BETWEEN 0 AND 100);
