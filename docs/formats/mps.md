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

`MpsModel.toExactSourceProblem` preserves parsed decimal numbers in the existing
exact tagged linear rows, clearing denominators by positive scaling. Real column
bounds are exact rows with open real declaration arrays; integer bounds outside
`Long` also remain rows. Declared integer columns retain integrality. Conditional
rows are disjunctions of the indicator's exact complement and the enabled row.
Integral integer objectives retain their source coefficients directly. Other
objectives use an auxiliary equality retaining original decimal coefficients and
constant in source units. The returned column mapping omits this auxiliary and
its source witness check requires exact feasibility, independently of the hybrid
lowering's tolerance correspondence. This adapter prepares source data; callers
select execution through the shared pipeline.

## Tolerance publication

Finite default MPS solves can accept a continuous leaf by source tolerance. Rows use

```text
1e-7 * max(1, abs(bound), sum(abs(a(i) * x(i))))
```

and column bounds use `1e-7 * max(1, abs(bound))`. Nonfinite row activity or
magnitude is checked again exactly against this scaled tolerance. The separate LP
gap test uses lowered objective units and accounts for reduced-cost movement, row
residuals and objective rounding. The rendered objective uses source decimal
coefficients at the returned point.

`TOLERANCE_OPTIMUM` differs from `ATTAINED_OPTIMUM`. A tolerance-accepted float
point is not an exact witness. Optimum publication requires no
`toleranceDifference`; infeasibility and unboundedness require `sourceExact`.
`--exact` requires exact source certificates. A hybrid lowering that differs from
the source is replaced by the exact source adapter for arithmetic execution.
Exact adapter witnesses require original decimal feasibility. Working-model
witnesses on the default finite LP lane retain the separate tolerance guarantee.

## Routing and result strength

Default purely continuous MPS sources use the finite LP lane, including fractional
objectives and its tolerance guarantee. When hybrid lowering differs from a source
with integer columns, or an explicit exact request, the frontend prepares original
decimal source rows before routing or bound closure. The shared exact linear route
owns those rows for both bounded and open mixed models. A rounded-model routing
refutation cannot establish original-source infeasibility.

BACKTRACK and FIXED run the admitted complete arithmetic traversal. LS and ALNS
search exact-coordinate neighborhoods with source-checked incumbent authority;
their conditional refutations cannot refute the original source. MIXED combines
that complete arm and the heuristic arms under the existing shared portfolio.
Continuous coordinates remain arithmetic-owned, and heuristic integer windows
do not restrict original source proof scope.

Integral objective descent retains exact bound and recession checks. Supported
Boolean or continuous linear objectives also admit strict-improvement theory rounds
beside heuristic repair. Completed refutations and attained optima retain source
proof authority; terminating optimization is not guaranteed for every admitted
source. Rational feasibility descent has no recession certificate and can return
an incumbent with an incomplete reason on an unbounded objective. Numerical or
arithmetic representation declines withhold terminal claims.

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
