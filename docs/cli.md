# Command-line interface

Klause solves one input instance per invocation. All formats share the solver-control
flags and solve driver; each frontend supplies parsing and output.

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

SMT-LIB optimization streams `; objective=<value>` for each incumbent in the source
objective's direction and emits `; optimizationStatus=<status>` at completion.
These comments appear without `-s`; `sat` alone does not establish optimality.
See [SMT-LIB result semantics](formats.md#smt-lib) for the status contract and
[saved benchmark results](benchmarking.md#saved-results) for proof credit and
the interpretation of interrupted incumbents and unbounded witness objectives.

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

The LP basis uses koblas containers, Level 1 kernels and sparse primitives, which work without a
vendor library. Koblas packages no BLAS binaries. Dense Level 2/3 operations require an installed
LP64 vendor: Linux selects oneMKL/AOCL on x64 or ArmPL on arm64, falling back to OpenBLAS; macOS
uses system Accelerate. Selection is immutable and each vendor call uses one compute thread.
There is no solver backend setting for this. See [dependency integration](lp/koblas.md)
for reproducible builds and artifact provenance.

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

## Flags

Solver-control flags are common to **every** mode:

- `-a` / `--all-solutions`, `-n <count>` — enumeration controls (satisfy).
- `-i` — accepted as a no-op (improving incumbents already stream on the optimize path).
- `-f` / `--free-search` — ignore the model's search annotations. Alias for `-e cp` (the free
  backtrack portfolio); with no `-f` and no `-e`, the default engine is `fixed`.
- `-t <ms>` time limit, `-r <seed>`, `-s` statistics, `-v` verbose.
- `-p <n>` — MiniZinc-standard parallelism (core count). The portfolio engines (`backtrack`/`mixed`/`localsearch`)
  run sequentially at `n=1` and as an `n`-worker parallel pool at `n>1`; the naked engine
  (`fixed`) is single-core. Pool size auto-tunes from `n`, overridable with
  `--param arms=N` (or the `ls=N`/`bt=N` split).
- `-e <engine>` / `--engine <engine>` — the engine enum (also via the `klause.engine` property):
  - `fixed` *(default)* — a single naked backtrack. It follows a FlatZinc `int_search` annotation;
    for a finite model with no annotation (DIMACS, OPB, and unannotated FlatZinc/XCSP3/MPS), it uses
    the standard conflict-driven heuristic. In that unannotated case, `var-selector` and `val-selector`
    are accepted as the heuristic for the one fixed run. They remain rejected when an annotation is
    present; use `-e cp` for a free-search portfolio.
  - `backtrack` (alias `bt`, `cp`) — backtrack-only portfolio (free). `mixed` — bt+ls portfolio.
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
  `fixed` uses no LP relaxation and accepts only `off` when this flag is supplied.
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
  - `portfolio`: `ls`, `bt` (worker counts), `seed`, `lambda`
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

An open theory is selected by the model route and uses the shared complete theory solver
independently of `-e`. SMT-LIB and source-exact MPS fragments/objectives that lack a complete
theory route can use open local search for witnesses; its engine policy is described by
[input semantics](formats.md#finite-and-open-models).
`--param node-limit=N` is a deterministic solve-wide budget: it counts
finite decision nodes on a finite route and open-theory decisions, checks, LIA row visits, and the
candidate hint's local-search moves on an open route. `--param open-hint-flips=N` spends up to N
local-search flips, once per request, on an unverified first-branch order over the model's shared
clauses; it is off unless asked for, changes only which side of a Boolean split is tried first, and
reports under `-s` (`openHint*`) what it drew and how many splits it went on to order. The flips come
out of the same `node-limit` budget the search spends — the draw gets what is left of it and is
charged for what it uses — so a hinted run and an unhinted one at the same limit do the same total
work. Without a `node-limit` there is nothing to divide and the draw gets the N it asked for; ask
for more flips than the limit holds and the draw takes the whole remainder, leaving the traversal
none. The allowance is not spent until `--param open-hint-min-splits=N` splits have asked for a
hint (default 128), so a model the theory dominates — which reaches almost no split — never pays
for one.
`--param open-branching=activity|source-order` chooses which Boolean the traversal splits:
`source-order` (the default) walks the variables in model order, `activity` follows conflict activity
over the shared session. Only the Boolean skeleton is branched either way — the theory decides the
arithmetic residual at each leaf. Activity order is opt-in.
`-s` reports `openAssertingConflicts` and `openNonAssertingConflicts` for the shared first-UIP analyzer.
The latter counts sound learned conflicts that retain chronological fallback because resolution cannot
make the clause asserting. A nonzero count is the trigger to inspect missing component reasons. Native
CP analysis, root refutations, and conflicts without a usable clause are outside these two counters.
`openReductionNs` reports the shared learned store's total wall time selecting clauses and rebuilding
watch lists at restart boundaries. It is zero when the cap is off or no reduction pass is needed.
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

The [FlatZinc float contract](formats.md#flatzinc-floats) defines finite images,
conditional linear rows, bucket fallback, expansion limits and exact-mode declines.
Keep lowering and source-publication changes consistent with that contract.

## Environment knobs

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

Presolve is *not* an env knob — set it per run with `--presolve`.

## Dependencies

The CLI depends on `:klause` for parsers and engines, and kotlinx-coroutines to bridge
the suspend portfolio API. Corpus selection and campaign execution belong to
[benchmarking](benchmarking.md). [Input semantics](formats.md) describes exact and
approximate lowering and the limits of each result.
