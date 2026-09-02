# Portfolio/provider-source architecture adversarial review

- Review date: 2026-09-02
- Scope: `ARCHITECTURE-SPINE.md`, `PORTFOLIO-SOLUTION-DESIGN.md`, and the corresponding Epic 2 / deletion stories in `epics.md`
- Method: construct independently reasonable downstream implementation units that satisfy the stated ADs, then test whether they can interoperate without inventing missing contracts
- Verdict: **FAIL — the direction is sound, but the portfolio boundary is not yet deterministic enough for independent implementation**

## Executive verdict

The core decision is coherent: a plan belongs to an immutable-base-currency portfolio, provider connections enter through a provider-neutral source boundary, and the MVP exposes one USD portfolio containing one Bybit source. The artifacts also correctly defer multi-source aggregation and transfer matching instead of pretending those policies already exist.

However, the documents still permit incompatible implementations of snapshot composition, official publication, time semantics, source identity, membership intervals, and deletion. Most importantly, the epic-level architecture requirement and deletion orchestration still use the pre-change `exchange` boundary. If implemented literally, portfolio-owned rows may be omitted from account deletion. That is not a documentation nicety; it is a user-data lifecycle defect.

## Two compliant but incompatible downstream units

### Unit A — sync-led relational publication

An implementation team can reasonably build the following while citing AD-1, AD-6, AD-9, AD-16, and AD-22:

- `source_snapshot` is owned by `sync` and keyed by an internal provider connection UUID.
- `portfolio_snapshot` is owned by `portfolio`; its components live in a relational `portfolio_snapshot_component(portfolio_snapshot_id, source_snapshot_id)` table because AD-9 says the snapshot contains source snapshot IDs.
- The portfolio snapshot `measured_at` equals the contributing Bybit source snapshot's provider measurement time.
- `sync` owns one `official_result_pointer` keyed by plan or portfolio. Inside one `REQUIRED` transaction it calls the portfolio public API to create the candidate snapshot, writes the calculation, and CAS-updates this pointer.
- Provider event identity is `(provider, provider_account_id, category, stable_source_id, revision)`, so rotating credentials or replacing a connection to the same Bybit UID does not duplicate history.
- Membership is a half-open interval `[included_at, excluded_at)` evaluated against source effective time.
- Account deletion explicitly invokes both `source.deleteUserData` and `portfolio.deleteUserData`.

### Unit B — connection-led embedded publication

A second team can also cite the same ADs and build this:

- `source_snapshot` is keyed by `provider_connection_id`; a replacement connection is treated as a new account namespace because the solution-design uniqueness rule explicitly uses `connection_id`.
- `portfolio_snapshot` stores a JSON array of constituent source snapshot IDs. This is immutable, contains the required IDs, and stays inside the portfolio-owned table.
- Its `measured_at` is the time the portfolio composition was created; the contributing source's provider measurement time remains only on `source_snapshot`.
- The official pointer is connection-scoped because AD-16 says publication advances only when a source's `sync_sequence` increases. The latest portfolio result is found through the calculation reached from that connection-scoped pointer.
- Membership is active when `excluded_at IS NULL`; snapshots already fetched before exclusion may still publish if the connection passed validation when the job began, because no interval containment rule is stated.
- Account deletion implements the epic's literal `identity·exchange·sync·plan·operations` port list. It deletes connection and sync data, assuming database relationships or the portfolio module will deal with portfolio rows later.

Both units preserve immutable facts, use provider namespaces, keep the MVP to one Bybit source, use public module APIs, and publish through short transactions. Yet they disagree on component storage, snapshot time, pointer ownership and key, provider-account continuity, exclusion races, and deletion coverage. They cannot be integrated without schema migration or retroactively selecting one team's semantics.

## Findings requiring tighter ADs

### P0-1 — Epic module and deletion contracts still name `exchange` and omit `portfolio`

Evidence:

- Architecture AD-1 and the solution design now define `source` and `portfolio` as separate owning modules.
- `epics.md` AR-2 still lists `identity`, `exchange`, `sync`, `plan`, `calculation`, `engagement`, `operations`; it neither names `source` nor `portfolio`.
- Story 5.2 AC-1 still requires deletion from `identity·exchange·sync·plan·operations`, again omitting `portfolio`.
- Story 3.2 still exposes the old `exchange_connection` restriction scope while AD-17 uses `provider_connection` and `portfolio`.

