# klause-cli

Unified command-line frontend for klause. One binary, two paths:

- **FlatZinc** (`.fzn`) — MiniZinc-compatible solver backend. Prints solutions
  in MiniZinc's standard FZN output format (`----------` / `==========` /
  `=====UNSATISFIABLE=====` / `=====UNKNOWN=====`).
- **XCSP3 / SMT-LIB QF_LIA** (`.xml`, `.xcsp`, `.smt2`) — solve a single
  instance, or walk a corpus with `--coverage` and report parsed / solved /
  unsupported counts.

## Dispatch

The path is chosen per invocation:

1. `--format` or `--coverage` anywhere in the args → XCSP3/SMT-LIB path.
2. An input file with an `.xml` / `.xcsp` / `.xcsp3` / `.smt2` / `.smt`
   extension → XCSP3/SMT-LIB path.
3. Everything else → FlatZinc path. The default is FlatZinc on purpose:
   MiniZinc invokes this binary through the `klause-mzn-lib` wrappers and
   expects unknown flags to be tolerated.

## Build and run

```
./gradlew :klause-cli:installDist
klause-cli/build/install/klause-cli/bin/klause-cli [flags] <file>
```

MiniZinc integration goes through `klause-mzn-lib` (see its README): the
`klause.msc` / `klause-ls.msc` solver configs point at the
`klause-mzn-lib/bin/klause-fzn` / `klause-fzn-ls` wrappers, which delegate to
this distribution.

## Flags

FlatZinc path (MiniZinc-standard plus klause extras):

- `-a` / `--all-solutions`, `-n <count>` — enumeration controls (satisfy).
- `-t <ms>` time limit, `-r <seed>`, `-s` statistics, `-v` verbose.
- `-e <engine>` / `--engine <engine>` — `backtrack` (default), `ls`,
  `portfolio`, `logicng`, `brute`. Also settable via the
  `klause.fzn.engine` system property.
- `--ozn FILE` — render output with klause's native `.ozn` applier instead
  of MiniZinc's `solns2out`.
- `--unbounded-int-lo N` / `--unbounded-int-hi N` — default domain for
  unbounded `var int` declarations.
- `--cp-seed` — opt-in hybrid CP-seeding for the `ls` engine: a short
  backtrack solve warm-starts local search.
- Portfolio worker counts: `-Dklause.fzn.portfolio.ls` /
  `-Dklause.fzn.portfolio.bt` (defaults 4 / 2).

XCSP3 / SMT-LIB path:

- `--format xcsp3|smtlib` — override extension-based detection.
- `-e backtrack|ls|brute` (default backtrack), `-t <ms>`, `-r <seed>`.
- `--coverage <dir>` — corpus coverage report.
- `-Dklause.xcsp.printSolution=true` — print the satisfying assignment.

## Dependencies

`:klause` (parsers for all formats live there), `:klause-logicng` (the
`logicng` engine), and kotlinx-coroutines (bridges the suspend Portfolio API
from the synchronous CLI).
