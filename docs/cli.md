# Command-line interface

SMT-LIB real-function sources use the shared exact-satisfaction dispatcher with
`solvePipeline=transcendental-theory` and `declaredTheoryRoute=transcendental`.
`transcendentalWork` counts their combined native arithmetic; representation
declines use `transcendentalResource`. The exact-mode result and closed witness
constructors are defined by [SMT-LIB source semantics](formats/smtlib.md).

The CLI reads one input instance, admits its source model, runs the selected solve
route and renders assignments or a terminal verdict. All formats share the
solve-control flags and driver; each frontend owns parsing and output semantics.

Start with the build and run commands, choose a format and operation, then use the
flag reference to control search and resources. [Input semantics](formats.md)
explains coverage and result strength; [architecture](architecture.md) explains the
pipeline behind the command.

## Build and run

The module is Kotlin Multiplatform (via the `com.eignex.cli` kbuild plugin): shared CLI
logic in `commonMain`, a small platform seam (`Platform.kt`) with JVM and POSIX actuals.

JVM distribution:

```
./gradlew :klause-cli:installJvmDist
klause-cli/build/install/klause-cli-jvm/bin/klause-cli [flags] <file>
```

The dist is class file version 69, so the launcher needs a **JDK 25 or newer** on `JAVA_HOME` /
`PATH`. Gradle itself is unaffected — it provisions the toolchain it compiles against — so a
machine whose default `java` is older builds fine and then fails at run time with
`UnsupportedClassVersionError: … class file version 69.0`. Point the launcher at the provisioned
JDK when that happens:

```
JAVA_HOME=$(ls -d ~/.gradle/jdks/*-25-*/ | head -1) \
  klause-cli/build/install/klause-cli-jvm/bin/klause-cli [flags] <file>
```

The JVM launcher enables `jdk.incubator.vector` for koblas reductions and grants native access for
installed BLAS libraries. Gradle test and Java execution tasks use the same options. To check the
portable JVM reductions, use `-Pkoblas.noSimd=true`; a distribution built with that property also
omits the Vector API option.

