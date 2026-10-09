# Fixed integer float images

Evidence for #2275, collected on 2026-10-09 through the klause-lab queue. The implementation is [PR #2356](https://github.com/Eignex/klause/pull/2356).

## Pinned inputs and original constraints

The [source fixture](../klause/src/jvmTest/resources/flatzinc/README.md) records the source revision, MiniZinc version, compilation library, hashes and license. Its 129 flattened constraints and 90 scalar range bounds are unchanged. Fifteen `int_eq` constraints pin the half-penny inputs to `(2,4,6)`, `(2,3,10)`, `(1,8,9)`, `(1,6,14)`, `(1,5,24)`.

`FlatZincEnigmaWitnessTest` checks the returned values directly against every original constraint and bound. It also checks totals `[6.0, 7.5, 9.0, 10.5, 15.0]`. The targeted FlatZinc JVM run passed 128 tests. The full local `check lintDocs` gate was skipped; GitHub CI supplies that gate.

## Paired lab jobs

[Job 796](http://192.168.50.104:8420/jobs/796) compares control `58ae735e7b527a9d5e1050a0111b78f38b8590f0` with implementation `a93215003f6185183aaf92b762354a5693a03fbc` on the original unpinned Enigma source, `celsius_fahrenheit/celsius_fahrenheit`, and `nonlin_cylinder/nonlin_cylinder` from `hakank`.

[Job 799](http://192.168.50.104:8420/jobs/799) compares the original pinned FlatZinc fixture using temporary benchmark-only commits: control `e0536676c885cbf18665a9a8abe458fc162b6ac7` and candidate `4a813bd31eee00a6631d14270ab020694700cf37`. Both register the identical fixture in `mzn-smoke` and accept its FlatZinc directly. The implementation PR excludes those harness changes. The user approved this isolated exception because SSH corpus staging was unavailable.

Both jobs ran on the lab Mac, with `parallel=1`, `engine=cp`, `processors=1`, `timeout=5000`, solver seeds `[1,2]`, and two repeats per seed. The queue builds each commit with `:klause-cli:installJvmDist`, disables result-cache replay, and alternates arms. The host reports ARM64, Java 25.0.4.1, koblas SIMD and Accelerate. These are fresh CLI subprocess runs, including JVM startup; `timeToBestMs` is the lab record's metric, while `solveTime` is the CLI's internal metric.

CLI build fingerprints are identical across the two jobs for each respective arm: control `808a303e4e5268ab6b810478b4831950b0b154223b3c23c25ebf11ebf3011bcc`, candidate `f2b6ed6720b15dfc4ee713b7dd52e02ff1824a93e8d5c1f5583972ad891af129`. The later PR changes only declaration placement, formatting and this report.

| Case | Candidate time to best, ms | Candidate solve time, s | Candidate nodes / conflicts | Control |
| --- | --- | --- | --- | --- |
| Unpinned, seed 1, repeat 1 | 594 | 0.277 | 240 / 101 | UNKNOWN at 5 s |
| Unpinned, seed 1, repeat 2 | 598 | 0.279 | 240 / 101 | UNKNOWN at 5 s |
| Unpinned, seed 2, repeat 1 | 892 | 0.574 | 945 / 572 | UNKNOWN at 5 s |
| Unpinned, seed 2, repeat 2 | 887 | 0.571 | 945 / 572 | UNKNOWN at 5 s |
| Pinned, seed 1, repeat 1 | 507 | 0.157 | 139 / 0 | UNKNOWN at 5 s |
| Pinned, seed 1, repeat 2 | 519 | 0.157 | 139 / 0 | UNKNOWN at 5 s |
| Pinned, seed 2, repeat 1 | 512 | 0.156 | 156 / 0 | UNKNOWN at 5 s |
| Pinned, seed 2, repeat 2 | 514 | 0.159 | 156 / 0 | UNKNOWN at 5 s |

All eight candidate runs returned the specified integer witness and totals. Unpinned controls explored 5,820–6,653 nodes with 2,179–2,655 conflicts; pinned controls explored 7,222–9,416 nodes with 3,017–3,941 conflicts. The small comparison establishes a repeatable feasibility improvement for these instances; it does not estimate a corpus-wide speedup.

## Independent source validation

The raw records retain `sourceValidation=unknown`. For unpinned Enigma, the automatic checker attempts to pin generated output name `X_INTRODUCED_21_`, which the MiniZinc source does not declare. For the pinned fixture, that checker cannot compile a `.fzn` as MiniZinc source. These records were not rewritten.

Each of the eight returned candidates was independently translated to the source names:

```minizinc
x = array2d(1..5, 1..3, [2,4,6,2,3,10,1,8,9,1,6,14,1,5,24]);
total = [6.0,7.5,9.0,10.5,15.0];
```

MiniZinc 2.9.7 compiled the original model and these assignments with its standard library, without Klause's globals or a reference solver:

```sh
minizinc -c --solver org.minizinc.mzn-fzn --random-seed 1   --output-fzn-to-file checked.fzn enigma_248_add_or_multiply.mzn witness.dzn
```

Every compilation succeeded and left only `solve satisfy` with its constant search annotation: no variables, constraints or contradictions. Together with the direct original-flat-constraint regression, this validates source feasibility and the original flattened witness, rather than accepting the solver verdict alone.

## Small regression selection

Celsius/Fahrenheit remained UNKNOWN in all four runs per arm. The rounded-bound cylinder remained on the grid and reported UNSAT in all four runs per arm; the bench correctly withheld source infeasibility credit (`feasible=null`, `floatApproximation=true`). These unchanged outcomes check routing and verdict parity, not correctness or improved performance of those unresolved cases. The dedicated rounded-bound test continues to require grid fallback and an exact-mode diagnostic.

Raw job files and per-case records are available through the two lab links. Local copies, experiment specs and independent source-check outputs are isolated under this session's ignored `build/issue-2275/` directory.
