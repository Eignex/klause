# klause-bench

The benchmarking harness for klause. It separates three orthogonal axes — **format** (how an instance is encoded), **source** (where the bytes come from), **solver** (which engine solves) — and drives everything from one CLI. The catalog (`catalog/Suites.kt`) is the single source of truth for which problems exist; nothing discovers instances from random directories.

The bench does one thing: **solve** a selection of problems with one solver (klause or a reference), under a budget. That is the only run form:

```
bench solve [filters…]      e.g.  bench solve suite=mzn-bench backend=choco
```

`solve` runs **one** solver per invocation, **as a subprocess**: klause via `klause-cli`, and reference solvers (`choco`/`gecode`/`yuck`/…) via `minizinc --solver <id>` — each emitting MiniZinc-format output. Output is saved **one file per problem** under `output/<config>/` (`<config>` = solver+settings+budget, e.g. `choco-p8-free-t300s`): a `<problem>.out` (raw solver stream = the log) and a self-describing `<problem>.json` (solver/settings/budget + parsed result). There is no in-session comparison and no in-process reference adapter: run `solve` once per config and diff two config dirs offline with `output/compare.sh` — so one solver's crash or warmup never contaminates another's baseline. The same offline diff doubles as a **regression check**: keep a baseline config's dir and compare a fresh run's dir against it (verdict counts catch quality regressions, the time aggregate catches slowdowns). Results are also content-addressed in `build/bench-cache/`, so re-running an identical instance replays instantly.

Solve records retain `elapsedMs`, the subprocess wall-clock duration including launch and output
consumption. It is separate from `timeToBestMs` and `timeToFirstFeasibleMs`, which stay null without
a witness. Legacy records and runs that never launched have `elapsedMs=null`. Comparison/CSV timing
uses the incumbent time for a witness, then a valid reported `stats.solveTime` (seconds), then
`elapsedMs`, then the budget. Reference proof timing uses solve time, elapsed time, then budget;
unproven reference witnesses prefer first-feasible time. Undecided runs keep the budget penalty.
Lab consumers must read `elapsedMs` as the fallback after `solveTime`; older lab builds ignore it.

MiniZinc float runs validate their final candidate by recompiling the original model with
its DZN assignments pinned, using MiniZinc's standard library and the same data seed.
No reference solver runs. Records include `sourceValidation` (`valid`, `invalid`, or `unknown`)
and `floatApproximation` in `stats`. Residual variables or constraints, a compiler failure,
or the 10-second checking timeout leave validation unknown. Checking time is outside the solve budget.
Rejected candidates and unchecked grid witnesses receive no solution credit. A checked grid witness
can establish feasibility, but a grid optimum or refutation cannot prove the source result.
Raw output and `reportedFeasible`, `reportedObjective`, and `reportedProven` preserve solver claims.
Earlier incumbents are not independently source checked; arm attribution is restricted to the final objective.

Solve records also retain `sourceHashes` for the plain model/data bytes and `finalWitness` for the
last rendered candidate, up to 8 MiB. A missing witness is not a validated assignment. These fields
are captured outside subprocess timing; legacy records and failed loads may omit them. AWS record
files retain them even when a lab API built against an older schema omits them from its case view.

Integer MiniZinc campaigns may opt in to the same final-witness source check with
`param=source-validation=true`. The bench consumes this parameter before launching the CLI;
checking remains outside the solve budget. Included files and the MiniZinc compiler
are outside the original model/data hashes.
Opt-in records also retain the full stdout hash in `sourceOutputSha256`, including
candidates rejected by checking. Their rendered candidates remain in `finalWitness`.
`invalid` candidates lose solution/proof credit. Integer candidates with `unknown` checking
remain reported witnesses and retain their checking status; checking a witness does not
independently establish optimality or infeasibility. The default policy is unchanged.

An existing output can be checked without solving again:

```
./gradlew :klause-bench:bench --args="validate-solution suite=hakank problem=arbitrage_loops/arbitrage_loops output=/path/to/solver.out"
```

## Quick start

```
./gradlew :klause-bench:bench --args="list"                       suites + usage
./gradlew :klause-bench:solveCampaign --args="solve suite=core"   build the CLI, then solve the core
./gradlew :klause-bench:bench --args="solve suite=core backend=choco"  a reference baseline to diff against
./gradlew :klause-bench:intDomainMicrobench                        host-sensitive IntDomain timing probes
```

### Real LP basis corpus

The `:klause:basisTrace` task operates on the committed, versioned basis corpus under
`klause/src/jvmTest/resources/basis-corpus/`. It runs only when invoked explicitly; ordinary tests never perform
capture or timing.


