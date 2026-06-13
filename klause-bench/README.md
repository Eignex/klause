# klause-bench

The benchmarking harness for klause. It separates three orthogonal axes — **format** (how an instance is encoded), **source** (where the bytes come from), **solver** (which engine solves) — and drives everything from one CLI. The catalog (`catalog/Suites.kt`) is the single source of truth for which problems exist; nothing discovers instances from random directories.

The bench does one thing: **solve** a selection of problems with one solver (klause or a reference), under a budget. That is the only run form:

```
bench solve [filters…]      e.g.  bench solve suite=mzn-bench backend=choco
```

`solve` runs **one** solver per invocation, **as a subprocess**: klause via `klause-cli`, and reference solvers (`choco`/`gecode`/`yuck`/…) via `minizinc --solver <id>` — each emitting MiniZinc-format output. Output is saved **one file per problem** under `output/<config>/` (`<config>` = solver+settings+budget, e.g. `choco-p8-free-t300s`): a `<problem>.out` (raw solver stream = the log) and a self-describing `<problem>.json` (solver/settings/budget + parsed result). There is no in-session comparison and no in-process reference adapter: run `solve` once per config and diff two config dirs offline with `output/compare.sh` — so one solver's crash or warmup never contaminates another's baseline. The same offline diff doubles as a **regression check**: keep a baseline config's dir and compare a fresh run's dir against it (verdict counts catch quality regressions, the time aggregate catches slowdowns). Results are also content-addressed in `build/bench-cache/`, so re-running an identical instance replays instantly.

## Quick start

```
./gradlew :klause-bench:bench --args="list"                       suites + usage
./gradlew :klause-bench:bench --args="solve suite=core"           klause solves the in-process core
./gradlew :klause-bench:bench --args="solve suite=core backend=choco"  a reference baseline to diff against
```

Tune any knob with `-Dklause.*` properties (forwarded to the run JVM), e.g. `-Dklause.bench.mzn.timeoutSec=30`.

## Commands

```
bench solve [filters…]               solve a selection (the bench's one measurement)
bench preview [filters…]             print what a run would cover, without running
bench list [<suite>]                 list suites, or the problems in one suite
```

## Filters

| filter | meaning |
|---|---|
| `suite=a,b` | restrict to named suites; `suite=core` expands to the in-process core |
| `kind=cop\|csp` | keep optimization (COP) or satisfaction (CSP) problems — classified from the source's objective directive (MiniZinc `solve minimize/maximize`, OPB `min:`, SMT-LIB `(minimize`, XCSP3 `<objective>`); applied before sampling, so a capped `kind` selection fills its cap |
| `category=SAT,UNSAT,CSP,OPTIMIZATION,…` | keep only these categories |
| `tag=…` / `name=<glob>[,…]` | tag membership / comma-separated OR of substring-or-`*`-glob patterns on the instance name (e.g. `name=cvrp,nfc,mario`) |
| `per-family=N` `max=N` `seed=N` | cap and deterministically sample (discovered corpora) |
| `backend=<minizinc solver id>` | the single solver `solve` runs as a subprocess: a registered MiniZinc solver (`choco`/`gecode`/`yuck`/…) via `minizinc --solver`; unset (or `klause`) runs klause via `klause-cli`. Alias `reference=`. |
| `timeout=<ms>` | per-instance solve budget |
| `engine=fixed\|cp\|mixed\|ls\|cp-single\|ls-single` `processors=N` | klause's search for a `solve` run (below), forwarded to the cli `-e`/`-p` (the cli owns the engine model). `engine` unset ⇒ no `-e`, so klause follows the cli's own default engine (the bench has no engine default of its own). `fixed=true` is a *separate* reference-only `-f` toggle |
| `param=key=value` (repeatable) | klause-cli `--param` engine knobs forwarded verbatim. `var-selector`/`val-selector` apply only to `engine=cp-single` (a single backtrack solver) — the way to A/B a heuristic: run `solve` twice with different `cp-single` params, then `compare.sh` the two dirs. Folded into the `<config>` dir name so runs don't clobber |
| `profile=cpu\|wall\|alloc` `profile-scope=solve\|all` `profile-top=N` | JFR profiling (below) |

