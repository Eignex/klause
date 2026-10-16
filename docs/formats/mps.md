# MPS source semantics

MPS supplies linear constraints, bounds, integrality and an objective. Klause
retains decimal source authority even when a numerical working model uses
binary64. Exact and tolerance-based publication have distinct guarantees.

[Shared input semantics](../formats.md) owns routing; [LP certification](../lp/certification.md)
owns numerical evidence. This page defines source correspondence, tolerances and
the claims each route may publish.

## Declared policy

`MpsModel.semantics` captures source defaults before lowering. Parsed models declare
`DECIMAL_LITERALS`; programmatic models interpret supplied doubles as exact binary64
literals. `toProblem(settings, semanticOverrides)` resolves caller policy before
constructing a model. `MpsCompiled.semantics` and its `Problem` retain the same effective
contract. Float grids, default undeclared ranges and optional-value policies are
inapplicable to MPS and reject overrides.

MPS defaults to `SourceTolerance(absolute=1/10000000, relative=1/10000000)`.
Each component can be replaced independently, including zero. Zero tolerance requires
exact source correspondence; inexact hybrid projections are rebuilt from source rows.
The compatibility `toExactSourceProblem` entry defaults to zero;
its explicit overrides and internal exact-row fallback retain their resolved policy.
Source representation and the permitted feasibility slack are separate properties.

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

A hybrid lowering refusal retains the exact source adapter for numerical admission.
An infeasible constant row remains an explicit contradiction for the solver.
Numerical admission checks original decimal rows even when the default continuous
working projection retains tolerance semantics. A nonzero source term that cannot
project finitely declines before numerical execution; the exact source remains
available for result qualification without selecting another execution route.

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
its source witness check uses the captured effective feasibility tolerance.
The compatibility entry defaults to zero tolerance. Both loading entry points use the same factor
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

MPS witness checks use original source decimals with exact rational arithmetic,
including finite binary64 candidate coordinates. Rows permit
`max(absolute, relative * max(abs(bound), sum(abs(a(i) * x(i)))))`;
column bounds permit `max(absolute, relative * abs(bound))`. Integrality remains
exact. The float and rational witness APIs apply the same effective policy,
including zero and overflow/cancellation of floating term activities. Cooperative
cancellation withholds the point; a partial check establishes no witness.
`MpsSourceWitness.exact` records whether the accepted point satisfies every row and
bound without slack, independently of `sourceExact` and the configured tolerance.

The separate LP
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
the source is replaced by the exact source adapter for numerical execution.
Exact adapter witnesses require original decimal feasibility. Working-model
witnesses on the default finite LP lane retain the separate tolerance guarantee.
Output comments identify actual exact original-source feasibility or tolerance
acceptance independently of the optimization status. An exact point alone does not
upgrade an inexact working-model optimum to an attained source optimum. A float
point retains tolerance optimum qualification even when its feasibility is exact;
attainment requires the certified rational point and matching source bound.
Optimal output includes `optimizationStatus=attained-optimum` or
`optimizationStatus=tolerance-optimum` for that accepted witness guarantee.

## Routing and result strength

MPS selects numerical execution independently of certification policy. Default
purely continuous sources use the finite LP lane and its tolerance guarantee.
Supported bounded integer sources retain the existing finite search and numerical
completion. Numerical bound closure can also prove every missing integer side,
including rows enabled by a source-fixed indicator. The prepared plan retains
original declarations independently of those derived bounds. Finite CLI execution
uses that same prepared plan and component ownership. Its original objective and
direction remain separate from the engine's minimized objective. An immutable execution policy also keeps finite LS and ALNS workers on
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

The direct numerical traversal requires a complete affine source capture and
retains native integer indicators as conditional source rows. An unresolved trigger
can split into the regions below, at and above its exact integer value through
adjacent exhaustive alternatives. No inactive region is clamped away. Bounds use
only rows whose original guards are established in that branch; source witnesses
still check every enabled original row. Recession capture requires fixed integer
guards. Other unsupported guarded relations explicitly decline. Supported finite
indicator models retain their numerical completion. LS and ALNS requests
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
