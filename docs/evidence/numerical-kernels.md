# Numerical kernel decisions

These summaries support current implementation choices on bounded workloads.
[LP architecture](../lp/architecture.md), [certification](../lp/certification.md) and
[dependencies](../lp/dependencies.md) own the contracts; measurement procedures live
in [capture and replay](../lp/replay.md). Raw evidence follows the
[external storage policy](../development.md#campaign-evidence).

## Dense basis selection

Retain the bounded dense-LU selection and windowed updates. Captured bases came
from MIPLIB2017 `markshare1`, `gr4x6`, `22433` and the committed basis corpus. Linux amd64/JDK 25
measurements used three fresh JVM forks with warm, paired complete factor builds,
including ownership and numerical guards.

On the populated six-row `markshare1` basis, windowed/full-buffer elapsed ratios
were 0.983 for retained and fresh owners; windowed/sparse ratios were 0.480 and
0.498. Independent products and operation replay checked both solve directions.
Windowed updates charged 125 fewer deterministic work units per build. Source
controls preserved outcomes, with 54 final witnesses independently checked.

Direct dense-build benefit was measured at order six; orders 2–5 remain inside the
bounded rule without that direct evidence. Larger captured bases favored sparse
construction, and the `22433` recording covers only its captured prefix. These
results justify neither a wider dense rule, a dependency-upgrade speedup nor a
whole-solver timing claim.

## Rational normalization

Retain the [fraction factory](../../klause/src/commonMain/kotlin/com/eignex/klause/simplex/exact/BigFraction.kt)
paths that skip redundant normalization when coprimality is established.
A [fixed-work comparison](https://github.com/Eignex/klause/actions/runs/38043606727)
covered six `nec-smt` inputs, three seeds and work allowances 3000/6000. All 108
pairs matched non-timing counters and outcomes; median paired duration reductions
ranged from 7.41% to 15.53% across inputs.

[Independent source checks](https://github.com/Eignex/klause/actions/runs/38042314816)
covered 104 matched MPS/SMT records. Claimed verdicts agreed with source constraints;
four rounded default-MPS points received separate source satisfiability checks.
Deadline-limited runs traversed different prefixes, so they establish neither
identical-work timing nor completed-solve gains.

## Cancellation polling

Retain the [polling contract](../lp/certification.md#resources-and-cancellation)
and dispatch frequency. A [fixed-work comparison](https://github.com/Eignex/klause/actions/runs/38043602143)
used three `nec-smt` inputs and five alternating pairs at seed 1/work 3000.
Counters and unknown outcomes matched; median duration reductions were 0.45%,
-0.57% and -0.20%, providing no useful dispatch-speedup evidence.

Overlapping CPU-profile frames do not measure additive cancellation cost.
Regression coverage protects live deadline rearming and external cancellation;
frame removal alone does not justify weaker polling.