Impact: an implementation can pass the literal story criteria while leaving `portfolio`, `portfolio_source_membership`, and `portfolio_snapshot` rows behind after account deletion. It can also build the wrong Modulith packages and operational restriction target vocabulary.

Required tightening:

1. Replace `exchange` with `source` everywhere the internal module boundary is meant, while retaining “Bybit” only as product/provider terminology.
2. Add `portfolio` to AR-2 and every module deletion inventory.
3. Make Story 5.2 explicitly require `identity`, `source`, `portfolio`, `sync`, `plan`, `engagement`, and user-linked `operations` deletion ports, with row-count and cross-user preservation tests.
4. Replace all internal `exchange_connection` restriction targets with `provider_connection`; include portfolio-scoped restriction tests.

### P1-2 — Portfolio snapshot composition has no canonical component relation or completeness invariant

Evidence:

- AD-9 says a portfolio snapshot contains verified component source snapshot IDs and a sum.
- The ER diagram draws a direct `SOURCE_SNAPSHOT` to `PORTFOLIO_SNAPSHOT` relation, but it does not model a component entity, component amount, ordering, or unique membership.
- The MVP stories test exactly one component, so both a scalar foreign key and an embedded array would pass.

Impact: a scalar FK, JSON array, and normalized component table are all compliant now but impose incompatible migration and query contracts when the explicitly anticipated second source arrives. A stored total also cannot be independently checked against immutable component values without a canonical component contract.

Required tightening: define `portfolio_snapshot_component` now, even though MVP cardinality is one. Require `(portfolio_snapshot_id, membership_id)` uniqueness, a non-null `source_snapshot_id`, component value in portfolio base currency, valuation/price-evidence reference where applicable, and a deterministic invariant that the snapshot total equals the exact sum of its complete component set under the recorded calculation policy. No mutable or unversioned JSON-only component list should be the authority.

### P1-3 — Official pointer ownership, scope, and CAS ordering are inconsistent with portfolio ownership

Evidence:

- The solution-design module table says `sync` owns “official pointer”.
- AD-16 says `portfolio` owns portfolio snapshots but publication CAS advances on a **source-level** `sync_sequence`.
- Story 2.2 speaks of an “official portfolio snapshot pointer”; Story 2.7 and Story 2.11 speak of one official pointer spanning source snapshot, portfolio snapshot, and calculation.
- No artifact states whether the pointer is keyed by connection, source, portfolio, plan, or plan revision, nor which module owns its table and transition API.

Impact: connection-scoped and portfolio/plan-scoped pointers can both pass the current wording. They order concurrent work differently and cannot support the same dashboard read contract. Source-local sequences also cannot totally order future portfolio publications triggered by different sources.

Required tightening: name the aggregate and owner. A safe contract is a sync-owned `official_plan_result` keyed by active plan (and carrying portfolio ID), referencing one immutable portfolio snapshot and one calculation version. Give it a monotonically increasing **portfolio publication sequence** or compare-and-set expected pointer version; retain source sync sequences only as provenance/fencing inputs. Require the portfolio snapshot creation and pointer swap to commit in the same outer transaction through public module APIs, and state that no module derives “current” using `max(created_at)`.

### P1-4 — `provider/account` and `provider/connection_id` define different event identities

Evidence:

- AD-16 defines source facts using `provider/account/category/stable source ID`.
- The solution design and AR-22/epics use a provider namespace but the database uniqueness rule is `(provider, connection_id, category, stable_source_id, revision)`.
- AD-21 verifies Bybit `userID`, yet no rule says whether that verified provider account ID is the stable `account` in AD-16 or only connection metadata.

Impact: after credential rotation, reconnect, or a future provider adapter replacement, the same provider event may be deduplicated across connections or duplicated once per connection. Both interpretations are currently defensible. Duplicating `TRANSFER_IN`/`TRANSFER_OUT` facts can corrupt plan adjustments.

