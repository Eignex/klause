# Repair-chain proposal probe

This opt-in CNF probe measures 1,000 `proposeRepairChains` calls on a state prepared by 500 ordinary CBLS moves.
Parsing, root baking, state construction, preparation and restoration checks are outside the timed region.
Round 0 warms the proposal path; rounds 1–4 repeat the same seeded proposal workload. Each call samples a violated
seed factor and uses depth 16 with four repair firsts and four ejection firsts. An instance solved by preparation is
rejected. Ordinary tests and benchmarks do not load the probe.

```
./gradlew -I klause-bench/scripts/repair-chains/repair-chains.init.gradle :klause:repairChainProbe --args="/absolute/path/to/instance.cnf 3"
```

`chainProbeCli` selects the main classes from a CLI distribution built at an exact baseline or candidate commit:

```
./gradlew -I klause-bench/scripts/repair-chains/repair-chains.init.gradle :klause:repairChainProbe -PchainProbeCli=/absolute/path/to/klause-cli-jvm --args="/absolute/path/to/instance.cnf 3"
```

Build each distribution with `:klause-cli:installJvmDist`, keep copies, and alternate baseline/candidate runs on one
host. Compare proposal digests, the next RNG value, emitted chains, repair calls and factor applies before comparing
elapsed nanoseconds and current-thread allocated bytes. `factorProbes` counts invariant Boolean delta calls, not
candidate evaluations; `factorApplies` counts invariant callbacks, not accepted moves. The wrappers add dispatch
overhead, and digest construction is timed. These timings describe prepared proposal generation, not full solves or
search quality. Use paired fixed-work and equal-time lab solves for those checks. The probe verifies assignment,
per-factor degrees, cost, break/make counts, tabu activity, configuration-change flags and best-cost watermark after
all calls.
