# 8. MPS source semantics

The formats so far were finite or, for FlatZinc, used binary64 floats. MPS is
the standard file format for linear and mixed-integer programs: linear rows,
column bounds, integrality markers and one linear objective, written as decimal
numbers. Klause keeps those decimals as the source authority even when a numerical
working model restates them in binary64, so an MPS result always says whether it
satisfies the file exactly or only within a published feasibility tolerance.

The chapter covers how a file is read, the declared policy, how the source is kept
beside its numerical working view, what a tolerance-accepted result may claim, and
how routing produces results of different strength. The numerical evidence itself
belongs to [LP certification](27-lp-certification.md).

## Reading MPS files

`Mps.parse` reads the standard sections: `NAME`, `OBJSENSE`, `ROWS`, `COLUMNS`,
`RHS`, `RANGES`, `BOUNDS`, `INDICATORS` and `ENDATA`. Fields are whitespace
separated, so fixed-column files whose names contain spaces are not supported.
Row types are `N`, `L`, `G` and `E`; the first `N` row is the objective and later
free rows are ignored. `LAZYCONS` rows are ordinary constraints, `USERCUTS` are
skipped, and anything after `ENDATA` is ignored.

Columns between `'MARKER'` `INTORG` and `INTEND` lines are integers. `BOUNDS` accepts
`UP`, `LO`, `FX`, `FR`, `MI`, `PL`, `BV`, `UI` and `LI`. `INDICATORS` lines read
`IF <row> <column> <0|1>`: the row holds when the binary column takes that value.
Numbers are parsed as exact rationals; a decimal scale beyond 10^5 in magnitude is
rejected. Malformed input raises `MpsFormatException`.

## Declared policy

`MpsModel.semantics` captures source defaults before lowering. Parsed models declare
`DECIMAL_LITERALS`; programmatic models read supplied doubles as exact binary64
literals. Float grid and optional-value overrides do not apply to MPS.

Wholly unranged integer columns default to `[0,1]` and wholly unranged real columns
to `[0,+infinity)`; overrides replace either endpoint independently. A column with
any explicit `BOUNDS` record keeps its complete source range. Lowering and source
validation share one captured declaration snapshot, and results under replaced
defaults identify that effective scope rather than claiming the file's own
defaults.

MPS defaults to `SourceTolerance(absolute=1/10000000, relative=1/10000000)`. Each
component can be replaced independently, including by zero, which requires exact
checks and certificates. `--exact` sets the tolerance to zero. The CLI captures one
[linear source snapshot](#source-correspondence) and registers it with the common
invocation, which owns preparation, validation and result qualification. Slack and
rendered objectives stay in source units when working rows are scaled.

## Source correspondence

`MpsModel.toLinearSourceProblem` captures original rational declarations, row sides,
source terms, integer indicators and the affine objective before engine
normalization. `MpsCompiled` keeps that representation and delegates point checks
and tolerance qualification to the shared validator;
`LinearSourceProblem.checkMpsWitness` exposes the same checker. Both `toProblem` and
`toExactSourceProblem` delegate to the shared linear lowerer once and build no
engine matrices themselves.

The lowered LP may restate continuous coefficients in binary64, and `sourceExact`
records whether that representation corresponds exactly. The shared lowerer proves
integral affine definitions from unconditional equalities over bounded integers and
gives such continuous columns finite integer backings; fractional and guarded
definitions stay continuous. Column names, witness values and objective units are
preserved across the mapping, and integer rows keep exact authority beyond `2^53`.

`toExactSourceProblem` keeps parsed decimals in native `ExactLinear` rows, clearing
denominators without rounding. Real bounds become exact rows and integer columns
keep integrality. `LinearIndicator` keeps each trigger and enabled comparison;
numerical rows enabled by a finite pin retain that premise, so retraction removes
their authority. Objectives over integer backings use an exact positive multiplier
when it fits, and an auxiliary equality otherwise; rendering undoes either exactly.
Wide coefficients admit exact positive row normalization for the numerical LP, and
nonfinite or underflowing projections decline numerical execution without
switching to an arithmetic theory. An infeasible constant row stays an explicit
contradiction.

## Tolerance publication

Witness checks use the original decimals with exact rational arithmetic. Rows
permit `max(absolute, relative * max(abs(bound), sum(abs(a(i) * x(i)))))` and
column bounds `max(absolute, relative * abs(bound))`; integrality stays exact.
`MpsSourceWitness.exact` records whether the accepted point satisfies every row and
bound without slack. Cancellation withholds the point.

For a checked point with at most 4,096 coordinates, rendering may snap residuals
within `1e-12` of zero to zero when the result is integral, fits `Long`, passes
every original check and keeps the same source objective; otherwise the original
float witness is kept. This changes no search or optimization authority.

`TOLERANCE_OPTIMUM` differs from `ATTAINED_OPTIMUM`. A tolerance-accepted point need
not be exact, and positive slack can enlarge the feasible set even when every row
is represented exactly. A nominal refutation or optimum therefore needs a checked
transfer to the accepted set. `MpsCompiled.exactClaimsCoverTolerance` checks that
the two sets coincide, using integer rounding, row activity lattices and empty
accepted intervals. `MpsCompiled.toleranceObjectiveBound` supplies an independent
exact bound from accepted declarations; an accepted point attaining it establishes
an attained optimum.

Without a transfer, CLI output keeps accepted points and source objectives, and
scopes native proof metadata as `nominalSource…` or `workingModel…` keys. An
untransferred nominal refutation prints `UNKNOWN` with its proof scope. Optimum
output reports `optimizationStatus=attained-optimum` or `tolerance-optimum` only for
its covered set; attainment requires a certified rational point and a matching
bound.

## Routing and result strength

MPS selects numerical execution independently of certification policy. Purely
continuous sources use the finite LP lane with its tolerance guarantee. Bounded
integer sources keep finite search with numerical completion, and numerical bound
closure can prove missing integer sides. The prepared plan keeps original
declarations and objective direction separate from derived bounds and the engine's
minimized objective.

Open integer declarations keep their missing sides and use LP relaxations with
exhaustive integer splits: each fractional endpoint splits at its floor, and a
branch optimum or refutation counts only once every sibling has certified
coverage. A source-valid improving ray needs an integral feasible base and an
integral recession step, so an unbounded relaxation alone never proves MIP
unboundedness. Unresolved indicator triggers split into the regions below, at and
above their integer value. Representation, resource or certification limits return
an incomplete result with any checked incumbent and root bound; they never fall
back to an open theory. Open numerical execution has no heuristic worker, so LS
and ALNS return unsupported there.

Every candidate is checked against original rows, bounds, indicators and
integrality before publication, with its objective recomputed from original
numbers. A rejected final witness cannot promote an earlier point to an optimum,
and a source-inexact proposal supplies no refutation, bound or optimum authority.
See [MpsCompiler](../klause/src/commonMain/kotlin/com/eignex/klause/formats/mps/MpsCompiler.kt)
and [MpsMode](../klause-cli/src/commonMain/kotlin/com/eignex/klause/cli/MpsMode.kt).

---

Previous: [7. FlatZinc](07-flatzinc.md) · [Contents](README.md) · Next: [9. SMT-LIB source semantics](09-smtlib.md)
