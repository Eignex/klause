# klause-mzn-lib

MiniZinc solver configurations, native predicate declarations, CLI wrappers and smoke models.
MiniZinc compiles `.mzn` source to FlatZinc using Klause's predicate declarations, then the
wrapper invokes the installed Klause CLI.

## Setup

Build the distribution from the repository root:

```sh
./gradlew :klause-cli:installJvmDist
minizinc --solver "$PWD/klause-mzn-lib/share/minizinc/solvers/klause.msc" \
  klause-mzn-lib/test-models/klause-test-queens.mzn -a -n 10
```

The wrapper finds the CLI through `KLAUSE_HOME`, defaulting to the repository root. To register
the solver globally, keep or adjust the configuration's relative library and executable paths.

| File | Purpose |
|---|---|
| [klause.msc](share/minizinc/solvers/klause.msc) | Solver ID `com.eignex.klause` and standard CLI flags |
| [klause-ls.msc](share/minizinc/solvers/klause-ls.msc) | Local-search solver ID `com.eignex.klause.ls` |
| [klause-fzn](bin/klause-fzn) | Installed CLI wrapper |
| [klause-fzn-ls](bin/klause-fzn-ls) | Wrapper selecting local search |
| [redefinitions.mzn](share/minizinc/klause/redefinitions.mzn) | Predicates advertised as native |
| [test-models](test-models) | Small smoke models |

The standard wrapper forwards flags unchanged, so the CLI's default engine applies; `-e fixed`
follows FlatZinc search annotations and `-f` selects free search. The LS wrapper prepends `-e ls`,
which a later `-e` overrides.

## Native predicates

Predicates declared in `redefinitions.mzn` reach Klause natively; the rest use MiniZinc's standard
decompositions. See [native predicate coverage](../docs/04-finite-formats.md#native-predicate-coverage).
