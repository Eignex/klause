# Finite input formats

DIMACS, WCNF, OPB/WBO and XCSP3 describe Boolean or finite-integer problems.
Their frontends preserve source objectives and names while lowering syntax to
shared factors. The same finite solving mechanisms serve these sources.

[Shared input semantics](../formats.md) owns admission and publication guarantees.
This page records format-specific encodings and source validation.

## Source models and objectives

DIMACS CNF supplies Boolean clauses. WCNF supplies weighted partial MaxSAT.
OPB/WBO supplies pseudo-Boolean constraints and objectives, including supported
product and soft-constraint encodings. XCSP3 supplies finite-domain variables,
supported globals and optional objectives. Each frontend retains its source
objective orientation and output names.

## OPB and WBO lowering

OPB/WBO objective normalization combines repeated
and complementary literals, negation offsets and soft penalties with exact integers.
Only the final Boolean coefficients and constant must fit signed 64-bit values;
an unrepresentable result throws `OpbLoweringException`. Product terms retain their
shared AND indicators and source conjunction semantics.

## XCSP3 lowering

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
[local-search definitions](../solver/local-search.md#maintained-literal-channels) retain
bounded inverse repairs and objective gradients across both coordinate spaces.

XCSP min/max arithmetic expressions mark deterministic auxiliaries and materialized affine
operands as definition hints. Top-level equalities retain output aliases; hints alone do
not orient unrelated sums or unhinted min/max globals. Domains and independently posted
output constraints remain authoritative during local-search definition maintenance.

### Source validation

The benchmark's opt-in XCSP source checker evaluates the final assignment against
the original XML and exact source objective, independently of Klause lowering.
Checked witnesses and reported proof claims remain distinct; supported syntax,
record fields and the saved-output validation command are defined in the
[benchmark validation contract](../../klause-bench/README.md#source-validation).

## WCNF lowering

WCNF objectives with equal soft weights fold unit costs into the original Boolean
objective.
A negative unit adds its weight when the variable is true; a positive unit adds a
constant and a negative coefficient. Non-unit soft clauses use fresh relaxation
variables equivalent to the negation of their source disjunctions. Hard clauses
force each penalty false when any source literal is true, so every feasible
lowered point carries the exact original soft-clause cost. Nonuniform weighted objectives retain relaxation
variables for their soft clauses. A unit also retains that encoding if folding its
cost would overflow a coefficient or constant. Empty soft clauses contribute fixed cost.

Lowering retains each explicit penalty's exact Boolean definition in
[WcnfProblem.boolFolds](../../klause/src/commonMain/kotlin/com/eignex/klause/formats/dimacs/DimacsDecoder.kt):
the conjunction of the complemented source literals. Consumers can use this metadata
with the shared Boolean definition mechanisms; the equivalence clauses remain the
authority for feasible source costs.
