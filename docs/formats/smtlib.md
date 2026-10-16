# SMT-LIB source semantics

The SMT-LIB frontend preserves logical formulas and arithmetic declarations for
the shared pipeline. Linear sources use finite or open arithmetic routes;
polynomial real satisfaction uses exact theory completion. Supported syntax and
execution strategy are separate boundaries.

[Shared input semantics](../formats.md) owns routing and result qualifications.
This page defines admission, lowering and source model rendering.

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

## Supported models

The frontend supports quantifier-free linear integer and real arithmetic, including
Boolean structure and supported minimize/maximize objectives, and polynomial
Int/Real/Bool satisfaction. Products and fixed nonnegative integral powers retain
exact source arithmetic. Bitvectors, arrays, strings and quantifiers decline.

The real-function extension admits unary `exp`, `log`, `sin` and `cos`, and the
zero-argument `pi` constant, in quantifier-free numeric sources. Function arguments
retain exact rational, polynomial and nested function expressions. Original
Boolean, integer and real declarations remain in the same source snapshot.
Lexical helpers and simultaneous `let` bindings preserve their definition scope;
a user-defined helper with a function name retains its own meaning.
Factor helper calls evaluate arguments in the caller scope, then isolate parameter
bindings from caller-local names while evaluating the body. Declared parameter sorts
are enforced even for unused arguments; integer arguments to real parameters are
promoted before the body is evaluated. Nested calls restore
the enclosing scope, and local bindings take precedence over zero-arity helper
names and their declared sorts.
Deferred Boolean `let` values retain the enclosing binding depth from before the
simultaneous bindings were introduced. Direct assertions and reified reads use
that captured view, so later shadows do not change a shared Boolean value.
Scope restoration remains iterative, including long chains of asserted aliases.

Linear lowering shares a comparison literal within one source parse only when its
folded exact coefficients, tagged integer/real coordinates, constant, relation and
strictness agree. Shared literals retain full equivalence in either polarity.
The parse-local table retains at most 65,536 coordinate/key entries; other
comparisons still lower independently. Lookup work charges the source token and
retains its cancellation. The table carries no solver state or cross-source names.

Boolean lowering folds the library constant and exact literal identities in
AND, OR and IFF after their children have been lowered. Empty, singleton,
duplicate and complementary operands retain their full logical meaning without
extra gate names. Child domain constraints remain in the source. Returning an
existing literal without emitting factors preserves its existing predicate
metadata; a newly emitted constant unit describes its positive Boolean coordinate.

Exp is the real exponential, log is the natural logarithm on strictly positive
arguments, and sine/cosine use radians. Every arithmetic atom must have defined
terms. Negation and ordinary Boolean connectives cannot make an invalid log term
satisfiable. Numeric and Boolean `ite` evaluate the selected branch. Simplifying
a zero power or Boolean result cannot erase a required domain. This extension
uses exact source semantics and supplies no delta/tolerance mode.

The shared source-bound dispatcher executes these constraints through traversal,
LS, ALNS and MIXED with original-source checks. Refutations require a completed
whole-source check; finite depth and neighborhood exhaustion retain UNKNOWN.
Transcendental objectives are captured and return UNKNOWN/Unsupported because
this route does not implement exact function optimization. See the
[transcendental theory contract](../theories/transcendental.md).

