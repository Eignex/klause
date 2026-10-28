# 20. Command line

The CLI parses one source, binds shared [source setup](19-api.md#source-setup) and
renders its results. Common controls reach every mode; format frontends retain
parsing and output semantics. The CLI adds no solving policy.

## CLI module

`klause-cli` is multiplatform: shared dispatch and protocols live in `commonMain`,
with JVM and POSIX actuals for `Platform.kt`. The JVM distribution requires JDK 25
or newer; native executables need no JVM. Build commands live in the
[repository README](../README.md#build), and runtime numerical options in
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

Each loader closes its input source when parsing returns or fails, including when
the parser stops before end of file. Compressed sources release their readers and
decompressor processes with the same lifetime.

Exact objective and unattained-limit rendering follow the
[arithmetic format contracts](05-arithmetic-formats.md#results-and-objectives);
[FlatZinc](04-finite-formats.md#minizinc-output-rendering) defines its completion markers.

## Finite Boolean publication

WCNF and OPB/WBO optimization publish each accepted improving exact objective on an
`o` line followed by its original-variable assignment on a `v` line. The pair is
flushed before the callback returns, so interruption preserves the incumbent.
`BufferedBestOutput` emits terminal status separately and does not repeat a streamed
witness at completion. Satisfaction assignments without an objective remain buffered
until the terminal status.

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

`--param luby=N` requires a strictly positive integer. Zero and negative units
produce a configuration error before worker execution, including when an adaptive
restart schedule takes precedence.

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
    recipe axes `sources`, `scoring` and `acceptance` edit the pool; `arm=label`
    pins one catalog arm, which the axes still edit.
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
- `--output-complete-assignment` (MiniZinc mode): include bounded original integer
  and Boolean coordinates as diagnostic comments, including hidden FlatZinc variables.
  The [output contract](04-finite-formats.md#minizinc-output-rendering) defines limits
  and unavailable markers. This capture does not validate the assignment.

### Arithmetic routes

Native arithmetic routes compose the selected engine with exact completion: complete
engines run the theory's traversal, `-e ls` and `-e alns` search exact-coordinate
neighborhoods with witness-only authority, and `-e mixed` combines them. Each route
has its own work currency, separate from `node-limit`:

| Route | Parameters | Contract |
|---|---|---|
| Open linear | `open-work-limit`, `open-branching`, `open-hint-flips`, `open-hint-min-splits` | [Linear arithmetic](15-linear-arithmetic.md) |
| Polynomial real | `nra-work-limit`, `nra-dense-coefficients`, `nra-matrix-cells` | [Polynomial completion](16-nonlinear-arithmetic.md#polynomial-real-arithmetic) |
| Nonlinear integer | `nia-candidates`, `nia-moves`, `nia-cell-refinement`, `nia-cell-reuse`, `nia-cell-steering`, `nia-integer-roots`, plus the polynomial limits | [Integer candidates](16-nonlinear-arithmetic.md#nonlinear-integer-arithmetic) |
| Quadratic optimization | the polynomial limits; open integers also use `nia-*` | [Quadratic execution](16-nonlinear-arithmetic.md#quadratic-strategy-execution) |
| Transcendental | `nra-*` limits and shared exact-satisfaction controls | [Function checks](16-nonlinear-arithmetic.md#transcendental-real-arithmetic) |

Omitted limits use each route's defaults: linear and polynomial work are unlimited,
nonlinear integer search caps candidates at 1,024 and heuristic moves at 256, and
transcendental work defaults to 100,000 units. Polynomial representation caps still
apply. Exhausted candidates or work yield UNKNOWN; LS and ALNS keep checked incumbents,
and neighborhood exhaustion supplies no global claim.

### Statistics

For controlled nonlinear integer comparisons, `nia-cell-refinement=true` enables
checked univariate production and `nia-cell-reuse=true` filters fixed tuples with
source-scoped cells. Both default to false; reuse requires the producer. With the
producer enabled, statistics include `niaCellProducer`, `niaCellReuse`,
`niaCellObserved`, `niaCellRetained`, `niaCellDeclined`, `niaExcludedTuples`,
`niaRepeatedSupports` and `niaCompletionChecks`. Excluded tuples were unvisited and
inside the current numeric window; each avoids one fixed completion. Completion
checks also include initial relaxations and real repair queries. Counts sum across
private cursors and do not establish cross-cursor support identity.

`nia-cell-steering=true` additionally alternates ordinary proposals with nearest
unvisited tuples in the exact retained-cell complement. It requires reuse and
defaults to false. `niaCellSteering`, `niaCellSelections`, `niaSteeredTuples`,
`niaAllowedTuples` and `niaDuplicateTuples` distinguish policy entry, selection
work, changed proposals, distinct tuples reaching completion per cursor and
already-visited candidate attempts. Empty complements retain ordinary traversal
and supply no refutation.

`nia-integer-roots=true` admits the complete integer roots of mandatory unary
polynomial equalities and defaults to false. Statistics then include
`niaRootEquations`, `niaRootDeclines`, `niaRootReuses`, `niaRootRefutedScopes`,
`niaRootExcludedTuples` and `niaRootCompletionChecks`. A refuted scope is a cursor
whose admitted roots miss its integer window; skipped tuples avoid fixed completions.

Complete-search value guidance reports `valueSupportOrders`, `valueProbeOrders`,
`valueProbes`, `valueProbeRefutations`, `valueProbeWork` and `valueProbeCappedOrders`
when used. Hypothetical probe refutations are separate from search failures;
probe work is included in total native propagation work. Bounded orderings also
charge each variable visited by residual scoring.

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

Observed retained ILS board restarts report `lsBoardRestartChanges` and
`lsBoardRestartDeclines`. They count completed post-preparation and post-repair
input-change outcomes, including explicit declines for unchanged boards; a pending
sliced repair contributes neither count. Changed inputs do not establish a new
feasible basin. The counters merge across workers and require no debug mode.

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

---

Previous: [19. Library API](19-api.md) · [Contents](README.md)
