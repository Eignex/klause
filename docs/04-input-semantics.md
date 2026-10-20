# 4. Input semantics

Chapter 3 described the source model. This chapter describes how input formats
produce one and what every format must preserve in doing so. A frontend
translates source syntax into the shared model and retains the information needed
to render and check source assignments; the pipeline then chooses solving
mechanisms from that model. Parsing a constraint, finding a candidate and proving
a result are separate steps, and source correspondence must survive each one.

The chapter starts with the rules all formats share: numeric authority, the
difference between finite and open models, and the current solving coverage. It
ends with relations whose source forms recur across several formats. The five
chapters that follow take each frontend in turn: the Kotlin schema, the finite
formats, FlatZinc, MPS and SMT-LIB.

## Source contracts

| Source | Detailed contract |
|---|---|
| Kotlin schema DSL | [Schema lowering](05-schema.md): float representation, exact products and refused contexts |
| FlatZinc | [FlatZinc semantics](07-flatzinc.md): Boolean channels, exact/grid floats and reconstruction |
| SMT-LIB | [SMT-LIB semantics](09-smtlib.md): logical/arithmetic admission, lowering and exact models |
| MPS | [MPS semantics](08-mps.md): decimal correspondence, exact certification and tolerances |
| DIMACS, WCNF, OPB/WBO and XCSP3 | [Finite formats](06-finite-formats.md): encodings, objectives and source validation |

## Numeric authority and execution selection

