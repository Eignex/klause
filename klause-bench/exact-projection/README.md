# Current-main exact arithmetic investigation

Partial work toward [#2348](https://github.com/Eignex/klause/issues/2348).
The reproducer is [PR #2373](https://github.com/Eignex/klause/pull/2373).
The baseline is `5471419aa8c832ee1065468a6861bb203a1ff8c7`, after the cancellation
polling work in [#2363](https://github.com/Eignex/klause/pull/2363).

## Reproduction

From the repository root, with a sibling `klause-lab` checkout:

```sh
../klause-lab/deploy/lab run klause-bench/exact-projection/profile-main.json
../klause-lab/deploy/lab run klause-bench/exact-projection/work-main.json
../klause-lab/deploy/lab fetch 851 /tmp/exact-profile-851
python3 klause-bench/exact-projection/profile.py /tmp/exact-profile-851
curl -fsS http://192.168.50.104:8420/experiments/JOB/cases > /tmp/exact-cases.json
python3 klause-bench/exact-projection/compare.py /tmp/exact-cases.json
```

The comparator expects paired arms named `main` and `candidate`. It reports every
outcome or non-timing solver-counter difference rather than assuming a node limit
alone fixes work. Timing, rate and elapsed-duration counters are excluded explicitly.

[Profile job 851](http://192.168.50.104:8420/?job=851) and
[work-baseline job 854](http://192.168.50.104:8420/?job=854) use dedicated AWS
c7i.2xlarge instances, one case at a time, CP, one processor and seed 1.
The three named nec-smt inputs are checkpass, handler_sigchld and mygetpwnam, each
`prp-1-46`. Selection sets `per-family=1000` so the controls survive family sampling.
Both jobs pin the commit rather than a moving branch. The lab builds
`:klause-cli:installJvmDist` remotely and retains CLI/runtime build fingerprints,
raw output, selection, commands, JVM options and records in the job Files pages.

JFR records the whole CLI JVM at a 20-second solve budget. Its samples include
startup and are statistical; inclusive symbols overlap and cannot be added as
exclusive costs. Profile timings are excluded from runtime comparisons.
The uninstrumented baseline caps search work at 1000 with a 120-second safety
deadline. These runs cannot establish a solved-runtime improvement if they stop
unknown at the cap. Paired measurements must use the same instance and executed
build identities; the separate baseline job is a characterization, not a timing
control for a later candidate.

No local Gradle build, solve, test, lint or documentation gate is run for this stack.
GitHub CI supplies the build/test/lint/documentation gates. Raw-record analysis is
separate from experiments and does not execute a solver.

## Current-main profile

Job 851 completed all three cases on instance `i-0481ad434ce5c125f`. The retained
[summary](profile-main-summary.json) includes the executed build fingerprints and
SHA-256 of each JFR file. All three outcomes are unknown at the time budget.

| Inclusive symbol | checkpass, 1793 samples | handler_sigchld, 1682 | mygetpwnam, 1764 |
| --- | ---: | ---: | ---: |
| BigFraction.of | 113 (6.3%) | 88 (5.2%) | 75 (4.3%) |
| withPublishedBounds | 86 (4.8%) | 9 (0.5%) | 13 (0.7%) |
| LiveQfLraSystem.install | 73 (4.1%) | 20 (1.2%) | 9 (0.5%) |
| LpExactState.projectScalars | 2 (0.1%) | 1 (0.1%) | 0 |
| Cancellation.invoke | 18 (1.0%) | 16 (1.0%) | 317 (18.0%) |

Rational construction is the largest remaining named numerical item on checkpass.
Its factory computes a GCD even for denominator one and divides numerator and
denominator even when the GCD is one. IEEE conversion also sends its already
reduced pair through that factory. These are candidates for a focused arithmetic
change; this profile supplies no absolute CPU saving or end-to-end speedup claim.
The residual cancellation share on mygetpwnam remains outside this change.