## What `solve` saves

`solve` runs one backend (`backend=`, default klause) over the selection **as a subprocess** (klause via `klause-cli`, references via `minizinc --solver <id>`), emitting MiniZinc-format output. It saves **one file per problem** under `output/<config>/`: `<problem>.out` (raw solver log) + `<problem>.json` (solver/settings/budget + objective + time-to-best (optimization) or feasibility (satisfaction) + proof status + `%%%mzn-stat` statistics + per-arm `attribution` for klause portfolios). Run once per config and diff two config dirs offline (`output/compare.sh`), which also serves as the wall-time regression check. klause solving needs `:klause-cli:installJvmDist`; because klause-cli renders the *model's* objective, maximize values are reported in the model's orientation (sign-correct against references).

## Offline analysis scripts (`output/`)

Both read the per-problem `output/<config>/*.json` `solve` records — no solving, no Gradle.

- **`compare.sh <dirA> <dirB>`** — diff two configs across their shared problems: direction-aware win/tie/loss, the "solves 100% of B's" superset, and the time-to-best aggregate.
- **`credit.sh <dir>`** — per-**arm** credit over ONE klause-portfolio config: firsts / bests / soles / improvements per arm + a greedy marginal-contribution ranking, read from each record's `attribution` (klause emits `%%%klause-arm:` lines under `-s` on a `-e mixed/cp/ls` optimize). Produce the data with `bench solve … backend=klause engine=mixed -p8`.

## Recipes

```
# solve a corpus with each backend, one invocation each (klause needs `:klause-cli:installJvmDist`)
bench solve suite=mzn-bench per-family=1 max=50 seed=1                 # klause (cli default engine), sampled slice
bench solve suite=mzn-bench per-family=1 max=50 seed=1 backend=choco   # Choco baseline, same selection
bench solve suite=mzn-bench per-family=1 max=50 seed=1 backend=yuck    # Yuck baseline
# each writes output/<config>/<problem>.{out,json} — diff two config dirs with output/compare.sh

# search-config A/B: run twice with different --param, then compare the two config dirs.
# heuristic A/B: var-/val-selector params apply only to engine=cp-single (a single backtrack solver).
bench solve suite=core kind=cop engine=cp-single param=var-selector=vsids timeout=30000
bench solve suite=core kind=cop engine=cp-single param=var-selector=chb   timeout=30000
# output/compare.sh output/klause-cp-single-p1-t30s-var-selector-vsids output/klause-cp-single-p1-t30s-var-selector-chb
```

### Solve: klause competition tracks as filter combinations

When `solve` runs klause (no `backend=`), the klause side is the `engine` enum — owned by klause-cli,
which the bench forwards verbatim to `-e`. With `engine` unset the bench passes no `-e`, so klause
follows the cli's **own** default engine (the bench has no engine default of its own). **`engine=fixed`**
(the MiniZinc-Challenge FD behaviour) is a single naked backtrack following the model's `int_search`
annotation; the portfolio engines **`cp`** (backtrack-only), **`mixed`** (bt+ls), **`ls`** run
sequentially at `-p1` and as a parallel pool at `-p N`; **`cp-single`** is a single naked free backtrack
(the only engine that takes `var-selector`/`val-selector` `--param`s). `processors` defaults to 1, so
multi-thread tracks request cores explicitly. The Challenge tracks are just `engine`/`processors` combinations (all compose with
`kind=cop|csp` and `timeout=`):

