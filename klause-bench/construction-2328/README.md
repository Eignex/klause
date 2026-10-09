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
raw streams with `lab fetch 853`. Each record includes the executed command,
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
