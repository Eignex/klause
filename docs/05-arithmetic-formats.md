# 5. MPS and SMT-LIB

MPS describes linear and mixed-integer programs with decimal coefficients; SMT-LIB
describes formulas over exact, potentially unbounded arithmetic. Both preserve the
original numbers and declarations independently of the execution representation.

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
The trigger must equal zero or one as an exact rational.
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

With positive feasibility tolerance, completed native optimization and refutation
report `OPTIMUM FOUND` and `UNSATISFIABLE` under numerical certification policy.
They do not require a separate proof over the feasible set enlarged by tolerance.
The final optimizing point must pass original-source checks, its objective must
agree with the native value within captured source slack, and the native terminal
must cover the unrestricted model. Interrupted search, rejected terminal witnesses
and restricted acceptance filters cannot supply these native completion claims.

`optimizationStatus=tolerance-optimum` identifies numerical optimality. An exact
point alone does not establish an exact optimum. `attained-optimum` requires an
exact source bound attained by the point, including an independently checked bound
over the accepted set. `LinearSourceOutcome.numericalClaims` records whether terminal
statuses use numerical tolerance policy, and output identifies that scope.

Zero tolerance, including `--exact`, requires exact original-source certification.
Native proof metadata retains its nominal-source or working-model scope when no
qualified claim is available. Unboundedness requires a checked source recession
proof under either policy. Original decimal objectives and caller declaration
scope remain visible in both modes.

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
back to an open theory. Numerical LS and ALNS use
[joint linear repair](11-neighborhoods.md#joint-linear-repair), publishing checked
incumbents without global bounds or terminal claims.

Every candidate is checked against original rows, bounds, indicators and
integrality before publication, with its objective recomputed from original
numbers. A rejected final witness cannot promote an earlier point to an optimum,
and a source-inexact proposal supplies no refutation, bound or optimum authority.
See [MpsCompiler](../klause/src/commonMain/kotlin/com/eignex/klause/formats/mps/MpsCompiler.kt)
and [MpsMode](../klause-cli/src/commonMain/kotlin/com/eignex/klause/cli/MpsMode.kt).

### Linear traversal diagnostics

`SourceSetupRequest.linearTraversal=NUMERICAL` selects the numerical LP/MIP owner
on the same linear source snapshot, including bounded integer declarations. The
CLI exposes this diagnostic as `--param linear-traversal=numerical`; `automatic`
retains normal routing. This choice is independent of the finite worker recipe
selected by `engine`, keeps the original tolerance and objective, and supplies no
finite or arithmetic-theory fallback. Numerical traversal retains sibling coverage
and withholds terminals for interrupted or uncertified leaves.

Automatic routing retains its finite recipes. Eligible sequential mixed optimization pools append
bounded numerical frontier and joint-repair workers; [portfolios](12-portfolio.md)
defines their admission and proof transport. `FINITE_COMPANIONS`
(`--param linear-traversal=finite-companions`) explicitly selects the same policy.
Other pools retain finite execution, and open declarations retain the numerical fallback.

`NUMERICAL_FRONTIER`, exposed as `--param linear-traversal=numerical-frontier`,
uses a retained bound-ordered alternative queue. The same source checking and
tolerance contracts apply to its shared-search and portfolio consumers.

## SMT-LIB source semantics

The SMT-LIB frontend preserves declarations, formulas, names and objective direction.
Its literals are exact rationals and its integers and reals are open unless the
source or an applicable semantic override constrains them. Shared setup selects the
arithmetic mechanism.

### Supported models

`SmtLib.parse` returns one `SmtLibProblem` with a `SourceAttachment`, declared name
maps and the original objective and direction. Callers pass
`SmtLibProblem.source` to [source setup](19-api.md#source-setup), as the CLI does;
the frontend never classifies a source by the solver it will need. Interruption
throws `CancellationException` before any source is published.

The frontend admits quantifier-free linear integer and real arithmetic with Boolean
structure and supported `minimize`/`maximize` objectives, polynomial Int/Real/Bool
satisfaction with products and fixed nonnegative integral powers, and a real-function
extension with unary `exp`, `log`, `sin`, `cos` and the constant `pi`. One
`check-sat` or `check-sat-assuming` query is accepted, with Boolean literal
assumptions conjoined to the assertions; `get-model` and `get-objectives` are
accepted requests. Bitvectors, arrays, strings, quantifiers, uninterpreted
functions, symbolic or zero division, negative or noninteger powers, nonlinear
integer `div`/`mod`/`to_int`, multiple or nonquadratic objectives, incremental
commands, multiple queries and `get-value` decline explicitly, even inside a
constant Boolean branch.

### Declaration policy

`smtLibSemantics` declares exact rational literals, continuous reals and open
integer and real declarations. Endpoint overrides constrain every original
declaration of that sort, including unused ones; assertions remain additional
constraints, and an explicit open replacement never removes an asserted
inequality. Linear factors keep real endpoints as exact comparisons and snapshots
keep them as formula atoms. Grid, tolerance and absent-value overrides are
rejected.

### Lexical scope and refusals

Admission checks declarations and expression sorts with an iterative lexical type
walk. Integer-to-real promotion is allowed; Boolean/numeric and real-to-integer
coercions are rejected. Logic declarations do not choose a representation. Quoted
and bare spellings of a symbol share one identity, and a negative numeral must be
written `(- n)`. A semicolon starts a line comment even when it directly follows a
bare token.

Parallel `let` values read the enclosing scope. Typed nonrecursive `define-fun`
macros capture their definition scope and check argument sorts even for unused
arguments; recursive helpers decline. Assertions see only the declarations visible
at their command. The binding tables are discarded after lowering.

### Linear and conditional lowering

Boolean lowering folds constants and literal identities in AND, OR and IFF, so
empty, duplicate and complementary operands keep their meaning without extra
gates. Comparison literals are shared within a parse only when their folded exact
rows agree completely.

Real and mixed comparisons retain `ExactLinear` rows with exact coefficients,
strictness and Boolean reification, without frontend digit columns; numerical
projections may decline values they cannot represent. Integer-only rational
comparisons keep exact denominator-cleared thresholds.

Integer conditionals (`ite` and decision lists) retain native `Element` relations
over branch values when finite, sharing one choice index across wide digit columns.
A result with an open bound instead keeps guarded exact equalities to its branches.
Affine real conditionals retain `RealElement`. Comparisons against a conditional
can lower to Boolean tests of its branches when the branch images are small, and
definitions no surviving constraint needs are dropped after a dependency fixpoint.
For up to eight Boolean inputs, a scalar defined by consistent, complete cases also
supplies a verified affine row beside the source factors.

The exact real component can certify supported difference fragments with a graph;
see [real graph admission](14-theories.md#real-graph-admission-and-checks).

### Integer division

Constant-divisor `div` and `mod` retain a shared Euclidean `DivMod`, with
`0 <= remainder < abs(divisor)`, including negative divisors and arbitrary-width
constants; wide digit expressions keep the affine `WideDivMod`. Open sides stay open,
and variable or zero divisors are unsupported (see
[integer division relations](02-model.md#integer-division-relations)).
Integer absolute values retain `Abs`, or `AffineAbs` for wide affine values.

### Polynomial source lowering

Bounded representable products keep their factor form: Int × Int uses `Product`,
Int × Real `RealProduct` and Real × Real `ContinuousProduct`. Nonlinear lowering
first closes integer bounds from exact unconditional linear premises; compound
integer operands receive exact defining equalities, and bounded fixed powers above
one retain `Power`. Scalar multiplication stays linear. Refuted integer premises
retain an explicit contradiction for shared routing after full source validation;
they do not trigger nonlinear fallback. Open or overflowing integers keep typed arbitrary-precision
coordinates for [nonlinear integer execution](16-nonlinear-arithmetic.md), and finite
mixed products compose through [exact polynomial completion](16-nonlinear-arithmetic.md#shared-model-composition).
A source outside the factor representation keeps an exact expression snapshot with
its original names; the same attachment carries factor sources and the exact real,
integer-polynomial and function snapshots.

`ir.PolynomialExpression` retains exact rational leaves, source real identities,
sums, products and nonnegative integer powers, and `ir.PolynomialRealFormula` adds
Boolean structure over polynomial relations. Normalization belongs to the
polynomial kernel; structural equality describes the owned graph, not algebraic
equivalence. `VarRemap` preserves coefficients, relations and polarity, and
removing a referenced variable declines.

Numeric conditionals keep a selection relation with their guard and ordered
branches; `distinct` keeps its operands as `AllDifferent` for bare integers or
affine `Distinct` otherwise. Objectives keep affine metadata when representable,
exact quadratic metadata otherwise, and their whole expression for function
objectives.

`exp` is the real exponential, `log` the natural logarithm on strictly positive
arguments, and `sin` and `cos` use radians. Every atom must have defined terms, so
negation cannot make an invalid `log` term satisfiable, and domain obligations stay
inside their conditional branch. There is no delta or tolerance mode. Function
constraints run through traversal, LS, ALNS and portfolios with original-source
checks; function objectives return UNKNOWN. See the
[transcendental theory](16-nonlinear-arithmetic.md#transcendental-real-arithmetic).

### Results and objectives

The CLI renders exact rational values, algebraic `(root-obj p k)` values and
symbolic function values in original coordinates; private auxiliaries are never
printed. Closed transcendental coordinates use `((_ klause-real exp) value)`,
`((_ klause-real log) value)`, `((_ klause-real sin) value)`,
`((_ klause-real cos) value)` and `((_ klause-real pi))`. These constructors have
fixed mathematical meanings independent of input helper names, and a decimal
approximation never replaces their authority.

Optimization emits `; objective=<value>` for each published incumbent and
`; optimizationStatus=<status>` at completion (`optimal`, `best-found`, `unbounded`),
without needing `-s`; `sat` alone never claims optimality. Incumbents stream as they
are accepted, each with its complete model. Exact objectives print as
arbitrary-precision integers or reduced `numerator/denominator` text. Linear
integer optimization uses the complete theory route when its objective range
exceeds binary64's consecutive integers.

Quadratic objectives report a separate `objectiveBound`, `objectiveBoundDirection`
and `objectiveBoundStrict` when a global bound is checked. An unattained boundary
reports `optimizationStatus=unattained-infimum` or `unattained-supremum` beside a
feasible incumbent, and is never printed as a model. Local search and ALNS report
`best-found`.

Open integers keep arbitrary-precision bounds, and a relaxation witness never
establishes integrality: shared search splits fractional integers at exact
thresholds. Strict real inequalities need an interior witness or an exact
strict-feasibility refutation. Budgets can leave a solve unknown.

---

Previous: [4. Finite formats and FlatZinc](04-finite-formats.md) · [Contents](README.md) · Next: [6. Sessions and resources](06-sessions.md)
