# 9. SMT-LIB source semantics

SMT-LIB is the last and most expressive input format. A script declares Boolean,
integer and real constants, asserts quantifier-free formulas over them and asks
whether the assertions are satisfiable, optionally while optimizing an objective.
Unlike the finite formats, its integers and reals are unbounded and its literals
are exact rationals, so an SMT-LIB source can need any of the arithmetic theories
described in Part IV. The frontend's job is to keep that meaning intact: it
preserves the formula, the declarations and their names, and leaves the choice of
mechanism to shared setup.

The chapter covers admission, declarations and lexical scope, then lowering from
Boolean and linear structure to nonlinear and function sources, and finally how
results are printed.

## Supported models

`SmtLib.parse` returns one `SmtLibProblem` with a `SourceAttachment`, declared name
maps and the original objective and direction. Callers pass
`SmtLibProblem.source` to [source setup](30-api.md#source-setup), as the CLI does;
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

## Declaration policy

`smtLibSemantics` declares exact rational literals, continuous reals and open
integer and real declarations. Endpoint overrides constrain every original
declaration of that sort, including unused ones; assertions remain additional
constraints, and an explicit open replacement never removes an asserted
inequality. Linear factors keep real endpoints as exact comparisons and snapshots
keep them as formula atoms. Grid, tolerance and absent-value overrides are
rejected.

## Lexical scope and refusals

Admission checks declarations and expression sorts with an iterative lexical type
walk. Integer-to-real promotion is allowed; Boolean/numeric and real-to-integer
coercions are rejected. Logic declarations do not choose a representation. Quoted
and bare spellings of a symbol share one identity, and a negative numeral must be
written `(- n)`.

Parallel `let` values read the enclosing scope. Typed nonrecursive `define-fun`
macros capture their definition scope and check argument sorts even for unused
arguments; recursive helpers decline. Assertions see only the declarations visible
at their command. The binding tables are discarded after lowering.

## Linear and conditional lowering

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
see [real graph admission](19-difference.md#real-graph-admission-and-checks).

## Integer division

Constant-divisor `div` and `mod` retain a shared Euclidean `DivMod`, with
`0 <= remainder < abs(divisor)`, including negative divisors and arbitrary-width
constants; wide digit expressions keep the affine `WideDivMod`. Open sides stay open,
and variable or zero divisors are unsupported (see
[integer division relations](03.1-source-relations.md#integer-division-relations)).
Integer absolute values retain `Abs`, or `AffineAbs` for wide affine values.

## Polynomial source lowering

Bounded representable products keep their factor form: Int × Int uses `Product`,
Int × Real `RealProduct` and Real × Real `ContinuousProduct`. Nonlinear lowering
first closes integer bounds from exact unconditional linear premises; compound
integer operands receive exact defining equalities, and bounded fixed powers above
one retain `Power`. Open or overflowing integers keep typed arbitrary-precision
coordinates for [nonlinear integer execution](23-nonlinear-integer.md), and finite
mixed products compose through [exact polynomial completion](24-polynomial-real.md#shared-model-composition).
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
[transcendental theory](25-transcendental.md).

## Results and objectives

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

Previous: [8. MPS source semantics](08-mps.md) · [Contents](README.md) · Next: [10. Sessions and streams](10-sessions.md)