```
./gradlew :klause:basisTrace --args="list"
./gradlew :klause:basisTrace --args="verify"
./gradlew :klause:basisTrace --args="replay"
./gradlew :klause:basisTrace --args="benchmark"
./gradlew :klause:basisTrace --args="replay trace=/path/to/one.kbtrace"
```

`list`, `replay`, `benchmark`, and `verify` use committed fixtures by default and emit one NDJSON record per
fixture (or one corpus-verification record). Replay rebuilds portable headings on the basis under test,
recomputes update preparation on its own current factors, and checks `B*x=b` and `B^T*x=b` directly against the
stored CSC matrix after builds and updates. A replay stops at the failing operation and resumes at the next
complete checkpoint.

Two oracles are independent of the basis under test. The stored CSC matrix and the tracked headings give the
residual check. The captured trace supplies the factorization outcome and the update acceptance the real solve
observed. Both are checked before anything else, so nothing downstream can mask them.

Replay records the koblas jar hash, kernel selection, and resolved vendor library/version. The selection
identifies the engine composition, not the implementation of every operation: short and strided Level 1
calls can use portable kernels. The sparse basis does not call vendor Level 2/3. Run with
`-Pkoblas.noSimd=true` to check the portable JVM reductions against the same captured observations.

Every accepted update is then rebuilt by a from-scratch Markowitz factorization of the same columns and solved
through the rebuild. This is a measurement, not a third oracle: it runs only on a basis the residual check has
already accepted and probes the same right-hand side, so it cannot find an unsolvable basis that check missed.
It reports `freshRelativeResidual`, the accuracy a from-scratch build reaches on that probe and so a drift
reference for the Forrest-Tomlin chain, and `freshDeclines`, a conditioning signal. A declining rebuild is
reported rather than failed: it applies threshold partial pivoting under a bounded Markowitz search while an
update accepts on an absolute pivot test alone, so the two disagree on badly scaled bases by construction.

`benchmark` performs one warmup and three repetitions. Setup, build/rebuild, FTRAN, BTRAN, update-only,
preparation-plus-update, composed lifecycle and setup-plus-lifecycle totals are reported separately with
median/min/max nanoseconds; setup, individual operations and preparation-plus-update also report Java thread
allocations. The oracle checks are outside the timed regions. Preparation-plus-update is labelled
`synthetic_prepared`: a shadow basis recomputes the referenced solves and applies the update, while shadow setup
and checkpoint builds stay outside that metric and the composed lifecycle.

`compare` replays one complete `mps-afiro` operation window under its captured rebuilds and a fixed
deferred schedule: skip the successful same-heading checkpoint at operation 41, rebuild at the next
captured checkpoint at 86, and continue through operation 93. It runs one warmup and four alternating
pairs; `trace=... checkpoint=...` selects another complete trace and validated same-heading checkpoint with a later build
and following solve. A same-heading checkpoint has the identical immutable matrix and ordered basis;
the trace does not record the production reason for rebuilding, so this is a basis-level cost experiment,
not authorization to change solver policy. Any changed/failed/initial checkpoint, unexpected backend
rebuild advice, numerical decline or 16-update harness limit invalidates that pair. The backend's own
50-update limit remains in force. Both arms receive the same ordered trace operations and RHS vectors,
but each computes its own spike and pivotal row. Cancellation is checked between operations in both arms.

The comparator checks each update state in a separate validation pass and checks every measured solve output
after replay against the stored CSC matrix and a fresh factorization of its recorded headings. This keeps
oracle probes out of measured backend work. Timed phases cover owner
setup, every captured build, RHS preparation and solves, updates, and close. NDJSON records phase and total
elapsed time, current-thread CPU, Java allocations, plus independent backend work units. Native allocations
are unavailable. Validity requires both arms to complete the whole window with independent numerical checks;
captured update labels do not prove the deferred arm. These operation traces contain no complete LP bounds,
objective, source proof or exact certificate, so they cannot establish an exact solver outcome. The fixed
40-boxed-integer full-solver regression remains a separate control for any later policy proposal.

```
./gradlew :klause:basisTrace --args="compare"
```

Repair, snapshots and extension are covered by the dedicated engine tests rather than by replay, which drives
only factorization, FTRAN, BTRAN and updates.

The paired HFactor comparison arm was retired when `koblas-hfactor` was removed, together with its `backend` and
`hfactorArtifactSha256` record fields and the `lp-wave2-hfactor` campaign runner. `klause-bench/lp-wave2-hfactor.md`
is kept as the historical measurement record from when that arm existed; the campaign it describes cannot be
re-run.

Capture is opt-in and bounded to the limits in `basis-corpus/manifest.json`. It records raw matrix/RHS IEEE bits,
source/unit headings, source mappings, strictness, update evidence and checkpoints; it never stores LU state or
native pointers. Examples for every supported source format:

