# Portfolio/source architecture rubric review

## Gate verdict

**CHANGES REQUIRED.** The provider-neutral portfolio/source direction is sound and the MVP boundary is appropriately narrow, but two high-severity contract gaps can still lead independent implementation stories to build incompatible module/deletion boundaries or compose an ineligible source snapshot. These are clear documentation fixes, not reasons to reject the model.

## Review scope and evidence

- Primary: `ARCHITECTURE-SPINE.md` (updated 2026-09-02)
- Companion: `PORTFOLIO-SOLUTION-DESIGN.md`
- Downstream requirements: `epics.md`, especially AR-2/AR-22 and Epic 2/5
- Deterministic gate: `lint_spine.py --workspace .../architecture-BokriLog-2026-08-24` passed with 0 findings.
- Review intent: full good-spine checklist, with additional attention to portfolio/source ownership, temporal membership, publication, deletion, and future multi-source containment.

## Critical findings

None.

## High findings

### H-1 — Downstream module and deletion contracts still name the retired `exchange` boundary and omit `portfolio`

**Evidence:** The spine adopts `source` and `portfolio` as owned modules in AD-1 and its module ownership convention. However, `epics.md` AR-2 still fixes the Spring Modulith set as `identity`, `exchange`, `sync`, `plan`, `calculation`, `engagement`, `operations` (line 78). Story 5.2 AC-1 likewise deletes through `identity·exchange·sync·plan·operations...` ports and does not name either `source` or `portfolio` (line 3315). Story 2.2 additionally asks for `snapshot·sync` deletion paths even though no `snapshot` module exists (line 1276).

**Why this matters:** These are executable requirements. One implementation unit can create `source`/`portfolio` from the spine while another follows AR-2 and Story 5.2, producing a duplicate `exchange` boundary and, more seriously, an account-deletion orchestration that does not call the portfolio owner. That violates AD-1, AD-19, and the new provider-neutral ownership model.

**Action:** **Autofix.** Replace `exchange` with `source`, add `portfolio` to AR-2 and Story 5.2 deletion ports, and replace the nonexistent `snapshot` deletion owner with the actual `sync` and `portfolio` owners. Add an Epic 5 deletion test proving provider connection, membership, source snapshot, and portfolio snapshot removal without cross-user effects.

### H-2 — Portfolio snapshot constituent eligibility is not enforceable across membership time and ownership

**Evidence:** AD-22 records membership start/end timestamps and AD-9 says the portfolio combines verified source snapshots, but neither rule states the exact eligibility predicate connecting a source snapshot to a portfolio snapshot. The solution design records membership intervals and immutable constituents, yet does not require that each constituent belongs to the same user/portfolio connection, is measured within the membership interval, is the eligible version for the portfolio cutoff, and uses the identical base currency. Epic 2 covers general ownership and the one-source constraint but does not state this temporal join invariant.

**Why this matters:** Disconnect, a delayed worker, or a future reconnect can leave multiple immutable source snapshots. Without an explicit predicate, two correct-looking implementations can disagree about whether a pre-membership, post-membership, superseded, or foreign-connection snapshot may be composed. In MVP this can publish stale value after disconnect; in later multi-source work it becomes a causal-integrity defect.

**Action:** **Autofix.** Add an enforceable AD-9/AD-22 rule and matching Story 2.2/2.7 acceptance test: a portfolio snapshot may reference only source snapshots whose connection and user match an active membership at the selected cutoff, whose measurement/effective time is inside the membership interval, whose currency equals the portfolio base currency, and whose candidate version is not superseded or restricted. Publication must recheck membership immediately before CAS; otherwise it rolls back.

## Medium findings

### M-1 — Official publication ordering is source-scoped while the official aggregate is portfolio/plan-scoped

AD-16 gates the official pointer with a source-level `sync_sequence`. This is sufficient only while the hard MVP rule permits exactly one active source. The Deferred section blocks multi-source activation until cutoff/freshness, conversion, membership, scope-adjustment, and transfer-matching contracts exist, but does not explicitly require a portfolio-scoped publication sequence or aggregate revision.

**Action:** **Defer to Deferred.** State that enabling multiple active sources also requires a portfolio-scoped monotonic aggregate revision/CAS and an explicit definition of which source revisions compose it. Keep source-level sequencing for MVP.

### M-2 — Active-plan uniqueness uses both user and portfolio as its stated key

