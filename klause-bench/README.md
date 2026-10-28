# klause-bench

JVM problem catalog and single-case runner for
[eignex-lab](https://github.com/Eignex/eignex-lab). Use the lab for experiments,
revision comparisons, reference solvers, witness checks, schedules and profiling.

## Build and run

Run from the repository root with JDK 25 or newer:

```sh
./gradlew :klause-cli:installJvmDist :klause-bench:installDist
cd klause-bench
./build/install/klause-bench/bin/klause-bench list
./build/install/klause-bench/bin/klause-bench select suite=dimacs-core files=true features=true
./build/install/klause-bench/bin/klause-bench solve-one suite=dimacs-core problem=php4 timeout=1000 out=/tmp/php4
```

Rebuild `:klause-cli:installJvmDist` after CLI or solver changes: `solve-one` runs
that installed CLI as a subprocess. `:klause-cli:installDist` does not refresh it.
The installed bench launcher uses `klause-bench` as its working directory.
For source-tree commands, use
`./gradlew :klause-bench:bench --args="select suite=dimacs-core"` from the repository
root; `-Dklause.*` properties are forwarded to the bench JVM.

## Select and solve

`list [suite]` lists suites or their problems. `select` emits JSON lines containing
the `suite` and `problem` identities accepted by `solve-one`.
`files=true` adds resolved model/data/include paths; `features=true` adds structural
classification. Fetched suites download their corpus during resolution.

| Selection filter | Purpose |
|---|---|
| `suite=a,b` | Restrict suites; `core` expands to the in-process core |
| `set=a,b` | Select named sets from [sets/](sets/); `suite=` intersects membership |
| `kind=cop\|csp`, `category=SAT,UNSAT,CSP,OPTIMIZATION,…` | Filter objective kind or category |
| `tag=a,b`, `name=<glob>[,…]` | Filter tags or names; name patterns are ORed |
| `per-family=N`, `max=N`, `seed=N` | Cap and deterministically sample selection |
| `balance=format` | Distribute the overall cap across formats |

Named sets contain one `<suite>/<problem>` per line; `@name` includes another set
and `#` starts a comment. They bypass default suite family caps.

`solve-one suite=ID problem=NAME out=DIR` accepts these controls:

| Option | Purpose |
|---|---|
| `timeout=MS`, `solver-seed=N`, `processors=N` | Solver deadline, seed and processor count |
| `engine=fixed\|cp\|mixed\|ls\|alns` | CLI engine; omitted uses the CLI default |
| `lp=off\|conservative\|default\|aggressive[±id…]` | LP ceiling and technique overrides |
| `presolve=off\|conservative\|default\|aggressive[,±pass…]` | Presolve emphasis and pass overrides |
| `exact=true\|false`, `param=key=value` | Exact mode and repeatable CLI parameters |

Each run writes `<problem>.out` and `<problem>.json` under `out`, including failed
loads and solves. Records retain solver reports, statistics, source hashes,
installed-build identity and available witnesses. Inspect the JSON record even
when the bench process exits successfully. Independent source validation runs in
the lab; the bench records `validationPolicy=reported-result-v1`.

## Run through eignex-lab

The [lab repository](https://github.com/Eignex/eignex-lab) owns submission, execution
and artifact collection. The adapter manifest is [lab/bench.json](lab/bench.json).
Its build tasks install the CLI, bench and pinned MiniZinc runtime:

```sh
./gradlew :klause-cli:installJvmDist :klause-bench:installDist :klause-bench:prepareLabRuntime
```

The adapter requires Linux x86_64, JDK 25 and GNU time. A minimal lab request is:

```json
{
  "repository": "Eignex/klause",
  "base": {"ref": "COMMIT_SHA"},
  "bench": {
    "manifest": "klause-bench/lab/bench.json",
    "request": {"parameters": {
      "suite": "dimacs-core", "name": "php4", "timeout-ms": "1000"
    }}
  }
}
```

Lab parameters use `timeout-ms`; direct `solve-one` uses `timeout`.
`engines`, `solver-seeds` and `repeats` expand cases. Use `param.NAME` for CLI
parameters, `bench.sourceValidation` for independent source checks and
`bench.runTimeoutSec` for the whole job's active-time limit.
Profile mains live in [bench/profile/](src/main/kotlin/com/eignex/klause/bench/profile/)
and run as lab profile jobs; consult the selected main for its arguments and
retain its output through `$OUT`.

## Corpus and build identity

`corpus status` lists cached collections, `corpus gc` applies the cache cap and
`corpus compress [path]` compresses supported plain inputs with `zstd` on `PATH`.
The cache defaults to `~/.cache/klause-bench/corpus/`, respecting `XDG_CACHE_HOME`
or `-Dklause.bench.corpusCache`. Set `KLAUSE_BENCH_CORPUS_MAX_GB=N` or
`-Dklause.bench.corpus.maxGb=N` to change its 60 GB cap; `0`/`off` disables eviction.
Prefetch or disable eviction when parallel processes share a cache.
See [MiniZinc setup](../klause-mzn-lib/README.md) for direct MiniZinc runs and the
[smoke corpus](smoke-corpus/README.md) for fixture provenance and licenses.

To reuse installed-build hashes across repeated bench processes, run
`provenance out=/absolute/path/provenance.json` with the installed launcher and set
`KLAUSE_BENCH_PROVENANCE` to that path. Regenerate it after rebuilding or restoring
an installation. Without it, each process captures its own installed-build hashes.

## LP capture and replay

The opt-in basis tool uses the [basis corpus](../klause/src/jvmTest/resources/basis-corpus/README.md):

```sh
./gradlew :klause:basisTrace --args="list"
./gradlew :klause:basisTrace --args="verify"
./gradlew :klause:basisTrace --args="replay trace=/absolute/path/to/trace.kbtrace"
```

Run captures, timing comparisons and profiles through the lab. Harness unit tests
use `./gradlew :klause-bench:test`; they do not run benchmark campaigns.