```
./gradlew :klause:basisTrace --args="capture id=local-mps format=mps source=/corpus/model.mps source-label=collection/model.mps@REV license=LICENSE output=/path/to/evidence/local-mps.kbtrace"
./gradlew :klause:basisTrace --args="capture id=local-mzn format=minizinc source=klause-mzn-lib/test-models/graph_coloring.mzn license=repository-internal output=/path/to/evidence/local-mzn.kbtrace"
./gradlew :klause:basisTrace --args="capture id=local-smt format=smtlib source=klause-bench/smoke-corpus/smtlib/lia-wide-span.smt2 license=repository-internal output=/path/to/evidence/local-smt.kbtrace"
```

MiniZinc source is compiled through this repository's `klause.msc`; an already flattened `.fzn` is accepted
directly. MPS and MiniZinc traces use the production LP-relaxation assembly. SMT traces are labelled
`SMT_SOURCE_DERIVED`: the frontend's authoritative exact asserted state is projected into the float engine for
this harness because production SMT theory does not yet own float factors. Boolean SMT branches and objectives
decline instead of being silently selected or relaxed. See `basis-corpus/PROVENANCE.md` for source revisions,
redistribution terms, hashes, exclusions and local regeneration inputs.

### CP LP owner lifecycle comparison

`scripts/lp-lifecycle/` contains an opt-in retained-versus-fresh comparator for a captured CP
consumer trace. It compiles against `:klause`'s internal JVM test API through its init script;
ordinary bench runs and tests do not load it. The source is the two-integer problem
`x,y ∈ [-3,7]`, `x+y >= 1`, minimizing `2x+y+5`. One root and four
push/push/pop/push/pop cycles must reproduce the frozen 21-state capture SHA-256
`deb7c8bb9b3c744e71b31893b7abd1c1f1b8511d8edeecda8f965566e6d4c648`.

```
./gradlew -I klause-bench/scripts/lp-lifecycle/lp-lifecycle.init.gradle :klause:lpLifecycleComparison --max-workers=2 > /tmp/lp-lifecycle.log
python3 klause-bench/scripts/lp-lifecycle/check_lifecycle.py /tmp/lp-lifecycle.log > /tmp/lp-lifecycle-summary.json
python3 -m unittest discover -s klause-bench/scripts/lp-lifecycle -p 'test_*.py'
```

The retained arm builds one original `Problem`, CP session and LP engine, then applies each bound
decision or pop to that session. The fresh arm starts from the same original `Problem` for each state,
replays that state's source decisions, builds its relaxation through `nodeRelaxation`, solves through
`solveNode`, certifies directly and closes the engine. Both arms pay for source assembly through the
same consumer methods. The recorded phases are setup, CP transition or reconstruction, relaxation
assembly, `solveNode`, direct certification and disposal. `solveNode` includes projection/import and
float solve; its subphases have no separate external timing seam. Model fingerprinting, output and
independent checking are outside phase timing. The checker verifies every original-coordinate exact
witness, objective constant and lower bound with a vertex oracle, plus state/model equality, paired
counts and closed owners. It rejects missing records rather than turning them into zero work.

Cold setup and transition include constructing a new CP session and replaying its decision prefix;
retained setup and transition update the live session. The complete total therefore measures that
whole-consumer reconstruction policy. It does not isolate the benefit of LP basis retention. Direct
`certifyLpResult` measures source proof without the rest of `lpBoundAndFix` (pruning, cuts and
explanations), so this diagnostic does not represent a full search run.

The comparison factory fixes the effective LP work allowance at 1,000,000 for both arms (including
retained `resolveBounds`) while preserving the production solver route, refactor cap, pricing and
cancellation. The factory reports requested pre-normalization limits too. This is a bounded diagnostic
configuration, not a production policy change. Java current-thread allocation counts exclude native
allocation and may be unavailable. Elapsed totals are paired in alternating arm order with one warmup;
they are descriptive on a shared host. The committed `.kbtrace` corpus records basis operations, not
complete CP consumer states, so it cannot substitute for this lifecycle comparison. The historical
Wave 2 acceptance remains separate.

Tune any knob with `-Dklause.*` properties (forwarded to the run JVM), e.g. `-Dklause.bench.mzn.timeoutSec=30`.

`:klause-bench:solveCampaign --args="solve ..."` (or `solve-one ...`) depends on
`:klause-cli:installJvmDist` and selects Gradle's Java toolchain for the subprocess launcher.
The generic `bench` task remains available for selection, analysis and reference-only runs.
When using `bench` to solve with klause, build with `./gradlew :klause-cli:installJvmDist` first.
That dist runs only on a JDK 25 or newer — the bench launches it as a subprocess, so a machine whose default `java` is older reports
every klause instance as a crash (`UnsupportedClassVersionError: … class file version 69.0`) while
Gradle itself builds fine. See `klause-cli/README.md` for the `JAVA_HOME` fix. Note also that
`installDist` is a *different*, no-op task that leaves a stale binary in place.

