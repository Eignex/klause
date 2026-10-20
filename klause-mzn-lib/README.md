# klause-mzn-lib

MiniZinc solver configurations, native predicate declarations, CLI wrappers and smoke models.
MiniZinc compiles `.mzn` source to FlatZinc using Klause's predicate declarations, then the
wrapper invokes the installed Klause CLI.

## Setup

Build the distribution from the repository root:

```sh
./gradlew :klause-cli:installJvmDist
minizinc --solver "$PWD/klause-mzn-lib/share/minizinc/solvers/klause.msc" \
  klause-mzn-lib/test-models/queens.mzn -a -n 10
```

The wrapper resolves the CLI through `KLAUSE_HOME`, defaulting to the repository root two
directories above the script. The JVM launcher needs JDK 25 or newer. Use the explicit
configuration path for an in-tree checkout. To register the solver globally, keep the
configuration's relative library and executable layout, or adjust those paths to the
installed locations before registering it with MiniZinc.

| File | Purpose |
|---|---|
| [klause.msc](share/minizinc/solvers/klause.msc) | Solver ID `com.eignex.klause` and standard CLI flags |
| [klause-ls.msc](share/minizinc/solvers/klause-ls.msc) | Local-search solver ID `com.eignex.klause.ls` |
| [klause-fzn](bin/klause-fzn) | Installed CLI wrapper |
| [klause-fzn-ls](bin/klause-fzn-ls) | Wrapper selecting local search |
| [redefinitions.mzn](share/minizinc/klause/redefinitions.mzn) | Predicates advertised as native |
| [test-models](test-models) | Small smoke models |

The CLI defaults to mixed search unless `klause.engine` / `KLAUSE_ENGINE` selects another
default. The standard wrapper forwards flags without selecting an engine. `-e fixed` follows
supported FlatZinc annotations; `-f` or `-e cp` selects the free backtrack portfolio. The LS
wrapper prepends `-e ls`; a later explicit `-e` takes precedence.

## Native predicates

`redefinitions.mzn` is the advertised native surface; predicates not declared there fall back
to MiniZinc's standard decomposition. See [native predicate coverage](../docs/04-finite-formats.md#native-predicate-coverage)
for what a declaration requires, and [FlatZinc](../docs/04-finite-formats.md#flatzinc) for the frontend semantics.