```
# fixed (default): single naked backtrack following the search annotation (FD track)
bench solve suite=mzn-bench timeout=300000

# free: single-core backtrack portfolio (ignores the annotation)
bench solve suite=mzn-bench engine=cp timeout=300000

# parallel: multi-thread backtrack portfolio
bench solve suite=mzn-bench engine=cp processors=8 timeout=300000

# open: multi-thread mixed (backtrack + local search) portfolio
bench solve suite=mzn-bench engine=mixed processors=8 timeout=300000

# ls: parallel local-search portfolio
bench solve suite=mzn-bench engine=ls processors=8 timeout=300000
```

References take the standard `-f`: the bench's `fixed=true` filter drops `-f` (follow the annotation),
default is free. When the baseline backend is Choco, `fixed=true` mirrors the annotation onto Choco
(sound now that the LCG fixed-search bug is worked around).

```
bench solve suite=mzn-bench engine=fixed timeout=300000            # klause FD (annotation)
bench solve suite=mzn-bench fixed=true backend=choco timeout=300000 # Choco, same annotation
```

(drop the `./gradlew :klause-bench:bench --args="…"` wrapper for brevity above.)

### Profiling

`profile=cpu|wall|alloc` records the run with Java Flight Recorder, prints a flat top-method table, and leaves `build/bench-prof.jfr` for JMC / `jfr print`. `profile-scope=solve` (default) starts the recording only after the selection is resolved, so parse/compile/fetch are discounted; `profile-scope=all` covers the whole run. Sampling is statistical (1 ms), so pair it with a long-running selection.

`solve` is special: it normally runs solvers as subprocesses (klause-cli / minizinc), which JFR on the bench JVM can't see. So `solve … profile=…` switches to **in-process profiling** — it runs the klause engine itself inside this JVM under the recorder, capturing the real `BacktrackSolver` / `LocalSearchSolver` hot paths. This needs a single-solver engine: `cp`/`cp-single`/`fixed` (→ `BacktrackSolver`) or `ls` (→ `LocalSearchSolver`); the `mixed` portfolio and external references aren't profilable. No JSON/cache is written in this mode — it measures the solver, not the figures.

```
bench solve suite=mzn-bench name=mario engine=cp-single profile=cpu timeout=30000
bench solve suite=mzn-bench name=mario engine=fixed profile=cpu timeout=30000
bench solve suite=mzn-bench name=mario engine=ls profile=alloc timeout=30000
```

For a deeper whole-JVM native profile, the gradle hook is still available: `-PasyncProfiler=/path/to/libasyncProfiler.so [-PprofFormat=traces=30] [-PprofOut=…]`.

## Catalog, corpus, and selection

Suites and problems are declared in `catalog/Suites.kt`. Add a problem with `vendored` (a small file under `smoke-corpus/`), `inCode` (built in Kotlin), `workspace` (a file elsewhere in the repo), or `external` (inside a fetched collection).

Vendored problems live in `smoke-corpus/` — small, fast instances meant to exercise parsers and cross-check solvers, not to stress them (see `smoke-corpus/PROVENANCE.md`). Non-redistributable collections (MiniZinc Challenge, libminizinc, hakank, SATLIB) are fetched on first use into `build/corpus-cache/` and declared with license + reason in `ExternalCollections`; the large MiniZinc corpora are exposed as discovered suites (`mzn-bench`, `libminizinc-tests`, `hakank`) selected by the family-aware machinery in `source/CorpusSelection.kt`. For parallel sweeps, `-Dklause.bench.shard=i/n` keeps every n-th selected instance (applied before resolution, so shards never race on the mzn→fzn cache).

## Reference solvers

The reference path depends on the instance's format:

