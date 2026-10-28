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
| `complete-assignment=true\|false` | Opt-in bounded complete integer/Boolean FlatZinc coordinates as witness comments |

Each run writes `<problem>.out` and `<problem>.json` under `out`, including failed
loads and solves. Records retain solver reports, statistics, source hashes,
installed-build identity and available witnesses. Inspect the JSON record even
when the bench process exits successfully. Independent source validation runs in
the lab; the bench records `validationPolicy=reported-result-v1`.

`complete-assignment=true` also works in lab request parameters and stays off by
default. The final witness preserves its framed comments after model reconstruction;
normal model output stays unchanged. Retain `report/model.fzn`, `model.ozn`, the
native output and compiler identity with the witness. A missing or unavailable
block cannot certify hidden coordinates; capture itself is not an independent check.

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

For repeatable finite portfolio diagnostics, use one processor, a fixed solver seed,
`param.portfolio-work=N` and explicit `param.slice-work`, `param.slice-max-work` and
`param.slice-growth` settings. The containing timeout is an emergency stop. Keep it
large enough for the work limit to finish, then compare the `portfolio ` event lines
in retained CLI stderr across repeats. The bench enables verbose output for this
option. Use `presolve=off` or freeze prepared inputs separately: this scheduler
control does not replace source preparation policy. Retain source hashes, effective parameters and installed build/runtime
provenance with the traces. Compare logical events separately from physical timing,
and report an emergency-interrupted last segment as partial. Default scheduling,
parallel deterministic batches and throughput qualification remain separate controls.

[9140](https://lab.eignex.com/jobs/9140) repeats `php4`, `random3sat-50-200` and
`sporttournament06` three times at seed 17 on `75aaf8357d`, with one Linux/amd64
processor, matching installed-build/runtime hashes, presolve and LP off, 2,000 work
units and a ten-second emergency deadline. Each source reproduces its complete
logical trace. Two-unit slices repeatedly reseed workers on the optimization source
and do not exercise imports.
[9150](https://lab.eignex.com/jobs/9150) keeps the same source/build identities for
`php4` and `sporttournament06`, with 4,000 work units, slices growing from 8 to 64,
reseeding off and Luby-2 restarts. All three repeats reproduce selections, imports,
rewards and termination. `php4` imports 17 peer clauses and credits 19 downstream
uses; the optimization worker observes incumbent versions 3, 4 and 5 before the work
limit. No emergency deadline fires. These are mechanism observations: source-check
receipts are absent, native verdicts remain unverified, and no default activation or
throughput improvement follows.

This repository owns catalog selection, runtime configuration and build/launch recipes.
`lab/recipes/` contains the migrated lab recipes. `scripts/convert-lab-recipe.sh`
translates legacy experiments or schedules from stdin without submitting them;
exact legacy confirmation lists require a newly pinned SDK plan and are refused.

Profiles use `scripts/lab-profile.sh` with a main class as their first argument.
The recipe declares build tasks; the script owns CLI paths, corpus properties and
recording options. After installing the bench, `scripts/draw-sets.sh <features-dir>
<references.csv> <sets-dir> [set,...]` runs the migrated seeded named-set generator.

The planner accepts `selections`, a JSON-encoded list of independent selector maps,
merged without duplicate catalog identities. Paired experiments use lab `configs`,
`grid` and `repeats`: `ref` selects a revision and `param.<key>` overrides an adapter
request key. Single-engine requests publish comparison identities independent of
engine; multi-engine requests retain engine in that identity. Reference-only recipes
use the same manifest to prepare inputs for lab-owned reference execution.

These workflows require the agnostic lab coordinator and workers (manifest schema 3).
Reference filtering runs after planning. This planner owns selection caps; the lab
does not refill them after filtering. Independent checkers remain in Eignex Lab.
Bench schedules preserve the explicit `bench.sourceValidation` policy.

The adapter accepts `LAB_CORPUS_MIRROR`, `LAB_CORPUS_MIRROR_WRITE`,
`LAB_CORPUS_KEEP_PACKED` and the preparation-only `LAB_INPUT_SIGNER`, with existing
local corpus settings as fallbacks. No lab-specific dependency is added to the core.

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

`bench.profile.WeightedSearchProfile` qualifies one hash-pinned WCNF/WBO source
through ordinary finite preparation and retained core search. Arguments are
`suite=ID problem=NAME sha256=HEX format=wcnf|wbo policy=ordinary|stratified|reasons|deletion
timeout-ms=10000 work=250000 slice-work=200 seed=3`.
Run it through the lab with a bounded profile allowance. It writes the original
source, installed-build provenance, exact floor/core-weight/threshold and accepted
incumbent trajectories, preparation/threshold/verification visits, query counts,
traversal work and a summary including source preparation and disposal time.
The summary retains slice count and final work after private retirement.
The timeout contains source preparation, native point checking and trajectory
writing; these instrumented observations establish no throughput claim. Native
Boolean point checks and native bound authority are separate from the lab's
independent source checks and independently validated proofs. Use ordinary lab
adapter runs for source-matched admission and primal controls.

The opt-in basis tool uses the [basis corpus](../klause/src/jvmTest/resources/basis-corpus/README.md):

```sh
./gradlew :klause:basisTrace --args="list"
./gradlew :klause:basisTrace --args="verify"
./gradlew :klause:basisTrace --args="replay trace=/absolute/path/to/trace.kbtrace"
```

Run captures, timing comparisons and profiles through the lab. Harness unit tests
use `./gradlew :klause-bench:test`; they do not run benchmark campaigns.
