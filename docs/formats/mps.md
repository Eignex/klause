# MPS source semantics

MPS supplies linear constraints, bounds, integrality and an objective. Klause
retains decimal source authority even when a numerical working model uses
binary64. Exact and tolerance-based publication have distinct guarantees.

[Shared input semantics](../formats.md) owns routing; [LP certification](../lp/certification.md)
owns numerical evidence. This page defines source correspondence, tolerances and
the claims each route may publish.

## Source correspondence

The MPS parser retains decimal source metadata, integer markers, bounds and
objective sense. The lowered LP may restate continuous coefficients in binary64.
An unconditional equality defining a free continuous column as an integral affine
sum of bounded source integers gives that column a finite integer representation
when the resulting rows and objective retain their source numbers. The proof uses
exact source coefficients and bounds; fractional definitions and inexact integer
lowerings remain continuous. Source column names, witness values and objective
units are preserved.
`sourceExact` records exact source correspondence; `toleranceDifference` identifies
a lowering difference relevant to tolerance publication. Rounded or oversized
pure-integer rows are rebuilt from source decimals; oversized objectives can use
an auxiliary defining row. Integer values and lower-bound shifts keep exact
authority beyond `2^53`.

`MpsModel.toProblem` retains original decimal rows for mixed sources whose binary64
hybrid projection differs from the source. Indicated rows also retain their native
source implication. These representations do not select an engine or transfer
bounded integer choices to arithmetic search. The default purely continuous hybrid
projection retains its explicit tolerance correspondence.

`MpsModel.toExactSourceProblem` preserves parsed decimal numbers in native
`ExactLinear` rows, clearing denominators without rounding. Real column
bounds are exact rows with open real declaration arrays; integer bounds outside
`Long` also remain rows. Declared integer columns retain integrality. `LinearIndicator` retains the source
integer trigger and enabled comparison;
selected LINEAR and POLYNOMIAL encoders install the equivalent guarded relation
privately. Finite integer checks and numerical row emission read that same source
implication. Numerical rows enabled by a finite pin retain both equality premises,
so retraction removes their authority.
Integral integer objectives retain their source coefficients directly. Other
objectives use an auxiliary equality retaining original decimal coefficients and
constant in source units. The returned column mapping omits this auxiliary and
its source witness check requires exact feasibility, independently of the hybrid
lowering's tolerance correspondence. Both loading entry points use the same factor
execution views. Integer-only rows
have exact finite filtering and scores; continuous rows have heuristic scores and
source-certified completion. Wide coefficients, RHS values and objective definitions
admit exact positive row normalization for the numerical LP, preserving rational
certificate authority and source units independently of floating working values.
Nonzero projection underflow or nonfinite working entries decline the affected
numerical procedure without selecting arithmetic-theory execution. Bounded integers
retain finite ownership when numerical completion is admitted.
Named objectives without terms retain their source constant and direction.
The shared pipeline selects execution from these capabilities after bound closure;
`sourceExact` and exact coefficient storage alone do not request whole-model exact
traversal. Explicit `--exact` requires certificates and disables tolerance acceptance;
representable original decimal rows retain numerical execution for purely continuous
sources as well as bounded mixed sources.

## Tolerance publication

Finite default MPS solves can accept a complete continuous candidate by source tolerance.
Root primal proposals can retain their LP float coordinates when the composed
source check accepts the full point; rejected proposals retain real-completion
fallback. These proposals carry the same tolerance witness guarantee. Rows use

```text
1e-7 * max(1, abs(bound), sum(abs(a(i) * x(i))))
```

and column bounds use `1e-7 * max(1, abs(bound))`. Nonfinite row activity or
magnitude is checked again exactly against this scaled tolerance. The separate LP
gap test uses lowered objective units and accounts for reduced-cost movement, row
residuals and objective rounding. The rendered objective uses source decimal
coefficients at the returned point.

For a source-exact model with at most 4096 coordinates, float rendering can try
replacing residuals within `1e-12` of zero by zero when the remaining real values
are integral and fit `Long`. Optional verification admits at most 32768 column,
row and coefficient entries. The candidate must pass every original decimal row,
bound and integrality check, and its source objective must equal the unchanged
float point's source objective. A failed check retains the original float witness.
This output-only reconstruction changes no search assignment, cutoff or
optimization-status authority.

`TOLERANCE_OPTIMUM` differs from `ATTAINED_OPTIMUM`. A tolerance-accepted float
point is not an exact witness. Optimum publication requires no
`toleranceDifference`; infeasibility and unboundedness require `sourceExact`.
`--exact` requires exact source certificates. A hybrid lowering that differs from
the source is replaced by the exact source adapter for arithmetic execution.
Exact adapter witnesses require original decimal feasibility. Working-model
witnesses on the default finite LP lane retain the separate tolerance guarantee.
Output comments identify an exact original-source witness or a source-tolerance
witness independently of the optimization status.
Optimal output includes `optimizationStatus=attained-optimum` or
`optimizationStatus=tolerance-optimum` for that accepted witness guarantee.

## Routing and result strength

MPS selects numerical execution independently of certification policy. Default
purely continuous sources use the finite LP lane and its tolerance guarantee.
Supported bounded integer sources retain the existing finite search and numerical
completion. An immutable execution policy also keeps finite LS and ALNS workers on
numerical leaf completion when presolve fixes their discrete coordinates; it cannot
materialize exact-theory neighborhood workers. Open integer declarations retain their missing sides and use numerical
LP relaxations with exhaustive integer splits; no finite window changes source scope.
Explicit `--exact` changes source correspondence and certificate requirements without
selecting arithmetic theory or theory portfolio workers.

The numerical source traversal retains exact original rows and objective direction.
Each fractional integer endpoint splits at its floor into both exhaustive alternatives.
A checked branch optimum or refutation supplies only branch authority until every
required sibling has certified coverage. A source-valid improving ray requires an
integral feasible base and an integral recession step for every source integer.
An unbounded LP relaxation alone cannot establish MIP unboundedness. Numerical
representation, resource, source-capture or certification limits return an incomplete
result with any independently checked incumbent and root bound. They never trigger
open-theory or theory-worker fallback, and an unresolved leaf cannot become infeasible.

The direct numerical traversal currently requires a complete affine source capture.
An open source with an unsupported guarded relation explicitly declines. Supported
finite indicator models retain their numerical completion. LS and ALNS requests
withhold numerical bounds and terminal claims and can publish checked witnesses only.

Every open candidate is checked against original rows, bounds, indicators and
integrality before publication. Its objective is recomputed from original numbers,
including the constant and source sense. An auxiliary objective coordinate is a
source-derived definition and is omitted from rendered original columns. Rejected
final witnesses cannot promote a buffered earlier point to an optimum or supply
the point required by an unboundedness claim. A source-inexact numerical proposal
alone supplies no original-source refutation, bound or optimum authority.
Rendering and acceptance state belongs to one load; retained outputs do not read
the source objective or witness acceptance of a later load.

See [MpsCompiler](../../klause/src/commonMain/kotlin/com/eignex/klause/formats/mps/MpsCompiler.kt)
and [MpsMode](../../klause-cli/src/commonMain/kotlin/com/eignex/klause/cli/MpsMode.kt).