Required tightening: introduce a stable, provider-namespaced `provider_account` (for Bybit, verified main UID) distinct from replaceable credentials and connection revisions. Bind facts and source snapshots to `provider_account_id`; record `provider_connection_id` only as collection provenance. Specify the unique key and reconnection policy explicitly. If MVP deliberately treats reconnection as a new source, state that and block history overlap until a reconciliation story exists.

### P1-5 — Membership interval semantics do not decide disconnect/publication races

Evidence:

- AD-22 requires inclusion start/end timestamps.
- Story 2.16 ends membership at the disconnection time and blocks late Worker publication.
- No rule defines half-open versus closed intervals, the authoritative timestamp used for eligibility, whether overlaps are excluded at DB level, or whether a snapshot must be inside the membership at measurement, fetch, job admission, and publication time.

Impact: a snapshot measured before disconnection but published after it can be accepted or rejected by equally plausible implementations. Future source replacement can also create overlapping active intervals at exact boundary timestamps.

Required tightening: define membership as `[included_at, excluded_at)` in UTC and enforce non-overlap for the same portfolio/source identity with an exclusion constraint or serialized command. For MVP publication, require membership to be active both at job admission and at the final publication check, and require the source snapshot's authoritative measurement time to fall within the membership interval. State the intended rule explicitly if pre-disconnect measurements must instead be publishable after disconnect (the current stories imply they must not).

### P1-6 — Portfolio `measured_at` and future cutoff semantics are underdefined

Evidence:

- Story 2.2 requires the provider-neutral portfolio snapshot contract to expose a measurement time.
- Story 2.3 fixes `t0` from that time.
- The architecture does not say whether it is provider measurement time, portfolio composition time, minimum/maximum component measurement time, or a chosen valuation cutoff.
- Multi-source cutoff/freshness composition is deferred, but the current schema must not silently assign a meaning that becomes incompatible later.

Impact: even with one Bybit source, one team can use provider measurement time and another can use publication/composition time, producing different `t0`, elapsed periods, expiry behavior, and audit replay.

Required tightening: for MVP, define `portfolio_snapshot.valued_at` (or `measured_at`) as exactly the sole component source snapshot's authoritative provider measurement time, and separately store `composed_at`/`observed_at`. Reserve multi-source cutoff semantics for a later policy version; do not overload publication time as valuation time.

### P1-7 — Cross-module transaction ownership needs an executable contract

Evidence:

- AD-1 forbids direct access to another module's tables.
- AD-16 assigns candidate rows across `sync` and `portfolio`, while the solution design requires one Spring `REQUIRED` publication transaction and says internal calls must not start another transaction.
- The owner of orchestration and rollback behavior when a portfolio public API succeeds but calculation/pointer insertion fails is implied, not specified as a port contract.

Impact: teams may annotate the portfolio API `REQUIRES_NEW`, use after-commit events, or return an unsaved object to sync. Each changes whether failed publication leaves an orphan portfolio snapshot, whether that is acceptable, and which tests should pass.

Required tightening: state that the sync publication application service owns the outer `REQUIRED` transaction; portfolio's `createCandidateSnapshot` command joins it with mandatory propagation and returns an immutable ID; no external call, asynchronous event, `REQUIRES_NEW`, or after-commit publication is allowed in this path. Decide whether unreferenced immutable candidates are valid audit facts or must roll back, then make Story 2.2/2.7/2.11 test that exact rule.

### P2-8 — Updated solution design has stale provenance metadata and residual pre-change vocabulary

Evidence:

- `PORTFOLIO-SOLUTION-DESIGN.md` contains the new portfolio model but its frontmatter still says `updated: 2026-08-26`.
- Its system context shows only direct Web-to-Bybit integration, which does not visualize the newly authoritative financial source port.
- Several epic passages still say `exchange` even where the architecture now says `source`.

Impact: reviewers cannot reliably tell which artifact version contains the accepted decision, and generated implementation work may follow stale module names.

Required tightening: update provenance dates, render the provider/source port in the context diagram, and run a vocabulary check for internal `exchange`, `exchange_connection`, and direct plan-to-connection references.

## Required acceptance probes before PASS

