# SMT-LIB source semantics

The SMT-LIB frontend preserves logical formulas and arithmetic declarations for
the shared pipeline. Linear sources use finite or open arithmetic routes;
polynomial real satisfaction uses exact theory completion. Supported syntax and
execution strategy are separate boundaries.

[Shared input semantics](../formats.md) owns routing and result qualifications.
This page defines admission, lowering and source model rendering.

## Supported models

The frontend supports quantifier-free linear integer and real arithmetic, including
difference fragments, Boolean structure and supported minimize/maximize objectives.
The theory packages cover difference arithmetic, LIA, LRA/LIRA and complete pure
polynomial real satisfaction. Bitvectors, arrays, strings, quantifiers and mixed
nonlinear integer/real arithmetic remain outside the supported theory surface.

Native factor coverage is broader than SMT source admission. `Product` represents
finite Int × Int multiplication and `RealProduct` represents finite Int × Real
multiplication, but the linear SMT facade rejects products of multiple nonconstant
operands. Polynomial fallback accepts pure Real/Bool satisfaction only; it does
not admit nonlinear integer sources even when their integers have finite bounds.
`ContinuousProduct` represents Real × Real multiplication in shared models and
[schema constraints](schema.md#schema-continuous-products). The [shared polynomial service](../theories/polynomial-real.md#shared-model-composition)
composes it with finite factors across backtracking, local search, ALNS and mixed
portfolios. Nonlinear integer SMT admission is tracked separately in
[#2625](https://github.com/Eignex/klause/issues/2625). See the
[implemented arithmetic routes](../formats.md#solving-coverage).

## Linear and conditional lowering

Asserted and reified equalities between an integer conditional result and a constant
can lower directly to Boolean tests of its branches. Completed conditional definitions and equality
results are shared within one parse; nested definitions are traversed iteratively.
Primitive equality literals are shared across source uses and branch expansion.
Comparisons finish open decision lists before expanding their branch tests.
Constant branch images of at most 1,024 distinct values can reject a comparison
before visiting its definition tree; unknown or larger images retain exact fallback.
Immutable guard meanings exclude unreachable arms from those images. A default
omits selector values covered by its guards, and a fully covered selector image
excludes the default even when that default is open arithmetic.
An integer conditional with a constant guard returns its selected branch directly.
Conditional definitions retain immutable equality meanings for their guards.
When a branch compares a simple integer variable with a constant, these meanings
can exclude conflicting selector tests and omit guard tests implied by that comparison.
Conflicting branches and excluded defaults are skipped before traversing their definitions.
Equality keys whose sign reversal exceeds a signed word retain their arithmetic encoding.
A parse expands comparisons after lowering all assertions and objectives, visiting
the newest comparisons first. Expansion shares a limit of 65,536 visits; comparisons
that cannot finish retain their exact arithmetic encoding. Numeric branch definitions remain enforced whenever
a surviving constraint, objective or shared reified predicate needs them. Unused
fresh definitions are omitted after following these dependencies to a fixpoint.
Their unused integer columns and private reified predicates are removed with
surviving constraints and objectives remapped in both variable namespaces.
The same dependency closure retains Boolean connective gates and arithmetic
predicate definitions only when a surviving use needs their value. Boolean-to-integer
channels remain consumers of an existing Boolean definition, so arithmetic uses retain
the gate and its inputs.
Source declarations and arithmetic leaves remain authoritative, including open
defaults and sparse decision-list selectors. Shared search has no unconstrained
predicate columns from these omitted definitions.
Decision lists whose selector tests have this Boolean expansion keep their shared
guarded encoding. They do not become numeric tables that require the symbolic
selector's auxiliary value; direct numeric selector tests remain eligible for
finite-domain table lowering.

The exact real component can certify supported difference fragments with a graph,
retaining the LP route for other states and conflict explanations. Its admission,
strict-edge encoding and lifecycle belong to the
[search contract](../theories/difference.md#real-graph-admission-and-checks).

## Results and objectives

Optimization emits `; objective=<value>` for each incumbent in the source objective's
direction and `; optimizationStatus=<status>` at completion, including `optimal`,
`best-found` and `unbounded`. These comments appear without `-s`; `sat` alone
does not establish optimality.
Exact theory objectives use arbitrary-precision integer or reduced `numerator/denominator`
text, including values outside `Long` and non-terminating rational values.
Finite witnesses report objectives from certified real values and exact integer
sums. Supported linear integer optimization uses the complete theory route when
its objective range can exceed the consecutive-integer range of binary64.

Open integers retain arbitrary-precision source bounds. A linear relaxation witness
does not establish integrality: shared search splits fractional integers at exact
floor/ceiling thresholds. Strict real inequalities require an interior witness or
an exact strict-feasibility refutation. A closure point on a strict boundary is
insufficient. Budgets can leave the solve unknown; support for linear syntax is
not an unlimited completeness guarantee for every integer model.

## Polynomial source lowering

The internal `SmtLibPolynomial.parse` entry point preserves pure Real/Bool polynomial
satisfaction sources separately from `SmtLib.parse` and its linear factors. It performs
no theory selection and publishes no SAT/UNSAT result. Public `SmtLibAdmission.parse`
first attempts the established linear facade. Only an explicit unsupported-syntax
decline reopens the same source through polynomial lowering. Pure Real/Bool
satisfaction sources with `define-fun` helpers use lexical polynomial lowering
before legacy expansion can capture caller-local bindings. Other successful linear
inputs retain their existing routes, including integer/mixed sources and objectives.
Helper parameter/return sorts and explicit integer operations preserve integer syntax
even when there is no declared integer variable.
Reopenable streamed inputs close each owned closeable source, or drain active
sources without a close contract before retrying. Cancellation stops that drain,
prevents reopening, and returns `SmtLibInput.Interrupted` without publishing partial metadata.

The polynomial admission publishes `ir.PolynomialRealProblem`, an opaque neutral
snapshot of formula, original declaration identities and auxiliary counts. Its
`pipelineRequest()` selects the complete polynomial theory. The CLI renders the
common `OpenTheoryResult`; parsing and frontend code do not invoke the engine.
Numeric conditional auxiliaries stay private and every original declaration receives
an exact value, including unused declarations. Point-identified rational coordinates
use exact SMT numerals/division. Other algebraic coordinates use `(root-obj p k)`
with an exact integer
defining polynomial and one-based distinct real-root index. Canonical names are
quoted in the model. A negative SMT numeral is `(- n)`; a bare `-n` is a symbol.

This route supports one satisfaction result and model publication. It declines
all-solution enumeration and polynomial optimization. Local search and ALNS generate finite candidates and request exact continuous
completion. They publish exact witnesses and retain heuristic proof guarantees:
failed completion never establishes global UNSAT. Mixed solving schedules complete
backtracking, local search and ALNS over the same polynomial constraints. Native
polynomial allowances belong to the containing invocation across all workers.

### Source representation

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

The source entry point supports exact decimal/numeral coefficients, constant rational
division, repeated-variable products, powers with exact constant nonnegative integer
exponents (including lexical/helper-bound constants), chained comparisons, pairwise `distinct`,
Boolean equality/xor/implication and conditionals. Numeric conditionals use fresh real
identities with guarded defining equalities. Source declaration counts and name maps
remain separate from total auxiliary counts; unconstrained declarations stay present.
Name-map keys use canonical unquoted symbols; rendering must quote them when needed.
A syntactic nonlinear marker distinguishes products of multiple nonconstant operands
and nonconstant powers above one, without requiring polynomial expansion.

### Lexical scope and refusals

Parallel `let` values read the enclosing scope. Typed nonrecursive `define-fun` macros
capture their definition scope, and assertions retain the declarations visible at
that source command. A compiler-owned append-only binding table stores each declaration
and macro once. Environments retain a visibility cutoff into that table; appending
bindings cannot expose forward references in an earlier assertion or macro body.
Assertions between binding commands share an environment. Local `let` and parameter
scopes capture only their own bindings and link to their enclosing environment,
without copying global tables. Lookups walk local scopes iteratively before reading
the visible global entry. The table and lexical environments belong to admission
and are discarded after lowering; published source IR retains neither.
Unsupported syntax is rejected even inside a constant Boolean
branch. One `check-sat` or `check-sat-assuming` query is accepted; Boolean literal
assumptions are conjoined with the assertions. `get-model` is accepted as a request.
Mixed integer sorts, symbolic or zero division, negative/noninteger/oversized powers,
uninterpreted functions, nonpolynomial operations, quantifiers, objectives, incremental
commands, multiple queries and `get-value` decline explicitly.

### Parsing resources

The dedicated parser accepts caller `Cancellation`, defaulting to `Never`. It polls
between streamed chunks, commands, evaluator frames and source identity validation,
and before/after rational coefficient folds. Each completed rational addition or
multiplication charges one source folding unit to the caller's meter. Interruption
throws the distinct internal `PolynomialSourceInterrupted` signal without publishing
a partial formula; invocation owners decide cancellation/budget result causality.
Polynomial expansion and decision work remain owned by the nonlinear kernel/theory.

### Admission API

`SmtLibAdmission.parse` returns a linear model, opaque `PolynomialRealProblem`, or
interrupted admission. The polynomial snapshot owns its source-name maps and returns
independent metadata views. Its `pipelineRequest()` and `OpenTheoryPipeline.execute`
extension provide complete polynomial satisfaction through the existing
`OpenTheoryResult`. `PolynomialTheoryExecution` retains native counters/resource
cause beside that common result; it does not reinterpret legacy open-work units.
`PolynomialTheoryParams` captures one invocation's work, allocation and stop controls.
The source snapshot can be reused for fresh invocations; no traversal state escapes.
