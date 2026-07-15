# Smoke-corpus provenance

This is the **smoke corpus**: a small set of tiny, fast instances tracked in
source, grouped by format. Every instance is deliberately small — enough to
exercise a parser and cross-check solvers, not to stress them. Everything here
is either self-authored (license: internal) or a small curated sample of a
permissively-licensed public benchmark. Larger or non-redistributable
collections are not vendored — they are fetched on demand into
`build/corpus-cache/` by `source.CorpusFetcher` and declared in
`catalog/Suites.kt → ExternalCollections` with their license and the reason they
aren't copied here. Run `./gradlew :klause-bench:bench --args="list"` to see all
catalog suites, including the discovered MiniZinc corpora.

Vendored directories and their provenance:

- `dimacs/` (DIMACS CNF + WCNF): SATLIB-style random 3-SAT, plus self-authored
  pigeonhole (PHP(4,3)/PHP(3,2), UNSAT), an implication chain, and a bipartite
  2-colouring — small SAT/UNSAT shapes from public benchmarks / internal. Also
  `maxsat-tiny.wcnf`: a self-authored MaxSAT (weighted partial WCNF) toy, internal.
- `opb/` (Pseudo-Boolean OPB + WBO): self-authored, internal (set-cover,
  cardinality), plus two small samples of the MaxSAT/PB Competition (academic
  benchmarks): `sporttournament06.opb` (a non-linear OPB with product terms) and
  `queens4-soft.wbo` (a WBO soft-constraint instance).
- `schema/` (klause JSON): self-authored, internal (campaign, roster);
  regenerate the campaign sample via `:klause-bench:dumpSchema`.
- `smtlib/` (SMT-LIB QF_LIA): self-authored, internal — basic, optimization,
  infeasible, and a disjunction.
- `xcsp3/` (XCSP3): self-authored, internal — magic series, sum COP, 3x3 magic
  square, graph colouring.
- `mps/` (MPS / MIP): self-authored, internal — four tiny instances covering the
  MPS front-end's distinct paths: an integer optimisation (`blend-tiny`), an
  integer feasibility with an equality row (`feasible-tiny`), a bounded-float
  column that is bucketed (`float-tiny`), and an infeasible integer model
  (`infeasible-tiny`). The classic MIPLIB 3.0 set is fetched, not vendored (see
  the `miplib3` collection in `catalog/Suites.kt`).

MiniZinc smoke models are not copied here. They live at
`klause-mzn-lib/test-models/` and are referenced by the `mzn-smoke` suite; they
are also owned by klause-mzn-lib's own docs and tests.
