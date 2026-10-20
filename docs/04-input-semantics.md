# 4. Input semantics

Chapter 3 described the source model. This chapter describes how input formats
produce one and what every format must preserve in doing so. A frontend
translates source syntax into the shared model and retains what it needs to render
and check source assignments; the pipeline then chooses solving mechanisms from
that model. Parsing a constraint, finding a candidate and proving a result are
separate steps, and source correspondence must survive each one.

The chapter covers the rules all formats share (numeric authority, finite versus
open models, current coverage) and the relations whose source forms recur across
formats. The next five chapters take each frontend in turn.

## Source contracts

| Source | Detailed contract |
|---|---|
| Kotlin schema DSL | [Schema lowering](05-schema.md): float representation, exact products and refused contexts |
| DIMACS, WCNF, OPB/WBO and XCSP3 | [Finite formats](06-finite-formats.md): encodings and objectives |
| FlatZinc | [FlatZinc semantics](07-flatzinc.md): Boolean channels, exact/grid floats and reconstruction |
| MPS | [MPS semantics](08-mps.md): decimal correspondence, exact certification and tolerances |
| SMT-LIB | [SMT-LIB semantics](09-smtlib.md): admission, lowering and exact models |

## Numeric authority and execution selection

Formats declare their defaults as
[typed source semantics](03-model.md#typed-source-semantics) that caller overrides
replace before lowering; [source setup](30-api.md#source-setup) consumes them.
MPS retains its own execution surface.

| Source | Numeric authority |
|---|---|
| Library IR and schema DSL | Declared integers, binary64 rational real coefficients and explicit exact objective coefficients |
| FlatZinc and compiled MiniZinc | Exact float components use binary64 rational literals; grid components retain their declared approximation |
| SMT-LIB | Exact numerals, lexical scopes and original declarations; no invented integer domains |
| MPS | Original decimals, indicator conditions and objective units; tolerance publication and exact certification are distinct |
| XCSP3 | Finite integer arithmetic only |
| DIMACS/WCNF and OPB/WBO | Boolean declarations and exact integer weights |

An exact source representation does not select an arithmetic engine. Completion
consumes captured encoder support, and a numerical representation limit can
withhold one projection while the source model stays the same. See
[encoder composition](18-theories.md#extension-roles-and-completeness).

## Finite and open models

A frontend admits only syntax it can preserve losslessly under its declared
semantics. A supported model can still receive UNKNOWN from an incomplete or
resource-limited solve; unsupported syntax keeps its own diagnostic.

Source routing chooses a finite solve, an open-theory solve, a refutation or an
unsupported result. It can first prove that open integer sides have finite bounds,
using a slice of the preparation allowance; failure to close a bound never replaces
it with a guessed domain. `--param open-bound-proof=false` disables that proof for
MPS, SMT-LIB and exact FlatZinc.

`-e` selects complete traversal, local search, ALNS or a mixed portfolio over the
selected route. Supported linear and polynomial sources keep the same constraints
and objective across those strategies. SMT-LIB and MPS can fall back to open local
search for an unsupported fragment, supplying witnesses without a completeness
claim; exact FlatZinc declines unsupported open fragments. MPS mixed models keep
CP ownership of bounded integer choices (see [MPS semantics](08-mps.md)).

## Solving coverage

This is current execution coverage, separate from what a frontend can parse.
[The introduction](01-introduction.md) requires common model breadth; remaining
work is listed under
[active work toward the vision](01-introduction.md#active-work-toward-the-vision).

| Model family | Mechanisms | Boundary |
|---|---|---|
| Boolean and finite integer factors | Finite propagation and backtracking; local search and portfolios | Refutations and optima require completed traversal and proof checks |
| [Difference logic](19-difference.md) | Integer graph components or real graph checks inside the linear route | Requires complete fragment coverage |
| [Linear real arithmetic](20-linear-real.md) | Exact real theory or certified LP completion | Bounds, strictness and Boolean structure stay authoritative |
| [Linear integer arithmetic](21-linear-integer.md) | Shared search, exact linear checks, integer reductions and branching | Open integer state stays theory-owned |
| [Linear mixed arithmetic](22-linear-mixed.md) | Exact linear checks with integer branching | Relaxation feasibility alone does not establish integrality |
| [Nonlinear integer arithmetic](23-nonlinear-integer.md) | Exact integer candidates and repairs; quadratic optimization by integer branching | Incomplete; exhausted allowances yield UNKNOWN |
| [Polynomial real arithmetic](24-polynomial-real.md) | Exact conditioned completion with backtracking, local search, ALNS and portfolios | Finite factors and exact quadratic objectives compose |
| [Transcendental real arithmetic](25-transcendental.md) | Exact function checks through shared traversal and LS/ALNS | Incomplete; unsupported equality certification yields UNKNOWN |
| [Quadratic optimization](23-nonlinear-integer.md#quadratic-objectives) | Checked convex bounds, objective-image completion and integer splits | Nonconvex or unresolved branches withhold global claims |

A relaxation supplies deductions, not support for omitted source constraints.
[Factor capabilities](../klause/src/commonMain/kotlin/com/eignex/klause/solver/FactorExecutionCapabilities.kt)
list the implemented engine roles.

## Arithmetic role preservation

Arithmetic products retain every operand occurrence and the separate result role,
including coordinates shared by several roles, and remapping preserves those
positions. Native projections and selected encodings describe the same source
equation. Bounded integer/real products keep finite completion when available.

Schema multiplication, FlatZinc `int_times` and XCSP3 `mul` retain repeated operands
and result aliases. Fixed nonnegative powers from the schema, FlatZinc, bounded
SMT-LIB and XCSP3 retain `Power`; SMT-LIB polynomial snapshots keep native power
expressions. MPS, DIMACS and WCNF have no product path. OPB/WBO monomials are
conjunctions: repeated literals are idempotent and complementary literals make the
term false.

## Absolute-value source forms

Absolute-value definitions keep their operand and result roles through aliases,
Boolean scopes and remapping; a surrounding predicate can be reified while the
function stays total. See [absolute-value relations](03.1-source-relations.md#absolute-value-relations).

| Source | Retained relation |
|---|---|
| Schema integer `abs` | `IntAbs` lowers to `Abs` |
| FlatZinc integer `abs` | `int_abs` retains `Abs`, including optional and reified uses |
| FlatZinc real `abs` | `float_abs` retains `RealAbs` in exact mode and `GridAbs` under the bucket policy |
| SMT-LIB integer `abs` | `Abs`, or `AffineAbs` for wide affine values |
| XCSP3 `abs` and `dist` | `Abs` after exact materialization of affine operands |
| SMT-LIB real `abs` | Not admitted |
| MPS, DIMACS/WCNF and OPB/WBO | No absolute-value syntax |

## Execution metadata

`ir.SearchHints` carries ordered Boolean/integer variable groups, selection intent,
fallback policy and solution guidance. Frontends translate syntax into these groups
before finite execution; real columns never enter integer branch groups, and
backtracking owns selector allocation.

---

Previous: [3.2 Engine projections](03.2-engine-projections.md) · [Contents](README.md) · Next: [5. Schema lowering](05-schema.md)