Bounded representable Int × Int products use `Product`; Int × Real products use
`RealProduct`, and Real × Real products use `ContinuousProduct`. Nonlinear source
lowering first closes integer bounds from exact unconditional linear premises under
the containing cancellation token. Proved finite ranges guide operand and global
lowering; surviving open sides retain exact source fallback. Linear-only loading
keeps its single-scan inference. Compound integer operands receive exact defining equalities. Every intermediate range must fit the
native integer representation, and mixed coefficients must preserve exact values.
Open or overflowing integers retain typed arbitrary-precision source coordinates
through [nonlinear integer execution](../theories/nonlinear-integer.md). Native
finite mixed products compose through [exact polynomial completion](../theories/polynomial-real.md#shared-model-composition).
Bounded fixed integer powers above one retain `Power`, including compound operands
with exact defining equalities. Zero and identity powers simplify arithmetically.
Polynomial source snapshots retain native power nodes. Wide exponents retain compact squaring DAGs;
representation limits produce explicit UNKNOWN without truncating the source.

Integer absolute values retain `Abs` for scalar operands and results, and
`AffineAbs` for exact wide affine values. Finite wide intermediates preserve their
digit columns. Arithmetic consumers receive equivalent nonnegative equality
alternatives; source factors expose only the implied inequality relaxation.

## Integer division

Constant-divisor `div` and `mod` retain a shared Euclidean `DivMod` factor, with
`0 <= remainder < abs(divisor)`, including negative divisors and arbitrary-width
constants. Wide digit expressions remain on the retained affine `WideDivMod` relation; selected
consumers install equivalent exact rows without introducing open scalar coordinates.
Finite digits retain their finite ownership. Open sides remain open; no finite bound
is guessed. Variable and zero divisors remain unsupported in this frontend.
The [selected linear encoding](../model.md#integer-division-relations) installs the
complete relation beside other constraints before theory checks.

## Linear and conditional lowering

Real and rational mixed comparisons retain `ExactLinear` rows with exact rational
coefficients, tagged original coordinates, strictness and Boolean reification.
Coefficient magnitude adds no frontend digit columns or unit auxiliaries. The
selected arithmetic consumer reads the complete exact row; numerical projections
may decline values they cannot represent. Integer-only rational comparisons retain
exact denominator-cleared lattice thresholds. Source witnesses and proofs remain
subject to the complete selected relation and original declaration checks.


Asserted and reified equalities between an integer conditional result and a constant
can lower directly to Boolean tests of its branches. Completed conditional definitions and equality
results are shared within one parse; nested definitions are traversed iteratively.
Primitive equality literals are shared across source uses and branch expansion.
Reified pairwise unit integer differences use lower/upper comparison gates with complete
Boolean-definition ownership. A deferred conditional equality keeps one arithmetic
placeholder, which is removed only after its branch expansion completes.
Comparisons finish open decision lists before expanding their branch tests.
Constant branch images of at most 1,024 distinct values can reject a comparison
before visiting its definition tree; unknown or larger images retain exact fallback.
After assertion and objective chains close, nested comparisons can complete missing
images through their closed branch and guard dependencies. This iterative completion
publishes only complete nonempty sets and preserves existing known images. Its node,
dependency and completion visits share the ordinary expansion allowance. Direct
one-level comparisons retain their existing path. Open, cyclic or capped metadata
keeps the arithmetic fallback; completing an image never changes numeric bounds.
Immutable guard meanings exclude unreachable arms from those images. A default
omits selector values covered by its guards, and a fully covered selector image
excludes the default even when that default is open arithmetic.
An integer conditional with a constant guard returns its selected branch directly.
A directly nested single-guard integer conditional can be restricted by the enclosing
branch's compiled literal or complement. This reads its definition without changing
shared uses; unmatched, multiple-guard and wide terms retain their exact lowering.
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
Finite integer conditionals retain native `Element` relations over materialized branch
values. Short and sparse finite decision lists use one finite choice index whose guards
retain the original equality meanings and default. Choices, affine branch columns
and result definitions participate in the same dependency closure, so Boolean
normalization can remove their unused private state. A Boolean-expanded selector
uses those guarded choices without requiring its removed numeric placeholder;
dense direct numeric selector tests remain eligible for table recovery over the
selector's whole declared domain. Wide finite conditionals use the existing
canonical digit columns with one shared choice index across every digit. Constant
branches retain constant digit arrays, including signed leading digits. A result
with an open bound retains guarded exact arithmetic equalities to its branches,
including the default when no guard holds. It introduces no finite choice index
or materialized branch columns. Wide terms with no finite magnitude use the same
guarded arithmetic relation and retain their exact source coefficients.

Choice indexes reify the original signed guard directly, retaining its identity
for arithmetic branch preferences. Guards are inputs to each conditional definition;
their source or predicate definitions remain independently owned when unused
choice state is removed or the same guard controls several selections.

Affine real conditionals retain `RealElement` over materialized branch columns.
Their choice index shares the source Boolean guard. Affine branch definitions keep
exact rational coefficients, including mixed integer/real terms, and source real
declarations remain arithmetic-owned.

The exact real component can certify supported difference fragments with a graph,
retaining the LP route for other states and conflict explanations. Its admission,
strict-edge encoding and lifecycle belong to the
[search contract](../theories/difference.md#real-graph-admission-and-checks).

Actual helper arguments and simultaneous `let` bindings retain their real log
domains even when their values are unused by the expanded body. These checks stay
inside their containing conditional branch; an unselected branch does not acquire
a domain obligation.

## Results and objectives

Closed transcendental coordinates use `((_ klause-real exp) value)`,
`((_ klause-real log) value)`, `((_ klause-real sin) value)` and
`((_ klause-real cos) value)`. Pi uses `((_ klause-real pi))`.
These model-value constructors have fixed mathematical meanings independent of
input helper names. They compose with exact rational arithmetic and the existing
algebraic `root-obj` values. A decimal approximation never replaces their authority.

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

## Polynomial source lowering

`SmtLib.parse` returns one `SmtLibProblem` containing the existing
`SourceAttachment`, declared name maps, original objective and direction. Factor
sources and exact real, integer-polynomial and function snapshots use that same
attachment and operation setup. Caller `ProblemSettings` are captured before
reading and retained by every source representation. Affine objective coefficients
use original source units; exact quadratic coefficients remain separate from finite
costs. Function objectives remain embedded in their original source snapshot.

Text, stream and source-factory overloads use the same exact declaration policy.
Typed bound replacements constrain original declarations; admission does not add a
finite integer box. Streamed admission opens its factory once and parses each
command once. Captured command nodes remain within admission while factor and
exact formula lowering consume them. A closeable stream is closed once; other
streams are exhausted. An observed read interruption prevents tail draining even
if the caller's cancellation predicate clears. Capture and lowering share the
containing work meter. Interruption throws `CancellationException` before a source
is published.

Factor-representable real products remain retained factors for library-selected
polynomial completion. Lexical helpers and objectives can retain exact formula
snapshots; helper declarations still receive lexical body and result-sort
validation. Unused declarations stay visible in original output namespaces.
Native integer snapshots preserve separate declaration-ordered Int/Real/Bool maps,
with shared numeric formula slots kept private to the prepared source plan.
Quadratic snapshots retain exact rational coefficients, original Boolean numeric
selections and objective direction. Unsupported function optimization keeps the
original expression and an honest execution decline.

Shared setup selects the solving mechanisms and publishes the common source result.
The CLI renders exact rational values, algebraic `(root-obj p k)` values and symbolic
function values in original coordinates. Numeric conditional auxiliaries remain
private. A negative SMT numeral is `(- n)`; a bare `-n` is a symbol.

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
Boolean equality/xor/implication and conditionals. Numeric conditionals retain a selection relation with a fresh result identity,
its original Boolean guard and ordered polynomial branch expressions. Selected
arithmetic consumers expand guarded equalities privately. Source declaration counts and name maps
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
Symbolic or zero division, negative/noninteger powers, nonlinear integer div/mod/to_int,
uninterpreted functions, nonpolynomial operations, quantifiers, multiple or nonquadratic objectives, incremental
commands, multiple queries and `get-value` decline explicitly.
`get-objectives` is accepted for a captured objective; the CLI reports exact
objective and bound metadata alongside its source status.

### Parsing resources

The dedicated parser accepts caller `Cancellation`, defaulting to `Never`. It polls
between streamed chunks, commands, evaluator frames and source identity validation,
and before/after rational coefficient folds. Each completed rational addition or
multiplication charges one source folding unit to the caller's meter. Interruption
throws the distinct internal `PolynomialSourceInterrupted` signal without publishing
a partial formula; invocation owners decide cancellation/budget result causality.
Polynomial expansion and decision work remain owned by the nonlinear kernel/theory.

### Admission API

`SmtLib.parse` is the public source entry. Its `SmtLibProblem.source` supplies the
same attachment to `SourceSetupRequest`; callers bind the retained affine or exact
quadratic objective and sense before `SourceSetup.prepare` and `SourceSetup.execute`.
The CLI and preparation/execution probes use this common contract instead of
frontend source classifications. Source ownership and captured resource defaults
survive every fresh invocation; traversal state belongs to execution.

A genuine open linear integer beside a bounded mixed product does not by itself
replace the factor model with a nonlinear source snapshot. Admission reads the
shared captured encoder support: a complete guarded finite linear cover retains
the bounded source owner and the open linear fragment. Genuine uncovered nonlinear
integer composition retains the exact nonlinear snapshot and its original names.

Native polynomial, integer-polynomial, function and quadratic CLI consumers attach
their admitted source snapshot and captured caller settings to `SourceSetup` before
execution. They retain exact original coordinate rendering, objective direction and
native result payloads. The mode does not construct their concrete pipeline requests.
The admission representation split remains separate from this shared execution binding;
see [source setup](../api.md#source-setup) and [resource allowances](../solver/resources.md).