Koblas supplies portable numerical kernels and can select an installed host BLAS
library for eligible dense calls. It packages no vendor binaries. Runtime selection,
threading and dependency pins are defined by [numerical integration](lp/dependencies.md#runtime-support).

Standalone native executable (no JVM, instant startup, no JDK needed):

```
./gradlew :klause-cli:linkReleaseExecutableLinuxX64
klause-cli/build/bin/linuxX64/releaseExecutable/klause-cli.kexe [flags] <file>
```

Release packaging — stripped per-OS binaries, the JVM dist zip, and SHA256SUMS in
`klause-cli/build/release-assets/`:

```
./gradlew :klause-cli:releaseAssets
```

MiniZinc integration goes through `klause-mzn-lib` (see [setup](minizinc.md)): the
`klause.msc` / `klause-ls.msc` solver configs point at the
`klause-mzn-lib/bin/klause-fzn` / `klause-fzn-ls` wrappers, which delegate to
this distribution.

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
`zstd` on `PATH`. MiniZinc source models go through the [MiniZinc integration](minizinc.md),
which compiles them to FlatZinc before invoking the CLI.

The registry in [Main.kt](../klause-cli/src/commonMain/kotlin/com/eignex/klause/cli/Main.kt)
and the flag declarations in [CliMode.kt](../klause-cli/src/commonMain/kotlin/com/eignex/klause/cli/CliMode.kt)
are the implementation source for supported names, aliases and help text.

SMT-LIB optimization emits `; objective=<value>` for each published incumbent in
the source objective's direction and `; optimizationStatus=<status>` at completion.
These comments appear without `-s`; `sat` alone does not establish optimality.
Finite optimization streams improvements. Open-theory, polynomial and quadratic
optimization report the returned incumbent after their invocation completes.
Quadratic results additionally separate the exact incumbent objective from its
certified `objectiveBound`, direction and strictness. Unattained boundaries report
`unattained-infimum` or `unattained-supremum` with a feasible model distinct from
the boundary.
See [SMT-LIB result semantics](formats/smtlib.md) for the status contract and
[saved benchmark results](../klause-bench/README.md#saved-results) for proof credit and
the interpretation of interrupted incumbents and unbounded witness objectives.

## Arithmetic publication adapter

`OutputProtocol.onArithmeticIncumbent` accepts an already checked source witness
and a `SourceValue` in original source objective units and direction. It carries
no terminal optimization claim. Buffered competition protocols emit the witness
alongside its exact objective through this adapter; MiniZinc retains its solution
separators. SMT precedes each arithmetic model with `sat`, asserting feasibility
without an optimization conclusion, and renders algebraic objectives with exact
root terms. `optimizationStatus` and checked bound metadata remain terminal.

`ArithmeticPublication` belongs to one CLI invocation. It suppresses repeated
rendered witnesses with the same retained exact objective representation and
counts successful emissions. Each arithmetic witness is flushed to stdout before
publication returns; output failure propagates to the caller. Quadratic
terminal metadata is delivered separately from the incumbent, preserving checked
bounds and nonattainment status without repeating an emitted model. The adapter
uses retained scalar metadata and performs no solver work or candidate admission.
A frontend source-rendering veto emits nothing and does not advance publication
state; it cannot suppress a later accepted rendering of the same payload.
[Portfolio admission](solver/portfolio.md) owns acceptance;
[source values](model.md#published-assignments-and-objectives) own exact payloads.

## Flags

Solver-control flags are common to **every** mode:

- `-a` / `--all-solutions`, `-n <count>` — enumeration controls (satisfy).
- `-i` — accepted as a no-op; finite optimization streams improvements independently
  of this flag, while open-theory, polynomial and quadratic routes report their
  returned incumbent.
- `-f` / `--free-search` — ignore the model's search annotations. Alias for `-e cp` (the free
  backtrack portfolio); with no `-f` and no `-e`, the built-in default is `mixed`,
  overridable through `klause.engine` / `KLAUSE_ENGINE`.
- `-t <ms>` time limit, `-r <seed>`, `-s` statistics, `-v` verbose.
- `-p <n>` — MiniZinc-standard parallelism (core count). Finite portfolio engines
  run sequentially at `n=1` and use up to `n` lanes at `n>1`; finite `fixed` rejects
  `n>1`. Finite pool size auto-tunes from `n`, overridable with `--param arms=N`
  (or the `ls=N`/`bt=N` split). Open linear MIXED/LS/ALNS execution uses up to `n`
  lanes; its BACKTRACK/FIXED routes run one traversal. Polynomial, nonlinear-integer
  and quadratic source adapters accept the flag but do not allocate extra lanes.
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
    pinned; single-solver A/B is just `-p1`). `--param bt-arm=label,…` instead pins named catalog arms.
  - `alns` (aliases `lns`, `hybrid-lns`) — adaptive large-neighbourhood search.
  - `ls` takes the ls strategy `--param`s (`arm=`, `strategy=`, `tabu-tenure`, `noise`, …). With
    `sources=`/`strategy=bare` it builds a composable recipe over the LS axes — `sources` (e.g.
    `violated,argmin`), `scoring` (`weighted|raw`), `acceptance` (`greedy|walksat|probsat|skew|sa`) —
    edited across the pool, for A/B-testing each axis.
- `--presolve <strength>` — `off`, `conservative`, `default`, or `aggressive`, with
  `+/-<pass>` deltas to toggle individual passes.
- `--lp <ceiling>` — relaxation ceiling `off`, `conservative`, `default`, or `aggressive`,
  with `+/-<technique>` deltas. Portfolio arms choose intensity below this ceiling.
  Finite `fixed` uses no LP relaxation and accepts only `off` when this flag is supplied.
- Backtrack overrides `--param lp-variable-shaving=true` and `lp-prune-hulls=true`
  enable objective-independent root work on satisfaction and optimization models.
  `lp-root-cuts=false` disables root cut harvesting independently of node separation.
- `--lp-pricing <policy>` — zero-objective LP entering selection:
  `min-bound-support` (default) or `largest-pivot`.
- `--exact` — preserve continuous FlatZinc floats and their open bounds; use the shared LRA/LIRA
  route for supported linear satisfaction models and exact LP certification for continuous objectives.
  Float constraints without exact lowering are declined. For MPS, require exact certificates instead
  of accepting continuous leaves by source tolerance, and decline a lowering that differs from its source.
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
    default `false`). Residual reporting observes ordinary LS arms without changing
    their recipes; ALNS bootstrap is excluded. Under `-s`, `lsBestResidualViolation`
    and `lsBestResidual.<factor-kind>` describe one best committed assignment's graded
    violations. `lsBestResidualFactor.<id>` gives bounded representative degrees,
    committed Boolean/integer coordinates and omission counts in local-search model
    id spaces after lowering and presolve. Reified rows use
    `ReifiedLinear.<op>.<shape>`, with `binary` for one
    input bounded to 0..1, `unary` for another single input and `multi` for multiple
    inputs. Additive `lsBestResidualFactorDetails.<id>.<key>` pairs report bounded
    ordered comparison/row semantics and captured coordinates' maintained definitions;
    [the local-search contract](solver/local-search.md#residual-diagnostics) defines
    their keys, omission counts and unsupported shapes. The categories partition the
    total. These diagnostics do not claim a
    feasible witness; collecting them adds scans at strict improvements and should
    be separated from acceptance timing.
  - resource work: `node-limit=auto|unlimited|N` and `presolve-work-limit=auto|unlimited|N`.
    `N` is nonnegative; zero is exhausted. Presolve automatic derives work from its phase
    time policy. Unlimited work keeps the separate phase clock. See
    [resource allowances](solver/resources.md) for units and parallel quota behavior.
  - presolve effort (any engine): `presolve-abort-fraction` (finite number in `[0,1]`, default `0.001`),
    `presolve-max-rounds` (nonnegative, default `1` for conservative, `16` for default/aggressive),
    `presolve-probe-per-var` and `presolve-probe-total` (nonnegative propagation-call caps;
    defaults `256`/`20000`, aggressive `4096`/`250000`). These edit only this invocation's plan.
    Finite statistics include effective settings, `presolvePreparationMs`, metered work, round entries,
    pass calls and root probe calls, including no-op preparations. Preparation time includes source-safe presolve but excludes frontend
    routing and input compilation; work/probe counters span the shared metered source and finite phases.
    Round caps apply per schedule, and probe caps apply separately to each integer SAC tier per bake;
    Boolean failed-literal probes use cancellation rather than those caps. Aggregate counters can
    therefore exceed one cap across reseeds. Missing meters omit work/call counters.
  - presolve (any engine): `affine-pivot-order` (`markowitz|stable_id`) — the order affine elimination
    picks its pivots in. `markowitz` (the default) takes the lowest estimated fill first; `stable_id` takes
    them in model order. Both yield the same solutions; the order decides how many variables the pass
    eliminates within its fill-in budget, so `stable_id` is only there as a measurement baseline.

Polynomial-real satisfaction composes complete finite traversal, local search,
ALNS and mixed portfolios with exact polynomial completion. Pure-real sources and
fixed finite projections use exact coordinate neighborhoods in heuristic arms.
Finite-column and exact quadratic objectives are supported; all-solution
enumeration remains outside this route. Its native parameters are
`nra-work-limit=auto|unlimited|N`, `nra-dense-coefficients=auto|unlimited|N` and
`nra-matrix-cells=auto|unlimited|N`: defaults are unlimited work, 65,536 coefficients
and 262,144 matrix cells. Zero work prevents solve entry; zero matrix cells declines
any requested nonempty elimination matrix. A dense cap must be positive. These
native units are separate from `node-limit` and linear open work.
Nonlinear integer/mixed satisfaction accepts `nia-candidates=auto|unlimited|N`
and `nia-moves=auto|unlimited|N`, with defaults 1,024 and 256. The selected engine
executes integer search, LS, ALNS or their mixed schedule through the shared pipeline.
`node-limit` is consumed once for search/heuristic strategy effort; `nra-work-limit`
remains cumulative exact arithmetic work. `lsMoves` and `lsCompletions` record
heuristic activity. Candidate exhaustion yields UNKNOWN. See [nonlinear integer execution](theories/nonlinear-integer.md).
Polynomial satisfaction results retain backend `exact-nra`; nonlinear integer satisfaction results use
`nonlinear-integer-<engine>`. `-s` reports `nraWork`, `nraProjectedPolynomials`,
`nraLiftedCells` and, on a resource stop, `nraResource`. Legacy open counters remain
zero. The whole-run deadline includes source admission. `open-bound-proof=false`
does not disable admission or polynomial solving because this route has no finite
bound proof. [Polynomial source semantics](formats/smtlib.md#polynomial-source-lowering)
defines exact root rendering and supported syntax.

Polynomial affine optimization uses the quadratic source executor, native
allowance and exact result publisher. Affine coefficients retain their source namespaces and declared
binary64 authority; maximization passes the original objective and explicit direction.
The publisher retains exact costs and bounds, unattained limits and incomplete reasons.
Quadratic optimization uses the same source request and native controls. Its
`nra-work-limit` caps combined numerical and polynomial work; `nraWork` retains
the polynomial subset, while `quadraticNumericalWork` and `quadraticNativeWork`
report numerical and combined totals. Open integer quadratic requests also use
`nia-candidates` as their shared branch-decision cap and `nia-moves` as their
heuristic move cap. Complete source traversal can certify optima, unattained
limits, unboundedness or infeasibility. LS/ALNS retain checked incumbents and
independently checked bounds; neighborhood exhaustion supplies no global claim.

An open theory is selected by the model route. Complete engines run its traversal;
`-e ls` and `-e alns` search exact-coordinate neighborhoods with witness-only authority,
and `-e mixed` combines those arms. Supported linear Boolean or real objectives use
strict-improvement theory rounds when integral descent is ineligible; an unattained
infimum can exhaust the invocation allowance. Open and polynomial heuristic controls
include `seed`, `max-flips`, `max-instructions` and `alns-iterations`; zero improving
iterations still permits ALNS bootstrap. SMT-LIB and source-exact MPS fragments or
objectives that lack a complete theory route can use open local search for witnesses;
its engine policy is described by
[input semantics](formats.md#finite-and-open-models).
`--param node-limit=N` is a solve-wide work budget. Finite routes charge decision nodes
and counted local-search instructions at five quarters per instruction; parallel finite
portfolios assign persistent worker quotas. Complete open routes charge native open-theory decisions, checks, LIA row visits, and the
candidate hint's local-search moves on an open route. `--param open-hint-flips=N` spends up to N
local-search flips, once per request, on an unverified first-branch order over the model's shared
clauses; it is off unless asked for, changes only which side of a Boolean split is tried first, and
reports under `-s` (`openHint*`) what it drew and how many splits it went on to order. The flips come
out of the same `node-limit` budget the search spends — the draw gets what is left of it and is
charged for what it uses — so hinted and unhinted runs share the same total
allowance. Without a `node-limit` there is nothing to divide and the draw gets the N it asked for; ask
for more flips than the limit holds and the draw takes the whole remainder, leaving the traversal
none. The allowance is not spent until `--param open-hint-min-splits=N` splits have asked for a
hint (default 128), so a model the theory dominates — which reaches almost no split — never pays
for one.
`--param open-branching=activity|source-order` chooses which Boolean the traversal splits:
`source-order` (the default) walks the variables in model order, `activity` follows conflict activity
over the shared session. This setting controls Boolean variable order; theory
components can also request exact integer-bound or other arithmetic decisions.
Activity order is opt-in.
`-s` reports `openAssertingConflicts`, `openNonAssertingConflicts` and
`openReductionNs`. The [search statistics contract](solver/search.md#statistics)
defines their scopes and the distinction from native CP analysis.

Every format's `-s` output includes `solvePipeline`: `finite-cp`, `open-theory`,
`polynomial-theory`, `nonlinear-integer-theory`, `quadratic-polynomial`, `quadratic-integer`,
`open-local-search`, `interrupted` or `refuted`, the frontend's
selected solve entry point or terminal admission outcome.
`declaredTheoryRoute` is the open request's theory classification before source-safe
preparation. `reportedBackend` is the final statistics snapshot's backend identifier;
it can be `mixed`, or retain the declared theory when preparation stops before a
theory runs. These labels describe different stages and do not independently prove
that a theory check executed. Polynomial requests declare `exact_nra`, distinct from
their reported `exact-nra` backend. Nonlinear integer requests declare `incomplete_nia`;
quadratic requests declare `quadratic_optimization`. The generic exact-satisfaction
adapter can supply its own pipeline and declared-route labels. A routing refutation
or interrupted source admission has no solve backend to report.

`frontendLoadMs` covers the driver's inclusive `session.load`: parsing, lowering,
routing and solvable construction. It excludes process startup and does not isolate
parsing. `frontendRoutingMs` and `frontendRoutingOutcome` appear only when the
frontend measured routing (`finite`, `open`, `unsupported` or `refuted`). Routing is
nested within frontend load, so the durations must not be added. The driver resets
these observations before each load and freezes them for its solve invocation.
Polynomial and interrupted source admission return before bound routing, so their
inclusive load observation has no separate frontend routing measurement.

`nativeCpPropagationWork`, `nativeCpRootPropagationWork`, `nativeCpPropagationMs`
and `nativeCpRootPropagationMs` expose existing native fixpoint measurements when
their snapshot contains positive native work or time. The root values are subsets
of the total values, including private native preparation; milliseconds truncate
nanoseconds. An omitted block means no evidenced native measurement, not zero
open-theory propagation or root cost. Open-theory root and propagation timing remain
unavailable. Existing LS counters remain independent; `--param ls-phase-times=true`
enables the finite portfolio's optional `lsPhaseNanos.*` measurements, but the open
portfolio does not wire that option into its LS workers.

`--param open-bound-proof=false` declines the routing bound proof, so a model whose open sides the
relaxation would have closed goes to the open theory instead of the finite lane. That is what runs one
instance down both lanes; shrinking `-t` does not substitute, since it starves the solve along with the
proof. Read by the front-ends that route open models — MPS, SMT-LIB and exact FlatZinc — and rejected by the others.

MiniZinc-mode-only flags:

- `--ozn FILE` — render output with klause's native `.ozn` applier instead of MiniZinc's
  `solns2out`.
- `--unbounded-int-lo N` / `--unbounded-int-hi N` — default domain for unbounded `var int`
  declarations.
- `--unbounded-float-lo N` / `--unbounded-float-hi N` — search bounds for bucketed `var float`
  declarations without a range, including array elements. Defaults are `-1000000.0` and `1000000.0`;
  finite choices and `--exact` preserve open float bounds instead.

The [FlatZinc float contract](formats/flatzinc.md#flatzinc-floats) defines finite images,
conditional linear rows, bucket fallback, expansion limits and exact-mode declines.
Keep lowering and source-publication changes consistent with that contract.

## Environment knobs

The CLI captures its core configuration in `CommonOptions` before loading the input.
Format lowering, finite domain storage, preparation and automatic LP planning share
that snapshot; later changes to the ambient library default cannot change the run.

Process-wide defaults a packaged image can ship without touching the command line. Each is read as
a JVM system property (the dotted name) or an environment variable (the same name uppercased with
`.` → `_`, e.g. `KLAUSE_FLOAT_BUCKETS`). A command-line flag, where one exists, overrides it. The
names are derived from a single declaration each (`KlauseConfigSchema` / `CliKnobs`), never spelled
twice.

- `klause.engine` — default engine for a bare invocation (`-e` overrides).
- `klause.portfolio.arms` — default portfolio arm-pool size (`--param arms=N` overrides).
- `klause.lp` — default LP relaxation ceiling spec, parsed like `--lp` (`--lp` overrides).
- Core compiler/solver knobs from `KlauseConfigSchema`: `klause.pin.absent.opt.vars`,
  `klause.unbounded.int.lo` / `.hi` (the default int range for unbounded FlatZinc declarations), `klause.unbounded.float.lo` / `.hi`, `klause.float.buckets`, `klause.float.scale`, `klause.lp.max.tableau.cells`,
  `klause.lp.ceiling.tableau.cells`, `klause.bitset.threshold`.

Presolve pass selection uses `--presolve`, with no environment override. Preparation
budget defaults use `klause.presolve.budget.ms` and
`klause.presolve.budget.fraction`; an explicit millisecond value overrides the derived
fraction policy. A nonpositive millisecond value disables the derived phase work
cap and clock. An explicit `presolve-work-limit` still controls work independently.
See [resource allowances](solver/resources.md#configuration-and-lifetime).

## Dependencies

The CLI depends on `:klause` for parsers and engines, and kotlinx-coroutines to bridge
the suspend portfolio API. Corpus selection and campaign execution belong to
[benchmarking](../klause-bench/README.md). [Input semantics](formats.md) describes exact and
approximate lowering and the limits of each result.

## Portfolio diagnostics

With statistics enabled, `armFailure.<label>` carries a single-line JSON object for
an ordinary caught arm exception. It contains `armId`, `phase`, `segment`, `work`,
`type`, `message` and `trace`; control characters are escaped. Labels distinguish
replicas, while arm IDs remain stable across reseeds. These records are separate
from quarantine warnings and their `faults` count. A sibling's accepted result can
still be the run's verdict. See [portfolio failures](solver/portfolio.md#reseeding-and-arm-failures).
