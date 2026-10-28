# 4. Finite formats and FlatZinc

DIMACS, WCNF, OPB/WBO and XCSP3 describe Boolean or finite-integer models. FlatZinc
adds floats, search annotations and an output model. Each frontend streams a source
document, lowers it to factors and keeps names and objective direction for rendering.

## Source models and objectives

Every parser reads a `CharSource` line by line or token by token, so a whole input
file is never held as one string. Malformed input raises a format-specific
`FormatException` subclass (`DimacsFormatException`, `OpbFormatException`,
`UnsupportedXcsp3Exception`), which callers can catch uniformly.

Each format declares exact [typed source semantics](02-model.md#typed-source-semantics)
(`dimacsSemantics`, `wcnfSemantics`, `opbSemantics`, `xcsp3Semantics`) with no
numeric-range, float, tolerance or optional-value policies, so those caller
overrides are rejected before lowering. The lowered result exposes its captured
interpretation as a `SourceSemanticsOwner`.

## DIMACS CNF

`Dimacs.parse` reads a `p cnf <variables> <clauses>` header followed by
zero-terminated clauses of signed variable indexes. Comment lines start with `c`.
A `%` line opens the trailing block some files carry, after which a lone `0` is an
end-of-file sentinel rather than a clause. The parser requires exactly one header
before the body, literals within the declared variable range, a terminating `0` on
the last clause and a clause count matching the header.

The resulting `CnfDocument` lowers each clause to a `Clause` factor over 0-based
Boolean variables. An empty clause lowers to a fresh marker variable constrained
both true and false, so the contradiction stays an ordinary factor.

## WCNF lowering

`Dimacs.parseWcnf` accepts both the header form `p wcnf <variables> <clauses> [top]`
and the headerless form, in which hard clauses start with `h` and the variable count
grows with the literals seen. Each other clause starts with a nonnegative weight;
with a header, a weight at or above `top` marks a hard clause. An empty hard clause
makes the instance unsatisfiable; an empty soft clause is always violated and adds
its weight to a fixed cost; a zero-weight soft clause imposes nothing.

With equal soft weights, unit soft clauses fold directly into the Boolean
objective. Other soft clauses get fresh relaxation variables equivalent to the
negation of their disjunctions, so every feasible lowered point carries the exact
original soft-clause cost. A unit also keeps that encoding if folding would
overflow a coefficient. The combined fixed cost of empty soft clauses must fit a
signed 64-bit value.

Lowering records each penalty's Boolean definition (the conjunction of the
complemented source literals) in
[WcnfProblem.boolFolds](../klause/src/commonMain/kotlin/com/eignex/klause/formats/dimacs/DimacsDecoder.kt).
The equivalence clauses remain the authority for costs; the WCNF CLI forwards the
definitions to the objective view, so objective moves update a literal and its
penalty together.

## OPB and WBO lowering

OPB statements end with `;`. `min:` introduces the objective, and every other
statement is a relation `terms op bound` with `>=`, `<=` or `=`. A term is an
integer coefficient followed by one or more literals `xN` or `~xN`; several literals
form a product, the conjunction of those literals. Lines starting with `*` are
comments, and the variable count is the largest index referenced. WBO adds a
`soft:` header with an optional top cost and soft relations prefixed by a bracketed
nonnegative cost. Coefficients and bounds are parsed at arbitrary integer width.

Lowering gives each distinct product one shared AND indicator, recorded as a
`BoolFoldDefinition` for consumers that maintain Boolean definitions. A hard
relation becomes `PseudoBoolean`; a soft relation becomes `ReifiedPseudoBoolean`
with a fresh satisfaction literal, and its cost enters the objective on the negation
of that literal. A soft top adds the hard relation that the total violated cost
stays below the top.

