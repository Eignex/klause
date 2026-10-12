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
`--exact` requires exact certificates and declines a lowering that differs from
the source. Exact source witnesses use exact decimal checks when correspondence
holds, and decimal tolerance otherwise.

## Routing and result strength

Purely continuous MPS sources use the finite LP lane, including fractional objectives.
Source-exact open mixed models use a supported complete theory when available.
Integral objective descent retains exact bound and recession checks. Supported
Boolean or continuous linear objectives also admit strict-improvement theory rounds
beside heuristic repair. Completed refutations and attained optima retain source
proof authority; terminating optimization is not guaranteed for every admitted
source. Source-inexact lowerings retain witness-only execution.

Default open models whose lowering differs from the source also use witness search.
Before publication, their arbitrary-width integer and rational real coordinates are
checked against original decimal rows, indicators and bounds with the source tolerance;
integer integrality remains exact. Their objective is recomputed from source decimals.
Rejected points are withheld and establish no refutation. These routes publish no
optimum, infeasibility or unboundedness claim, even when the lowered model was decided.
The source difference is reported beside the result. `--exact` retains its source
correspondence requirement. Check routing and source qualifications before attributing
a missing result to the simplex.

### Paired strategy coverage gap

The source-inexact open fallback executes witness search under MIXED or LS, while
BACKTRACK/FIXED return Unsupported without traversing the source. This does not
provide the complete-search/local-search pair required by the
[vision](../vision.md#shared-model-breadth). The missing source-preserving arm is
tracked in [#2654](https://github.com/Eignex/klause/issues/2654). Withholding claims
proved only about a rounded lowering remains required; it does not make this
strategy exception part of the intended support contract.

See [MpsCompiler](../../klause/src/commonMain/kotlin/com/eignex/klause/formats/mps/MpsCompiler.kt)
and [MpsMode](../../klause-cli/src/commonMain/kotlin/com/eignex/klause/cli/MpsMode.kt).
