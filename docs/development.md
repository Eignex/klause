# Development

Start with [system architecture](architecture.md) before changing subsystem
boundaries. This guide owns setup smoke commands and contribution workflows;
the [CLI](cli.md) owns launcher and runtime details.

## Package boundary check

`python3 scripts/check_architecture.py` checks documented outbound dependencies across
production Kotlin source sets. `python3 scripts/test_check_architecture.py` checks the
checker itself. `./gradlew checkArchitecture` runs both without compiling Kotlin,
and every module's `check` depends on it. Python 3 is required for this check.
The checker is lexical; JVM/native compilation and consumer tests validate symbol
resolution and module visibility. [System architecture](architecture.md) defines
the enforced ownership rules.

## Getting started

```sh
./gradlew :klause-cli:installJvmDist
klause-cli/build/install/klause-cli-jvm/bin/klause-cli --help
```

The launcher requires JDK 25 or newer. Gradle provisions its compilation toolchain;
the shell that invokes the installed launcher must also select a suitable JDK.
See [CLI setup](cli.md#build-and-run) for runtime options and native builds.

Solve a committed instance:

```sh
klause-cli/build/install/klause-cli-jvm/bin/klause-cli \
  -e cp -t 10000 -s klause-bench/smoke-corpus/smtlib/lia-wide-span.smt2
```

Use the [MiniZinc integration](minizinc.md) for `.mzn` source models. The CLI reads
FlatZinc, XCSP3, SMT-LIB, MPS, DIMACS, OPB/WBO and WCNF directly.
[Input semantics](formats.md) explains the result guarantees and exact mode.

## Kotlin DSL

Declare a schema, compile it and decode solutions:

```kotlin
class Order : VariableSchema() {
    val a by intVar(min = 1, max = 3)
    val b by intVar(min = 1, max = 3)
    val c by intVar(min = 1, max = 3)
    val unique by constraint { allDifferent(a, b, c) }
}

val schema = Order()
val compiled = schema.compile()
for (sample in BacktrackSolver(compiled).enumerate().take(5)) {
    println(compiled.decode(schema.a, sample))
}
```

The [schema DSL](../klause/src/commonMain/kotlin/com/eignex/klause/schema/) and
[solver APIs](../klause/src/commonMain/kotlin/com/eignex/klause/solver/) provide
schema construction, compilation, decoding and solving. Variables can be optional;
constraints lower to reified or aggregation-aware forms. Sets and non-linear float
wrappers compile to supported finite encodings.

## Result requirements

Enumeration and sampling operate over compiled finite problems; counting and
near-uniform sampling mechanisms live under
[count](../klause/src/commonMain/kotlin/com/eignex/klause/count/).
Select the API and engine for the required result. A local-search witness does
not establish exhaustive search or optimality; an interrupted complete search
can remain undecided. LP result strength and source reconstruction follow the
[certification contract](lp/certification.md#proof-packages).

## Validation

Opening or pushing a PR does not require a local `./gradlew check lintDocs` gate.
GitHub CI supplies the full gate. Targeted local tests are optional; record skipped
or interrupted validation accurately and check CI before describing a PR as green.
Fix failures through follow-up commits.

When running the full gate locally:

```sh
./gradlew check lintDocs
```

`check` runs tests and light checks. `lintDocs` runs detekt and Dokka link linting.
Do not add `--rerun-tasks`. Batch edits before running the gate and do not edit
source while analysis runs. Detekt's import autocorrection can change a file
during analysis; a repeatable receiver/offset error naming that file can require
committing the correction and rerunning. Analysis-service failures with no project
file in the stack can be flakes; rerun before investigating. A finding naming a
file and rule needs investigation. Check the named symbol for Dokka translation
failures.

All library modules and the CLI use the same JVM and Linux x64 default targets.
`klause-numerics` and `klause-lattice` publish alongside `klause`, with their own
ABI snapshots and numerical tests. The JVM CI job runs each module's `check`; the
Native job tests both numerical leaves, the solver and its CLI consumer.
The default build targets JVM and Linux x64. Releases opt into Linux arm64 and
macOS arm64 with `-Ptargets.full`. Native tests and documentation checks run in
CI. Benchmarks, capture and timing tools are opt-in; see [benchmarking](../klause-bench/README.md).

## Tests

Unit tests define focused behavior and subsystem contracts: parsing and lowering,
state ownership, rollback, explanations, exact arithmetic, result publication and
resource limits. Keep a small set of representative examples for each contract.
[Klause-lab](../klause-bench/README.md#klause-lab) owns solver regression coverage,
corpus validation and broad scenario sweeps. Avoid duplicating those runs in unit
tests or adding a separate toy solve
for every DSL operator, search policy or frontend feature. Add a unit test when
it clarifies a distinct contract; extend an existing case for a variant of the
same behavior.

Use the surrounding framework, layout and assertions. Name classes after the
file under test and name tests in backticks with plain ASCII describing behavior.
Keep one observable behavior per test, using arrange, act, assert. Parameterize
variants of one behavior and separate semantically different behaviors.
Reuse existing fixtures/helpers. JVM tests must stay below 300 ms; longer
integration and measurement runs belong in explicit tools.

## Source style and boundaries

Prefer clear names and types. Comments explain soundness, performance or lifecycle
reasons rather than narrating code or changes. Public APIs receive KDoc; use
`[Symbol]` links and functional math notation such as `succ(i)`.
Follow the [package boundaries](architecture.md#package-boundaries), especially
the closed numerical-engine dependency list.

## Commits, PRs and issues

Use one-line Conventional Commits without scopes, bodies or trailers:
`feat: add streaming endpoint`, `fix: handle empty input`, `docs: consolidate documentation`.

Follow the [PR template](../.github/pull_request_template.md), placing
`Closes #NN` on its own line when the PR closes an issue. Describe the final
behavior and relevant validation concisely. Issues follow the
[issue template](../.github/issue_template.md); link code and PRs instead of
pasting long excerpts.

## Documentation

System guides live under `docs/` and are indexed by its [README](README.md).
`solver/` owns search-state and scheduling contracts; `lp/` owns numerical
authority, certification and dependency integration. `testing/` owns fixture
provenance, and `evidence/` contains short summaries supporting current decisions.
Root guides provide development, architecture and frontend entry points.
Benchmark usage, records, independent source checks and diagnostics live in
[klause-bench/README.md](../klause-bench/README.md). Other module and artifact
READMEs are short entry points. Public API details belong
in KDoc. Keep fixture identities/licenses in [provenance](testing/fixtures.md)
and immutable capture manifests. Repository agent instructions and GitHub
templates retain their functional locations.

### Campaign evidence

Keep benchmark result directories local and ignored. Store temporary campaign notes,
scripts, manifests, raw results and reports in `klause-evidence/campaigns/<name>/`,
outside the repository alongside its primary checkout. All worktrees share this
evidence directory. `klause-bench` retains the active harness, current fixtures/sets
and reusable tools.

Do not commit generated lab/campaign archives, raw records, payloads, manifests or
campaign-only scripts anywhere in the repository, including compressed or encoded
bundles. `docs/evidence/` may contain short Markdown summaries supporting current
design decisions, never lab archives.

Keep `.github` limited to the build/release workflows and issue/PR templates. Do
not add campaign, lab or benchmark-evidence workflows, dispatch inputs, jobs or
artifact uploads to existing workflows, or companion guides for campaign CI
dispatch. Run measurement checking outside the repository's GitHub workflows.
Reusable local analysis tools and regression fixtures belong in `klause-bench`.

Consolidate conclusions that establish architecture or invariants into the relevant
`docs/` page. Use `docs/evidence/` only for small, durable evidence supporting a
current design decision; temporary campaigns and raw-result archives stay in
`klause-evidence`.

Retain campaign bundles only while they support active work. After promoting useful
conclusions, tooling or regression fixtures, delete obsolete evidence rather than
copying historical archives into the shared directory.

### Maintaining documentation

Read the relevant architecture and contract pages before changing a subsystem.
Maintain them in the same change as code affecting ownership, dependencies,
invariants, result/proof semantics, resource policy or developer workflows.
Architecture rules constrain implementation; document deliberate changes together
with their callers and validation. Keep standalone system documentation under
`docs/` and benchmark workflows in `klause-bench/README.md`.
Extend the page that owns a contract and link to it from related guides;
split a page only when it combines distinct responsibilities. Remove competing
descriptions and superseded files.

Document implemented behavior and its limits. Verify claims against the current
source rather than importing old plans, removed APIs, historical benchmark
outcomes or comparisons into system guides. Update links and remove the
superseded document as part of consolidation.