### Installed-build provenance

Klause cache keys include SHA-256 hashes of every installed distribution file (launcher and
all dependency jars), the selected Java runtime files, OS/architecture and inherited Java/runtime
options. Hashes use relative paths and file contents, so a byte-identical rebuild reuses results;
a replaced jar invalidates results even if the launcher timestamp and source commit are unchanged.
Keep the installed distribution and runtime fixed while a campaign runs. For a campaign that starts a
bench JVM per case, capture their hashes once after installing the CLI and bench:

```bash
cd klause-bench
./build/install/klause-bench/bin/klause-bench provenance out=build/provenance.json
export KLAUSE_BENCH_PROVENANCE="$PWD/build/provenance.json"
```

Each bench process validates the manifest's absolute roots, file set, identities, sizes, modification
and change times before reusing the hashes. Manifests require Unix file metadata; changed files or a
copied installation are refused. Generate a fresh manifest after installing or restoring a build on
each host. Without the environment variable, the bench hashes the installed trees itself.
Inherited runtime options are captured for each case. The harness-only `-Dklause.bench.cache`,
`-Dklause.bench.corpusCache` and `-Dklause.workspace.root` properties are excluded from the
fingerprint; solver JVM options and input-processing properties remain.

Per-problem JSON records carry `buildProvenance`, `buildFingerprint` and `validationPolicy`.
`gitSha` describes the harness checkout and does not identify the installed solver. Cache keys also
include the original solver settings, before output-label sanitization, and the validation policy.
Legacy records decode with absent provenance; they cannot supply the installed-build identity.
Reference build provenance remains outside this contract.

Launcher hashes include the default Vector API/native-access flags; runtime options capture inherited
overrides. These identify the requested kernel environment. They do not identify the vendor BLAS
library actually selected by a solve, external native libraries, or MiniZinc's compilation toolchain.
Those measurements and broader acceptance records remain separate work. A passing gate or a cache
hit does not establish performance or LP certification.

Bounded opt-in checks use existing fixtures:

```
./gradlew :klause-bench:solveCampaign --args="solve suite=dimacs-core name=implication-chain timeout=1000 label=acceptance"
./gradlew :klause:basisTrace --args="verify"
./gradlew :klause:basisTrace --args="replay"
```

The solve writes its verdict and provenance beside the raw stream. The basis tasks emit their own
verification/replay records; missing fixtures and numerical declines retain their existing semantics.
None of these campaigns run as part of `check`.

## Commands

```
bench solve [filters…]               solve a selection (the bench's one measurement)
bench preview [filters…]             print what a run would cover, without running
bench select [filters…]              the same selection as JSON lines: suite, problem, collection, family, format, category;
                                     with features=true also each instance's structure, logic and themes
bench solve-one suite=<id> problem=<name> [solve args…] [out=<dir>]
                                     solve exactly one instance as `select` names it; writes its record to out=<dir>
                                     (default output/<config>/) and no per-run table, for drivers that run one
                                     instance per process. An instance that fails to load still gets a record:
                                     stats.unsupported when klause declines the model, stats.loadError when it does
                                     not compile or parse. backend=reference runs the format's reference
                                     solver (clasp, the XCSP3 cp-sat image, z3, SCIP, or cp-sat for MiniZinc), as
                                     `bench reference` does, on the instance as it is; solver=<id> picks a second one:
                                     kissat for CNF, cvc5 for SMT-LIB, highs for MPS, any MiniZinc solver (chuffed)
bench list [<suite>]                 list suites, or the problems in one suite
```

## Named sets

`klause-bench/sets/<name>.txt` lists a fixed set of problems, one `<suite>/<problem>` per line as `select` prints
them; `@<set>` includes another set and `#` starts a comment. `set=<name>` selects it, so every bench that names a set
runs the same problems however the corpus or the reference results change. Each focused set covers one theme, what
solving the problem asks of klause, as `select features=true` classifies it (`InstanceClassifier.THEMES`): open-domain
integers, linear reals, scheduling, routing or packing globals, other globals, MIP, SAT, MaxSAT, PB. A set mixes
difficulties by how long the reference solver took. `sweep` includes every focused set. A set changes only on purpose,
and a material change takes a new name, so results under one name stay comparable.

## Filters