Coefficient width allocates no integer channels and selects no other relation;
[weighted Boolean consumers](02-model.md#weighted-boolean-relations)
choose their arithmetic from the same exact constants. Objective normalization
combines repeated and complementary literals, negation offsets and soft penalties
with exact integers; only the final coefficients and constant must fit signed
64-bit values, or `OpbLoweringException` is thrown.

## XCSP3 lowering

`Xcsp3.parse` streams the XML instance in one forward pass, materializing only one
variable, constraint or objective subtree at a time. Variables are integer `var`
and `array` declarations; arrays declare one variable per cell, named `x[i][j]`,
and may give different cells different domains. Domains are integer values and
ranges and lower to finite `IntDomain`s with holes. Only the first objective is
used: `minimize` or `maximize` over a `sum` (with optional coefficients), a
`minimum`/`maximum` through an `ArrayMinMax` auxiliary, or an `nValues` count.
Any other variable kind, constraint or objective type is rejected with
`UnsupportedXcsp3Exception`; the frontend never drops a constraint it cannot encode.

Constraints keep their global structure where Klause has a matching factor:

| XCSP3 constraint | Retained relation |
|---|---|
| `extension` | Positive tables become `Table` with wildcards and ranges in short tuples; negative tables are complemented into a `Table` when the domain product is at most 10^6 and become nogood clauses otherwise |
| `intension` | Linear relations become `Linear` or `ReifiedLinear`; disjunctions of single-variable comparisons become one `ComparisonClause`; other Boolean structure uses Tseitin literals |
| `sum`, `count`, `knapsack`, `binPacking` | Exact linear rows over the terms or over reified 0/1 channels; `count` needs one constant value, `binPacking` constant sizes and a `le` capacity or loads |
| `allDifferent`, `allEqual`, `ordered`, `instantiation` | `AllDifferent` (with `except`, per row and column for matrices), `AllDifferentTuples` for multiple lists, pairwise equalities, `Increasing` chains (or difference rows with lengths) and fixings |
| `element`, `channel`, `minimum`, `maximum` | `Element` (including constant and variable matrices), `Inverse` and channel rows, `ArrayMinMax` with optional index |
| `cardinality`, `nValues`, `precedence`, `lex` | `GlobalCardinality` (with `closed`), `NValue`, `ValuePrecede`, `LexLess` chains including matrix rows and columns |
| `regular`, `mdd` | `Regular` automata; a cleanly layered MDD becomes an `Mdd`, other diagrams fall back to an automaton |
| `cumulative`, `noOverlap`, `circuit` | `Cumulative` with a `le` capacity; one-dimensional no-overlap as unit `Cumulative`, boxes as `Diffn`; `Circuit` with subcircuit semantics and a size constraint |
| `group`, `slide`, `block` | Instantiated per argument row; groups reuse one parsed automaton or support table across rows |

Some forms are rejected because the retained factor cannot express them, such as
`zeroIgnored="false"` no-overlap, a `circuit` with a nonzero start index and
`binPacking` conditions other than an upper capacity.

Multiple `allDifferent` lists retain ordered tuple distinctness in one factor,
without auxiliary coordinates or pairwise clauses. Repeated coordinates and
declared domains stay intact. Tuple exceptions and tuple reification attributes
are unsupported and raise `UnsupportedXcsp3Exception`.

Scalar `allDifferent` retains one native relation, including excepted values and
wide finite domains. Matrix rows and columns use the same span-independent path;
fewer than two positions impose no constraint.

### Expressions and selection

Integer conditionals retain a native `Element` over their else and then values
with a 0/1 condition channel. Constant and variable matrix selection retain native
`Element` factors with row-major index equalities and per-axis bounds, so an
invalid column never selects another row's cell. Vector selection and indexed
extrema read `list@startIndex`; conflicting offsets and `rank="first"`/`"last"`
selection decline.

Boolean-valued arithmetic terms use fresh 0/1 integers tied to their source literal
by a reified equality. Unit equality terms `x = v` of sums and sum objectives and
`binPacking` memberships share one such indicator per `(x, v)` within a compile.
Counts reuse those indicators, and counting ones over a declared binary integer
uses the integer itself. An eligible declared count result owns the sum directly;
count results and reused channels carry typed definitions.
Direct products of a Boolean and an integer use their declared equality output
instead, and an extremum whose condition is `(eq, v)` posts its `ArrayMinMax`
onto `v`. Top-level intension equalities with `min` or `max` also retain the
declared result in either orientation, unless it occurs within the operands.
The frontend records which auxiliaries, selection results and affine
outputs are functionally defined, so
[local search](10-local-search.md#maintained-literal-channels) can derive them
from their inputs instead of searching them. `Xcsp3Problem.definitionHints` adds
[typed relations](02-model.md#source-definition-hints) for materialized affine
terms, declared `binPacking` loads, extremum results and the shared indicators,
which local search materializes without the factor-shape guards that bare
references need. Source factors remain the authority for every constraint.

### Arithmetic relations

Top-level `ne(dist(x,y),k)` with a constant integer `k` posts exact linear
disequalities for the two signed differences, without materializing the distance.
Zero needs one disequality; a negative distance is impossible, so its disequality
imposes no constraint. Nested and reified distance expressions keep their arithmetic
relation. The equivalent top-level `ne(abs(sub(x,y)),k)` form uses the same rows.

Complete connected groups of unconditional unit-coefficient affine disequalities
can additionally retain collective distinctness, even when the source uses no
global. Recognition checks every pair against exact offsets anchored at one
variable. Ambiguous triangles and incomplete or inconsistent groups decline.
It indexes at most 65,536 rows, 512 variables, 32,768 distinct labelled edges and
eight labels per pair, and spends at most one million adjacency/label probes with
cooperative cancellation. Exceeding collection limits discards the optional index;
no source row is removed. Reified and soft constraint attributes are unsupported
and rejected, including group templates and streamed group/block containers.

Each admitted group retains one exact affine `Distinct` over the original integer
coordinates and replaces every indexed source row with its verified pair label,
including duplicate and reversed rows. Other labels and uncommitted groups retain
their source rows. The global and its covered-row removals commit together;
cancellation can leave completed groups committed without changing other rows.
No auxiliary domains, equality channels or definition hints are
materialized. Finite matching/Hall propagation applies bounds and hole deductions
directly to source domains; lazy reasons read those domains at the deduction's
trail position. Offset arithmetic remains exact beyond signed-word endpoints.
Recovery cancellation skips uncommitted optional work.

`pow(base, exponent)` retains `Power` with a fixed nonnegative integer exponent;
variable, negative and fractional exponents are unsupported. `div` and `mod`
retain a shared truncating `DivMod`: nonzero signed-word constants are admitted,
and a variable divisor requires a nonnegative dividend and positive divisor (see
[integer division relations](02-model.md#integer-division-relations)).
`nValues` retains `NValue`, including `<except>` and distinct-count objectives,
without enumerating the domain span (see
[distinct-value counts](02-model.md#distinct-value-counts)).

### Constraint classes

A `class` attribute applies to its constraint and to everything nested inside a
classed `block`, `group` or `slide`, including group and slide templates; nested
classes combine and end with their element. `symmetryBreaking` and
`redundantConstraints`, and their hyphenated spellings `symmetry-breaking` and
`redundant-constraints`, mark the emitted factors in `Problem.impliedFactorMask`;
any `symmetryBreaking` constraint sets `Problem.hasSymmetryBreaking`, even when it
only restricts domains. Other classes are inert. A shared definition stays marked
only while every constraint that reads it is classified, and a recovered `Distinct`
only when all its source rows are. Classified constraints stay enforced; the two
fields feed the same local search weights and presolve symmetry gate as
[FlatZinc's source annotations](#source-annotation-constraints).

## FlatZinc

FlatZinc is MiniZinc's flat constraint language. The frontend keeps source names,
constraints, search annotations and output reconstruction. Float components retain
exact arithmetic or declared grid semantics; grid refutations and optima apply only
to that finite interpretation.

### Declared semantics and caller ranges

`flatZincSemantics` declares binary64 literal authority, a default grid policy,
`[-1000000, 1000000]` for unranged integer declarations and open endpoints for
unranged floats. Parsing resolves `SemanticOverrides` before declaration planning
and keeps both defaults and effective policy in `FlatZincProgram.semantics`.

Endpoint replacements apply only to unranged scalar and array declarations; an
explicit source range or value set, including one introduced by a ranged alias,
stays authoritative. `BoundOverride.Open` keeps a genuinely open side, and the
finite placeholder endpoints carry no source-bound authority. Rational real
endpoints must be exactly representable in binary64 and are never rounded.

Float grid windows stay separate from continuous source bounds. A window disjoint
from the declared range declines the grid without refuting the source, and a
singleton window cannot certify a finite choice. When effective endpoints differ
from the defaults, the output protocol prints them and withholds default-source
refutation, unboundedness and optimum markers.

### Integer relations

Integer min/max predicates retain `ArrayMinMax`; integer and Boolean array selection
retain one-based `Element` relations with ordered repeated entries. Set selection
shares one index across membership indicators and forbids members outside the result
universe. Integer set membership keeps signed-word values.

`int_div` and `int_mod` share truncating
[DivMod](02-model.md#integer-division-relations) relations for identical operands,
linking separately declared outputs by exact equalities. A divisor domain containing
zero keeps its nonzero alternatives.

`all_different_int` and `alldifferent_except_0` retain native `AllDifferent`
over signed-word domains. Fixed nonnegative integer powers retain `Power`; exact
float powers use polynomial completion and bucketed powers use `GridPower`.
Variable or negative exponents decline.

### FlatZinc Boolean channels

The frontend recognizes `bool2int` channels independently of constraint order.
Nonnegative weighted sums of channelled integers constrained to zero lower to
Boolean conjunctions over the complemented inputs; reified sums use the shared
clause encoder. Positive bounds and signed sums keep arithmetic propagation. If the
eligible rows would expand to more than 32,768 clauses in total, all of them keep
arithmetic propagation, so constraint order never selects a partial encoding.

### Source annotation constraints

`redundant_constraint` and `symmetry_breaking_constraint` (and their `fzn_`
spellings) keep their Boolean requirement. Wrappers and the `klause_redundant` and
`klause_symmetry` annotations mark the emitted factors in `Problem.impliedFactorMask`,
and symmetry declarations set `Problem.hasSymmetryBreaking`. Local search seeds a
lower violation weight for these factors, and presolve reads the symmetry flag
before adding its own breaks; the factors stay enforced for every consumer.

### Source definitions

`FlatZincProgram.definitionHints` retains `defines_var` targets and their relation
data in compiled source coordinates, with constants, aliases, repeated roles and
polarity. Unrecognized orientations remain reference hints. Parsing does not order
a definition graph; [definition materialization](10-local-search.md#source-definition-materialization)
does that on request. Source constraints enforce every relation independently of
these annotations.

### Native predicate coverage

The MiniZinc library in `klause-mzn-lib` declares which predicates Klause handles
natively; everything else arrives as MiniZinc's standard decomposition. Its
`redefinitions.mzn` is the authoritative advertised surface: Boolean, integer and
float linear predicates and the supported globals. Every declaration needs a
matching parser and lowering path. Fixed nonnegative integer `pow` emits
`klause_int_pow`, and its reification reifies only the equality to the result.
The `bin_packing_load` decomposition annotates each bin's load equality as the
`defines_var` of that load, so local search derives loads from the assignment
instead of searching them.

#### Exact globals and real composition

`--exact` keeps finite globals native. An `int2float` connection adds arithmetic
participation without replacing a finite global by relaxation rows. Linear or
polynomial completion respects the finite choices; complete traversal combines their
certified results. [Factor ownership](02-model.md#factor-projection-selection)
defines the composition rule.

| Family | Retained enforcement |
|---|---|
| Distinctness, equality, membership, sorting and counts | `AllDifferent`, `SymmetricAllDifferent`, `Sort`, `GlobalCardinality`, `NValue`, equality channels and cardinalities |
| Selection, extrema and permutations | `Element`, `ArrayMinMax`, `Inverse` with source index offsets |
| Ordering and precedence | `Increasing`, `LexLess`, `ValuePrecede` |
| Geometry, scheduling and circuits | `Diffn`, `Cumulative`, `Circuit` with the declared zero-size/cycle semantics |
| Tables and languages | `Table`, `Regular`, `Mdd` |
| Packing, window sums and sets | Exact assignment/membership indicators, clauses and weighted rows |

The library normalizes circuit and symmetric-permutation indexes before flattening;
FlatZinc successors are one-based independently of their value bounds. Empty counted
arrays require zero occurrences, and occurrence bounds are intersected with array
length before narrowing. Strict disjunctive scheduling adds separation for zero-duration
tasks: they may touch an endpoint but cannot lie inside another task. Ordinary
disjunctive scheduling permits that placement.

Asserted optional disjunctives with at least two fixed strictly positive durations
retain a unary `Cumulative` through `klause_disjunctive_opt`. The library forwards
the original `occurs` literals and `deopt` starts; it adds no absent-value
canonicalization. A statically absent task uses an ignored constant start, so
`deopt(<>)` is never evaluated. Repeated starts, durations and presence channels
keep their positions. Ordinary and strict forms agree on this positive-duration
fragment. Zero or variable durations, smaller arrays and whole-constraint
reification or negation keep the standard exact decompositions, including strict
zero-task separation. Output reconstruction keeps the compiler's original mapping.

Set universes and constant members require signed 32-bit values; ordering excludes
`Int.MIN_VALUE`. Automaton indexes, inverse offsets and constant GCC occurrence
bounds also require signed 32-bit parameters. Window sums and counted values keep
signed-word bounds. Open integers in CP-only globals, variable/negative powers and
arbitrary real functions remain unsupported. The advertised predicate list lives in
[redefinitions.mzn](../klause-mzn-lib/share/minizinc/klause/redefinitions.mzn).

### FlatZinc floats

Canonical compilation retains original real declarations and exact real relations;
`FlatZincProgram.sourceProgram` and `sourceAttachment()` expose that frame without
a grid. A grid policy only permits a private view. Automatic execution prefers an
admitted original complete arm and runs the grid view only when none exists. The
legacy `problem` getter and `prepareFiniteCompatibility` request the finite
compatibility view explicitly.

Supported float operations retain exact relations: strict and reified comparisons,
`RealAbs`, `RealArrayMinMax`, constant-array `RealElement`, constant scaling and
products. Finite float choices become `RealFiniteDomain` memberships, products with
a finite operand keep `ContinuousProduct` with the declared values, and
unrestricted products go to [polynomial completion](16-nonlinear-arithmetic.md#polynomial-real-arithmetic). Linear
consumers install alternatives privately; an LP relaxation alone never establishes
membership or a product.

In the compatibility view, a component that needs approximation or has more than
4,096 finite alternatives uses integer buckets. Bucketed search decides a finite
grid: it may miss solutions between grid points, and rounding can produce
candidates that violate continuous-source equations. FlatZinc publishes those
candidates as grid witnesses, and completed search reports the grid optimum or
refutation. Grid relations (`GridArrayMinMax`, `GridAbs`,
`GridProduct`, `GridPower`) keep their step, origin and nearest-bucket rounding.

`--exact` preserves continuous variables and open bounds, routes linear
satisfaction through the shared real/mixed theory and requires exact certification
for continuous objectives. It never selects a grid; unsupported exact models
decline. Overflow during lowering and integer constants that
binary64 cannot represent decline with a diagnostic.

#### Rounded FlatZinc bounds

Exact mode checks mathematical arithmetic over parsed binary64 literals and keeps
every declared bound. For example, binary64 `0.01 * 200` exceeds `2.0` by
`3/72057594037927936`: rounding the result to `2.0` violates the defining row.
A compiler-rounded integer image crossing a declared bound therefore declines exact
mode; default compilation records the approximation and uses the grid. See
[FlatZincFloatPolicy](../klause/src/commonMain/kotlin/com/eignex/klause/formats/flatzinc/FlatZincFloatPolicy.kt).

### MiniZinc output rendering

Exact coordinates are emitted as `% klause-exact:` comments beside
MiniZinc-compatible decimal assignments. Rational coordinates retain their fraction;
irrational coordinates use `root(index;[c0,c1,...])`, naming the selected real root
of the rational polynomial in ascending coefficient order. Decimal assignments are
display approximations; an irrational display uses the midpoint of its captured
isolating interval. The fraction or root description retains source authority.
Mixed exact/grid witnesses add
`% klause-coordinate:` and `% klause-float-component:` comments with each grid
point's binary64 value and approximation reason. With a selected finite grid,
`==========` reports a grid optimum or completed satisfaction search, and
`=====UNSATISFIABLE=====` reports a grid refutation. The
`% klause-float-claim:` comment scopes these claims to the declared finite grid,
and `% klause-grid-verdict:` retains the native grid verdict. Rounded grid witnesses
need not satisfy continuous-source equations. Incomplete optimization keeps its
incumbent without an optimum marker; an unresolved search remains UNKNOWN.
Caller declaration-range overrides still withhold default-source completion claims.

`OznApplier` renders bindings through the parsed `.ozn` output model with signed
64-bit integers. `array1d` through `array6d` keep contiguous index sets and
row-major order, and subscripts are checked against every axis. Ranges stay
symbolic; expansion, comprehensions and Cartesian products are limited to
1,000,000 elements. Overflow, unsupported sizes and invalid subscripts raise
`OznParseException` without publishing a partial solution block.

The CLI's optional complete-assignment comments retain original named integer and
Boolean coordinates after reconstruction, including variables absent from the output
model, and the original objective. A framed block precedes the solution separator;
it carries no validation or proof claim. Capture admits at most 65,536 names, four
MiB of name characters and eight MiB of assignment text. Float or set coordinates
and exceeded limits emit an unavailable marker instead of a partial block. Capture
is disabled by default and spends the invocation's output work and time.

---

Previous: [3. Input semantics and the schema DSL](03-input-semantics.md) · [Contents](README.md) · Next: [5. MPS and SMT-LIB](05-arithmetic-formats.md)
