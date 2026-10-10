# Development

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

The default build targets JVM and Linux x64. Releases opt into Linux arm64 and
macOS arm64 with `-Ptargets.full`. Native tests and documentation checks run in
CI. Benchmarks, capture and timing tools are opt-in; see [benchmarking](benchmarking.md).

## Tests

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
Module and artifact READMEs are short entry points. Public API details belong
in KDoc. Keep fixture identities/licenses in [provenance](testing/fixtures.md)
and immutable capture manifests. Repository agent instructions and GitHub
templates retain their functional locations.

### Campaign evidence

Keep benchmark result directories local and ignored. Store temporary campaign notes,
scripts, manifests, raw results and reports in `klause-evidence/campaigns/<name>/`,
outside the repository alongside its primary checkout. All worktrees share this
evidence directory. `klause-bench` retains the active harness, current fixtures/sets
and reusable tools.

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
with their callers and validation. Do not create standalone documentation outside
`docs/`. Remove competing descriptions and superseded files.

Document implemented behavior and its limits. Verify claims against the current
source rather than importing old plans, removed APIs, historical benchmark
outcomes or comparisons into system guides. Update links and remove the
superseded document as part of consolidation.