| filter | meaning |
|---|---|
| `suite=a,b` | restrict to named suites; `suite=core` expands to the in-process core |
| `set=a,b` | the problems of named sets in `klause-bench/sets/` (see Named sets), whole: no suite default cap applies; with `suite=`, only the set problems in those suites |
| `kind=cop\|csp` | keep optimization (COP) or satisfaction (CSP) problems — classified from the source's objective directive (MiniZinc `solve minimize/maximize`, OPB `min:`, SMT-LIB `(minimize`, XCSP3 `<objective>`); checked as each family's cap is filled, so a capped `kind` selection fills its cap without reading every source |
| `category=SAT,UNSAT,CSP,OPTIMIZATION,…` | keep only these categories |
| `tag=…` / `name=<glob>[,…]` | tag membership / comma-separated OR of substring-or-`*`-glob patterns on the instance name (e.g. `name=cvrp,nfc,mario`) |
| `per-family=N` `max=N` `seed=N` | cap and deterministically sample. Families are the corpus's own (the XCSP3 series, the MiniZinc challenge problem, …) and are counted per suite. A discovered suite's default cap (1 per family for most) applies only when `per-family` is unset; `per-family` replaces it, and `seed` samples within each family |
| `balance=format` | split `max` evenly across the formats present, water-filling short formats' surplus into larger ones — so a broad multi-format sweep touches every format instead of filling with the format that has the most families |
| `backend=<minizinc solver id>` | the single solver `solve` runs as a subprocess: a registered MiniZinc solver (`choco`/`gecode`/`yuck`/…) via `minizinc --solver`; unset (or `klause`) runs klause via `klause-cli`. Alias `reference=`. |
| `timeout=<ms>` | per-instance solve budget |
| `label=<name>` | free-form run tag folded into the `<config>` dir name (e.g. a klause version / fix name), so re-running the same config coexists as a distinct dir instead of overwriting — then `compare.sh` the two. References are version-stable, so this is mainly for klause across updates |
| `solver-seed=N` | the solver's random seed (`-r`); unset keeps the bench's fixed seed. A non-default seed is folded into the `<config>` dir name, so seeds of one config coexist |
| `engine=fixed\|cp\|mixed\|ls` `processors=N` | klause's search for a `solve` run (below), forwarded to the cli `-e`/`-p` (the cli owns the engine model). `engine` unset ⇒ no `-e`, so klause follows the cli's own default engine (the bench has no engine default of its own). `fixed=true` is a *separate* reference-only `-f` toggle |
| `lp=off\|conservative\|default\|aggressive[±id…]` | klause only: forwarded as klause-cli `--lp` (the LP-relaxation emphasis / per-technique deltas). Folded into the `<config>` dir name, so `lp=off` vs `lp=aggressive` coexist — run twice and `compare.sh` to A/B the LP cost/benefit |
| `exact=true\|false` | klause only: forwards `--exact` when true, preserving continuous FlatZinc floats and requiring exact MPS certificates. Exact and default runs use separate result directories and cache keys. |
| `param=key=value` (repeatable) | klause-cli `--param` engine knobs forwarded verbatim. `var-selector`/`val-selector` resolve a one-arm override pool on `engine=cp`, and on `engine=fixed` for an unannotated model (single-solver heuristic A/B) — run `solve` twice with different selectors, then `compare.sh` the two dirs. Folded into the `<config>` dir name so runs don't clobber |
| `profile=cpu\|wall\|alloc` `profile-scope=solve\|all` `profile-top=N` | JFR profiling (below) |

## What `solve` saves

`solve` runs one backend (`backend=`, default klause) over the selection **as a subprocess** (klause via `klause-cli`, references via `minizinc --solver <id>`), emitting MiniZinc-format output. It saves **one file per problem** under `output/<config>/`: `<problem>.out` (raw solver log) + `<problem>.json` (solver/settings/budget + objective + time-to-best (optimization) or feasibility (satisfaction) + proof status + `%%%mzn-stat` statistics + per-arm `attribution` for klause portfolios). Run once per config and diff two config dirs offline (`output/compare.sh`), which also serves as the wall-time regression check. klause solving needs `:klause-cli:installJvmDist`; because klause-cli renders the *model's* objective, maximize values are reported in the model's orientation (sign-correct against references).

## Offline analysis scripts (`output/`)

`compare.sh` reads the per-problem `output/<config>/*.json` `solve` records — no solving, no Gradle.

