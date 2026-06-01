# Bench corpus provenance

Problems tracked in source, grouped by format. Everything here is either self-authored
(license: internal) or a small curated sample of a permissively-licensed public benchmark.
Larger / non-redistributable collections are **not** vendored — they are fetched on demand
into `build/corpus-cache/` by `source.CorpusFetcher` and declared in
`catalog/Suites.kt → ExternalCollections` with their license and the reason they aren't
copied here. Run `./gradlew :klause-bench:listCorpus` to see all suites + external
collections with cache status.

| Directory   | Format          | Provenance / license |
|-------------|-----------------|----------------------|
| `dimacs/`   | DIMACS CNF      | SATLIB-style random 3-SAT + a pigeonhole instance (public benchmarks). |
| `opb/`      | Pseudo-Boolean  | Self-authored (internal). |
| `schema/`   | klause JSON     | Self-authored (internal); regenerate via `:klause-bench:dumpSchema`. |
| `flatzinc/` | FlatZinc        | Self-authored (internal). |
| `smtlib/`   | SMT-LIB QF_LIA  | Self-authored (internal). |
| `xcsp3/`    | XCSP3           | Self-authored (internal). |

MiniZinc smoke models are **not** copied here — they live at `klause-mzn-lib/test-models/`
and are referenced by the `mzn-smoke` suite (they are also owned by klause-mzn-lib's own
docs and tests).
