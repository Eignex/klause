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
contract. Float grids and optional-value policies are inapplicable to MPS and reject overrides.

Wholly unranged marker integers default to `[0,1]`; wholly unranged real columns
default to `[0,+infinity)`. Typed declaration-default overrides replace either endpoint
independently before lowering. A declaration with any explicit BOUNDS record retains
its complete source range, including omitted or explicitly open sides. Programmatic
variable ranges are explicit. Copies preserve parsed range origins for retained
declarations; replacement variables carry their supplied ranges.

Lowering and source validation share one captured effective declaration snapshot.
Caller finite endpoints retain literal authority independently of the MPS text
infinity convention. Signed-word integer extrema remain finite. Rational endpoints
that binary64 cannot represent exactly use exact source rows; a missing numerical
projection cannot erase the bound. The parsed model and declared defaults remain
available for separate original-file checking. `sourceExact` describes correspondence
to the effective snapshot. CLI results under replaced defaults identify the effective
source scope, including witnesses, optimum and refutation status; they do not claim
authority under the original file's declaration defaults.

MPS defaults to `SourceTolerance(absolute=1/10000000, relative=1/10000000)`.
Each component can be replaced independently, including zero. Zero tolerance requires
exact source checks and certificates. The compatibility `toExactSourceProblem` entry
defaults to zero; explicit overrides retain their resolved policy.
Source representation and the permitted feasibility slack are separate properties.

The CLI loader captures shared typed caller overrides in one [linear source snapshot](#source-correspondence).
It registers one source attachment with the common CLI invocation, which owns preparation,
engine controls, source validation and result qualification. The format renderer receives
checked original declaration-order coordinates and their original rational objective;
it does not compile a second source model or retry routing. `--exact`
replaces the feasibility tolerance with zero while preserving other fields for
applicability checking. Finite leaf checks and numerical source publication use
that captured policy throughout the loaded source's lifetime. Absolute slack and
rendered objective values remain in source units when working rows or costs are scaled.

## Source correspondence

`MpsModel.toLinearSourceProblem` captures original rational declarations, row sides,
separate source terms, integer indicators and the affine objective before engine
normalization. Caller declaration defaults and feasibility tolerance resolve once
for that snapshot. Cancellation withholds an interrupted capture. Its copied term
containers retain their numbers when the parsed model's coefficient arrays change.
`MpsCompiled` retains that same neutral representation for its witness helpers and
delegates original-unit point checks and tolerance qualification to the shared
validator and evidence mechanisms used by source setup.
`LinearSourceProblem.checkMpsWitness` exposes that same point checker for a captured
MPS source, returning no witness when the candidate is rejected or checking stops.
Each compatibility compilation captures this source once and delegates to the shared
linear lowerer once. `toProblem` preserves the captured policy and
`toExactSourceProblem` requests exact rows; neither entry retries another compiler.

MPS lowering returns an engine-neutral `Problem` with its source columns, numbers
and effective semantics retained for validation. The model-facing LP layer assembles
numerical models and certificates from factor views. The format constructs no
engine matrices, shifted columns, slack columns or engine-specific premise records.

The MPS parser retains decimal source metadata, integer markers, bounds and
objective sense. The lowered LP may restate continuous coefficients in binary64.
The shared lowerer proves integral affine definitions from unconditional equalities
over bounded integer coordinates. Such continuous declarations receive finite integer
backings, including chains of proved definitions. Fractional and guarded definitions
remain continuous. Original declaration kinds and order remain distinct from working
coordinate kinds and private auxiliaries. Source column names, checked witness values
and objective units are preserved across that mapping.
`sourceExact` records representation correspondence. A working approximation does not
replace the original numbers used for tolerance validation. Integer rows and bounds
retain exact authority beyond `2^53`; oversized objectives can use an auxiliary
defining row.

`MpsModel.toProblem` uses the same shared linear working view as source setup. Mixed
sources and indicated rows retain exact comparisons and their source implication;
purely continuous numerical projections retain explicit correspondence to the source.
The format owns no row encoding, affine-definition inference or alternate model builder.
Explicit signed-zero bound literals are restored only on zero-valued bounds in the
fresh compatibility view, preserving the rational bound and its captured feasibility policy.

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
The compatibility exact entry delegates canonical-source compilation to the shared
linear lowering. Objectives over integer backings use an exact positive multiplier when
their working coefficients fit signed words; other objectives use an auxiliary
equality retaining original coefficients and constant. The multiplier need not be
a power of ten. Rendered objectives undo it exactly. The returned column mapping omits this auxiliary and
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

For a checked MPS point with at most 4096 source coordinates, rendering can try
replacing residuals within `1e-12` of zero by zero when the remaining real values
are integral and fit `Long`. Optional verification admits at most 32768 column,
row and coefficient entries. The candidate must pass every original decimal row,
bound and integrality check, and its source objective must equal the unchanged
float point's source objective. A failed check retains the original float witness.
The replacement uses the captured original source independently of the working
view's numeric correspondence. This output-only reconstruction changes no search assignment, cutoff or
optimization-status authority.

`TOLERANCE_OPTIMUM` differs from `ATTAINED_OPTIMUM`. A tolerance-accepted float
point need not be an exact witness. Positive slack can also enlarge the feasible
set even when every nominal row is represented exactly. A nominal refutation or
optimum requires a checked transfer to the tolerance-accepted set; `sourceExact`
and exact point feasibility alone supply no such transfer.

`MpsCompiled.exactClaimsCoverTolerance` checks feasible-set equivalence under the
captured policy. Zero policy preserves the set directly. Positive policy checks
integer declaration rounding, exact row activity lattices, redundant rows and
empty accepted intervals. Enabled one-term rows refine accepted bounds using
original coefficient and RHS units; an indicator contributes only when its exact
trigger is established. Relative slack includes absolute term activities before
cancellation. Interrupted or unsupported equivalence checks supply no transfer.
The mechanism lives in solver evidence; the frontend supplies original source
numbers independently of normalized working rows.

`MpsCompiled.toleranceObjectiveBound` supplies an independent exact bound from
accepted declarations and enabled one-term rows, lower for minimization and upper
for maximization. A checked accepted point attaining that bound establishes an
attained optimum, including when nominal and accepted feasible sets differ.
A required open endpoint or interruption withholds the bound. These checks use the
containing preparation cancellation and work ledger.

CLI output retains accepted points and source objective values when the native
optimum lacks a transfer. Native proof metadata remains scoped as
`nominalSourceOptimizationStatus` and `nominalSourceLpRootBound` for an exact
nominal model, or corresponding `workingModel` keys for an inexact projection.
The root bound retains its objective scale. Such metadata does not assert a bound
over the expanded accepted set. An untransferred nominal refutation prints
`UNKNOWN` with its proof scope. Valid transferred claims retain their terminal
status; an exact nominal feasible base and ray remain valid in the accepted
superset. Optimum output identifies `optimizationStatus=attained-optimum` or
`optimizationStatus=tolerance-optimum` only for its covered accepted set.

`--exact` requires exact source certificates. Shared setup derives an exact working
view from the retained source under that zero-tolerance policy.
Output comments identify exact source feasibility or tolerance acceptance
independently of optimization authority. A float point retains tolerance optimum
qualification even when its feasibility is exact; attainment requires a certified
rational point and matching source bound.

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
