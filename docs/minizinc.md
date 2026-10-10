# MiniZinc integration

`klause-mzn-lib` provides solver configurations, a globals library, wrapper scripts
and smoke models. MiniZinc compiles `.mzn` source to FlatZinc using Klause's
predicate declarations, then the wrapper invokes the installed Klause CLI.

## Setup

Build the distribution from the repository root:

```sh
./gradlew :klause-cli:installJvmDist
minizinc --solver "$PWD/klause-mzn-lib/share/minizinc/solvers/klause.msc" \
  klause-mzn-lib/test-models/queens.mzn -a -n 10
```

The wrapper resolves the CLI through `KLAUSE_HOME`, defaulting to the repository
root two directories above the script. The JVM launcher needs JDK 25 or newer.
Use the explicit configuration path for an in-tree checkout. To register a solver
globally, retain the configuration's relative library and executable layout, or
adjust those paths to the installed locations before registering it with MiniZinc.

| File | Purpose |
|---|---|
| [klause.msc](../klause-mzn-lib/share/minizinc/solvers/klause.msc) | Solver ID `com.eignex.klause` and standard CLI flags |
| [klause-ls.msc](../klause-mzn-lib/share/minizinc/solvers/klause-ls.msc) | Local-search solver ID `com.eignex.klause.ls` |
| [klause-fzn](../klause-mzn-lib/bin/klause-fzn) | Installed CLI wrapper |
| [klause-fzn-ls](../klause-mzn-lib/bin/klause-fzn-ls) | Wrapper selecting local search |
| [redefinitions.mzn](../klause-mzn-lib/share/minizinc/klause/redefinitions.mzn) | Predicates advertised as native |
| [test-models](../klause-mzn-lib/test-models/) | Small smoke models |

The CLI defaults to fixed search, following supported FlatZinc annotations.
`-f` or `-e cp` selects the free backtrack portfolio; `-e mixed` selects mixed
search. The LS configuration selects local search through its wrapper.

## Native coverage

`redefinitions.mzn` is the authoritative advertised native surface. It includes
Boolean, integer and float linear predicates and declarations for supported
globals. Predicates not declared there fall back to MiniZinc's standard library
decomposition. A declaration must have a matching FlatZinc parser/lowering path;
adding it alone does not implement a constraint.

To extend native coverage, implement the factor and supported model/lowering
paths, declare the predicate, and add focused source and end-to-end tests.
Count emitted FlatZinc predicates to inspect which operations survive as native
constraints. Coverage measures lowering choices; it does not establish search
strength or performance.

## Source validation

The bench retains DZN `.ozn` mappings to reconstruct source variables from
compiler-introduced FlatZinc outputs. Source pinning recompiles with MiniZinc's
standard library and independently checks the objective. Certified rational
witnesses travel in FlatZinc comments alongside standard decimal output and are
checked independently against the original flattened predicates and bounds.
Records distinguish MiniZinc source checks from exact checks with FlatZinc's
binary64 literal semantics. Integer campaigns opt in with
`param=source-validation=true`. Unsupported or incomplete checks remain unknown.
Checking feasibility does not certify source optimality.
See [benchmark result validation](benchmarking.md#source-validation).