The solution design says `portfolio당 활성 계획 최대 1개`; Story 2.7 and Story 2.15 say `사용자당 활성 계획 최대 1개`. They are equivalent only under the MVP's one-portfolio-per-user constraint. AD-22 says the plan belongs to a portfolio, so the durable invariant should be portfolio-scoped; a separate user-level cap can remain an explicit MVP product constraint if desired.

**Action:** **Discuss/autofix.** Normalize the domain uniqueness to `(portfolio_id) WHERE active`; if the product also permits only one portfolio per user in MVP, document that as a distinct constraint and test both independently.

### M-3 — Future scope adjustment is simultaneously an adopted direction and an unapproved deferred contract

AD-22 says future sources “use” inclusion-time scope adjustment, while its preceding sentence and Deferred correctly state that the feature is unavailable until policies are approved. The solution design calls it a future continuity mechanism. This is directionally coherent, but “direction” is not sufficient for implementation and can be misread as an approved formula.

**Action:** **Autofix.** Mark inclusion-time scope adjustment as a non-executable design direction and explicitly forbid implementation until valuation time, FX/price evidence, rounding, failure, removal, and transfer-matching rules become adopted ADs/stories.

### M-4 — Companion metadata does not reflect the portfolio rewrite

`PORTFOLIO-SOLUTION-DESIGN.md` still declares `updated: '2026-08-26'` although its content contains the 2026-09-02 portfolio/source changes.

**Action:** **Autofix.** Update the companion date/version metadata when reconciling the artifacts.

## Low findings

### L-1 — Provider-neutral naming is not complete in provider-specific rules

AD-21 binds “exchange connection creation and replacement,” while the adopted generic entity is `provider_connection`. Bybit-specific content is appropriate; only the aggregate noun is stale.

**Action:** **Autofix** terminology to “Bybit provider connection.”

### L-2 — The architecture sequence diagram skips the newly explicit portfolio composition step

The design-paradigm diagram includes `portfolio`, but the main sequence goes from provider values to append facts/calculation and then publication without showing source snapshot → portfolio snapshot → calculation. The companion explains it correctly.

**Action:** **Autofix** the sequence diagram so it cannot teach a direct Bybit-to-calculation path.

## Good-spine checklist

| Checklist item | Result | Assessment |
| --- | --- | --- |
| Fixes the real divergence points for the level below | **Partial** | Provider-neutral plan ownership, base currency, one-source MVP, immutable snapshots, and the multi-source gate are fixed. H-1 and H-2 remain real implementation divergence points. |
| Every AD Rule is enforceable and prevents its stated divergence | **Partial** | AD-1/AD-9/AD-22 are largely enforceable, but constituent eligibility is underspecified and the future scope-adjustment sentence is not executable. |
| Nothing Deferred can let two units diverge | **Pass for MVP / partial for expansion** | Multiple active sources are explicitly prohibited, containing current divergence. Add portfolio publication ordering and make scope adjustment non-executable before expansion. |
| Named technology is verified-current | **Pass based on artifact evidence** | Stack entries are pinned to allowed lines and verified baselines; deterministic lint reports no unpinned technology. This review did not independently browse vendor release sources. |
| Ratifies rather than contradicts brownfield codebase | **Not applicable / no brownfield evidence** | Artifacts describe a planned system; no implemented source tree was presented as an inherited constraint. |
| Covers capabilities from the driving spec | **Pass** | The spine and epics retain Bybit MVP connection, valuation, cashflow, plan comparison, lifecycle, operations, and deletion capabilities while adding the requested expansion seam. |
| Does not weaken an inherited parent spine | **Not applicable** | No parent spine is declared. |
| Every owned dimension is decided, deferred, or open | **Pass with corrections** | Deployment, environment, storage, operations, security, lifecycle, provider expansion, and data integrity are addressed. Multi-source computation is deliberately deferred and blocked. |

## Positive conclusions

- The user-facing choice—one unified plan over one portfolio—is preserved without pretending the MVP already aggregates multiple institutions.
- `plan -> portfolio`, provider namespacing, immutable source/portfolio snapshots, and the exact-one-BYBIT-source rule form a low-regret expansion seam.
- The architecture correctly keeps Bybit `totalEquity` authoritative for its source while insulating calculation from Bybit-specific DTOs.
- The explicit prohibition on a second active source is essential and successfully contains unresolved cutoff, FX, transfer-matching, and scope-adjustment questions.
- Operational, security, backup, deployment, and deletion dimensions remain present rather than being displaced by the new domain model.

