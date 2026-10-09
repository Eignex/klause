# RWS conflict reproduction

Issue [2352](https://github.com/Eignex/klause/issues/2352) concerns repeated native CP conflicts on
`smtlib-qflia/RWS/Example_2.txt`. The frozen current-main baseline is `5471419aa8c832ee1065468a6861bb203a1ff8c7`.
The input's decompressed SHA-256 is `07bde5aa000211fa6c05c266e17dfc0fdbbd4df1f4dc92c613202ef23732ff05`.
The corpus is fetched by the existing SMT-LIB bench collection; it is not vendored here.

`baseline-lab.json` specifies LP off, one processor, seed 1, 100,000 committed nodes and a
60-second wall allowance, three repetitions of each route/revision. It compares current main,
historical regression `7cbf3ba14`, historical control `86156e3e2`, and current-main `satOptimized`
and default routes. [AWS lab 852](http://192.168.50.104:8420/experiments/852) executes the specification
serially on one instance. JFR is disabled. Completed results are retained in the validation report.

Submit and retain the raw records, executed arm identities and job metadata:

```sh
deploy/lab run /path/to/klause/klause-bench/rws-conflict/baseline-lab.json
curl -fsS http://192.168.50.104:8420/experiments/852/cases -o baseline-cases.json
curl -fsS http://192.168.50.104:8420/experiments/852/arms -o baseline-arms.json
curl -fsS http://192.168.50.104:8420/jobs/852 -o baseline-job.json
python3 summarize.py baseline-lab.json baseline-cases.json baseline-arms.json baseline-job.json baseline-results.json
```

Run the summarizer on the remote runner or CI. It audits complete arm/seed/repetition coverage,
executed revisions, binary fingerprints, process settings and actual solve records. Raw records
retain exact CLI commands, all counters and the validation policy. A SAT verdict using the reported
result policy is not an independently verified source witness. Compare reference verdicts separately.
Completed solves and consumed node allowances support fixed-work observations; a wall cutoff before
100,000 nodes does not. Instrumented diagnostics do not support ordinary timing claims.

Before the remote-only execution policy arrived, local `:klause-cli:installJvmDist` completed twice
(baseline and a temporary diagnostic build). Two 10-second diagnostic solves completed; a Java 22
launch failed before solving because the CLI requires Java 25. The uninstrumented diagnostic stalled
at 873 nodes. Temporary clause tracing observed repeated clauses with multiple unassigned literals
at their purported asserting target. Tracing was removed, and no local workload was active when the
policy changed. These observations guide investigation and are not paired performance evidence.
Full local `check lintDocs` and unit tests were skipped. GitHub CI supplies build/test/lint/docs gates;
all further experiments use AWS lab.

The [validation report](report.md) records the results. The stack is
[#2372](https://github.com/Eignex/klause/pull/2372) (reproduction),
[#2377](https://github.com/Eignex/klause/pull/2377) (pin lifetime fix), then
[#2385](https://github.com/Eignex/klause/pull/2385) (evidence).
