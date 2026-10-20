# 6. Finite input formats

The schema of the previous chapter is built in Kotlin. The remaining frontends read
files, and this chapter covers the four whose problems are entirely Boolean or
finite-integer: DIMACS CNF, weighted CNF (WCNF), pseudo-Boolean OPB/WBO and XCSP3.
They share one shape. A streaming parser turns the text into a source document, a
lowering step turns that document into factors, and the result keeps the source
objective, its direction and the source names needed to print a solution. No
numeric interpretation is configurable, so these chapters are the simplest place to
see the frontend contract from [input semantics](04-input-semantics.md) at work.

The chapter starts with what the four formats share, then takes them in order of
growing expressiveness: clauses, weighted clauses, weighted Boolean relations and
finally XCSP3's finite-domain variables and global constraints.

## Source models and objectives

Every parser reads a `CharSource` line by line or token by token, so a whole input
file is never held as one string. Malformed input raises a format-specific
`FormatException` subclass (`DimacsFormatException`, `OpbFormatException`,
`UnsupportedXcsp3Exception`), which callers can catch uniformly.

Each format declares exact [typed source semantics](03-model.md#typed-source-semantics):
`dimacsSemantics`, `wcnfSemantics`, `opbSemantics` and `xcsp3Semantics`. None of
them has numeric-range, float, tolerance or optional-value policies, so the
corresponding caller overrides are rejected before lowering. The lowered result
(`Problem` for DIMACS, `WcnfProblem`, `OpbProblem`, `Xcsp3Problem`) exposes the
captured interpretation as a `SourceSemanticsOwner`; omitted resource settings
capture the ambient configuration. Each frontend retains its objective orientation
and output names, and the same finite solving mechanisms serve all four.

## DIMACS CNF

`Dimacs.parse` reads a `p cnf <variables> <clauses>` header followed by
zero-terminated clauses of signed variable indexes. Comment lines start with `c`.
A `%` line opens the trailing block some files carry, after which a lone `0` is an
end-of-file sentinel rather than a clause. The parser requires exactly one header
before the body, literals within the declared variable range, a terminating `0` on
the last clause and a clause count matching the header.

The resulting `CnfDocument` lowers each clause to a `Clause` factor over 0-based
Boolean variables. An empty clause makes the instance trivially unsatisfiable;
lowering represents it by a fresh marker variable constrained both true and false,
so the contradiction stays an ordinary factor rather than a special case in the
solver.

## WCNF lowering

`Dimacs.parseWcnf` accepts both the header form `p wcnf <variables> <clauses> [top]`
and the headerless form, in which hard clauses start with `h` and the variable count
grows with the literals seen. Each other clause starts with a nonnegative weight;
with a header, a weight at or above `top` marks a hard clause. An empty hard clause
makes the instance unsatisfiable; an empty soft clause is always violated and adds
its weight to a fixed cost; a zero-weight soft clause imposes nothing.

WCNF objectives with equal soft weights fold unit costs into the original Boolean
objective.
A negative unit adds its weight when the variable is true; a positive unit adds a
constant and a negative coefficient. Non-unit soft clauses use fresh relaxation
variables equivalent to the negation of their source disjunctions. Hard clauses
force each penalty false when any source literal is true, so every feasible
lowered point carries the exact original soft-clause cost. Nonuniform weighted objectives retain relaxation
variables for their soft clauses. A unit also retains that encoding if folding its
cost would overflow a coefficient or constant. Empty soft clauses contribute fixed cost.
Their combined fixed cost must fit a signed 64-bit value; overflowing input declines
with a format diagnostic.

Lowering retains each explicit penalty's exact Boolean definition in
[WcnfProblem.boolFolds](../klause/src/commonMain/kotlin/com/eignex/klause/formats/dimacs/DimacsDecoder.kt):
the conjunction of the complemented source literals. Consumers can use this metadata
with the shared Boolean definition mechanisms; the equivalence clauses remain the
authority for feasible source costs. Repeated literals occur once in each definition;
opposite polarities remain distinct.

The WCNF CLI forwards these definitions to the objective view. Unit penalties join
the maintained Boolean sweep, so Boolean objective-leaf moves update the source
literal and its penalty together. Wider folds retain the priced-input admission policy.

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

Weighted source literals retain `PseudoBoolean`, or `ReifiedPseudoBoolean` for a
soft relation, at arbitrary integral width. Repetition, complement polarity and
product indicators retain their source roles. Coefficient width does not allocate
integer channels or choose a different relation. Native finite propagation and
local search select their arithmetic after reading the same exact constants;
[weighted Boolean consumers](03.1-source-relations.md#weighted-boolean-relations) own their
arithmetic and optional relaxation admission.

OPB/WBO objective normalization combines repeated
and complementary literals, negation offsets and soft penalties with exact integers.
Only the final Boolean coefficients and constant must fit signed 64-bit values;
an unrepresentable result throws `OpbLoweringException`. Product terms retain their
shared AND indicators and source conjunction semantics.

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
| `allDifferent`, `allEqual`, `ordered`, `instantiation` | `AllDifferent` (with `except`, per row and column for matrices), pairwise equalities, `Increasing` chains (or difference rows with lengths) and fixings |
| `element`, `channel`, `minimum`, `maximum` | `Element` (including constant and variable matrices), `Inverse` and channel rows, `ArrayMinMax` with optional index |
| `cardinality`, `nValues`, `precedence`, `lex` | `GlobalCardinality` (with `closed`), `NValue`, `ValuePrecede`, `LexLess` chains including matrix rows and columns |
| `regular`, `mdd` | `Regular` automata; a cleanly layered MDD becomes an `Mdd`, other diagrams fall back to an automaton |
| `cumulative`, `noOverlap`, `circuit` | `Cumulative` with a `le` capacity; one-dimensional no-overlap as unit `Cumulative`, boxes as `Diffn`; `Circuit` with subcircuit semantics and a size constraint |
| `group`, `slide`, `block` | Instantiated per argument row; groups reuse one parsed automaton or support table across rows |

Some forms are rejected because the retained factor cannot express them, such as
`zeroIgnored="false"` no-overlap, a `circuit` with a nonzero start index and
`binPacking` conditions other than an upper capacity.

### Expressions and selection

Integer conditional expressions retain a native `Element` over their ordered else
and then values, with a 0/1 condition channel. Constant matrices retain native
selections of the row start, row length and flattened cell. Row-dependent column
bounds prevent an invalid column from selecting another row's cell, including
ragged constant rows. Variable matrix selection retains its existing axis guards.

Vector selection and indexed extrema read the standard `list@startIndex` attribute.
The existing parent `startIndex` spelling remains a compatibility alias; conflicting
explicit offsets decline. Indexed minimum/maximum retains both `ArrayMinMax` and
`Element`; its numerical condition is optional. Selection with `rank="first"` or
`rank="last"` declines explicitly. Unindexed vector membership remains unsupported.

### Definitions for local search

The frontend records which auxiliary and output variables are functionally defined
by the constraints it emitted. These definition hints let local search derive those
values from their inputs instead of searching them; source factors remain the
authority for every constraint.

XCSP3 variable-matrix selection uses a
row-major index equality and a native `Element` factor. The compiler marks the
flattened index, selected result and affine expression outputs as functionally defined. Local search
maintains index-only affine chains from their source inputs; independently constrained
affine outputs remain searched. Eligible unique Element results follow indexes and selected
cells, with inverse repairs into their source coordinates or admissible cells. Competing
and cyclic definitions remain searched. Bounds on each source index enforce the matrix range when
its declared domain extends beyond it, including nonzero axis offsets; repeated
cells and aliases retain their source variable identities. Unsupported constructs
decline through the common solve surface. Boolean-valued XCSP arithmetic terms
use fresh 0/1 integers tied directly to their source literal by a reified equality,
including negated literals; products, sum terms and bin-packing indicators share this encoding.
Direct binary products of a Boolean expression and a declared integer or positive
numeric literal use their declared equality output directly. Literals use shared fixed
integer columns, so existing Product admission maintains the source output during moves.
Either operand and equality orientation and negated Boolean literals retain this encoding;
zero, negative literals and general arithmetic expressions retain ordinary arithmetic lowering.
The compiler marks the fresh binary channels as deterministic outputs. Domain-aware
definition inference maintains those literal values and their unique unit unary
predicates when the predicates have no other Boolean factor occurrences. Equality,
disequality and ordered comparisons admit safe unit-coefficient normalization;
[local-search definitions](15.2-preparation.md#maintained-literal-channels) retain
bounded inverse repairs and objective gradients across both coordinate spaces.

XCSP min/max arithmetic expressions mark deterministic auxiliaries and materialized affine
operands as definition hints. Top-level equalities retain output aliases; hints alone do
not orient unrelated sums or unhinted min/max globals. Domains and independently posted
output constraints remain authoritative during local-search definition maintenance.

### Arithmetic relations

Integer intension `pow(base, exponent)` retains `Power` with a fixed nonnegative
integer exponent. Compound operands receive source equalities; materialized output
bounds must fit the signed-word representation. Variable, negative and fractional
exponents are unsupported. DIMACS/WCNF have no numeric powers; OPB/WBO monomials
remain Boolean conjunctions, including repeated literals.

XCSP3 `div` and `mod` expressions retain a shared truncating `DivMod` relation.
Nonzero signed-word constants are admitted. The variable-divisor path retains its
nonnegative-dividend and positive-divisor requirement; wider literal divisors and
other variable sign combinations remain unsupported. MPS, DIMACS/WCNF and OPB/WBO
have no applicable integer quotient/remainder syntax or recovered relation.
See [integer division relations](03.1-source-relations.md#integer-division-relations).

XCSP3 `nValues` retains `NValue`, including `<except>`, constant or variable
conditions and distinct-count objectives. The frontend allocates one count coordinate
and its source condition without enumerating the domain's value span. Excluded
values remain factor data; propagation, local search and the selected LP hull consume
the [distinct-value count relation](03.1-source-relations.md#distinct-value-counts).

---

Previous: [5. Schema lowering](05-schema.md) · [Contents](README.md) · Next: [7. FlatZinc](07-flatzinc.md)
