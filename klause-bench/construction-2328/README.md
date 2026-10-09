# Construction slice reproducer

Issue [2328](https://github.com/Eignex/klause/issues/2328) reports 9.4 seconds of
construction in a 10-second IHTC solve. `baseline.json` pins current main
`5471419aa8c832ee1065468a6861bb203a1ff8c7` after the first-solution changes in
[2359](https://github.com/Eignex/klause/pull/2359) and
[2360](https://github.com/Eignex/klause/pull/2360).

Run with klause-lab:

```sh
/home/rasmus/Workspaces/klause-lab/deploy/lab run klause-bench/construction-2328/baseline.json
```

The initial execution is [lab 853](http://192.168.50.104:8420/jobs/853).
It uses one AWS runner, one case at a time, 10-second deadlines, seed 3 and
three repetitions of default and 12-arm pools. The lab builds each pinned
revision with `:klause-cli:installJvmDist` and preserves installed-build and
runtime hashes. Its rotated paired case order avoids concurrent timing cases.
IHTC is the trigger; Fortress, CyclicBandwidth and CoinsGrid control for the
first-solution and proof behavior of small and expanded pools.

Read raw case records with `lab cases 853`, export `lab csv 853`, and retain
available reports with `lab fetch 853`. Each record includes the executed command,
installed-build fingerprint, budget, incumbent attribution and solver counters.
Compare `arm.*` statistics: `initMs` is inside segment `ms`; `maxMs` includes
construction; `work` includes root propagation at the scheduler's conversion
rate. Aggregate by family using worker labels (`bt/`, `ls/`, `alns/`). Search
wall time is segment time minus construction time, including verification and
other segment overhead; it is not isolated kernel time.

These are uninstrumented deadline experiments, not fixed-work throughput
measurements. Solver incumbents are checked by the portfolio's witness checker;
a non-proven incumbent does not establish optimality. This baseline alone
makes no performance comparison or improvement claim. Full local
`check lintDocs` is skipped; GitHub CI supplies the full gate.

The stack starts with [2370](https://github.com/Eignex/klause/pull/2370),
then the construction fix in [2375](https://github.com/Eignex/klause/pull/2375).
The evidence layer archives remote records using:

```sh
python3 klause-bench/construction-2328/collect.py 853
```

`lab-853/cases.json.gz` retains complete case records, including distribution
and runtime hashes. `job.json.gz` retains exact remote commands, case ordering,
exit codes and the submitted spec. `summary.json` reads their counters and
incumbents without executing a solve. `sha256.json` identifies the archived
files. Missing main-build `initWork` is retained as unknown; setup work must
not be inferred as zero. Search time below means search and segment overhead,
including verification, excluding opening time.

All 24 baseline cases finish. Both default and expanded pools find checked
model incumbents on all three controls in all three repetitions. Expanded
CoinsGrid proves objective 2236 in all three; default CoinsGrid remains
unproven. Neither IHTC pool finds an incumbent. Its first backtrack opening
takes 2008–2108 ms and the second about 2500 ms; only those two arms execute.
This is a current observation, distinct from the issue's historical 9400 ms.
No external source witness check or new reference run was performed.

[Lab 856](http://192.168.50.104:8420/jobs/856) compares the first semantic
candidate at `dbe4070b2` against main, with the same serial uninstrumented
configuration. [Lab 864](http://192.168.50.104:8420/jobs/864) is a separate
JFR diagnostic on IHTC. These jobs are pending; they supply no final
candidate performance claim yet. Successful uninstrumented AWS cases retain
reports rather than raw solver streams; profiling cases also retain stdout,
stderr and JFR artifacts. The lab does not include input content hashes in
these records, so the catalog names and pinned catalog commits identify inputs.

Before the remote-only execution policy was received, a local current-main
CLI install completed and one focused test attempt failed compilation. The
next local focused attempt was stopped with exit 130 before any tests ran.
No completed local test validation is claimed. All subsequent builds,
experiments and profiles use AWS klause-lab; all test/lint/docs gates use
GitHub CI.

The diagnostic [lab 864](http://192.168.50.104:8420/jobs/864) completes both
profiled IHTC cases. Its candidate retires both backtrack openings and runs
all five remaining arms. This only diagnoses yielding under instrumentation;
it is not paired uninstrumented timing evidence. Complete profile case
records are archived in `lab-864`; the lab Files page retains JFR recordings,
resource reports and measurement manifests. The raw stdout is the bench
summary, not a complete source assignment.

[Lab 872](http://192.168.50.104:8420/jobs/872) validates final production
commit `f2c80682e3499439d200fdd22e4a6edea3a72a2e` against pinned main,
with seeds 3 and 7 and two repetitions. It remains serial on one AWS runner;
its build and runtime fingerprints determine the executed identities.
