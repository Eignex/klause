# klause-cli

Unified command-line frontend for klause. One binary, a registry of **mode** front-ends —
one per format / competition — that share a single solver-control flag set and one solve
driver, and differ only in how they parse input and print results:

- **MiniZinc / FlatZinc** (`.fzn`) — the default. MiniZinc's standard FZN output
  (`----------` / `==========` / `=====UNSATISFIABLE=====` / `=====UNKNOWN=====`),
  `%%%mzn-stat` for `-s`.
- **XCSP3** (`.xml`, `.xcsp`, `.xcsp3`) — the XCSP3 competition protocol: `o <cost>` per
  improving incumbent, a final `s SATISFIABLE` / `s OPTIMUM FOUND` / `s UNSATISFIABLE` /
  `s UNKNOWN`, a `v <instantiation>` line with named values, and `c` stat comments for `-s`.
- **SMT-LIB QF_LIA** (`.smt2`, `.smt`) — `sat` / `unsat` / `unknown` plus a
  `(get-model)`-style `(define-fun …)` block on sat, `;` stat comments for `-s`.

Each invocation solves exactly one instance. Whole-corpus solving across the external
libraries lives in klause-bench (`bench solve suite=xcsp3-core|smtlib-core`), which is
catalog-driven and fetches them — it is not a CLI concern.

Adding a new competition front-end is a new `CliMode` + its `OutputProtocol` (see
`CliMode.kt`); the parser, router, and engines are untouched.

## Dispatch

The mode is chosen per invocation:

1. An explicit `--format <name>` (`minizinc` / `xcsp3` / `smtlib`) wins.
2. Otherwise the input file extension (`.fzn` → MiniZinc, `.xml`/`.xcsp`/`.xcsp3` → XCSP3,
   `.smt2`/`.smt` → SMT-LIB).
3. Otherwise MiniZinc. The default is MiniZinc on purpose: MiniZinc invokes this binary
   through the `klause-mzn-lib` wrappers and expects unknown flags to be tolerated.

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

MiniZinc integration goes through `klause-mzn-lib` (see its README): the
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
  - `fixed` *(default)* — single naked backtrack following the model's `int_search` annotation (FD).
  - `backtrack` (alias `bt`, `cp`) — backtrack-only portfolio (free). `mixed` — bt+ls portfolio.
    `localsearch` (alias `ls`) — local-search portfolio.
  - `backtrack` also accepts the per-solver `var-selector`/`val-selector` (and `luby`/`phase-saving`/…)
    `--param`s: they edit the arm pool across its arms (so `-p8` stays a full pool with the override
    pinned; single-solver A/B is just `-p1`). `--param bt-arm=label,…` instead pins named catalog arms.
  - `ls` takes the ls strategy `--param`s (`arm=`, `strategy=`, `tabu-tenure`, `noise`, …). With
    `sources=`/`strategy=bare` it builds a composable recipe over the LS axes — `sources` (e.g.
    `violated,argmin`), `scoring` (`weighted|raw`), `acceptance` (`greedy|walksat|probsat|skew|sa`) —
    edited across the pool, for A/B-testing each axis.
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
  - presolve (any engine): `affine-pivot-order` (`markowitz|stable_id`) — the order affine elimination
    picks its pivots in. `markowitz` (the default) takes the lowest estimated fill first; `stable_id` takes
    them in model order. Both yield the same solutions; the order decides how many variables the pass
    eliminates within its fill-in budget, so `stable_id` is only there as a measurement baseline.

MiniZinc-mode-only flags:

- `--ozn FILE` — render output with klause's native `.ozn` applier instead of MiniZinc's
  `solns2out`.
- `--unbounded-int-lo N` / `--unbounded-int-hi N` — default domain for unbounded `var int`
  declarations.

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
  `klause.unbounded.int.lo` / `.hi` (the default int range for unbounded FlatZinc *and* SMT-LIB
  vars), `klause.float.buckets`, `klause.float.scale`, `klause.lp.max.tableau.cells`,
  `klause.lp.ceiling.tableau.cells`, `klause.bitset.threshold`.

Presolve is *not* an env knob — set it per run with `--presolve`.

## Dependencies

`:klause` (parsers and all three engines live there) and kotlinx-coroutines
(bridges the suspend Portfolio API from the synchronous CLI). The CLI has no
other module dependencies; bench runs external reference solvers via
`minizinc --solver`, not via in-process adapters.
