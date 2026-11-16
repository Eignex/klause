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

MPS defaults to `SourceTolerance(absolute=1/10000000)`: an absolute slack with no
relative component. Each component can be replaced independently. Zero in both requires
exact checks and certificates, including exact finite LP certification; a positive
component makes finite LP deductions numerical. `--exact` sets the tolerance to zero.
The CLI captures one
[linear source snapshot](#source-correspondence) and registers it with the common
invocation, which owns preparation, validation and result qualification. Slack and
rendered objectives stay in source units when working rows are scaled.

## Source correspondence

`MpsModel.toLinearSourceProblem` captures original rational declarations, row sides,
source terms, integer indicators and the affine objective before engine
normalization. `LinearSourceProblem.checkMpsWitness` checks points against it with the
shared validator, and `toleranceQualification` qualifies its tolerance. `toProblem`
delegates to the shared linear lowerer once and builds no engine matrices itself.

The lowered LP may restate continuous coefficients in binary64, and `sourceExact`
records whether that representation corresponds exactly. The shared lowerer proves
integral affine definitions from unconditional equalities over bounded integers and
gives such continuous columns finite integer backings; fractional and guarded
definitions stay continuous. Column names, witness values and objective units are
preserved across the mapping, and integer rows keep exact authority beyond `2^53`.

Under zero feasibility tolerance, `toProblem` keeps parsed decimals in native
`ExactLinear` rows, clearing denominators without rounding. Real bounds become exact
rows and integer columns keep integrality. `LinearIndicator` keeps each trigger and
enabled comparison; numerical rows enabled by a finite pin retain that premise, so
retraction removes their authority. Objectives over integer backings use an exact
positive multiplier when it fits, and an auxiliary equality otherwise; rendering
undoes either exactly.
Wide coefficients admit exact positive row normalization for the numerical LP, and
a source with a nonfinite or underflowing projection declines before routing, without
switching to an arithmetic theory. An infeasible constant row stays an explicit
contradiction.

## Tolerance publication

Witness checks use the original decimals with exact rational arithmetic. Rows
permit `max(absolute, relative * max(abs(bound), sum(abs(a(i) * x(i)))))` and
column bounds `max(absolute, relative * abs(bound))`; integrality stays exact.
Under the default policy both reduce to the absolute slack, so a row whose terms
are large but cancel is held to the same slack as a row of small terms; a relative
component makes the slack grow with that scale. `LinearSourceWitness.exact` records whether the accepted point satisfies every row and
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
over the accepted set; a native finite optimum supplies one only under exact LP
certification. `LinearSourceOutcome.numericalClaims` records whether terminal statuses
use numerical tolerance policy, and output identifies that scope.

Zero tolerance, including `--exact`, requires exact original-source certification.
Native proof metadata retains its nominal-source or working-model scope when no
qualified claim is available. Unboundedness requires a checked source recession
proof under either policy. Original decimal objectives and caller declaration
scope remain visible in both modes.

## Routing and result strength

MPS lowers its captured source to the same factor model other sources use and
takes the shared route; the linear source stays attached to qualify claims in
original units. Purely continuous sources use the finite LP lane with its
tolerance guarantee. Bounded integer sources keep finite search with numerical
completion, and numerical bound closure can prove missing integer sides. The
prepared plan keeps original declarations and objective direction separate from
derived bounds and the engine's minimized objective.

Open integer declarations keep their missing sides and go to the exact linear
theory. An optimization runs its LP authority first, with exhaustive integer
splits: each fractional endpoint splits at its floor, and a branch optimum or
refutation counts only once every sibling has certified coverage. A source-valid
improving ray needs an integral feasible base and an integral recession step, so
an unbounded relaxation alone never proves MIP unboundedness. Unresolved indicator
triggers split into the regions below, at and above their integer value. When the
authority declines, the theory keeps searching for checked incumbents but makes no
terminal claim. Satisfaction goes to the theory directly. Explicit LS and ALNS use the open theory's exact-coordinate
neighborhoods, publishing checked incumbents without global bounds or terminal claims.

Every candidate is checked against original rows, bounds, indicators and
integrality before publication, with its objective recomputed from original
numbers. A rejected final witness cannot promote an earlier point to an optimum,
and a source-inexact proposal supplies no refutation, bound or optimum authority.
See [PreparedLinearSource](../klause/src/commonMain/kotlin/com/eignex/klause/solver/pipeline/PreparedLinearSource.kt)
and [MpsMode](../klause-cli/src/commonMain/kotlin/com/eignex/klause/cli/MpsMode.kt).

Sequential mixed optimization pools over a linear source append bounded numerical
frontier and joint-repair workers; [portfolios](12-portfolio.md) defines their
admission and proof transport.

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

The frontend admits ground free sorts and functions, quantifier-free bitvectors,
and linear integer and real arithmetic with Boolean structure and supported
`minimize`/`maximize` objectives, polynomial Int/Real/Bool satisfaction with products,
integer exponentiation and fixed nonnegative real powers, and a real-function
extension with unary `exp`, `log`, `sin`, `cos` and the constant `pi`. One
`check-sat` or `check-sat-assuming` query is accepted, with Boolean literal
assumptions conjoined to the assertions; `get-model` and `get-objectives` are
accepted requests. Ground extensional arrays compose with admitted scalar sorts,
including nested arrays and arrays used as indexes or function arguments and results.
Strings, quantifiers, negative or noninteger real powers, multiple or nonquadratic objectives, incremental
commands, multiple queries and `get-value` decline explicitly, even inside a
constant Boolean branch.

### Bitvector lowering

Bitvector semantics follow SMT-LIB 2.7's
[FixedSizeBitVectors revision of 2025-09-09](https://smt-lib.org/theories-FixedSizeBitVectors.shtml)
and [QF_BV extensions revision of 2025-02-25](https://smt-lib.org/logics-all.shtml#QF_BV).
Fixed-width bitvectors retain their declared positive width as Boolean coordinates,
with no machine-word packing. Bitwise operators, modular arithmetic, comparisons,
division, signed remainder and modulo, symbolic shifts and overflow predicates lower
to shared Boolean circuits. Concatenation, extraction, extension, repetition and
indexed rotations preserve the source bit order. Division by zero and shifts by at
least the word width retain the SMT-LIB values.

`ubv_to_int` and `sbv_to_int` use exact weighted Boolean sums; `int_to_bv` channels
`x = unsigned(bits) + 2^width*k` with a fresh open integer quotient `k`. The source
integer remains open.
Private bits reconstruct width-correct source binary values, and an independent
arbitrary-precision evaluator checks each original bitvector operation against its
encoded inputs and output before a reconstructed model is published. Circuit width,
gate and work budgets stop admission cleanly, including constant-folded operations.

### Ground functions and type parameters

Free sort constructors, sort aliases and typed ground functions are resolved before
scalar lowering. Each ground application receives a private result and captured
arguments. Pairwise congruence requires equal results whenever evaluated arguments
are equal, including exact arithmetic arguments. Only free sort identities receive
finite representative domains; interpreted arithmetic domains remain open. Model
recipes restore original ranks, typed domain values, function entries and total
defaults while public scalar maps retain original scalar sorts.

Global sort parameters retain their universal source obligations. Necessary ground
instances can refute them; finite instance satisfaction alone supplies no SAT
certificate. Boolean schema tautologies admit interpretation-independent checking.
Pure free-function, equality and Boolean schemas also admit one shared Boolean
archetype transported to two distinguished values of each instantiated sort.
Datatype declarations disable that construction because their domains may be
singletons. Definitions whose bodies contain type parameters absent from their
ranks carry universal coherence obligations even when unused. Uncertified schema
obligations withhold SAT and terminal claims through the source witness validator.

Reconstructed polymorphic models retain immutable family recipes. A later query
can instantiate an unobserved rank from the same chosen interpretation; known
ranks resolve their distinct private binding keys. Partial model output preserves
the source function name and concrete rank of every returned instance.

### Algebraic datatypes

Datatype declarations retain instantiated constructor and selector sorts, including
parametric and mutually recursive declarations. Typed `match` patterns bind fields
in their branch scope and require exhaustive coverage. Capture represents matches
with tester, selector and conditional nodes; datatype operator metadata preserves
raw symbol spelling, operator kind and instantiated rank through specialization.
Ill-founded declarations and recursive occurrences below a field sort's head are
rejected.

Ground constructors, selectors and testers reduce to typed function consistency,
constructor tags and strict integer depth comparisons. Exhaustiveness introduces
one layer of constructor witnesses only; witness fields do not trigger recursive
unfolding. Typed operation applications share scalar coordinates by their exact
function rank and argument identities. Explicit constructor terms retain their
known shape instead of allocating exhaustive alternatives. Testers on a known
shape reduce to Boolean literals, and matching selectors return its captured fields.
Arrays and function
applications retain opaque datatype sorts until
their own reduction has introduced every relevant coordinate. Scalar erasure then
maps datatype values to integer identities and supplies constructor recipes for
original and private coordinates.

Finite datatype domains constrain identities by their constructor cardinality,
including finite word and extensional array fields. Cardinality arithmetic saturates
at the number of datatype coordinates after array and function exposure:
domains at least that large can always inject those identities. Recursive and other
infinite datatype domains impose no integer interval. Strict depth comparisons
exclude cycles without a depth bound. Reconstruction follows the observed acyclic
constructor graph and assigns unused minimum-depth constructor terms to remaining
identities, preserving disequalities. Source checks compare actual constructor
fields and enforce consistent total selectors even on a different constructor.
Each observed selector rank retains its checked function table and typed default;
later ground queries use that interpretation for the underspecified branch.
Reconstruction tables are cached within one witness for mutually dependent
instantiated sorts; nested parameters can therefore cross array fields without
sharing a recursive table with a different instantiation. The retained typed
registry supplies datatype cardinalities to extensional array value comparisons.

### Declaration policy

`smtLibSemantics` declares exact rational literals, continuous reals and open
integer and real declarations. Endpoint overrides constrain every original
declaration of that sort, including unused ones; assertions remain additional
constraints, and an explicit open replacement never removes an asserted
inequality. Linear factors keep real endpoints as exact comparisons and snapshots
keep them as formula atoms. Grid, tolerance and absent-value overrides are
rejected.

### Lexical scope and refusals

Admission captures one typed source before choosing factor or exact-formula
lowering. Its iterative lexical walk interns terms by their resolved symbol,
argument identities and structured sort, retaining a shared graph independently
of either compiler. Sorts distinguish Boolean, integer, real, fixed-width words,
arrays and named sort applications; recognizing a sort does not admit an encoding
for its operators. Integer-to-real promotion is allowed; Boolean/numeric and real-to-integer
coercions are rejected. Logic declarations do not choose a representation. Quoted
and bare spellings of a symbol share one identity, and a negative numeral must be
written `(- n)`. A semicolon starts a line comment even when it directly follows a
bare token.

Parallel `let` values read the enclosing scope. Typed nonrecursive `define-fun`
macros capture their definition scope and check argument sorts even for unused
arguments; recursive helpers decline. Assertions see only the declarations visible
at their command. Compiler binding tables are discarded after lowering; the owned
typed source and original declaration order and spellings remain attached.

Reduction passes carry transformed commands, original declarations, named private
recipes and original-source checks together. Every pass and fallback shares the
same execution control, so representation changes do not reset an encoding
budget. Caller endpoint overrides apply only to original scalar declarations;
private reduction coordinates keep the bounds supplied by their encoding. Only
original zero-arity numeric declarations receive caller endpoint overrides;
numeric function results do not acquire universal bounds from that policy.

`SmtLibProblem.reconstructModel` reads retained scalar coordinates by name and
composes source-owned recipes into scalar, word, function and constructor values.
The CLI uses that surface for finite and open assignments and prints only original
bindings; public scalar name maps also exclude private encoding coordinates.
Composite reductions attach their source checks through the common source witness
validator, which checks library and CLI candidates before publication and final
claims. Reconstruction repeats those checks before rendering. The common pipeline
also checks scalar arithmetic witnesses against their captured source. Check work
uses the solving invocation's allowance, and interruption withholds the entire
reconstructed model and its terminal claim.
Source checks and rendering use the same exact-value formatter, including rational,
algebraic and symbolic coordinates carried by native assignments or samples.
Different exact terms can denote the same real; their text does not establish
mathematical equality or disequality.

### Ground arrays

After ground function exposure, arrays lower through read-over-write constraints,
index congruence and reified extensional equality. Each compared array pair carries
an index witnessing disequality; positive equality makes its reads agree. Closure
includes store indexes, source reads and introduced witnesses, with a separate
index pool for each complete array sort. Inner-array witnesses therefore do not
create outer reads merely because both arrays use the same index sort. The
encoding does not enumerate a bitvector address space or assume that a free sort
is infinite. Scalar indexes and elements retain their source sorts and open bounds.

Reconstruction gives every array a total finite-store interpretation, sharing one
typed default coordinate per element sort. Nested defaults and private arrays
introduced by other reductions participate in the same closure. Source checks
validate reads, stores, conditionals and equality through certified semantic value
comparison, including native algebraic real indexes. An unavailable comparison
withholds the witness. Model rendering uses store/constant-array terms; the
constant-array input extension is unsupported.
Polymorphic family queries instantiate unobserved array ranks as typed total
arrays, carrying the same semantic comparison and element-point interpretation.

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

Integer `div` and `mod` introduce open integer quotient and remainder coordinates.
A nonzero divisor requires the exact equation `a = b*q + r` and
`0 <= r < abs(b)`, including negative divisors. A zero divisor leaves the two
results independently unspecified; application congruence requires equal arguments
to give equal quotient values and equal remainder values. Real division similarly
requires `b*q = a` for nonzero `b` and retains total function consistency at zero.
No range for the operands or outputs is guessed.

`to_int` introduces an open integer coordinate `q` with `q <= x < q+1`, and
`is_int` compares the original value to that floor. The operands may be nonlinear.
Indexed divisibility uses the exact Euclidean remainder relation for any positive
numeral index. Integer absolute values use a sign conditional.

The standard integer operator `**` retains an integer output and its full integer
semantics. Zero exponents give one, including `0**0`; negative exponents give zero
except at bases one and minus one. Positive exponents at other bases use the
existing exact exponential and logarithm expressions for the absolute magnitude,
with Euclidean parity controlling the sign. The logarithm's argument is positive
on every branch. The `^` extension keeps fixed nonnegative powers. Original
operation checks independently validate retained quotient, floor and power values
before a witness is published.
Arithmetic operands containing the partial logarithm extension retain their
conditional activation, including the auxiliary equations and original checks.
Mandatory constant equalities can substitute exact source coordinates before
arithmetic reduction; their original defining equalities remain in the encoded
source. Closed arithmetic and certified function enclosures simplify exact floors
without introducing a finite search window. Zero-division interpretations retain
the selected ground results and one total default for later fixed-model queries.

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