## Recommended gate disposition

Apply H-1 and H-2 plus the straightforward terminology/metadata/diagram fixes before final handoff. Record portfolio-scoped aggregate publication ordering in Deferred. After those corrections, the portfolio/source architecture is suitable for MVP implementation and preserves the intended path to Binance or Toss Securities without implementing speculative multi-source behavior now.

## Revalidation — 2026-09-02

**Verdict: PASS WITH ONE NON-BLOCKING COMPANION WORDING FOLLOW-UP.** Both high findings are closed, the deterministic spine lint still passes with zero findings, and no critical/high blocker remains for MVP implementation.

| Original finding | Status | Revalidation evidence |
| --- | --- | --- |
| H-1 stale module/deletion boundaries | **Closed** | `epics.md` AR-2 now declares `source` and `portfolio`; Story 5.2 AC-1 calls deletion ports for `identity/source/portfolio/sync/plan/engagement/operations` and explicitly includes provider account/connection, membership, source fact/snapshot, portfolio snapshot/component/pointer, and plan-result pointer. Its two-user test covers cross-user preservation. The nonexistent `snapshot` deletion-module wording is gone. |
| H-2 constituent eligibility | **Closed** | New AD-23 makes component uniqueness, user/provider-account ownership, UTC membership interval, base currency, revision/restriction validity, admission/final-publication recheck, and all-or-nothing rollback enforceable. Story 2.2 AC-7 and its boundary matrix test exact start/end, superseded/restricted/currency mismatch, second component, and disconnect race; Story 2.7 rechecks the same predicate immediately before pointer CAS. |
| M-1 source-vs-portfolio publication ordering | **Closed** | AD-16 limits source `sync_sequence` to provenance/fencing. AD-23 gives `portfolio` a monotonic `publication_sequence`, a current snapshot pointer, and `sync` a plan-result pointer under one outer `REQUIRED` transaction. AR-7 matches this. Deferred now requires the source-revision set and concurrent update ordering before multi-source activation. |
| M-2 user-vs-portfolio active-plan uniqueness | **Closed** | Story 2.7 and Story 2.15 now use portfolio-scoped active-plan uniqueness and independently state the MVP one-portfolio-per-user constraint; the solution design already specifies the portfolio conditional unique index. |
| M-3 adopted-vs-deferred scope adjustment | **Partially closed; non-blocking** | AD-22 now calls scope adjustment a **non-executable design direction** and forbids implementation until valuation time, price/FX evidence, rounding, failure, removal, and transfer-matching rules are adopted; `epics.md` likewise prevents activation without later stories. The companion's section 6 paragraph still says future additions “maintain continuity” with scope adjustment before its following sentence blocks the feature. This does not authorize MVP implementation, but should be aligned to the exact “non-executable direction” wording for a fully clean companion. |
| M-4 stale companion metadata | **Closed** | `PORTFOLIO-SOLUTION-DESIGN.md` is updated to `2026-09-02`. |
| L-1 stale provider terminology | **Closed** | AD-21 now binds “Bybit provider connection” and introduces stable provider-account identity. |
| L-2 sequence omitted composition | **Closed** | The spine sequence now explicitly shows append facts/source snapshot, compose portfolio snapshot components, calculate, and atomic portfolio/plan pointer publication. |

### Additional consistency check

- `DEVELOPMENT.md` does not define product module boundaries and explicitly delegates product stack authority to `ARCHITECTURE-SPINE.md`; it therefore neither conflicts with nor weakens the portfolio/source model.
- The new `provider_account_id` identity closes a reconnect/credential-replacement hole: facts and source snapshots are stable across replacement connections, while connection ID remains collection provenance.
- MVP multi-source admission remains fail-closed: schema-level 1:N component representation does not imply application-level permission, and official publication rejects a component count other than exactly one.
- Lint result: `ok: true`, `total_findings: 0`.

### Remaining action

**Autofix, non-blocking:** in `PORTFOLIO-SOLUTION-DESIGN.md` section 6, prefix the future scope-adjustment paragraph with the same explicit statement used by AD-22: it is a non-executable direction and must not be implemented until separate adopted ADs and stories define all required policies. No MVP architecture or story blocker remains.
