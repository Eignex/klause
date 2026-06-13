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

Each invocation solves exactly one instance. Whole-corpus parse/solve coverage reporting
lives in klause-bench (`bench coverage:xcsp3|smtlib`), which is catalog-driven and fetches
the external libraries — it is not a CLI concern.

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

Standalone native executable (no JVM, instant startup):

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
- `-p <n>` — MiniZinc-standard parallelism (core count). The portfolio engines (`cp`/`mixed`/`ls`)
  run sequentially at `n=1` and as an `n`-worker parallel pool at `n>1`; the naked engines
  (`fixed`/`cp-single`) are single-core. Pool size auto-tunes from `n`, overridable with
  `--param arms=N` (or the `ls=N`/`bt=N` split).
- `-e <engine>` / `--engine <engine>` — the engine enum (also via the `klause.fzn.engine` property):
  - `fixed` *(default)* — single naked backtrack following the model's `int_search` annotation (FD).
  - `cp` — backtrack-only portfolio (free). `mixed` — bt+ls portfolio. `ls` — local-search portfolio.
  - `cp-single` — single naked free backtrack; the **only** engine that accepts `var-selector`/
    `val-selector` `--param`s (single-solver heuristic experiments).
- `--format <name>` / `--mode <name>` — force a mode regardless of file extension.
- `--param <key>=<value>` — repeatable engine params (unknown/malformed keys are a usage
  error, exit 2):
  - `cp`: `seed`, `max-decisions`, `luby`, `phase-saving`, `max-learned`, `lbd-glue`,
    `var-selector` (`vsids|random|smallest-domain|input-order`), `val-selector`
    (`random|min|max|middle`)
  - `ls`: `seed`, `max-flips`, `lambda`, `tabu-tenure`, `pair-swap-budget`
  - `portfolio`: `ls`, `bt` (worker counts), `seed`, `lambda`

MiniZinc-mode-only flags:

- `--ozn FILE` — render output with klause's native `.ozn` applier instead of MiniZinc's
  `solns2out`.
- `--unbounded-int-lo N` / `--unbounded-int-hi N` — default domain for unbounded `var int`
  declarations.

## Dependencies

`:klause` (parsers and all three engines live there) and kotlinx-coroutines
(bridges the suspend Portfolio API from the synchronous CLI). The CLI has no
other module dependencies; bench runs external reference solvers via
`minizinc --solver`, not via in-process adapters.