1. **Modulith ownership probe:** a test or architecture assertion shows `source`, `portfolio`, `sync`, and `plan` as separate modules; no old `exchange` package is accepted as the owner of new tables.
2. **Snapshot schema probe:** two source snapshots can be attached to one synthetic portfolio snapshot through the chosen component contract in a schema-level test, even though production admission still rejects two active MVP sources.
3. **Publication probe:** inject failure after portfolio candidate creation and before pointer CAS; verify the documented rollback/orphan rule and that readers never observe a partial official result.
4. **Timestamp probe:** provider measured time differs from fetch, composition, and publication time; verify `t0` uses only the documented valuation timestamp.
5. **Identity probe:** reconnect the same Bybit main UID with a replacement credential/connection and replay one stable event ID; verify the explicitly chosen deduplication behavior.
6. **Membership race probe:** disconnect during an in-flight sync; verify interval closure, final eligibility check, no late pointer publication, and no overlap with a replacement membership.
7. **Deletion probe:** seed every `source`, `portfolio`, `sync`, and `plan` table for two users; delete one and prove all intended user-owned rows are removed while the other user is unchanged.
8. **Restriction vocabulary probe:** provider-connection and portfolio restrictions apply to admission, lease, publication, read, and display using the same target types across architecture and stories.

## Final recommendation

Do not revert the portfolio direction. Tighten it. AD-22 is the right extension boundary, but it needs companion rules for stable provider-account identity, membership interval semantics, normalized snapshot components, valuation timestamps, and a portfolio/plan-scoped official publication contract. The P0 deletion/module vocabulary mismatch must be corrected before implementation stories are considered ready.

## Revalidation — 2026-09-02

- Revalidation verdict: **PASS — all prior P0/P1 blockers are closed in the architecture, solution design, and implementation stories.**
- The original FAIL verdict above is retained as review history and is superseded by this section.

Closure evidence:

1. **Module/deletion vocabulary — closed.** AR-2 now defines `source` and `portfolio` Modulith modules; Story 3.2 uses `provider_connection` and `portfolio` restriction scopes; Story 5.2 explicitly calls deletion ports for `identity`, `source`, `portfolio`, `sync`, `plan`, `engagement`, and user-linked `operations`, enumerates provider account through portfolio component/pointer data, and requires two-user isolation tests.
2. **Normalized snapshot components — closed.** AD-23 and the solution design require authoritative `portfolio_snapshot_component` rows, `(portfolio_snapshot_id, membership_id)` uniqueness, source snapshot and valuation evidence, exact total/component equality, and a schema capable of multiple synthetic components while MVP publication still enforces exactly one.
3. **Pointer ownership and CAS — closed.** `portfolio` owns its monotonic `publication_sequence` and current snapshot pointer; `sync` owns the plan-keyed `official_plan_result` pointer. Source `sync_sequence` is restricted to collection provenance/fencing, current state cannot be inferred with `max(created_at)`, and the stories test both CAS dimensions.
4. **Stable provider-account identity — closed.** AD-16/AD-21, AR-12, the solution design, and Story 2.1 distinguish the stable `(provider, verified_external_account_id)` account from replaceable connections. Facts use `(provider_account_id, category, stable_source_id, revision)` and connection ID is collection provenance only; reconnect replay is explicitly tested.
5. **Membership race semantics — closed.** Membership is the UTC half-open interval `[included_at, excluded_at)`, overlapping intervals are forbidden, the source measurement must lie within the interval, and membership must remain active both at admission and immediately before publication. Stories 2.2 and 2.11 reject disconnect-after-admission late publication.
6. **Valuation timestamp — closed.** MVP `portfolio_snapshot.measured_at` is exactly the sole Bybit source's provider measurement time and is distinct from `composed_at` and `published_at`; Story 2.2 requires a divergent-time test and Story 2.3 derives `t0` from that documented timestamp.
7. **Outer publication transaction — closed.** AD-23 assigns orchestration to the sync publication application service's outer `REQUIRED` transaction; portfolio commands must join it, `REQUIRES_NEW`, asynchronous, and after-commit publication are prohibited, and every candidate/component/calculation/pointer failure rolls the entire publication back. Story 2.7 contains matching stage-failure tests.

No blocker remains from this adversarial review. The prior acceptance probes are now represented as explicit story tests or architecture constraints; passing those tests remains an implementation-readiness condition rather than a documentation defect.