- **`compare.sh [--incomplete] <dirA> <dirB>`** — score two configs across their shared problems by the [MiniZinc Challenge](https://www.minizinc.org/challenge/2026/rules/) pairwise (Borda) rule: per problem, the better solver scores 1, a tie splits by the time fraction `timeUsed(B)/(timeUsed(A)+timeUsed(B))`, and an unsolved instance scores 0. "Better" is the priority chain solved > optimal > quality, direction-aware. `--complete` (default; the FD/free/parallel/open classes) counts proving optimality as better; `--incomplete` (the local-search class) ignores optimality and flat-scores ties 0.5. Reports each config's total Borda score, strict win/loss/tie counts, the "solves 100% of B's" superset, and any `UNSOUND` clash (A beating a proven optimum, or SAT vs proved-UNSAT).

A portfolio solve under `-s` prints one `%%%klause-arm:` attribution line per installed incumbent. An attribution line has an exact `objective=<Long>` discrete channel and, only when a continuous column has cost, `continuousObjective=<Double>` for the whole model-oriented objective. Saved records retain the former as decimal-text `exactObjective` (safe past 2^53) and retain the legacy numeric `objective` for old result files; a missing modern continuous channel means there is no continuous contribution, not a zero offset.

`--param reseed-stale-threshold=N` sets the portfolio's non-improving-segment threshold (default 3; 0 disables reseeding).
It applies only to resumable optimization arms after an incumbent exists and preserves terminal verdicts.
Statistics retain `portfolioReseedStaleThreshold` and per-arm `reseeds` alongside work and initialization cost.

To rank portfolio arms for the hand-edited catalogs, mine historic lab solves: save each experiment's `deploy/lab cases <id>` output and run `bench mine [by=config|suite|family|format|category|kind] <cases.json> …`. It ranks arms by win share and set-cover over the best-holders, and lists each arm's work, work-weighted reward and per-signal credit from the `arm.<label>` lines a `-s` portfolio run prints. Any quarantined arm run is listed first.

## Recipes

```
# solve a corpus with each backend, one invocation each (klause needs `:klause-cli:installJvmDist`)
bench solve suite=mzn-bench per-family=1 max=50 seed=1                 # klause (cli default engine), sampled slice
bench solve suite=mzn-bench per-family=1 max=50 seed=1 backend=choco   # Choco baseline, same selection
bench solve suite=mzn-bench per-family=1 max=50 seed=1 backend=yuck    # Yuck baseline
# each writes output/<config>/<problem>.{out,json} — diff two config dirs with output/compare.sh

# search-config A/B: run twice with different --param, then compare the two config dirs.
# heuristic A/B: var-/val-selector params resolve a one-arm override pool on engine=cp.
bench solve suite=core kind=cop engine=cp param=var-selector=vsids timeout=30000
bench solve suite=core kind=cop engine=cp param=var-selector=chb   timeout=30000
# output/compare.sh output/klause-cp-p1-t30s-var-selector-vsids output/klause-cp-p1-t30s-var-selector-chb

# tracking klause across updates: rebuild klause, re-run with a version label, then compare to the
# prior version. References don't change, so only klause is re-run; old labelled dirs stay put.
bench solve suite=mzn-bench engine=cp label=pre-count-native timeout=300000   # before a fix
# … land the fix, ./gradlew :klause-cli:installJvmDist :klause-bench:installDist …
bench solve suite=mzn-bench engine=cp label=count-native     timeout=300000   # after
# output/compare.sh output/klause-cp-p1-t300s-count-native output/klause-cp-p1-t300s-pre-count-native
```

### Solve: klause competition tracks as filter combinations

When `solve` runs klause (no `backend=`), the klause side is the `engine` enum — owned by klause-cli,
which the bench forwards verbatim to `-e`. With `engine` unset the bench passes no `-e`, so klause
follows the cli's **own** default engine (the bench has no engine default of its own). **`engine=fixed`**
(the MiniZinc-Challenge FD behaviour) is a single naked backtrack following the model's `int_search`
annotation; the portfolio engines **`cp`** (backtrack-only), **`mixed`** (bt+ls), **`ls`** run
sequentially at `-p1` and as a parallel pool at `-p N`. **`cp`** also takes the per-solver
`var-selector`/`val-selector` `--param`s (they edit the pool across its arms).
`processors` defaults to 1, so
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

`solve` is special: it normally runs solvers as subprocesses (klause-cli / minizinc), which JFR on the bench JVM can't see. So `solve … profile=…` switches to **in-process profiling** — it runs the klause engine itself inside this JVM under the recorder, capturing the real `BacktrackSolver` / `LocalSearchSolver` hot paths. This needs a single-solver engine: `cp`/`fixed` (→ `BacktrackSolver`) or `ls` (→ `LocalSearchSolver`); the `mixed` portfolio and external references aren't profilable. No JSON/cache is written in this mode — it measures the solver, not the figures.

```
bench solve suite=mzn-bench name=mario engine=cp profile=cpu timeout=30000
bench solve suite=mzn-bench name=mario engine=fixed profile=cpu timeout=30000
bench solve suite=mzn-bench name=mario engine=ls profile=alloc timeout=30000
```

For a deeper whole-JVM native profile, the gradle hook is still available: `-PasyncProfiler=/path/to/libasyncProfiler.so [-PprofFormat=traces=30] [-PprofOut=…]`.

## Catalog, corpus, and selection

Suites and problems are declared in `catalog/Suites.kt`. Add a problem with `vendored` (a small file under `smoke-corpus/`), `inCode` (built in Kotlin), `workspace` (a file elsewhere in the repo), or `external` (inside a fetched collection).

Vendored problems live in `smoke-corpus/` — small, fast instances meant to exercise parsers and cross-check solvers, not to stress them (see `smoke-corpus/PROVENANCE.md`). Non-redistributable collections (MiniZinc Challenge, libminizinc, hakank, SATLIB, SMT-LIB, XCSP3, PB, MaxSAT, MIPLIB) are fetched on first use into `~/.cache/klause-bench/corpus/` (`$XDG_CACHE_HOME`, or `-Dklause.bench.corpusCache`) and declared with license + reason in `ExternalCollections`; the large corpora are exposed as discovered suites selected by the family-aware machinery in `source/CorpusSelection.kt`. For parallel sweeps, `-Dklause.bench.shard=i/n` keeps every n-th selected instance (applied before resolution, so shards never race on the mzn→fzn cache).

MiniZinc Challenge discovery excludes 2026 families without `.dzn` or `.json` instance data and
`2010/depot_placement`, whose legacy `is_output` annotation fails with current MiniZinc.
Exclusions apply before sampling and caps; 2026 families become eligible when their data files are available.

### Compressed corpus

The cache stores fetched instances zstd-compressed as `<name>.<ext>.zst` (`.cnf`, `.wcnf`, `.opb`, `.wbo`, `.xml`, `.smt2`, `.mps`), which makes these text formats 5-15x smaller. MiniZinc sources (`.mzn`/`.dzn`/`.json`) and git-cloned collections stay plain, since `minizinc` reads them as real files. zstd is chosen because a klause solve receives the `.zst` path and decompresses it inside the measured time, and zstd decodes at about 1 GB/s. The `zstd` binary must be on `PATH` for the bench and for `klause-cli`.

Instance names, families, and `build/bench-cache/` keys ignore the compression: the key hashes the decompressed bytes, so a result cached from a plain file still hits once the file is compressed. Plain instances keep working, so vendored files and a partly migrated cache need nothing. Reference solvers read the decompressed text, and cp-sat (a docker mount) and z3 get a staged plain copy under `<cache>/.plain/`.

A cache fetched before compression is migrated in place, file by file: each archive is written beside the plain file, checked by decompressing it and comparing SHA-256, and only then replaces it. The command skips files already compressed and can be interrupted and re-run.

```
./gradlew :klause-bench:bench --args="corpus compress"                       the whole corpus cache
./gradlew :klause-bench:bench --args="corpus compress /path/to/collection"   one directory
```

### Cache size cap

The corpus cache is capped at 60 GB (decimal). Set the cap with `-Dklause.bench.corpus.maxGb=N` or `KLAUSE_BENCH_CORPUS_MAX_GB=N`; `0` or `off` removes it. The unit of eviction is a whole collection directory, since every collection is re-fetchable and evicting one only costs a re-download. Before fetching a collection and again once it has landed, the bench evicts least recently used collections until the cache fits the cap. It never evicts a collection this run has used, and when only those remain it warns and carries on over the cap. Only the evicting JVM's own use pins a collection, so parallel shards sharing a cache should fetch their collections first or run with the cap off.

Each collection's size and last use are kept in `<cache>/.meta/<id>.properties`, so a lookup never walks the instance files. The size is recorded when a fetch or `corpus compress` finishes, and measured once for a collection fetched before it was tracked. An eviction renames the collection into `<cache>/.trash/` before deleting it, so an interrupted eviction never leaves a half collection that looks fetched; the next eviction pass empties the trash. The result cache `build/bench-cache/` is not capped.

```
./gradlew :klause-bench:bench --args="corpus gc"       apply the cap now, print what it evicted and the cache
./gradlew :klause-bench:bench --args="corpus status"   list collections with size and last use
```

### Dataset categorization

Every discovered suite carries a `Category` (`SAT`/`UNSAT`/`CSP`/`OPTIMIZATION`/…) and `Format`; `bench classify` additionally fills in source-text structural features (`InstanceClassifier`) that land in the local reference tables (below) — `structure` (the coarse niche class: `global`/`linear`/`pseudo-boolean`/`sat`/`arithmetic`), global/linear constraint counts, `boolHeavy`, and **`logic`**: the axis that matters most for SMT-LIB, where it is the instance's declared `(set-logic …)` verbatim (`QF_LIA`, `QF_NRA`, …), and a lighter one for MPS, where it is `MIP`/`LP` from the presence of an `INTORG` marker. Suites are also named by logic/year where that is the natural grouping (`smtlib-qflia`, `smtlib-qflra`, `mzn-challenge-2025`, `pb-comp-2025`), so `suite=` selection doubles as a category filter without needing `bench classify` to have run first.

SMT-LIB suites cover the logics klause's theories (`theory.difference`/`theory.lia`/`theory.qflra`) decide: `smtlib-qflia`/`-qflra`/`-qflira`/`-qfidl`/`-qfrdl`. Logics klause has no theory for are not suites.

## Reference solvers

The reference path depends on the instance's format:

- **MiniZinc instances** run the reference **end-to-end via `minizinc --solver <id>`** on the original `.mzn`(+`.dzn`) — the competition setup, where the solver compiles the model with **its own** globals library and uses its native propagators. This is the faithful baseline. Yuck is registered out of the box; Choco is provisioned with `./gradlew :klause-bench:installChoco` (fetches the choco-parsers FlatZinc jar + Choco's `mzn_lib` globals and registers `choco.msc` under `~/.minizinc/solvers`, so `minizinc --solver choco` runs Choco with its own native globals).
- **Non-MiniZinc formats** (XCSP3 / SMT-LIB / DIMACS / OPB — no `.mzn` to hand to `minizinc`) have no `minizinc --solver` baseline, so a `solve` run measures **klause only** via `klause-cli` (the in-process Choco/OR-Tools/Yuck/LogicNG adapter modules were removed in the subprocess refactor). They still get an **oracle** through `bench reference`, each by a format-native strong solver: cp-sat for XCSP3 (the CPMpy container), clasp for DIMACS/OPB, and z3 for every SMT-LIB logic — one local, gitignored `reference/<solver>.csv` per solver. The shared reference results live in klause-lab's database, filled by `solve-one backend=reference` cases. The one-file-per-solver split is also how the same MiniZinc dataset gets multiple reference baselines: `output/<config>.csv` from a `bench solve backend=choco` run and one from `backend=cp-sat` (or any other registered MiniZinc solver) sit side by side and diff with `output/compare.sh`, same as the local oracle tables.

Running references end-to-end via `minizinc --solver` is deliberate: an in-process adapter would re-derive the reference from klause's **already-decomposed** `Problem` and inherit klause's lowering (e.g. a `subcircuit` turned into clauses, or an internal `GaussianXor`) instead of the solver's native global — distorting the baseline.


Some formats have a second reference solver, which `solve-one backend=reference solver=<id>` runs, so a problem can
carry two verdicts: kissat beside clasp for DIMACS CNF, cvc5 beside z3 for SMT-LIB, HiGHS beside SCIP for MPS, and any
MiniZinc solver (Chuffed beside cp-sat) for MiniZinc. All run single-threaded; kissat, cvc5 and HiGHS are native
binaries found on `PATH`, or at `-Dklause.bench.<name>=<path>`.

An MPS reference's claim is checked against the model before it becomes a verdict, since SCIP and HiGHS judge their
own presolved, scaled models under their own tolerances. Each returns its solution (SCIP by `display solution`, HiGHS
in a solution file, written beside the case record as `<problem>.sol`). The check rounds and fixes every integer
variable, then recomputes every bound and row of the original model to a relative 1e-6. A solution that fails is
repaired by solving the LP over the continuous variables with the integers fixed (HiGHS, at 1e-9), and checked again.
When that LP finds no completion, an elastic one minimizes the largest row violation, scaled by each bound as the check
scales it: its optimum is the closest completion, which the check accepts or rejects, and a solution whose closest
completion lies beyond the check's tolerance is invalid. One that cannot be repaired is unknown, as is one the check
cannot settle. The objective recorded is the one
recomputed from the checked solution. An optimum counts as proven only when its dual bound meets its primal bound:
HiGHS stopping within its 0.01% gap tolerance records a solution, not a proof. A HiGHS run whose claim does not hold
up (infeasible, or a solution that fails the check) is retried without presolve on what is left of its budget, and
that run is checked the same way. The record keeps the solver's status, solution status, dual bound, gap, the
violations, how the solution was checked, the options, and `referenceVersion`: the solver build, its options and the
check's version. That identity is part of the cache key, so a result judged another way is never replayed, and the
lab replaces a row of another version whatever its strength.
## Running the parity sweep

The parity sweep measures klause against the reference solvers on the MiniZinc Challenge corpus. The method is fixed so every run is comparable:

1. **One backend per `bench solve` invocation** — there is no in-session comparison (a crash or warmup can't contaminate another solver's number). Each run writes one `.out` + `.json` per problem under `output/<config>/`; comparison is done **offline**, direction-aware (maximize vs minimize), by diffing two config dirs: `output/compare.sh <dirA> <dirB>`. Results are also content-addressed in `build/bench-cache/` (keyed by `sha256(model+data) · time-settings · solver+settings`), so re-running an identical instance replays instantly — reference baselines stay frozen while klause iterates (klause's key folds in the commit the cli was built from, or the cli binary's mtime when its sources have uncommitted changes, so rerunning an unchanged commit replays). Disable with `-Dklause.bench.cache=false`.
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
