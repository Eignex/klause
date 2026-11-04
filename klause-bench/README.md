# klause-bench

JVM problem catalog and single-case CLI runner for [eignex-lab](https://github.com/Eignex/eignex-lab).
Use its maintained authenticated HTTP client at https://lab.eignex.com for comparisons, source checks and profiling.

Build the runtime with `:klause-cli:installJvmDist :klause-bench:installDist :klause-bench:prepareLabRuntime`.
The lab calls `select` to resolve problem files and `solve-one` to run the installed CLI.
See the [lab run guide](https://github.com/Eignex/eignex-lab/blob/main/docs/runs.md) for requests and artifact retention.

A paired row runs every arm for one workload, seed and repetition on one machine in one worker attempt.
Use automatic compatible placement; other rows and replacement attempts may use other machines.
Keep failed attempts and all preparation costs. SSH access and lab administration belong to the operations owner.

Declared-input lab planning uses the corpus-pack index for unmaterialized members.
Complete local collections retain byte authority. The lab verifies each planned input
before execution. Selective ZIP collections discover eligible archive members when
a complete local collection and pack index are absent, then fetch only selected members.

Every requested selection must match a source. Planning resolves all selected sources
before sealing directory identities; each solve verifies its planned files and directories.
