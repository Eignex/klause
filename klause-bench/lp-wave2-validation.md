# LP Wave 2.4 integrated acceptance

## Verdict

**NOT_ESTABLISHED.** The bounded integrated campaign does not close Wave 2 and does not authorize
Wave 3. Session 2.1a's source-derived trace preserves every exact outcome and reduces update engine
work by 72.44%, but it adds five update refactorizations and its busy-host median time is 4.00 times
the historical arm. Session 2.2's source-derived trace fails the independent initial exact check in
both arms, so no update-work comparison is admissible. The required captured bound-only CP
factorization reduction was not measured, and rule-8 wall-time evidence remains descriptive and
unestablished.

This change adds acceptance tooling and evidence only. It does not edit production or B7 sources.

## Frozen comparison

- Candidate: `a1e51591f4dcefd66682ba5128388b3a34ebcb80`, tree
  `2e73c9bc8de8435b161864ea90d2a09a88f07832`.
- Corrected #1978 parent: `89d809913709452affaa925d0dfdb89ba5859b18`.
- Historical comparison: `cd66668668851eea37350db5af0d416fb27a7a0e`, tree
  `1c9364f93aedf5564576df51302c7ce359a549b3`.
- Contract SHA-256: `d11a5b5c61d12f143b13523a991e1f4e60f1b0b588216bf360b2a1ebe0cb19d3`.
- Manifest SHA-256: `300b718bc08c06340d22d71ed2ee18bb81cf88421b2628c734b0447c5cf670ab`.
- Candidate policy: `MIN_BOUND_SUPPORT`, seed 21. Both workloads have nonzero objectives, so zero-cost
  pricing is explicitly inactive (`pricingAttempts=0`).
- Historical policy: implicit Harris/largest-pivot behavior; seed unsupported and inactive.
- Backend: `KOTLIN_PRODUCT_FORM` in both arms.
- Frozen Koblas artifacts: `koblas` `0.1.1-20260910.200234-195`
  (`2efba2a3...e3d7`), `koblas-jvm` `0.1.1-20260910.200234-198`
  (`7d01a039...020`), and `koblas-hfactor` `0.1.1-20260910.200234-95`
  (`6ddd5dcb...4d22`).

The historical checkout received only the recorded compile-compatibility patch removing an obsolete
Koblas opt-in. The campaign used one discarded warmup and exactly three alternating pairs with at most
two Gradle workers and a 60-second external timeout per arm. No arm timed out and no repetition was
retried. Preparation-plus-solve timing includes state preparation and solve, and excludes exact
validation. Initial solve counters are reported separately from update counters.

## Results

Medians are over the three frozen repetitions. Historical basis-operation work is unavailable at the
2026-06-26 comparison point and is not inferred from engine work.

| Source-derived trace | Arm | Exact accepted | Initial engine work | Update engine work | Update basis work | Initial factors | Update factors | Median prep+solve ns |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| 2.1a, 40 updates | historical | 40/40 | 16 | 5,504 | unavailable | 1 | 0 | 4,623,519 |
| 2.1a, 40 updates | candidate | 40/40 | 21 | 1,517 | 881 | 1 | 5 | 18,494,008 |
| 2.2, 32 revisions | historical | 0/32 | unavailable | unavailable | unavailable | unavailable | unavailable | unavailable |
| 2.2, 32 revisions | candidate | 0/32 | unavailable | unavailable | unavailable | unavailable | unavailable | unavailable |

For 2.1a, candidate update engine work is 27.56% of historical work, passing the per-trace 0.95
deterministic-work threshold. All three candidate repetitions report complete, nonsaturated basis
accounting, zero unknown work, zero repair attempts, zero logical fallbacks, five
`SYNTHETIC_WORK` refactor triggers, and a closed owner. The observed-source state hash
`1d649d88...4599` and exact-outcome hash `f0fcaef7...10d7` match between arms and across repetitions.

The 2.1a candidate/historical median wall-time ratio is 3.99999, failing the 1.05 rule. These timings
come from a busy shared host and remain descriptive even if favorable; they cannot establish the
rule-8 production threshold.

For 2.2, the first independent exact check throws `IllegalStateException: Check failed` before update
measurement in both arms. Each repetition persists a failed record with the fixed 32-attempt
denominator, zero accepted exact outcomes, unavailable work, and an unclosed measurement owner. The
failure is repeatable within the sole campaign, but the retained record does not localize which exact
KKT/source assertion failed. It is therefore treated as unavailable exactness evidence, not repaired,
excluded, or retried. Per-trace and aggregate work claims are invalid.

## Acceptance accounting

| Obligation | Result |
|---|---|
| Unchanged exact outcomes on 2.1a | Established: 120/120 per arm, matching state/outcome hashes |
| Unchanged exact outcomes on 2.2 | **NOT_ESTABLISHED:** independent initial exact checks fail in both arms |
| At least 5% less update engine work per integrated trace and aggregate | **NOT_ESTABLISHED:** 2.1a passes; 2.2 and aggregate are invalid |
| Honest separate basis-operation accounting | Established for candidate; historical correctly reports unavailable |
| Fewer factorizations on captured bound-only CP trace | **NOT_ESTABLISHED:** no captured CP trace; 2.1a candidate is 6 total versus 1 historical |
| Rule-8 wall time | **NOT_ESTABLISHED:** 2.1a fails the ratio and busy-host times are descriptive; 2.2 is invalid |
| Unknown, saturation, repair, fallback and owner denominators | Established for 2.1a; explicit failed records retained for 2.2 |

## Combined Wave 2 and B5 evidence

The earlier accepted sessions continue to establish their scoped correctness claims: 2.1a covers
rational/IEEE declaration authority, raw-bit-sensitive identity, exact source-coordinate status and
certificate handling, and arbitrary bound backjumps; 2.1b covers stable row identities, scoped
append/pop/deactivate/compact behavior, preparation failure, publication order and owner cleanup; 2.2
covers bounded refactor policy, exact-rank repair classification, fallback declines, snapshot/status
normalization and restart cleanup; B5b covers validated append transfer, exact source/status mapping,
balanced ownership and complete failed/fallback work accounting. Corrected #1978 propagates the chosen
pricing policy through residual, root, leaf, general, component and presolve construction paths while
preserving the annotation arm.

This campaign corroborates exact state and outcome equality only on 2.1a. Its 2.2 exact-check failure,
missing captured CP factorization evidence, unfavorable descriptive timing, and unavailable aggregate
comparison prevent the combined record from proving integrated Wave 2 acceptance. No future ladder or
production-default claim is made.

## Evidence

Raw JSONL, per-repetition logs, input checksums, the machine-readable summary, and the reviewed contract
are retained under `/home/rasmus/Workspaces/lp-evidence/session-2.4`. The summary intentionally exits
nonzero with `passed=false`, `capturedCpFactorization=NOT_ESTABLISHED`, and
`wallTimeEvidence=DESCRIPTIVE_NOT_ESTABLISHED`.
