# 9. SMT-LIB source semantics

SMT-LIB is the last and most expressive input format. A script declares Boolean,
integer and real constants, asserts quantifier-free formulas over them and asks
whether the assertions are satisfiable, optionally while optimizing an objective.
Unlike the finite formats, its integers and reals are unbounded and its literals
are exact rationals, so an SMT-LIB source can need any of the arithmetic theories
described in Part IV. The frontend's job is to keep that meaning intact: it
preserves the formula, the declarations and their names, and leaves the choice of
mechanism to shared setup.

The chapter first fixes what the frontend admits and how declarations and lexical
scopes are read. It then follows lowering from Boolean and linear structure through
integer division to nonlinear and function sources, and ends with how results and
objectives are printed.

## Supported models

`SmtLib.parse` is the public entry. It returns one `SmtLibProblem` containing a
`SourceAttachment`, declared name maps, and the original objective and direction.
Callers bind that objective and pass `SmtLibProblem.source` to
[source setup](30-api.md#source-setup); the CLI uses the same contract, and the
frontend never classifies a source by the solver it will need. Text, stream and
source-factory overloads share one declaration policy, parse each command once and
charge one containing work meter. Interruption throws `CancellationException`
before any source is published.

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

`smtLibSemantics` declares exact rational literals, continuous real variables and
open integer and real declarations. `SmtLib.parse`
resolves `SemanticOverrides` before reading source commands. Integer and exact
rational real endpoint replacements constrain every original declaration of that
sort, including unused declarations. Assertions remain additional constraints;
bound inference intersects them with the declaration policy. Explicit open
replacements remove a caller default, never an asserted source inequality.

Linear factors retain real endpoints as exact rational comparisons. Polynomial,
nonlinear integer and function snapshots retain the same endpoints as formula
atoms. Auxiliary coordinates receive only their defining constraints and inferred
ranges. Strict comparisons and integrality remain unchanged. A finite
`Long.MIN_VALUE` or `Long.MAX_VALUE` supplied through a typed replacement is a
finite endpoint; the legacy positional integer arguments use those values as
open markers. Typed replacements take precedence independently on each side.

Grid float interpretation, feasibility tolerance and absent-value policies are
rejected for SMT-LIB. Source wrappers capture the effective policy alongside
numeric authority; its qualification does not establish a witness or proof.
Resource defaults are captured from `KlauseConfig.current` consistently by the
factor facade and its builder.

## Lexical scope and refusals

Admission checks declaration signatures and expression sorts with an iterative
lexical type walk. Integer-to-real promotion is allowed; Boolean/numeric and
real-to-integer coercions are rejected. Logic declarations do not choose a source
representation. Quoted and bare spellings of one symbol share its declaration
identity, while output metadata keeps the declaration's original spelling, and a
negative numeral must be written `(- n)` because a bare `-n` is a symbol.

Parallel `let` values read the enclosing scope. Typed nonrecursive `define-fun`
macros capture their definition scope; recursive helpers decline before expansion.
Helper calls evaluate arguments in the caller scope, enforce declared parameter
sorts even for unused arguments, and isolate parameter bindings while evaluating
the body. Assertions retain the declarations visible at their command, so a later
binding cannot leak into an earlier assertion. A user-defined helper whose name
matches a built-in function keeps its own meaning. The binding tables belong to
admission and are discarded after lowering; published source IR retains neither.

## Linear and conditional lowering

Boolean lowering folds constants and literal identities in AND, OR and IFF after
their children are lowered, so empty, duplicate and complementary operands keep
their logical meaning without extra gate names. A comparison literal is shared
within one parse only when its folded exact coefficients, coordinates, constant,
relation and strictness agree; the sharing table is bounded and other comparisons
lower independently.

Real and rational mixed comparisons retain `ExactLinear` rows with exact rational
coefficients, tagged original coordinates, strictness and Boolean reification.
Coefficient magnitude adds no frontend digit columns or unit auxiliaries. The
selected arithmetic consumer reads the complete exact row; numerical projections
may decline values they cannot represent. Integer-only rational comparisons retain
exact denominator-cleared lattice thresholds. Source witnesses and proofs remain
subject to the complete selected relation and original declaration checks.

Integer conditionals (`ite` and decision lists) are the largest part of SMT-LIB
lowering. Finite conditionals retain native `Element` relations over materialized
branch values; short and sparse decision lists use one finite choice index whose
guards keep the original equality meanings and default. Wide finite conditionals
share one choice index across their digit columns. A result with an open bound
instead keeps guarded exact equalities to its branches, without a finite index.
Affine real conditionals retain `RealElement` over branch columns sharing the
source Boolean guard. Equalities and comparisons against a conditional can be
lowered to Boolean tests of its branches when the branch images are small and
known; otherwise the exact arithmetic encoding remains. This expansion shares a
bounded visit allowance, and definitions no surviving constraint or objective needs
are dropped with their private columns after a dependency fixpoint. Source
declarations and arithmetic leaves always remain.

Source lowering also recovers scalar tables over declared Booleans. For up to eight
Boolean inputs, a scalar defined by cases that cover every assignment consistently
supplies an unconditional affine row beside the source factors, verified entry by
entry under a bounded allowance. Missing, conflicting or nonlinear cases supply no
row, and no source coordinate is removed or given a finite domain by it.

The exact real component can certify supported difference fragments with a graph,
retaining the LP route for other states and conflict explanations. Its admission,
strict-edge encoding and lifecycle belong to the
[search contract](19-difference.md#real-graph-admission-and-checks).

## Integer division

Constant-divisor `div` and `mod` retain a shared Euclidean `DivMod` factor, with
`0 <= remainder < abs(divisor)`, including negative divisors and arbitrary-width
constants. Wide digit expressions remain on the retained affine `WideDivMod` relation; selected
consumers install equivalent exact rows without introducing open scalar coordinates.
Finite digits retain their finite ownership. Open sides remain open; no finite bound
is guessed. Variable and zero divisors remain unsupported in this frontend.
The [selected linear encoding](03.1-source-relations.md#integer-division-relations) installs the
complete relation beside other constraints before theory checks.

Integer absolute values retain `Abs` for scalar operands and results, and
`AffineAbs` for exact wide affine values. Finite wide intermediates preserve their
digit columns. Arithmetic consumers receive equivalent nonnegative equality
alternatives; source factors expose only the implied inequality relaxation.

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

`ir.PolynomialExpression` retains exact reduced arbitrary-precision rational leaves,
source real identities, sums, products and nonnegative integer powers. Relations
retain both polynomial sides and equality, disequality or strict/non-strict ordering.
`ir.PolynomialRealFormula` carries constants, source Boolean identities, negation,
conjunction and disjunction. The mathematical kernel owns polynomial normalization;
source IR does not implement sparse polynomial algebra. Structural equality describes
the owned expression/formula graph, rather than general algebraic equivalence.
`VarRemap` preserves coefficients, relations and Boolean polarity, including aliases;
removing a referenced variable declines. Captured operands and source maps cannot be
changed through caller buffers or getter results. Variable counts are checked before
publication.

Numeric conditionals in a snapshot retain a selection relation with its original
guard and ordered branches; `distinct` retains its ordered operands, using
`AllDifferent` for bare integer positions and exact affine `Distinct` otherwise.
Objective capture evaluates the original expression in its lexical scope:
representable affine coefficients keep affine metadata, quadratic and non-binary
rational coefficients keep the exact objective metadata, and function objectives
keep their whole expression. Polynomial expansion and decision work belong to the
nonlinear kernel and theory.

Exp is the real exponential, log the natural logarithm on strictly positive
arguments, and sine and cosine use radians. Every arithmetic atom must have defined
terms, so negation cannot make an invalid log term satisfiable, and a helper
argument keeps its log domain even when its value is unused. Domain obligations
stay inside their conditional branch. This extension uses exact semantics and has
no delta or tolerance mode. Its constraints run through traversal, LS, ALNS and
mixed portfolios with original-source checks; transcendental objectives return
UNKNOWN because no exact function optimization exists. See the
[transcendental theory contract](25-transcendental.md).

## Results and objectives

The CLI renders exact rational values, algebraic `(root-obj p k)` values and
symbolic function values in original coordinates; private auxiliaries are never
printed. Closed transcendental coordinates use `((_ klause-real exp) value)`,
`((_ klause-real log) value)`, `((_ klause-real sin) value)`,
`((_ klause-real cos) value)` and `((_ klause-real pi))`. These constructors have
fixed mathematical meanings independent of input helper names, and a decimal
approximation never replaces their authority.

Optimization emits `; objective=<value>` for each published incumbent in the source
objective's direction and `; optimizationStatus=<status>` at completion, including `optimal`,
`best-found` and `unbounded`. These comments appear without `-s`; `sat` alone
does not establish optimality. Finite, open-theory, polynomial and quadratic optimization
stream accepted source improvements before the invocation completes.
Exact theory objectives use arbitrary-precision integer or reduced `numerator/denominator`
text, including values outside `Long` and non-terminating rational values.
Finite witnesses report objectives from certified real values and exact integer
sums. Supported linear integer optimization uses the complete theory route when
its objective range can exceed the consecutive-integer range of binary64.

Quadratic objectives retain exact rational/algebraic incumbent values and report a
separate `objectiveBound`, `objectiveBoundDirection` and `objectiveBoundStrict`
when a global bound is checked. An unattained boundary reports
`optimizationStatus=unattained-infimum` or `unattained-supremum`; its model remains
a feasible incumbent with its own objective value. The boundary is never printed
as an attained model. Local search and ALNS report `best-found`, even when a
neighborhood optimum is known. `sat` describes source feasibility only.

Open integers retain arbitrary-precision source bounds. A linear relaxation witness
does not establish integrality: shared search splits fractional integers at exact
floor/ceiling thresholds. Strict real inequalities require an interior witness or
an exact strict-feasibility refutation. A closure point on a strict boundary is
insufficient. Budgets can leave the solve unknown; support for linear syntax is
not an unlimited completeness guarantee for every integer model.

Finite optimization incumbents stream each original objective with its complete
SMT model, and terminal metadata does not repeat an already published incumbent.

---

Previous: [8. MPS source semantics](08-mps.md) · [Contents](README.md) · Next: [10. Sessions and streams](10-sessions.md)
