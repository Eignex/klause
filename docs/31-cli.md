# 31. Command-line interface

The command-line interface is the second public surface built on the
[library API](30-api.md). It reads one input instance, admits its source model
through the frontends of Part II, runs the selected solve route and renders
assignments or a terminal verdict. All formats share the solve-control flags and
driver; each frontend owns parsing and output semantics.

The chapter starts with building and running the CLI, then covers format dispatch
and how arithmetic results are published, and ends with the flags and the
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
[SMT-LIB result semantics](09-smtlib.md).

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
[portfolio admission](16-portfolio.md) owns acceptance.

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
  `+/-<pass>` deltas; applies to finite or linear preparation.
- `--lp <ceiling>`: relaxation ceiling with the same levels and `+/-<technique>`
  deltas. Arms choose intensity below it. `--lp-pricing` selects zero-objective
  entering policy. Native arithmetic routes reject explicit LP flags.
- `--exact`: continuous float interpretation and zero source tolerance for the
  format's declared policies. Unsupported exact lowering declines explicitly.
- `--param <key>=<value>`: repeatable engine parameters. Unknown keys exit with
  status 2. The families are CP and LS strategy settings, portfolio slice policy
  (`slice-work`, `slice-max-work`, `slice-growth`), resource work
  (`node-limit`, `presolve-work-limit`, see [resource allowances](11-resources.md)),
  `dry-run-presolve=true` for a preparation summary without solving, and presolve
  effort ([presolve](12-presolve.md)).
- `--ozn FILE` (MiniZinc mode): render output with the native `.ozn` applier
  instead of `solns2out`.

### Arithmetic routes

Native arithmetic routes compose the selected engine with exact completion: complete
engines run the theory's traversal, `-e ls` and `-e alns` search exact-coordinate
neighborhoods with witness-only authority, and `-e mixed` combines them. Each route
has its own work currency, separate from `node-limit`:

| Route | Parameters | Chapter |
|---|---|---|
| Open linear | `open-work-limit`, `open-branching`, `open-hint-flips`, `open-hint-min-splits` | [20](20-linear-real.md)–[22](22-linear-mixed.md) |
| Polynomial real | `nra-work-limit`, `nra-dense-coefficients`, `nra-matrix-cells` | [24](24-polynomial-real.md) |
| Nonlinear integer | `nia-candidates`, `nia-moves`, plus the polynomial limits | [23](23-nonlinear-integer.md) |
| Quadratic optimization | the polynomial limits; open integers also use `nia-*` | [28](28-exact-arithmetic.md#exact-quadratic-certificates) |
| Transcendental | shared exact-satisfaction controls | [25](25-transcendental.md) |

Omitted work limits are unlimited. Exhausted candidates or work yield UNKNOWN; LS and
ALNS keep checked incumbents, and neighborhood exhaustion supplies no global claim.

### Statistics

`-s` adds statistics to every format's output. `solvePipeline` names the selected
entry point or terminal admission outcome (`finite-cp`, `open-theory`,
`polynomial-theory`, `quadratic-polynomial`, `interrupted`, `refuted` and so on).
`declaredTheoryRoute` is the request's classification before preparation and
`reportedBackend` the final backend; these labels describe stages and do not prove
that a theory check executed. Arithmetic routes report their work under their own
prefix (`open*`, `nra*`, `quadratic*`, `transcendental*`) and, on a resource stop,
the limit that stopped them. The [search statistics](14.5-primal-and-statistics.md#statistics)
define the conflict counters.

`frontendLoadMs` covers parsing, lowering, routing and solvable construction; the
nested `frontendRoutingMs` must not be added to it. An omitted native work block is
not a zero. `armFailure.<label>` reports a caught arm exception as one-line JSON; a
sibling's accepted result can still be the verdict. See
[portfolio failures](16.2-portfolio-scheduling.md#reseeding-and-arm-failures).

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
[typed source semantics](03-model.md#typed-source-semantics). A nondefault effective
interpretation is printed as a protocol comment before results; it adds no proof
authority. [FlatZinc floats](07-flatzinc.md#flatzinc-floats) define the grid contract.

## Environment knobs

The CLI captures its configuration in `CommonOptions` before loading the input, so
later changes to library defaults cannot change the run. Process-wide defaults are
read as a JVM system property or as the uppercased environment variable
(`klause.bitset.threshold` → `KLAUSE_BITSET_THRESHOLD`); a flag overrides either.
They cover the default engine, arm-pool size, LP ceiling, LP and domain resource
limits, and the preparation budget (`klause.presolve.budget.ms`,
`klause.presolve.budget.fraction`; see
[resource allowances](11-resources.md#configuration-and-lifetime)). Source
interpretation is never an environment setting: it belongs to the format's typed
`SemanticOverrides`.

---

Previous: [30. Library API](30-api.md) · [Contents](README.md) · Next: [32. Development](32-development.md)
