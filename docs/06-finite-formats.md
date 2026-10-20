# 6. Finite input formats

The schema of the previous chapter is built in Kotlin. The remaining frontends read
files, and this chapter covers the four whose problems are entirely Boolean or
finite-integer: DIMACS CNF, weighted CNF (WCNF), pseudo-Boolean OPB/WBO and XCSP3.
They share one shape: a streaming parser turns the text into a source document, a
lowering step turns it into factors, and the result keeps the source objective,
its direction and the names needed to print a solution. No numeric interpretation
is configurable, so these formats show the frontend contract from
[input semantics](04-input-semantics.md) in its simplest form. The chapter takes
them in order of growing expressiveness.

## Source models and objectives

Every parser reads a `CharSource` line by line or token by token, so a whole input
file is never held as one string. Malformed input raises a format-specific
`FormatException` subclass (`DimacsFormatException`, `OpbFormatException`,
`UnsupportedXcsp3Exception`), which callers can catch uniformly.

Each format declares exact [typed source semantics](03-model.md#typed-source-semantics)
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
[weighted Boolean consumers](03.1-source-relations.md#weighted-boolean-relations)
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

Integer conditionals retain a native `Element` over their else and then values
with a 0/1 condition channel. Constant and variable matrix selection retain native
`Element` factors with row-major index equalities and per-axis bounds, so an
invalid column never selects another row's cell. Vector selection and indexed
extrema read `list@startIndex`; conflicting offsets and `rank="first"`/`"last"`
selection decline.

Boolean-valued arithmetic terms use fresh 0/1 integers tied to their source literal
by a reified equality; direct products of a Boolean and an integer use their
declared equality output instead. The frontend records which auxiliaries,
selection results and affine outputs are functionally defined, so
[local search](15.2-preparation.md#maintained-literal-channels) can derive them
from their inputs instead of searching them. Source factors remain the authority
for every constraint.

### Arithmetic relations

`pow(base, exponent)` retains `Power` with a fixed nonnegative integer exponent;
variable, negative and fractional exponents are unsupported. `div` and `mod`
retain a shared truncating `DivMod`: nonzero signed-word constants are admitted,
and a variable divisor requires a nonnegative dividend and positive divisor (see
[integer division relations](03.1-source-relations.md#integer-division-relations)).
`nValues` retains `NValue`, including `<except>` and distinct-count objectives,
without enumerating the domain span (see
[distinct-value counts](03.1-source-relations.md#distinct-value-counts)).

---

Previous: [5. Schema lowering](05-schema.md) · [Contents](README.md) · Next: [7. FlatZinc](07-flatzinc.md)
