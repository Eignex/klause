# klause-bench

JVM problem catalog and single-case CLI runner for [eignex-lab](https://github.com/Eignex/eignex-lab).
Use its maintained authenticated HTTP client at https://lab.eignex.com for comparisons, source checks and profiling.

Build the runtime with `:klause-cli:installJvmDist :klause-bench:installDist :klause-bench:prepareLabRuntime`.
The lab calls `select` to resolve problem files and `solve-one` to run the installed CLI.
See the [lab run guide](https://github.com/Eignex/eignex-lab/blob/main/docs/runs.md) for requests and artifact retention.

A paired row runs every arm for one workload, seed and repetition on one machine in one worker attempt.
Use automatic compatible placement; other rows and replacement attempts may use other machines.
Keep failed attempts and all preparation costs. SSH access and lab administration belong to the operations owner.
