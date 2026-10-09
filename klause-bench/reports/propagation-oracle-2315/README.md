# Propagation oracle independence

Behavioral evidence for [#2315](https://github.com/Eignex/klause/issues/2315).
The stack starts with [the retained reproducer](https://github.com/Eignex/klause/pull/2371),
followed by [the oracle correction](https://github.com/Eignex/klause/pull/2376)
and integration validation.

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

## Integration contracts

The integration layer reuses the existing fixtures and checks their source semantics directly:

- `ResumableSearchTest`: exhaustive 128-mask knapsack optimum, capacity/domain checks and objective
  recomputation for one-shot, sliced and callback witnesses.
- `ResumableNodeSliceTest`: exhaustive 27-point mixed fixture with the minimum feasible real coordinate,
  one-shot and one-node slices, a required pause, and source constraint/objective checks.
- `FinitePipelineTest`: reconstruct every affine fixture solution and compare with all 44 declared pairs;
  check the continuous coordinate and affine equation together.
- `LpPropagatorTest`: check exact coordinates under an appended row, after scope pop, and in retained
  snapshots from both scopes.
- `LiveLpTheoryTest`: cancelled complete checks withhold models, repeated interruption remains unknown,
  and clearing cancellation restores a directly checked source witness; proof vetoes cannot emit models
  or turn unknown into infeasible.
- `LpTerminalDeclineTest`: validate mixed incumbents against the fractional row and declared bounds,
  preserve best-found status after decline, and withhold cancelled/declined satisfaction witnesses.

These changes add no production algorithm, capability, configuration or session redesign.
GitHub CI executes the JVM, Linux native and lint/docs gates for each stack layer.

## Limits

These are deterministic behavioral checks, one invocation per listed command, not benchmarks.
Gradle's configured Kover instrumentation is enabled; XML durations include runtime and instrumentation
effects and are not throughput or deadline evidence. Other sessions were using the machine, so no timing
comparison is made. No CLI subprocess was executed. Any additional solve experiment must use klause-lab with `host=aws`.
Seed is `0` in the reproducer and resumable fixture; other existing fixture seeds are retained in source.
Reference enumeration is exhaustive for the three declared values with no step or time truncation.
The resumable fixture uses its existing cancellation polls and 60-second slice ceiling.
The full local `check lintDocs` gate is skipped; GitHub CI supplies the full gate.
