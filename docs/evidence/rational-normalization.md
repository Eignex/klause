# Rational normalization fast paths

The [fraction factories](../../klause/src/commonMain/kotlin/com/eignex/klause/simplex/exact/RationalSimplex.kt)
skip redundant GCD/division work when their inputs establish coprimality.
The normalization invariant and exact-value argument are described in
[LP certification](../lp/certification.md).

The measurement compares `9e5c82d2f90abc1154deaf2d211ac4648bb2088e` with
`0c6485046ee69caa46edd86b645cc5061cc9fedc`, a source-identical build except
for routing these factory paths through general normalization. It measures
one CP process on AWS, default LP, seeds 1, 7 and 31, fixed work 3000 and
6000, and three alternating pairs per seed/work combination. Executed source
hashes, build fingerprints and provenance are retained in the
[CI evidence](https://github.com/Eignex/klause/actions/runs/38043606727).
All 108 pairs attained the requested work and matched every non-timing counter
and outcome. The figures below are medians of paired duration reductions;
ranges contain the six seed/work block medians.

| nec-smt input, each `prp-1-46` | Paired median reduction | Block median range |
|---|---:|---:|
| `med/mygetpwnam` | 7.41% | 2.96–9.86% |
| `large/handler_sigchld` | 9.05% | 2.78–13.50% |
| `large/checkpass` | 11.95% | 8.09–13.48% |
| `large/int_from_list` | 8.72% | 6.83–14.46% |
| `large/user_is_in_group` | 15.53% | 11.50–16.55% |
| `large/getoption_group` | 11.52% | 8.20–12.42% |

Sixty-second deadline controls across the same six inputs and three seeds
also retain unknown outcomes. Their observed median theory-check rates rise
for each input. These deadline runs measure progress through different prefixes;
they do not establish identical-work timing or completed-solve speedups.

The [independent source checks](https://github.com/Eignex/klause/actions/runs/38042314816)
cover 104 matched MPS/SMT default/exact records: 24 finite optima, 76 feasible
records in total, 16 infeasible records and 12 unknown records. Every claimed
verdict agrees with original source constraints. Each claimed finite optimum
has a feasible source objective and excludes all strictly better objectives.
All captured SMT models and exact-mode MPS points satisfy their sources exactly.
Four default-mode MPS points are rounded rather than exact feasible points;
source satisfiability is checked separately. Exact egout lowering refusal is an
unsupported result, not an infeasibility claim.

This supports retaining the factory paths within the pinned workloads and
builds. It does not establish a general solve-time guarantee, decide these
nec-smt inputs, or remove exact lowering limitations. Raw records and campaign
scripts stay outside Git under the shared active-evidence policy; useful tools
are maintained in [diagnostic tools](../testing/tools.md#record-handling).
