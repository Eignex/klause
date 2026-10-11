# Numerical kernel decisions

These summaries support current implementation choices. The
[LP architecture](../lp/architecture.md), [certification](../lp/certification.md)
and [dependency integration](../lp/dependencies.md) pages own their contracts.
Measurements establish only the workloads, builds and runtimes stated here.
Raw records and campaign scripts follow the
[external evidence policy](../development.md#campaign-evidence).

## Dense basis selection

The basis owner selects dense LU for dimensions 2–6 with at least 50% selected-basis
nonzeros and more nonzeros than rows. This conservative envelope is supported by
production-relaxation captures and the committed
[basis corpus](../testing/fixtures.md#basis-corpus).

On Linux amd64, JDK 25.0.1 and Koblas JVM artifact
`cf9486a34f4a1797ce477109594c3fb3858e8eebea7ecbc30ea263de53a8895d`,
seven alternating pairs after warmup compared 2000 six-row refactorizations per
arm. The populated `markshare1` basis had 31 nonzeros out of 36; selected/sparse
median elapsed ratios were 0.64 for retained owners and 0.53 for fresh
setup/factorization/disposal. A repeat gave 0.71 and 0.53. Captured 34- and
198-row bases favored sparse construction; the six-row unit basis made no dense
attempt. The 198-row trace was capture-limited and covers only its recorded prefix.

Independent CSC products checked both solve directions outside timing. Replay
checked factorization/update outcomes and fresh sparse residuals. Matched macOS
arm64 source controls preserved verdict and proof status across 40 exact-MPS
and 15 default-MPS paired blocks. Rational source checking covered all 90 final
witnesses; separate tiny-model checks covered the claimed optima and contradictions.
Exact and tolerance policies were compared within their respective pairs.

This supports the guarded selection without a complete-solver speedup claim.
The measurements used portable/SIMD kernels; no installed vendor library was
selected. Deterministic work still charges dense work. Do not enlarge the rule
from synthetic timing alone. Use [dense comparison replay](../lp/replay.md#dense-selection-comparison)
for reproducible operation-level checks.

## Rational normalization

The [fraction factories](../../klause/src/commonMain/kotlin/com/eignex/klause/simplex/exact/RationalSimplex.kt)
avoid redundant GCD/division when coprimality is established. Exact normalization
and value preservation are defined in
[certification](../lp/certification.md#certification-mechanisms).

A [fixed-work build comparison](https://github.com/Eignex/klause/actions/runs/38043606727)
against general normalization covered six `nec-smt` inputs, three seeds, work
allowances 3000/6000 and three alternating pairs per seed/allowance. All 108
pairs spent the allowance and matched every non-timing counter and outcome.
Median paired duration reductions ranged from 7.41% to 15.53% across inputs;
seed/allowance block medians ranged from 2.78% to 16.55%.

[Independent source checks](https://github.com/Eignex/klause/actions/runs/38042314816)
covered 104 matched MPS/SMT records, including 24 finite optima, 16 infeasible
and 12 unknown results. Every claimed verdict agreed with source constraints.
Four default-MPS points were rounded rather than exactly feasible; their source
satisfiability was checked separately. Exact lowering refusals remained unsupported.

The evidence supports retaining these factory paths on the measured workloads.
Deadline controls traversed different prefixes and retained unknown outcomes;
they establish neither identical-work timing nor completed-solve gains.

## Cancellation polling

The [resource contract](../lp/certification.md#resources-and-cancellation) keeps
work-metered checks immediate, ordinary polling bounded and dynamic deadlines live.
Ordered compositions preserve construction-time deadline and first-meter snapshots.

A CPU profile attributed overlapping samples to cancellation calls in
reconstruction, LP wrappers and shared search. Such frame attribution does not
measure additive cost or establish savings from dispatch changes.
A [fixed-work dispatch comparison](https://github.com/Eignex/klause/actions/runs/38043602143)
covered three `nec-smt` inputs with five alternating pairs, seed 1 and work 3000.
Non-timing counters and unknown outcomes matched. Median paired duration
reductions were 0.45%, -0.57% and -0.20%, providing no useful dispatch-speedup
evidence. The implementation retains its dispatch and polling frequency.

Regression coverage protects live deadline rearming and external cancellation
through both public polling methods. Frame removal or inlining alone cannot
justify weakening that contract.