Each row states the format's default semantics. Formats declare them as
[typed source semantics](03-model.md#typed-source-semantics) that caller overrides
replace before lowering, and [source setup](30-api.md#source-setup) consumes them
under the [shared contract](01-introduction.md#format-semantics-caller-settings-and-automatic-setup).
MPS retains its own execution surface.

| Source | Numeric authority and ownership |
|---|---|
| Library IR and schema DSL | Declared integers, binary64 rational real coefficients and explicit exact objective coefficients remain source data. Source strategies prove available bounds before selecting completion; raw prepared-plan capture preserves its source identity without solving. |
| FlatZinc and compiled MiniZinc | Exact float components use binary64 rational literals; grid components retain their declared approximation. Bounded `int2float` products can retain finite completion. Original MiniZinc checks are separate from checking the compiled FlatZinc predicates. |
| SMT-LIB | Exact numerals, lexical scopes and original declarations govern admission. Representable factors and exact expression snapshots retain source names, semantics and objective units. Library setup selects complete encoders and execution ownership without inventing integer domains. |
| MPS | Original decimals, indicator conditions and objective units govern certification. Native exact source rows expose separate finite, numerical and exact views; default tolerance publication and explicit exact certification retain distinct guarantees. |
| XCSP3 | Admitted arithmetic is finite integer arithmetic. Exact integer rows and native globals retain finite ownership; there is no original-decimal continuous LP path. |
| DIMACS/WCNF and OPB/WBO | Boolean declarations and exact integer weights use the finite route. These formats supply no continuous declarations or original-decimal completion policy. |

An exact source representation does not select an arithmetic engine. Completion
consumes captured encoder support and its conditions; numerical representation
limits can withhold one projection while preserving the same source model.
See [library entry points](30-api.md#source-aware-strategy-entry-points),
[presolve ownership](12-presolve.md#rebuild-ownership) and
[encoder composition](18-theories.md#extension-roles-and-completeness).

## Finite and open models

The [support policy](01-introduction.md#shared-model-breadth) separates
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
routing proof for MPS, SMT-LIB and exact FlatZinc, forcing a supported source
through its open route. Unsupported syntax or a declined exact
operation remains undecided.

MPS mixed models whose hybrid projection differs from source decimals retain native
exact rows, indicators and objective definitions. Source correspondence does not
select their execution policy: bounded integer choices retain CP ownership, and
selected numerical projections remain available. The default purely continuous LP
lane retains its separately identified tolerance guarantee. See
[MPS source semantics](08-mps.md).

## Solving coverage

The solver supports finite constraints, linear arithmetic and polynomial real
constraints through different mechanisms. This is current execution coverage,
separate from what an individual frontend can parse. [The vision](01-introduction.md)
requires common model breadth and an available complete-search/local-search pair.
Planned execution and evidence refactors are tracked under
[active work toward the vision](01-introduction.md#active-work-toward-the-vision).

| Model family | Mechanisms | Boundary |
|---|---|---|
| Boolean and finite integer factors, including globals and integer products | Finite propagation and backtracking; local-search projections and portfolios | Refutations and optimum claims require completed proof checks and traversal |
| [Difference logic](19-difference.md) | Integer graph components or real graph checks inside the linear route | Requires complete fragment coverage and numerical graph admission |
| [Linear integer arithmetic](21-linear-integer.md) | Shared search, exact linear checks, integer reductions and branching | Open integer state remains theory-owned; optimization has separate admission |
| [Linear real arithmetic](20-linear-real.md) | Exact real theory or certified LP completion | Source bounds, strictness and Boolean structure remain authoritative |
| [Linear mixed arithmetic](22-linear-mixed.md) | Shared search and exact linear checks with integer branching | Relaxation feasibility alone does not establish integrality; strategy coverage has route-specific gaps |
| [Polynomial real arithmetic](24-polynomial-real.md) | Exact conditioned completion with backtracking, local search, ALNS and mixed portfolios | Finite factors and exact quadratic objectives compose; open integer coordinates retain separate integer ownership |
| [Nonlinear integer arithmetic](23-nonlinear-integer.md) | Exact integer candidates and repairs; quadratic optimization through shared wide integer branching | Unresolved open integer traversal remains incomplete; candidate or branch allowance exhaustion yields UNKNOWN |
| [Transcendental real arithmetic](25-transcendental.md) | Exact real function source checks through shared traversal and LS/ALNS neighborhoods | Symbolic witnesses retain rational/algebraic leaves; unsupported equality certification and incomplete coverage retain UNKNOWN |
| [Continuous and mixed quadratic optimization](23-nonlinear-integer.md#quadratic-objectives) | Checked convex bounds and witnesses, objective-image completion, finite traversal and exact open integer splits | Local neighborhoods supply incumbents only; undecided nonconvex or infinite integer branches withhold global claims |
| Transcendental constraints such as `exp`, `log` and `sin` | No built-in source/theory route | [#2627](https://github.com/Eignex/klause/issues/2627) tracks this gap |

A relaxation supplies deductions, not support for omitted source constraints.
[Factor capabilities](../klause/src/commonMain/kotlin/com/eignex/klause/solver/FactorExecutionCapabilities.kt)
describe implemented engine roles. Format-specific admission and result rules are
linked from the [source contracts](#source-contracts); [theory contracts](18-theories.md) define the fragment-specific checks
and composition boundaries.

## Arithmetic role preservation

Arithmetic products retain every operand occurrence and the separate result role,
including coordinates shared by multiple roles. Remapping preserves those positions;
coordinate membership may deduplicate adjacency without changing the relation.
Native projections and selected consumer encodings describe that same source equation.
Conditioned linear encodings retain their captured pins and exact combined coefficients.
See [local-search grading](15.2-preparation.md#projections-and-preparation) and
[theory encoding](18-theories.md#extension-roles-and-completeness).

Bounded integer/real products retain finite completion when it is available,
including frontend requests that prefer exact linear traversal. That preference
selects an admitted linear component; it does not require polynomial completion
of a nonlinear source relation. Original source checks still govern publication.

Schema multiplication, FlatZinc `int_times` and XCSP3 `mul` retain repeated operands
and result aliases. Fixed nonnegative powers from schema, FlatZinc/MiniZinc, bounded SMT-LIB and XCSP3
retain `Power`; SMT-LIB exact polynomial snapshots retain native power expressions.
Selected multiplication-only consumers can use the equivalent shared lowering DAG.
FlatZinc float representations preserve the same roles under their declared exact or
grid semantics. MPS admits linear arithmetic; DIMACS and WCNF admit Boolean clauses,
so neither has an arithmetic product path. OPB/WBO Boolean monomials use conjunction:
repeated literals are idempotent and complementary literals make the term false.

## Absolute-value source forms

Absolute-value definitions retain their operand and result roles through aliases,
Boolean scopes and remapping. A surrounding predicate can be reified while the
numeric function remains a total definition. Optional MiniZinc values retain their
presence conditions through compilation to FlatZinc. Selected consumers enforce the
complete relation; the separately declared inequalities remain a relaxation.

| Source | Retained relation and admission |
|---|---|
| Schema integer `abs` | `IntAbs` lowers to `Abs`; integer schema handles are total values |
| FlatZinc/MiniZinc integer `abs` | `int_abs` retains `Abs`, including compiled optional and reified uses |
| FlatZinc/MiniZinc real `abs` | `float_abs` retains `RealAbs` in exact mode and `GridAbs` under the declared bucket policy |
| SMT-LIB integer `abs` | Scalar values retain `Abs`; wide affine values retain `AffineAbs` and existing finite digit columns |
| XCSP3 integer `abs` and `dist` | Retain `Abs` after exact materialization of admitted affine operands |
| SMT-LIB real `abs` | No real absolute-value syntax is admitted by the linear, polynomial or function source normalizers |
| MPS, DIMACS/WCNF and OPB/WBO | No arithmetic absolute-value source syntax or recovery; sign/index/range normalization is parser metadata |

See [model ownership](03-model.md) for finite magnitude boundaries, exact wide source
checks and private real/grid projections. [FlatZinc semantics](07-flatzinc.md)
owns exact and grid result qualifications.

## Execution metadata

`ir.SearchHints` carries ordered Boolean/integer variable groups, variable-selection
and value-selection intent, fallback policy and solution guidance. Frontends translate
syntax into these groups before finite execution; real-column identifiers never enter
integer branch groups. Backtracking owns selector allocation. Source expressions,
FlatZinc output, MiniZinc output templates and protocol comments belong to the
FlatZinc frontend. The shared pipeline consumes neutral finite execution data.

---

Previous: [3.2 Engine projections](03.2-engine-projections.md) · [Contents](README.md) · Next: [5. Schema lowering](05-schema.md)
