# 20. Command line and development

The command-line interface is the second public surface built on the
[library API](19-api.md). It reads one input instance, admits its source model
through the frontends of Part II, runs the selected solve route and renders
assignments or a terminal verdict. All formats share the solve-control flags and
driver; each frontend owns parsing and output semantics.

The chapter starts with building and running the CLI, then covers format dispatch,
how arithmetic results are published, and the flags and statistics it reports. It
ends with the build targets, validation tasks and library entry points used during
development.

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
[numerical dependencies](18-exact-arithmetic.md#runtime-support).

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

`--format <name>` (alias `--mode`) takes priority over the input extension; an
unrecognized extension defaults to MiniZinc mode. Compressed `.zst` inputs require
`zstd` on `PATH`. MiniZinc source models go through the
[MiniZinc integration](../klause-mzn-lib/README.md), which compiles them to FlatZinc
first. The registry in
[Main.kt](../klause-cli/src/commonMain/kotlin/com/eignex/klause/cli/Main.kt) and the
flags in [CliMode.kt](../klause-cli/src/commonMain/kotlin/com/eignex/klause/cli/CliMode.kt)
define supported names, aliases and help text.

SMT-LIB optimization emits `; objective=<value>` for each published incumbent and
`; optimizationStatus=<status>` at completion; `sat` alone does not establish
optimality. Quadratic results separate the exact incumbent objective from its
certified `objectiveBound`; unattained boundaries report `unattained-infimum` or
`unattained-supremum`. FlatZinc carries that status in `%` comments and omits the
`==========` terminator, which would assert an attained optimum. See
[SMT-LIB result semantics](05-arithmetic-formats.md#smt-lib-source-semantics).

## Arithmetic publication adapter

`OutputProtocol.onArithmeticIncumbent` accepts an already checked source witness and
its value in original objective units and direction; it carries no terminal claim.
SMT precedes each model with `sat`, and MiniZinc keeps its solution separators.
`optimizationStatus` and checked bounds arrive separately at completion, so a
terminal never repeats an emitted model and an incomplete result's incumbent keeps
`best-found` status.

`ArithmeticPublication` belongs to one invocation. It suppresses repeated witnesses
and flushes each one before returning. Source refinement runs before duplicate
handling, so a source veto cannot be bypassed and a vetoed rendering emits nothing.
MPS witnesses use original units, exact decimals and their exact-source or tolerance
qualification. The adapter performs no solver work or admission:
[portfolio admission](12-portfolio.md) owns acceptance.

Frontend solve paths keep a `PreparedSourceInvocation` and consume
`SourceExecutionResult` from `SourceSetup.execute` without selecting another solver
or reinterpreting a native refutation. Linear sources such as MPS render through
`OutputProtocol.onLinearSourceResult`, which pairs the native result with its
original-unit qualification.

## Flags

Solver-control flags are common to every mode; `--help` lists every parameter.

- `-a` / `--all-solutions`, `-n <count>`: enumeration (satisfy). `-i` is accepted as a
  no-op, since optimization always streams improvements.
- `-t <ms>` time limit, `-r <seed>`, `-s` statistics, `-v` verbose.
- `--debug`: enable statistics and lightweight runtime instrumentation. The same
  setting is available as `klause.debug=true` / `KLAUSE_DEBUG=true`.
- `-p <n>`: parallelism. Portfolio engines run sequentially at `n=1` and use up to
  `n` lanes otherwise; `fixed` rejects `n>1`. Pool size auto-tunes from `n`,
  overridable with `--param arms=N`.
- `-e <engine>` (also `klause.engine`):
  - `fixed`: one backtrack run following a FlatZinc `int_search` annotation, or the
    conflict-driven default heuristic when there is none.
  - `backtrack` (aliases `bt`, `cp`; `-f` / `--free-search`): backtrack-only
    portfolio ignoring search annotations. Selector `--param`s edit the whole pool;
    `bt-arm=label,…` pins named arms.
  - `mixed` (default): backtrack plus local search.
  - `localsearch` (alias `ls`): local-search portfolio. Strategy `--param`s and the
    recipe axes `sources`, `scoring` and `acceptance` edit the pool.
  - `alns` (aliases `lns`, `hybrid-lns`): adaptive large-neighbourhood search.
- `--presolve <strength>`: `off`, `conservative`, `default` or `aggressive`, with
  `+/-<pass>` deltas; applies to canonical factor preparation, including polynomial
  routes. `off` is accepted for native formulas without a factor view.
- `--lp <ceiling>`: relaxation ceiling with the same levels and `+/-<technique>`
  deltas. Arms choose intensity below it. `--lp-pricing` selects zero-objective
  entering policy. Native arithmetic routes reject explicit LP flags.
- `--exact`: continuous float interpretation and zero source tolerance for the
  format's declared policies. Unsupported exact lowering declines explicitly.
- `--param <key>=<value>`: repeatable engine parameters. Unknown keys exit with
  status 2. The families are CP and LS strategy settings, portfolio slice policy
  (`slice-work`, `slice-max-work`, `slice-growth`), resource work
  (`node-limit`, `presolve-work-limit`, see [resource allowances](06-sessions.md#resource-allowances)),
  `dry-run-presolve=true` for a preparation summary without solving, and presolve
  effort ([presolve](07-presolve.md)).
- `--ozn FILE` (MiniZinc mode): render output with the native `.ozn` applier
  instead of `solns2out`.

### Arithmetic routes

Native arithmetic routes compose the selected engine with exact completion: complete
engines run the theory's traversal, `-e ls` and `-e alns` search exact-coordinate
neighborhoods with witness-only authority, and `-e mixed` combines them. Each route
has its own work currency, separate from `node-limit`:

| Route | Parameters | Contract |
|---|---|---|
| Open linear | `open-work-limit`, `open-branching`, `open-hint-flips`, `open-hint-min-splits` | [Linear arithmetic](15-linear-arithmetic.md) |
| Polynomial real | `nra-work-limit`, `nra-dense-coefficients`, `nra-matrix-cells` | [Polynomial completion](16-nonlinear-arithmetic.md#polynomial-real-arithmetic) |
| Nonlinear integer | `nia-candidates`, `nia-moves`, plus the polynomial limits | [Integer candidates](16-nonlinear-arithmetic.md#nonlinear-integer-arithmetic) |
| Quadratic optimization | the polynomial limits; open integers also use `nia-*` | [Quadratic execution](16-nonlinear-arithmetic.md#quadratic-strategy-execution) |
| Transcendental | `nra-*` limits and shared exact-satisfaction controls | [Function checks](16-nonlinear-arithmetic.md#transcendental-real-arithmetic) |

Omitted limits use each route's defaults: linear and polynomial work are unlimited,
nonlinear integer search caps candidates at 1,024 and heuristic moves at 256, and
transcendental work defaults to 100,000 units. Polynomial representation caps still
apply. Exhausted candidates or work yield UNKNOWN; LS and ALNS keep checked incumbents,
and neighborhood exhaustion supplies no global claim.

### Statistics

`--debug` adds `runtimeDebug=true` and `jvmCompilationSupported` to the statistics.
On a JVM with compilation-time monitoring, `jvmCompilationStartupMs` is the
accumulated compilation time at the first snapshot, before input loading, and
`jvmCompilationMs` is the increase through loading and solving to the final
statistics snapshot. Their sum is the accumulated time at that snapshot. These
are approximate compiler-thread elapsed milliseconds, summed across compiler
threads, rather than solver CPU time or a fraction of wall time. Unsupported
runtimes, including native executables, omit the numeric counters. The collector
is initialized only for debug runs; `-s` alone does not enable it. Debug mode does
not start JFR, compilation logs or heap sampling.

`-s` adds statistics to every format's output. `solvePipeline` names the selected
entry point or terminal admission outcome (`finite-cp`, `open-theory`,
`polynomial-theory`, `quadratic-polynomial`, `interrupted`, `refuted` and so on).
`declaredTheoryRoute` is the request's classification before preparation and
`reportedBackend` the final backend; these labels describe stages and do not prove
that a theory check executed. Arithmetic routes report their work under their own
prefix (`open*`, `nra*`, `quadratic*`, `transcendental*`) and, on a resource stop,
the limit that stopped them. The [search statistics](09-learning.md#statistics)
define the conflict counters.

`openLearnedWatchVisits` counts inspected shared learned-clause watch entries,
including those skipped by a true cached member. `openLearnedBlockedWatchVisits`
counts those skips; it does not include the source-clause store.

`frontendLoadMs` covers parsing, lowering, routing and solvable construction; the
nested `frontendRoutingMs` must not be added to it. An omitted native work block is
not a zero. `armFailure.<label>` reports a caught arm exception as one-line JSON; a
sibling's accepted result can still be the verdict. See
[portfolio failures](12-portfolio.md#reseeding-and-arm-failures).

Source routes use the same `presolvePreparationMs`, work, allowance, round-entry and
pass-call fields as finite execution. Empty and stopped preparations retain these
observations even when no reduction fired. `presolveWork` uses phase units;
construction entries and root propagation work are native counters whose converted
cost is already included. The preparation duration excludes theory search.

The [effort snapshot](07-presolve.md#observations) is summarized under
`presolveInvocation_<LANE>_<pass>_<OUTCOME>_` and
`presolveStage_<LANE>_<STAGE>_<OUTCOME>_`. Each observed group reports calls,
exclusive nanoseconds (`Ns`), phase `Work`, separate `NativeWork` and summed
input/output dimensions. Pass groups also report `RebuildWork` (included in
`Work`), narrowed and fixed columns. Occurrences cover only the accompanying
`OccurrenceCalls`; absent occurrence totals mean unobserved. Eligibility skips,
empty analyses and interruptions retain their own groups, ordered by key. Output
size depends on the pass/stage catalogue and outcomes, not the number of calls.

`presolveInvocationWork + presolveStageWork + presolveUnattributedWork` equals
`presolveWork`. Native totals remain separate and must not be added to that sum.
`dry-run-presolve` prints the same keys on stderr alongside its model summary.

### Source interpretation controls

Source controls resolve against the selected format's declared semantics before
lowering; a control the format does not declare is a usage error. Each endpoint
replaces its default independently, and explicit source bounds stay authoritative.

| Control | Interpretation |
|---|---|
| `--unbounded-int-lo N` / `--unbounded-int-hi N` | Default integer declaration endpoints; `open` removes an endpoint |
| `--unbounded-real-lo N` / `--unbounded-real-hi N` | Default real declaration endpoints; `open` removes an endpoint |
| `--float-semantics continuous\|grid` | Continuous values or the format's declared grid |
| `--float-buckets N` / `--float-scale N` | Default grid point count and float-linear coefficient scale |
| `--float-grid-lo N` / `--float-grid-hi N` | Finite window for unranged grid floats |
| `--source-absolute-tolerance N` / `--source-relative-tolerance N` | Source feasibility slack components |

Values accept exact decimals, scientific notation and rationals; a value that cannot
be represented is rejected rather than rounded. Tolerance uses
`max(absolute, relative * scale)` with exact integrality, as defined in
[typed source semantics](02-model.md#typed-source-semantics). A nondefault effective
interpretation is printed as a protocol comment before results; it adds no proof
authority. [FlatZinc floats](04-finite-formats.md#flatzinc-floats) define the grid contract.

## Environment knobs

The CLI captures its configuration in `CommonOptions` before loading the input, so
later changes to library defaults cannot change the run. Process-wide defaults are
read as a JVM system property or as the uppercased environment variable
(`klause.bitset.threshold` → `KLAUSE_BITSET_THRESHOLD`); a flag overrides either.
They cover the default engine, arm-pool size, LP ceiling, LP and domain resource
limits, and the preparation budget (`klause.presolve.budget.ms`,
`klause.presolve.budget.fraction`; see
[resource allowances](06-sessions.md#configuration-and-lifetime)). Source
interpretation is never an environment setting: it belongs to the format's typed
`SemanticOverrides`.

## Development

The [repository modules](01-introduction.md#repository-modules) and
[package boundaries](01-introduction.md#package-boundaries) of the introduction are
the map for working on the code; the chapters in between describe the contract of
each component you might change.

### Modules and building

The Gradle build includes `:klause` (model, DSL, compilation, formats and solving),
`:klause-cli` (the command-line driver described [above](#build-and-run)) and
`:klause-bench`, a JVM module that is not published. The default build targets JVM
and Linux x64; releases opt into Linux arm64 and macOS arm64 with `-Ptargets.full`.

```sh
./gradlew :klause-cli:installJvmDist
klause-cli/build/install/klause-cli-jvm/bin/klause-cli --help
```

`./gradlew check` compiles and runs the tests and light checks; `./gradlew lintDocs`
runs detekt and Dokka link linting over the Kotlin sources and their KDoc. Kotlin
test tasks report and fail individually through
`kotlin.tests.individualTaskReports=true`. Exact arithmetic and lattice tests belong
to `klause`; native tests run in CI alongside a smoke start of the release CLI
executable.

### Library entry points

The [schema DSL](03-input-semantics.md#schema-lowering) owns the declaration,
compilation and decoding example. [Library APIs](19-api.md) define source-aware
strategies, finite sessions, extension surfaces and coordinate ownership.
Enumeration, counting and near-uniform sampling use compiled finite models;
[source result guarantees](01-introduction.md#results-and-completeness) and
[LP certification](17-linear-programming.md#proof-packages) define the authority of
their results.

### Tests

Unit tests cover parsing, lowering, ownership, rollback, explanations, exact
arithmetic, publication and resource limits with local deterministic fixtures or
fakes. Test execution and setup tasks make no network requests and provision no
external tools; ordinary Gradle dependency resolution is separate.
External-reference integration belongs in klause-lab. Full PR validation runs in
GitHub CI; local targeted tests are optional.

---

Previous: [19. Library API](19-api.md) · [Contents](README.md)
