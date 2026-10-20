# 31. Command-line interface

The command-line interface is the second public surface built on the
[library API](30-api.md). It reads one input instance, admits its source model
through the frontends of Part II, runs the selected solve route and renders
assignments or a terminal verdict. All formats share the solve-control flags and
driver; each frontend owns parsing and output semantics.

The chapter starts with building and running the CLI, then covers format dispatch
and how arithmetic results are published, and ends with the flag reference and the
statistics it reports. [Input semantics](04-input-semantics.md) explains coverage and
result strength.

## Build and run

`klause-cli` is a Kotlin Multiplatform module: shared CLI logic lives in `commonMain`,
with a small platform seam (`Platform.kt`) that has JVM and POSIX actuals.

```
./gradlew :klause-cli:installJvmDist
klause-cli/build/install/klause-cli-jvm/bin/klause-cli [flags] <file>
```

The JVM distribution needs JDK 25 or newer at run time. The native executable
needs no JVM:

```
./gradlew :klause-cli:linkReleaseExecutableLinuxX64
klause-cli/build/bin/linuxX64/releaseExecutable/klause-cli.kexe [flags] <file>
```

Numerical kernel selection at run time is described in
[numerical dependencies](29-numerical-dependencies.md#runtime-support).

## Formats and dispatch

| Format name | Extensions | Output |
|---|---|---|
| `minizinc` | `.fzn` | FlatZinc solution separators and verdicts; `%%%mzn-stat` statistics |
| `xcsp3` | `.xml`, `.xcsp`, `.xcsp3` | XCSP3 status, objective and named instantiation |
| `smtlib` | `.smt2`, `.smt` | `sat` / `unsat` / `unknown` and model definitions |
| `dimacs` | `.cnf` | SAT status and Boolean assignment |
| `opb` | `.opb`, `.wbo` | Pseudo-Boolean status, objective and assignment |
| `wcnf` | `.wcnf` | Weighted MaxSAT status, objective and assignment |
| `mps` | `.mps` | Linear/mixed-integer status, objective and named values |

`--format <name>` (alias `--mode`) takes priority over the input extension. An
unrecognized extension defaults to MiniZinc mode. Compressed `.zst` inputs require
`zstd` on `PATH`. MiniZinc source models go through the [MiniZinc integration](../klause-mzn-lib/README.md),
which compiles them to FlatZinc before invoking the CLI.

The registry in [Main.kt](../klause-cli/src/commonMain/kotlin/com/eignex/klause/cli/Main.kt)
and the flag declarations in [CliMode.kt](../klause-cli/src/commonMain/kotlin/com/eignex/klause/cli/CliMode.kt)
are the implementation source for supported names, aliases and help text.

SMT-LIB optimization emits `; objective=<value>` for each published incumbent in
the source objective's direction and `; optimizationStatus=<status>` at completion.
These comments appear without `-s`; `sat` alone does not establish optimality.
Every optimization route streams accepted improvements before its invocation
completes. Quadratic results separate the exact incumbent objective from its
certified `objectiveBound`; unattained boundaries report `unattained-infimum` or
`unattained-supremum` with a feasible model distinct from the boundary. FlatZinc
carries that status in `%` comments and omits the `==========` terminator, which
would assert an attained optimum. See [SMT-LIB result semantics](09-smtlib.md) for
the status contract.

## Arithmetic publication adapter

`OutputProtocol.onArithmeticIncumbent` accepts an already checked source witness
and a `SourceValue` in original source objective units and direction. It carries
no terminal optimization claim. SMT precedes each arithmetic model with `sat`,
asserting feasibility without an optimization conclusion; MiniZinc retains its
solution separators. `optimizationStatus` and checked bound metadata are delivered
separately at completion, so a terminal never repeats an emitted model and a
surviving incumbent of an incomplete result keeps `best-found` status.

`ArithmeticPublication` belongs to one CLI invocation. It suppresses repeated
accepted witnesses, counts successful emissions and flushes each witness to stdout
before returning. Source refinement runs before duplicate handling, so a source veto
cannot be bypassed, and a vetoed rendering emits nothing and does not advance
publication state. MPS objectives use original units and exact decimal formatting,
and every streamed MPS witness records its exact-source or tolerance qualification.
The adapter performs no solver work or candidate admission:
[portfolio admission](16-portfolio.md) owns acceptance and
[source values](03-model.md#published-assignments-and-objectives) own exact payloads.

Frontend solve paths retain a `PreparedSourceInvocation`. `SourceRendering` carries
only finite, exact arithmetic or original linear coordinate renderers. `SolveCore`
captures application defaults and explicit flag provenance in `SourceExecutionPolicy`;
output adapters consume `SourceExecutionResult` from `SourceSetup.execute` without
selecting another solver or reinterpreting a native refutation from the requested
engine name. Linear sources such as MPS render through `OutputProtocol.onLinearSourceResult`,
which pairs the native result with its original-unit qualification; renderers use
that qualification for source bounds and terminal claims.

## Flags

Solver-control flags are common to **every** mode:

- `-a` / `--all-solutions`, `-n <count>` — enumeration controls (satisfy).
- `-i` — accepted as a no-op; finite, open-theory, polynomial and quadratic
  optimization stream improvements independently of this flag.
- `-f` / `--free-search` — ignore the model's search annotations. Alias for `-e cp` (the free
  backtrack portfolio); with no `-f` and no `-e`, the built-in default is `mixed`,
  overridable through `klause.engine` / `KLAUSE_ENGINE`.
- `-t <ms>` time limit, `-r <seed>`, `-s` statistics, `-v` verbose.
- `-p <n>` — MiniZinc-standard parallelism (core count). `fixed` rejects `n>1`
  across finite and native source execution. Portfolio engines run sequentially
  at `n=1` and use up to `n` lanes at `n>1`. Finite pool size auto-tunes from `n`,
  overridable with `--param arms=N` (or the `ls=N`/`bt=N` split). Open linear,
  polynomial, nonlinear-integer, quadratic and function source factories retain
  their private workers and native reservations on the requested lanes.
- `-e <engine>` / `--engine <engine>` — the engine enum (also via the `klause.engine` property):
  - `fixed` — a single naked backtrack. It follows a FlatZinc `int_search` annotation;
    for a finite model with no annotation (DIMACS, OPB, and unannotated FlatZinc/XCSP3/MPS), it uses
    the standard conflict-driven heuristic. In that unannotated case, `var-selector` and `val-selector`
    are accepted as the heuristic for the one fixed run. They remain rejected when an annotation is
    present; use `-e cp` for a free-search portfolio.
  - `backtrack` (alias `bt`, `cp`) — backtrack-only portfolio (free). `mixed` *(default)* — bt+ls portfolio.
    `localsearch` (alias `ls`) — local-search portfolio.
  - `backtrack` also accepts the per-solver `var-selector`/`val-selector` (and `luby`/`phase-saving`/…)
    `--param`s: they edit the arm pool across its arms (so `-p8` stays a full pool with the override
    pinned; `-p1` runs a single solver). `--param bt-arm=label,…` instead pins named catalog arms.
  - `alns` (aliases `lns`, `hybrid-lns`) — adaptive large-neighbourhood search.
  - `ls` takes the ls strategy `--param`s (`arm=`, `strategy=`, `tabu-tenure`, `noise`, …). With
    `sources=`/`strategy=bare` it builds a composable recipe over the LS axes — `sources` (e.g.
    `violated,argmin`), `scoring` (`weighted|raw`), `acceptance` (`greedy|walksat|probsat|skew|sa`) —
    edited across the pool.
- `--presolve <strength>` — `off`, `conservative`, `default`, or `aggressive`, with
  `+/-<pass>` deltas to toggle individual passes. Explicit overrides and presolve
  tuning apply to finite or linear source preparation; other native routes decline.
- `--lp <ceiling>` — relaxation ceiling `off`, `conservative`, `default`, or `aggressive`,
  with `+/-<technique>` deltas. Portfolio arms choose intensity below this ceiling.
  Finite `fixed` uses no LP relaxation and accepts only `off` when this flag is supplied.
  Native arithmetic routes reject an explicit ceiling.
- Backtrack overrides `--param lp-variable-shaving=true` and `lp-prune-hulls=true`
  enable objective-independent root work on satisfaction and optimization models.
  `lp-root-cuts=false` disables root cut harvesting independently of node separation.
- `--lp-pricing <policy>` — zero-objective LP entering selection:
  `min-bound-support` (default) or `largest-pivot`. An explicit pricing flag applies
  to finite execution; native arithmetic routes reject it, including the default value.
- `--exact` — require continuous float interpretation and zero source feasibility tolerance
  for the policies the selected format declares. Already-exact formats retain their exact
  interpretation. An explicit grid or positive tolerance conflicts with this request. Unsupported
  exact lowering is declined explicitly. MPS requires source-certified execution under zero tolerance.
- `--format <name>` / `--mode <name>` — force a mode regardless of file extension.
- `--param <key>=<value>` — repeatable engine params (unknown/malformed keys are a usage
  error, exit 2):
  - `cp`: `seed`, `max-decisions`, `luby`, `phase-saving`, `max-learned`, `lbd-glue`,
    `var-selector` (`vsids|random|smallest-domain|input-order`), `val-selector`
    (`random|min|max|middle`)
  - `ls`: `seed`, `max-flips`, `lambda`, `tabu-tenure`, `pair-swap-budget`, `noise`, `smooth-prob`,
    `smooth-factor`; recipe axes `sources`, `scoring` (`weighted|raw`), `acceptance`
    (`greedy|walksat|probsat|skew|sa`), `cb`, `skew-alpha`, `cooling-rate`, `initial-temp`, `min-temp`
  - portfolio slices: `slice-work` (positive, default `5000`), `slice-max-work` (at least
    `slice-work`, default `150000`) and `slice-growth` (finite and at least `1`, default `1.5`).
    Under `-s`, `portfolioBaseSliceWork`, `portfolioMaxSliceWork` and `portfolioSliceGrowth`
    report the effective policy, including omitted defaults.
  - `portfolio`: `ls`, `bt` (worker counts), `seed`, `lambda`, `ls-residuals` (boolean,
    default `false`). Residual reporting observes ordinary LS arms without changing their
    recipes and reports the best committed assignment's graded violations under `-s`
    (`lsBestResidual*`); [residual diagnostics](15.4-lifetime.md#residual-diagnostics)
    defines the keys. These reports do not claim a feasible witness.
  - resource work: `node-limit=auto|unlimited|N` and `presolve-work-limit=auto|unlimited|N`.
    `N` is nonnegative; zero is exhausted. Presolve automatic derives work from its phase
    time policy. Unlimited work keeps the separate phase clock. See
    [resource allowances](11-resources.md) for units and parallel quota behavior.
  - preparation diagnostics: `dry-run-presolve=true` returns the selected finite or
    open-linear preparation summary, with factor-class counts from the returned model,
    without solving. Unsupported native phases decline explicitly.
  - presolve effort (any engine): `presolve-abort-fraction` (in `[0,1]`, default `0.001`),
    `presolve-max-rounds` (default `1` for conservative, `16` otherwise), and the
    propagation-call caps `presolve-probe-per-var` and `presolve-probe-total` (defaults
    `256`/`20000`, aggressive `4096`/`250000`), applied per integer probing tier. They edit
    only this invocation's plan; see [presolve](12-presolve.md).
  - presolve (any engine): `affine-pivot-order` (`markowitz|stable_id`) — the order affine elimination
    picks its pivots in. `markowitz` (the default) takes the lowest estimated fill first; `stable_id` takes
    them in model order. Both yield the same solutions; the order decides how many variables the pass
    eliminates within its fill-in budget.

MiniZinc-mode-only flags:

- `--ozn FILE` — render output with klause's native `.ozn` applier instead of MiniZinc's
  `solns2out`.

### Arithmetic routes

Native arithmetic routes compose the selected engine with exact completion: complete
engines run the theory's traversal, `-e ls` and `-e alns` search exact-coordinate
neighborhoods with witness-only authority, and `-e mixed` combines them. Each route
has its own native work currency, separate from `node-limit`:

| Route | Parameters | Chapter |
|---|---|---|
| Open linear | `open-work-limit`, `open-branching`, `open-hint-flips`, `open-hint-min-splits` | [20](20-linear-real.md)–[22](22-linear-mixed.md) |
| Polynomial real | `nra-work-limit`, `nra-dense-coefficients`, `nra-matrix-cells` | [24](24-polynomial-real.md) |
| Nonlinear integer | `nia-candidates`, `nia-moves`, plus the polynomial limits | [23](23-nonlinear-integer.md) |
| Quadratic optimization | the polynomial limits; open integers also use `nia-*` | [28](28-exact-arithmetic.md#exact-quadratic-certificates) |
| Transcendental | shared exact-satisfaction controls | [25](25-transcendental.md) |

`--param node-limit=N` is a solve-wide decision and heuristic instruction budget.
Finite and open routes charge committed decisions and counted local-search
instructions at five quarters per instruction; parallel portfolios assign persistent
worker quotas. `open-work-limit=auto|unlimited|N` bounds native open arithmetic
work, including checks, LIA row visits and hint production; omitted limits are
unlimited. `open-hint-flips=N` spends up to N local-search flips, once per request
and from the same `open-work-limit`, on an unverified first-branch order over the
model's shared clauses; it changes only which side of a Boolean split is tried first
and waits until `open-hint-min-splits` splits (default 128) have asked for a hint.
`open-branching=auto|activity|source-order` chooses which Boolean the traversal
splits; `auto` selects conflict activity only for a complete mixed worker with one
default linear point owner. `open-bound-proof=false` declines the routing bound
proof, so a model whose open sides the relaxation could close retains open traversal;
other frontends reject the option.

Polynomial limits default to unlimited work, 65,536 dense coefficients and 262,144
matrix cells; zero work prevents solve entry and a dense cap must be positive.
Nonlinear integer candidates and moves default to 1,024 and 256, and candidate
exhaustion yields UNKNOWN. Quadratic optimization caps combined numerical and
polynomial work with `nra-work-limit`. Complete traversal can certify optima,
unattained limits, unboundedness or infeasibility; LS and ALNS keep checked
incumbents, and neighborhood exhaustion supplies no global claim. SMT-LIB
real-function sources use the shared exact-satisfaction dispatcher; their exact
results and witness constructors are defined by [SMT-LIB source semantics](09-smtlib.md).

### Statistics

`-s` adds statistics to every format's output. `solvePipeline` names the selected
solve entry point or terminal admission outcome: `finite-cp`, `open-theory`,
`polynomial-theory`, `transcendental-theory`, `nonlinear-integer-theory`,
`quadratic-polynomial`, `quadratic-integer`, `open-local-search`, `interrupted` or
`refuted`. `declaredTheoryRoute` is the request's theory classification before
source-safe preparation, and `reportedBackend` the final snapshot's backend
identifier. These labels describe different stages and do not independently prove
that a theory check executed. Each arithmetic route reports its native work under
its own prefix (`open*`, `nra*`, `quadratic*`, `transcendental*`) and, on a
resource stop, the limit that stopped it. The [search statistics contract](14.5-primal-and-statistics.md#statistics)
defines the open-search conflict counters and their scopes.

`frontendLoadMs` covers parsing, lowering, routing and solvable construction;
`frontendRoutingMs` and `frontendRoutingOutcome` appear only when the frontend
measured routing, which is nested within the load, so the durations must not be
added. Native CP propagation work and time are reported only when the snapshot
contains positive native work; an omitted block is not a zero.

With statistics enabled, `armFailure.<label>` carries a single-line JSON object for
an ordinary caught arm exception, with `armId`, `phase`, `segment`, `work`, `type`,
`message` and `trace`. These records are separate from quarantine warnings, and a
sibling's accepted result can still be the run's verdict. See
[portfolio failures](16.2-portfolio-scheduling.md#reseeding-and-arm-failures).

### Source interpretation controls

Common source controls resolve against the selected format's declared semantics before lowering.
A control whose field the format does not declare is a usage error.
Each endpoint replaces its default independently; an omitted endpoint retains its default. Explicit
source bounds remain authoritative.
The mapper preserves a caller's typed base overrides. An exact request replaces applicable
base float/tolerance values; explicit conflicting CLI grid or positive-tolerance controls are rejected.

| Control | Interpretation |
|---|---|
| `--unbounded-int-lo N` / `--unbounded-int-hi N` | Default integer declaration endpoints; `open` removes an endpoint, and finite Long extrema stay finite |
| `--unbounded-real-lo N` / `--unbounded-real-hi N` | Default real declaration endpoints, separate from grid windows; `open` removes an endpoint |
| `--float-semantics continuous\|grid` | Continuous values or the format's declared grid interpretation |
| `--float-buckets N` / `--float-scale N` | Default grid point count and float-linear coefficient scale |
| `--float-grid-lo N` / `--float-grid-hi N` | Finite approximation window for unranged grid floats; aliases `--unbounded-float-lo` / `--unbounded-float-hi` |
| `--source-absolute-tolerance N` / `--source-relative-tolerance N` | Independently replace source feasibility slack components, retaining the other component |
| `--pin-absent-optional-values true\|false` | Schema absent-value canonicalization; current file modes reject this inapplicable policy |

Real values accept exact decimals, scientific notation and `numerator/denominator` rationals.
Decimal source-option exponents are bounded by the input representation; a value is rejected rather
than rounded. A format can decline precision it cannot represent. Source tolerance uses
`max(absolute, relative * scale)` with exact integrality, as defined in the
[typed source contract](03-model.md#typed-source-semantics).

FlatZinc, SMT-LIB and MPS support undeclared numeric defaults. MPS replaces defaults
only for declarations without any BOUNDS record; explicit one-sided and open ranges
retain their complete source range. Grid controls apply to FlatZinc; SMT-LIB requires
continuous values. MPS also exposes decimal source feasibility tolerance. Boolean and finite XCSP3 formats reject inapplicable numeric
policies. A nondefault effective interpretation is printed as a protocol comment before results,
including exact rational endpoints. That qualification adds no proof authority.

The [FlatZinc float contract](07-flatzinc.md#flatzinc-floats) defines finite images,
conditional linear rows, bucket fallback, expansion limits and exact-mode declines.
Keep lowering and source-publication changes consistent with that contract.

## Environment knobs

The CLI captures its core configuration in `CommonOptions` before loading the input.
Format lowering, finite domain storage, preparation and automatic LP planning share
that snapshot; later changes to the ambient library default cannot change the run.

Process-wide defaults a packaged image can ship without touching the command line. Each is read as
a JVM system property (the dotted name) or an environment variable (the same name uppercased with
`.` → `_`, e.g. `KLAUSE_BITSET_THRESHOLD`). A command-line flag, where one exists, overrides it. The
names are derived from a single declaration each (`KlauseConfigSchema` / `CliKnobs`), never spelled
twice.

- `klause.engine` — default engine for a bare invocation (`-e` overrides).
- `klause.portfolio.arms` — default portfolio arm-pool size (`--param arms=N` overrides).
- `klause.lp` — default LP relaxation ceiling spec, parsed like `--lp` (`--lp` overrides).
- Resource knobs from `KlauseConfigSchema`: `klause.lp.max.tableau.cells`,
  `klause.lp.ceiling.tableau.cells`, `klause.bitset.threshold`, and preparation controls.

Source declaration bounds, float grids, fixed-point scale and absent optional-value
policy belong to the format or schema's typed `SemanticOverrides`. Global semantic
property/env keys are rejected with that migration path. Resource settings retain
one invocation snapshot independently of the source interpretation.

Presolve pass selection uses `--presolve`, with no environment override. Preparation
budget defaults use `klause.presolve.budget.ms` and
`klause.presolve.budget.fraction`; an explicit millisecond value overrides the derived
fraction policy. A nonpositive millisecond value disables the derived phase work
cap and clock. An explicit `presolve-work-limit` still controls work independently.
See [resource allowances](11-resources.md#configuration-and-lifetime).

## Dependencies

The CLI depends on `:klause` for parsers and engines, and kotlinx-coroutines to bridge
the suspend portfolio API.

---

Previous: [30. Library API](30-api.md) · [Contents](README.md) · Next: [32. Development](32-development.md)
