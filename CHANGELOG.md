# Changelog

## Agreement cases

Reviewing an unfulfilled obligation without judging the person (`stir-doc/AGREEMENT_CASES.md`). V24 adds a STIR-owned reviewer roster set by community authority (registered in osTRIS as the community resolution authority), cases opened by the party an obligation is owed to, append-only evidence with an optional reviewers-only visibility, an append-only timeline, and three separate decision types - finding about the effect, attribution of conduct, sanction (a warning only) - each with its own appeal window and an appeal decided by a reviewer other than its author. Containment pauses an Agreement's execution and amendments, never a person. A final finding can order a full reversal of the community-unit exchange: an adjudicated osTRIS `REVERSAL` on a `FINAL_DISPUTE_RESOLUTION` basis, signed by the reviewers' own keys. Nothing is automatic. `GET /obligations` adds `cases`, `corrections` and `containment`; `GET /api/stir/instance` reports `agreementCasesVersion: 1`; notifications `CASE_OPENED` and `CASE_UPDATED`. Requires the osTRIS resolution surface (`claude/dispute-resolution-contract`).

## Agreement fulfilment

Per-obligation fulfilment for every Agreement (`stir-doc/AGREEMENT_FULFILMENT.md`). V23 adds obligations derived from the snapshot (never rewriting it), typed append-only declarations by each party, amendments that name what they replace and chain to the snapshot, and a persisted, classified record of every economic execution attempt (`COMMITTED`, `EXPOSURE_REFUSAL`, `AUTHORISATION_INCOMPLETE`, `INVALID_DESCRIPTION`, `IMPLEMENTATION_FAULT`, `UNKNOWN`). `/economic/me` adds `availableCapacity`; profiles add an opt-in, score-free fulfilment history. New permission `stir.agreements.fulfil`; `GET /api/stir/instance` reports `agreementFulfilmentVersion: 1`. The four new tables are RLS-scoped and covered by the governed-state audit trigger.

## Upstream 0.4 baseline

The backend now compiles against the public `es.idynamicsax.idax:idax-core:0.4.0` artifact from the public IDAX Maven repository. No STIR domain or HTTP contract changed as part of this dependency alignment.

## 0.3.0-SNAPSHOT

Economic Exchange: `MarketplaceEconomicBinding`/`ParticipantEconomicBinding` (`V3__economic_exchange.sql`, same forced-RLS pattern) make tenant<->osTRIS-community/unit and user<->osTRIS-participant/account bindings explicit, never inferred. `EconomicActivationService`/`EconomicController` bootstrap the marketplace's one economic community/unit and activate a participant with a client-generated Ed25519 public key. `Trade`/`TradeService`/`TradeController` carry one osTRIS EXCHANGE per Agreement through activate -> sign (relayed, never STIR-produced) -> commit -> COMMITTED/REJECTED, with `sync()` reconciliation and idempotent activate/commit; a REJECTED write is recorded through a separate `@Transactional(REQUIRES_NEW)` bean (`TradeRejectionRecorder`) so it survives the failing commit's own rollback. `OstrisClient` is the sole caller of osTRIS's HTTP API, relaying the caller's own bearer token. `Agreement.payerUserId`/`payeeUserId` are frozen at accept time from `Listing.direction`; `economicPhase` gains AWAITING_SIGNATURES/COMMITTING/COMMITTED/REJECTED alongside the existing NOT_APPLICABLE/AWAITING_ECONOMIC_EXECUTION. `AgreementSnapshot` canonicalization moved to real RFC 8785 JCS (`io.github.erdtman:java-json-canonicalization`, format `STIR-AGREEMENT-JCS-1`, schemaVersion 2). New permissions: `stir.economic.{read,manage}`.

## 0.2.0-SNAPSHOT

Marketplace MVP: ParticipantProfile (tenant-scoped presence, distinct from IDAX login); Offer/Negotiation/Agreement/AgreementSnapshot on top of Listing. Negotiation is an append-only, alternating-author Offer thread (accept/counter/decline all version-checked, 404 for non-parties, 409 for stale/closed/superseded); accepting freezes one Agreement + one immutable canonical AgreementSnapshot (own compact sorted-key JSON canonicalizer + SHA-256, not a general JCS library) with `economicPhase=AWAITING_ECONOMIC_EXECUTION` - no osTRIS call happens yet. Listing read paths now include the owner's ParticipantProfile.displayName; write paths are unchanged. Same tenant-only forced-RLS + explicit server-side party/ownership pattern as 0.1's Listing on every new table (`V2__marketplace.sql`). New permissions: `stir.participants.{read,update}`, `stir.negotiations.{read,create,update}`, `stir.agreements.read`.

## 0.1.0-SNAPSHOT

Initial public marketplace foundation; development preview.