- **MiniZinc instances** run the reference **end-to-end via `minizinc --solver <id>`** on the original `.mzn`(+`.dzn`) — the competition setup, where the solver compiles the model with **its own** globals library and uses its native propagators. This is the faithful baseline. Yuck is registered out of the box; Choco is provisioned with `./gradlew :klause-bench:installChoco` (fetches the choco-parsers FlatZinc jar + Choco's `mzn_lib` globals and registers `choco.msc` under `~/.minizinc/solvers`, so `minizinc --solver choco` runs Choco with its own native globals).
- **Non-MiniZinc formats** (XCSP3 / SMT-LIB / FlatZinc — no `.mzn` to hand to `minizinc`) are solved by **klause only**, via `klause-cli` on the original file. There is no external reference for these (the in-process Choco/OR-Tools/Yuck/LogicNG adapter modules were removed in the subprocess refactor).

Running references end-to-end via `minizinc --solver` is deliberate: an in-process adapter would re-derive the reference from klause's **already-decomposed** `Problem` and inherit klause's lowering (e.g. a `subcircuit` turned into clauses, or an internal `GaussianXor`) instead of the solver's native global — distorting the baseline.

## Running the parity sweep

The parity sweep measures klause against the reference solvers on the MiniZinc Challenge corpus. The method is fixed so every run is comparable:

1. **One backend per `bench solve` invocation** — there is no in-session comparison (a crash or warmup can't contaminate another solver's number). Each run writes one `.out` + `.json` per problem under `output/<config>/`; comparison is done **offline**, direction-aware (maximize vs minimize), by diffing two config dirs: `output/compare.sh <dirA> <dirB>`. Results are also content-addressed in `build/bench-cache/` (keyed by `sha256(model+data) · time-settings · solver+settings`), so re-running an identical instance replays instantly — reference baselines stay frozen while klause iterates (klause's key folds in the cli-binary mtime). Disable with `-Dklause.bench.cache=false`.
2. **Curated selection, not random sampling** — a fixed set chosen to span distinct global constraints, so a small set still exercises the breadth that matters. The MiniZinc Challenge itself is ~95% optimization, so the set is COP-heavy by design.
   - **8 COP**: `elitserien` (alldifferent, global_cardinality, inverse, member, regular), `gfd-schedule` (cumulative, at_most, nvalue), `cargo` (cumulative, diffn), `is` (among, circuit, table), `nfc` (network_flow), `mario` (path, subcircuit), `evilshop` (cumulative, disjunctive), `zephyrus` (arg_sort, lex_less).
   - **2 CSP**: `multi-knapsack` (knapsack), `oocsp_racks` (global_cardinality, increasing, element).
   - Spell it with the `name=` OR filter: `name=elitserien,gfd-schedule,cargo,is/*,nfc,mario,evilshop,zephyrus` (the `is/*` glob anchors the family so it doesn't substring-match e.g. `opt-cryptanalysis`).
3. **Tracks** (each a `solve` filter combination; see the recipes above). klause: `free` (`engine=cp`), `parallel` (`engine=cp processors=8`), `open` (`engine=mixed processors=8`), `fixed` (`engine=fixed`), `ls` (`engine=ls processors=8`). References: Choco for the complete tracks (`fixed=true` for the FD track), Yuck (`processors=8`) for `ls`.
4. **Budgets**: complete tracks **300000 ms**, the LS track **180000 ms**.

```
# LS track: klause local search vs Yuck, 180s, on the curated set
bench solve suite=mzn-bench kind=cop per-family=1 name=elitserien,gfd-schedule,cargo,is/*,nfc,mario,evilshop,zephyrus engine=ls       timeout=180000
bench solve suite=mzn-bench kind=cop per-family=1 name=elitserien,gfd-schedule,cargo,is/*,nfc,mario,evilshop,zephyrus backend=yuck    timeout=180000
# then: output/compare.sh output/klause-ls-p8-free-t180s output/yuck-p20-free-t180s
```

The `output/` scripts (`run-baselines.sh` curated subset, `run-baselines-full.sh` whole corpus, `compare.sh` for offline diffs) drive the baseline sweeps; the per-config result dirs they produce are committed (version-controlled) so baselines are shared and diffable across machines. To stop a background sweep, kill the `run-*.sh` bash loop **first** (else it spawns the next leg), then the forked `BenchCli` JVM by PID.

## Verifying a change

```
./gradlew :klause-bench:test                              unit, parser, and selection tests
./gradlew :klause-bench:bench --args="solve suite=core"   klause solves the in-process core
```
