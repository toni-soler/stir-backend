# Changelog

## 0.2.0-SNAPSHOT

Marketplace MVP: ParticipantProfile (tenant-scoped presence, distinct from IDAX login); Offer/Negotiation/Agreement/AgreementSnapshot on top of Listing. Negotiation is an append-only, alternating-author Offer thread (accept/counter/decline all version-checked, 404 for non-parties, 409 for stale/closed/superseded); accepting freezes one Agreement + one immutable canonical AgreementSnapshot (own compact sorted-key JSON canonicalizer + SHA-256, not a general JCS library) with `economicPhase=AWAITING_ECONOMIC_EXECUTION` - no osTRIS call happens yet. Listing read paths now include the owner's ParticipantProfile.displayName; write paths are unchanged. Same tenant-only forced-RLS + explicit server-side party/ownership pattern as 0.1's Listing on every new table (`V2__marketplace.sql`). New permissions: `stir.participants.{read,update}`, `stir.negotiations.{read,create,update}`, `stir.agreements.read`.

## 0.1.0-SNAPSHOT

Initial public marketplace foundation; development preview.
