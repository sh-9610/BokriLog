# Architecture Spine Rubric — Final Narrow Re-review

- **Targets:** `ARCHITECTURE-SPINE.md`, `PORTFOLIO-SOLUTION-DESIGN.md`
- **Scope:** closure of the two remaining high findings after AD-20, AD-21, and AD-17 scope changes
- **Verdict:** **PASS — no critical or high finding remains.**

## High Finding Closure

### User-data isolation — Closed

AD-20 now makes the authenticated session the sole authority for internal `user_id`; scopes every user-owned query, command, job, publication, and repository query; rejects client-supplied ownership as authority; gives cross-user and nonexistent targets the same observable error contract; and requires horizontal privilege-escalation integration tests for every aggregate. The companion carries the same contract. This fully closes the previous tenant-isolation gap.

### Read-only Bybit admission and resource-level quarantine — Closed

AD-21 now requires `readOnly=1`, supported UTA, expected Bybit user ID, regional reserved-IP inclusion, minimum permissions, and a successful wallet read to form one accepted validation result before storing credentials or a connection. Write-capable, ambiguous/excess permission, UID/IP mismatch, and failed live-read cases are rejected without storage or automatic retry escalation.

AD-17 now enumerates `plan`, `calculation_policy_version`, `exchange_connection`, `sync`, `advertising`, and global-feature restriction scopes; requires every relevant read, admission, lease, publication, and display path to evaluate the effective restriction fail-closed; and preserves the PRD's global fallback, 60-second automatic stop, and manual-only recovery. This fully closes the remaining quarantine-scope gap.

## Regression Check

- No new contradiction was introduced between AD-17, AD-20, AD-21, the module ownership convention, capability map, or companion design.
- `engagement` is now present in the module-ownership convention.
- The duplicate publication capability rows are merged and governed by AD-6, AD-7, and AD-16.
- Support intake and incident communication are now visible in the capability map.
- Existing deletion/backup, append-only, publication fencing, identity, advertising, deployment, observability, cost, and Deferred release-gate contracts remain coherent.

## Checklist

| Criterion | Result |
| --- | --- |
| Real divergence points fixed | **Pass** |
| Rules enforce their stated prevention | **Pass** |
| Deferred items have safe defaults/revisit gates | **Pass** |
| PRD/UX capability coverage | **Pass** |
| Operational/environmental envelope | **Pass** |
| New critical/high contradiction | **None found** |

The rubric lens is ready for the parent reviewer gate to finalize alongside deterministic lint and the other configured reviewer lenses.
