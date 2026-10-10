# Diagnostic tools

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
paired duration and duplicate-arm variation. Store case records and raw outputs
in an external measurement archive.

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

A useful table profiling invocation after installing the CLI is:

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
they can reach different prefixes. Profiles stay separate from uninstrumented
timing; frame disappearance after inlining is not an absolute CPU saving.

## Record handling

Use the active harness's [result records](../benchmarking.md#saved-results) and
[source checks](../benchmarking.md#source-validation) for new measurements.
Freeze source hashes, settings and build/runtime identities before comparing
runs. Store generated outputs and historical bundles outside the repository.
Missing metrics remain explicit, and unknown outcomes supply no witness or proof.

Shared conflict counters do not describe native CP analysis. A reported SAT
result or agreement between runs is not an independent source-witness check.
Fixed-deadline cases stopped before a decision cap do not establish fixed-work
throughput. Current search behavior is documented in [search](../search.md);
presolve settings are in the [CLI guide](../cli.md#flags).
