# LP Wave 2.4 latest-Koblas HFactor comparison

## Verdict

**VALID_COMPARISON; WAVE_2_NOT_ESTABLISHED.** All six real-operation traces replayed successfully through the
portable Kotlin basis engine and HFactor for all three measured repetitions, with identical operation counts,
accepted updates, zero state errors, and residuals within the source-matrix tolerance.

Across the four `PRODUCTION_RELAXATION` traces, the sum of per-trace setup-plus-lifecycle medians is 9.3% lower
for the Kotlin engine. Its composed lifecycle is 68.9% higher than HFactor's: Kotlin's much cheaper setup offsets
slower factorization and update work in these short traces. This is a descriptive basis-engine result, not an
integrated LP, pricing-policy, CP-search, or rule-8 wall-time acceptance result.

## Compared build

- Klause campaign revision: `3024ef514c16cb4b95118bceffa20ae9ac739f4a`, tree
  `d3927acccf94f2487246d5eb249eb852e955abf4`, based on merged #1978.
- Contract SHA-256: `31477d298a4ce4b0ccac10a448475a0c134c3cbf770c70398c6a5d42f6092af9`.
- Manifest SHA-256: `28f1b8ccb7d2922a3c5ac53bc5ccbccb978c360d0a6f1dc89ad48e6b96d159f4`.
- Koblas resolved normally after `--refresh-dependencies`; no init script, version override, local publication,
  historical checkout, or artifact pin was used.
- Resolved snapshots: `koblas` `20260911.045251-201`, `koblas-jvm` `20260911.045251-204`, and
  `koblas-hfactor` `20260911.045251-101`.
- Runtime artifact SHA-256: Koblas JVM `83896749...3a42`; HFactor `6ddd5dcb...4d22`.
- Runtime: Java `25.0.1+8-LTS`, Kotlin `2.4.10`, Linux/amd64.

The single campaign used one discarded paired warmup and exactly three paired measured replays per trace,
with two Gradle workers and a 300-second external campaign limit. There were no retries or exclusions.

## Results

Ratios are `Kotlin / HFactor`; below one favors Kotlin. Times are medians in nanoseconds. The first four rows are
production-relaxation captures. The two SMT rows are source-derived and excluded from the production aggregate.

| Trace | Kotlin total | HFactor total | Total ratio | Kotlin lifecycle | HFactor lifecycle | Lifecycle ratio |
|---|---:|---:|---:|---:|---:|---:|
| mps-adlittle | 6,362,995 | 5,038,185 | 1.263 | 6,320,822 | 3,923,788 | 1.611 |
| mps-afiro | 1,001,096 | 2,119,109 | 0.472 | 917,138 | 418,856 | 2.190 |
| mzn-graph-coloring | 177,805 | 926,588 | 0.192 | 142,566 | 56,943 | 2.504 |
| mzn-timetabling | 175,118 | 423,981 | 0.413 | 134,032 | 50,640 | 2.647 |
| smt-lia-unsat | 73,897 | 298,350 | 0.248 | 54,523 | 25,004 | 2.181 |
| smt-lia-wide-span | 151,909 | 516,669 | 0.294 | 123,301 | 67,782 | 1.819 |
| **Production median sums** | **7,717,014** | **8,507,863** | **0.907** | **7,514,558** | **4,450,227** | **1.689** |

The production sums expose where the difference comes from:

| Component | Kotlin median sum | HFactor median sum | Ratio |
|---|---:|---:|---:|
| Setup | 238,620 | 4,209,675 | 0.057 |
| Factorization | 4,048,225 | 743,922 | 5.442 |
| FTRAN | 1,026,520 | 1,200,970 | 0.855 |
| BTRAN | 698,143 | 1,243,366 | 0.561 |
| Update only | 1,796,060 | 781,899 | 2.297 |
| Synthetic preparation + update | 1,926,527 | 1,311,890 | 1.469 |

The two update-bearing production traces replayed 64 accepted updates per measured repetition in each backend
(47 adlittle and 17 afiro), with no declines or refactor advice. The maximum observed relative residual was
`5.56e-17` for both backends. Absolute residuals are large on the deliberately wide-scale systems but remain
well below the scale-aware recorded tolerances.

## Interpretation limits

HFactor timing includes the comparison adapter's carrier copies, while its reported allocation excludes native
heap. Those costs are neither removed nor estimated, so allocation totals are not compared here. The
`synthetic_prepared` measurement uses a shadow basis to recompute referenced solves and apply an update; shadow
setup and checkpoint builds are excluded from that metric and from composed lifecycle. Backend work counters,
repair, snapshots, and extension are not comparable and are not inferred.

The result therefore says that the Kotlin engine has cheaper startup and faster FTRAN/BTRAN on this bounded
corpus, while HFactor has materially faster factorization and update operations. It does not attribute any
difference to the pricing work merged in #1978 and does not close the broader Wave 2 acceptance gaps.

## Evidence

The complete campaign log, twelve aggregate NDJSON records, exact input hashes, resolved dependency coordinates,
and machine-readable summary are retained under
`/home/rasmus/Workspaces/lp-evidence/session-2.4-hfactor`. The reviewed contract is retained at
`/home/rasmus/Workspaces/lp-evidence/session-2.4-hfactor-contract.md`.
