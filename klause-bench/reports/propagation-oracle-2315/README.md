# Propagation oracle independence

Behavioral evidence for [#2315](https://github.com/Eignex/klause/issues/2315).
The stack starts with [the retained reproducer](https://github.com/Eignex/klause/pull/2371),
followed by the oracle correction and integration validation.

## Baseline and reproducer

Main baseline: `5471419aa8c832ee1065468a6861bb203a1ff8c7`.
Run once in the isolated `oracle-reference-2315` worktree:

```sh
./gradlew :klause:jvmTest --tests '*IncreasingPropagatorTest' --tests '*ResumableSearchTest' --tests '*FinitePipelineTest' --tests '*LpTerminalDeclineTest'
./gradlew :klause:jvmTest --tests '*FactorPropagationOracleTest'
```

The baseline passes 27 tests. The reproducer passes while both `assertSound` and `assertGac`
accept deliberately unsound root minimum, maximum, pin and hole deductions.
The relation is `x <= 2` with declared domain `{0,1,2}`; its existing linear invariant is delegated unchanged.
Only its propagation projection is replaced. Baking leaves two reference solutions for min/max/hole
and one for pin, hiding the declared solutions each deduction removes.
The original model still declares three values.
This small faulty projection is necessary to test the oracle itself; production factors remain unchanged.

`main/` and `reproducer/` retain raw Gradle logs and JUnit XML. The commit files identify source builds;
the reproducer executed with its test source uncommitted over the main baseline, then that exact source
was committed under the recorded SHA. Source, JVM library and compiled-test aggregate SHA-256 fingerprints
are retained. The test aggregate hashes sorted relative paths and their file hashes:

```sh
rg --files -0 klause/build/classes/kotlin/jvm/test | sort -z | xargs -0 sha256sum | sha256sum
```

## Rejection regression and correction

The same reproducer with its acceptance expectation reversed fails on the original oracle:
`assertFailsWith<AssertionError>` receives no exception. `red/` retains that raw failure, the exact
patch over its recorded base commit, and source/binary/test-class fingerprints. This completed
before the execution-policy update that forbids local workloads.

The corrected oracle enumerates source declarations directly through `LocalSearchModel.open`, without
baking or running root deductions. Its existing local-search evaluation remains the default semantic
reference. Optional direct predicates check invariant agreement on every assignment, including rejected
assignments, and classify reference solutions. Existing increasing and nvalue fixtures exercise these
predicates; the oracle regression also rejects a deliberately faulty invariant. The original-space cap
is checked before root propagation and before materializing value indices. Baked inputs are rejected
because their original declarations cannot be recovered. Open integer declarations and continuous
columns are outside this finite oracle's scope and are rejected explicitly.

All correction and integration build/test/lint/docs validation runs on GitHub CI. No local workload
was active when the policy update arrived, so none was interrupted. The archived baseline, reproducer
and red runs above are completed pre-policy evidence, not post-policy validation. This issue changes
test infrastructure only; no CLI solve measurement or performance campaign is needed.

## Limits

These are deterministic behavioral checks, one invocation per listed command, not benchmarks.
Gradle's configured Kover instrumentation is enabled; XML durations include runtime and instrumentation
effects and are not throughput or deadline evidence. Other sessions were using the machine, so no timing
comparison is made. No CLI subprocess was executed. Any additional solve experiment must use klause-lab with `host=aws`.
Seed is `0` in the reproducer and resumable fixture; other existing fixture seeds are retained in source.
Reference enumeration is exhaustive for the three declared values with no step or time truncation.
The resumable fixture uses its existing cancellation polls and 60-second slice ceiling.
The full local `check lintDocs` gate is skipped; GitHub CI supplies the full gate.
