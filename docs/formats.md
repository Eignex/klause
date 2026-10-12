# Input semantics

A frontend translates source syntax into a shared model and retains the information
needed to render and check source assignments. The pipeline chooses solving
mechanisms from that model. Parsing a constraint, finding a candidate and proving a
result are separate steps; source correspondence must survive each one.

This page explains shared routing and current coverage. Detailed parsing,
representation and publication contracts live with their source format. The
[CLI](cli.md#formats-and-dispatch) lists dispatch names and extensions.

## Source contracts

| Source | Detailed contract |
|---|---|
| Kotlin schema DSL | [Schema lowering](formats/schema.md): float representation, exact products and refused contexts |
| FlatZinc and MiniZinc output | [FlatZinc semantics](formats/flatzinc.md): Boolean channels, exact/grid floats and reconstruction |
| SMT-LIB | [SMT-LIB semantics](formats/smtlib.md): logical/arithmetic admission, lowering and exact models |
| MPS | [MPS semantics](formats/mps.md): decimal correspondence, exact certification and tolerances |
| DIMACS, WCNF, OPB/WBO and XCSP3 | [Finite formats](formats/finite.md): encodings, objectives and source validation |

## Solving coverage

The solver supports finite constraints, linear arithmetic and polynomial real
constraints through different mechanisms. This is current execution coverage,
separate from what an individual frontend can parse. [The vision](vision.md)
requires common model breadth and an available complete-search/local-search pair;
the specific remaining misalignments are listed below.
Planned execution and evidence refactors are tracked under
[active work toward the vision](vision.md#active-work-toward-the-vision).

| Model family | Mechanisms | Boundary |
|---|---|---|
| Boolean and finite integer factors, including globals and integer products | Finite propagation and backtracking; local-search projections and portfolios | Refutations and optimum claims require completed proof checks and traversal |
| [Difference logic](theories/difference.md) | Integer graph components or real graph checks inside the linear route | Requires complete fragment coverage and numerical graph admission |
| [Linear integer arithmetic](theories/linear-integer.md) | Shared search, exact linear checks, integer reductions and branching | Open integer state remains theory-owned; optimization has separate admission |
| [Linear real arithmetic](theories/linear-real.md) | Exact real theory or certified LP completion | Source bounds, strictness and Boolean structure remain authoritative |
| [Linear mixed arithmetic](theories/linear-mixed.md) | Shared search and exact linear checks with integer branching | Relaxation feasibility alone does not establish integrality; strategy coverage has route-specific gaps |
| [Polynomial real arithmetic](theories/polynomial-real.md) | Exact conditioned completion with backtracking, local search, ALNS and mixed portfolios | Finite factors and exact quadratic objectives compose; open integer coordinates retain separate integer ownership |
| [Nonlinear integer arithmetic](theories/nonlinear-integer.md) | Exact integer candidates and repairs; quadratic optimization through shared wide integer branching | Unresolved open integer traversal remains incomplete; candidate or branch allowance exhaustion yields UNKNOWN |
| [Continuous and mixed quadratic optimization](theories/nonlinear-integer.md#quadratic-objectives) | Checked convex bounds and witnesses, objective-image completion, finite traversal and exact open integer splits | Local neighborhoods supply incumbents only; undecided nonconvex or infinite integer branches withhold global claims |
| Transcendental constraints such as `exp`, `log` and `sin` | No built-in source/theory route | [#2627](https://github.com/Eignex/klause/issues/2627) tracks this gap |

A relaxation supplies deductions, not support for omitted source constraints.
[Factor capabilities](../klause/src/commonMain/kotlin/com/eignex/klause/solver/FactorExecutionCapabilities.kt)
describe implemented engine roles. Format-specific admission and result rules are
linked below; [theory contracts](theories.md) define the fragment-specific checks
and composition boundaries.

### Identified vision misalignments

These restrictions and publication risks conflict with the support contract; they
are work to close, not permanent exceptions to shared breadth or source checking.

| Gap | Current behavior | Tracking |
|---|---|---|
| Real linear objectives on polynomial models | Linear objective admission rejects costs accepted by an equivalent quadratic objective specification | [#2655](https://github.com/Eignex/klause/issues/2655) |
| Public solver facades | Direct finite baking bypasses source services used by the shared pipeline | [#2656](https://github.com/Eignex/klause/issues/2656) |

## Finite and open models

The [support policy](vision.md#shared-model-breadth) separates
source semantics, strategy coverage and proof authority. A frontend admits only
syntax it can preserve losslessly under its declared semantics. A supported model
can still receive UNKNOWN from an incomplete or resource-limited solve; unsupported
syntax and execution retain their own diagnostics or termination cause. Admission
coverage still differs between strategies; these differences are implementation
gaps under the shared portfolio requirement.

Source routing chooses a finite solve, an open-theory solve, a refutation or an
unsupported result. It can first prove that open integer sides have finite bounds.
That proof uses a slice of the shared preparation allowance and the solve's
cancellation token. Failure to close a bound must not replace it with a guessed
finite domain.

`-e` selects complete traversal, local search, ALNS or a mixed portfolio over the
selected source route. Supported linear and polynomial sources retain the same
constraints and objective across those strategies, with exact arithmetic completion. SMT-LIB and MPS can fall back to open local search for
an unsupported fragment or objective; it supplies witnesses without a completeness
claim. Exact FlatZinc declines unsupported open fragments.
`--param open-bound-proof=false` disables the
routing proof for MPS, SMT-LIB and exact FlatZinc, allowing a supported source to
be measured through its open route. Unsupported syntax or a declined exact
operation remains undecided.

MPS mixed models whose hybrid lowering differs from source decimals prepare exact
original rows and objective definitions before routing. Their complete and heuristic
arms operate on the same source-equivalent model; the default purely continuous LP
lane retains its separately identified tolerance guarantee. See
[MPS source semantics](formats/mps.md).

## Execution metadata

`ir.SearchHints` carries ordered Boolean/integer variable groups, variable-selection
and value-selection intent, fallback policy and solution guidance. Frontends translate
syntax into these groups before finite execution; real-column identifiers never enter
integer branch groups. Backtracking owns selector allocation. Source expressions,
FlatZinc output, MiniZinc output templates and protocol comments belong to the
FlatZinc frontend. The shared pipeline consumes neutral finite execution data.
