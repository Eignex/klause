# Bench corpus provenance

Problems tracked in source, grouped by format. Everything here is either
self-authored (license: internal) or a small curated sample of a
permissively-licensed public benchmark. Larger or non-redistributable
collections are not vendored — they are fetched on demand into
`build/corpus-cache/` by `source.CorpusFetcher` and declared in
`catalog/Suites.kt → ExternalCollections` with their license and the reason they
aren't copied here. Run `./gradlew :klause-bench:bench --args="list"` to see all
catalog suites, including the discovered MiniZinc corpora.

Vendored directories and their provenance:

- `dimacs/` (DIMACS CNF): SATLIB-style random 3-SAT plus a pigeonhole instance,
  from public benchmarks.
- `opb/` (Pseudo-Boolean): self-authored, internal.
- `schema/` (klause JSON): self-authored, internal; regenerate via
  `:klause-bench:dumpSchema`.
- `flatzinc/` (FlatZinc): self-authored, internal.
- `smtlib/` (SMT-LIB QF_LIA): self-authored, internal.
- `xcsp3/` (XCSP3): self-authored, internal.

MiniZinc smoke models are not copied here. They live at
`klause-mzn-lib/test-models/` and are referenced by the `mzn-smoke` suite; they
are also owned by klause-mzn-lib's own docs and tests.
