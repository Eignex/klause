# Diagnostic tools and saved artifacts

The tools below are opt-in. They use retained fixtures or explicitly selected
corpus inputs and do not run during ordinary tests. Rebuild the CLI with
`:klause-cli:installJvmDist` before subprocess measurements.
[Benchmarking](../benchmarking.md) defines selection, result validation and
measurement policy; [LP replay](../lp/replay.md) covers basis traces and scoped
owner lifecycle.

## Component-split fixtures

[lp-component-split](../../klause-bench/scripts/lp-component-split/) contains
generation and analysis scripts for the explicit `lp-component-split` suite.
Each bounded dense block has the exact witness `x = 1`. The linked MiniZinc
control adds a redundant row joining blocks. Use `exact=true` and a continuous
objective to exercise CP residual LP certification; grid floats and exact
satisfaction can select different routes. The optimization fixture attains 1
at `x = 1` and finite `k = 0` or `1`.

```sh
python3 klause-bench/scripts/lp-component-split/generate.py
python3 klause-bench/scripts/lp-component-split/analyze.py /path/to/cases.json /path/to/raw
python3 klause-bench/scripts/lp-component-split/noise.py /path/to/cases.json
```

`analyze.py` reads raw `<case-index>.out` files and checks dense source witnesses.
It rejects incomplete/failed records and missing elapsed timing, separating proof
counts and incumbent times from subprocess duration. Optional split/component
counters omitted when zero are read as zero. `noise.py` requires balanced
`off/on/on/off` order, completeness and matching outcomes before reporting
paired duration and duplicate-arm variation. Saved records live under
[component-split reports](../../klause-bench/reports/lp-component-split-1455/).

## Repair-chain probe

[repair-chains](../../klause-bench/scripts/repair-chains/) measures 1000
`proposeRepairChains` calls after 500 ordinary local-search moves. Parsing,
baking, construction, preparation and restoration checks stay outside the
timed region. Round zero warms up; rounds one through four repeat the seeded
workload. Inputs solved during preparation decline.

```sh
./gradlew -I klause-bench/scripts/repair-chains/repair-chains.init.gradle \
  :klause:repairChainProbe --args="/absolute/path/to/instance.cnf 3"
```

`-PchainProbeCli=/absolute/path/to/klause-cli-jvm` selects an installed build's
classes. Compare proposal digests, RNG state, emitted chains, repair calls and
factor callbacks before comparing time or current-thread allocation. Wrapper
dispatch and digest construction are timed. The probe checks restoration of
assignment, factor degrees, costs, break/make counts, tabu/configuration flags
and best-cost watermark. It measures prepared proposal generation rather than
full-solve quality.

## Table profiling

[table-throughput](../../klause-bench/scripts/table-throughput/) retains fixed-node
and fixed-deadline specifications plus raw measurements. A useful profiling
invocation after installing the CLI is:

```sh
KLAUSE_CLI_OPTS="-Xmx3g -XX:+UseSerialGC -XX:ActiveProcessorCount=1 -XX:StartFlightRecording=filename=table.jfr,settings=profile" \
  klause-cli/build/install/klause-cli-jvm/bin/klause-cli \
  -e cp --param arms=1 --param bt-arm=conflictDriven --param node-limit=100 \
  -r 3 -s -t 30000 /absolute/path/to/instance.xml
jfr print --json --events jdk.ExecutionSample table.jfr
```

Separate root construction, propagation and explanation samples. Compare
fixed-node timing only when decisions, failures, restarts, root fixings and
depth match and both runs spend the cap. Deadline runs measure search progress;
they can reach different prefixes. Recorded specs pin their own historical
builds and are not current default settings.

## Exact-arithmetic diagnostics

[exact-projection](../../klause-bench/exact-projection/) retains profile,
fixed-work, deadline and witness records with their analyzers:

```sh
python3 klause-bench/exact-projection/profile.py /path/to/profile-directory
python3 klause-bench/exact-projection/compare.py /path/to/cases.json
python3 klause-bench/exact-projection/summarize.py /path/to/cases.json /path/to/output-prefix
```

Profiles are independent of uninstrumented timing. Frame disappearance after
inlining is not an absolute CPU saving. Witness records check source feasibility;
unknown outcomes supply no witness or proof. Preserve pinned manifests and
build/runtime identities when analyzing saved cases.

## Saved search and propagation campaigns

These directories contain specs, raw records, provenance and analysis scripts:

| Directory | Subject |
|---|---|
| [learned-clauses](../../klause-bench/learned-clauses/) | Shared conflict coverage, retention selection and experimental ranking |
| [rws-conflict](../../klause-bench/rws-conflict/) | Native CP Boolean-pin undo lifetime reproducer and controls |
| [propagation-oracle](../../klause-bench/reports/propagation-oracle-2315/) | Independent propagation source checks and controls |
| [construction](../../klause-bench/construction-2328/) | Resumable-arm startup accounting and controls |
| [arms campaign](../../klause-bench/campaigns/arms-414/) | Pool composition, reseeding, incumbent attribution and source checks |
| [presolve campaign](../../klause-bench/campaigns/presolve-554/) | Effort settings, rounds, probing and deadline controls |
| [wall-clock records](../../klause-bench/reports/wall-clock-2353/) | Subprocess elapsed-time fallback controls |

Current learned-clause, pin lifetime and scheduling behavior is documented in
[search](../search.md); presolve knobs are in the [CLI guide](../cli.md#flags).
Pinned experiment options can belong to unmerged or removed code. Read their
specifications and analyzer arguments before attempting a rerun.

The learned-clause analyzer takes case JSON, a frozen manifest and arm JSON:

```sh
python3 klause-bench/learned-clauses/analyze.py cases.json manifest.json arms.json \
  --baseline baseline --output results
python3 klause-bench/rws-conflict/summarize.py \
  spec.json cases.json arms.json job.json results.json
python3 klause-bench/campaigns/presolve-554/analyze.py cases.json --control explicit-default
```

Analyzers enforce their recorded corpus, completion and identity requirements.
Retain missing metrics explicitly. Shared conflict counters do not describe
native CP analysis. A reported SAT result or agreement between runs is not an
independent source-witness check. Fixed-deadline cases stopped before a decision
cap do not establish fixed-work throughput.
