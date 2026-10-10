# Fixture provenance

Fixtures remain beside the tests and measurement tools that consume them. Their
source identities, licenses and hashes describe those committed bytes, rather
than the current solver's performance.

## Basis corpus

The version-1 `.kbtrace` corpus lives under
[basis-corpus](../../klause/src/jvmTest/resources/basis-corpus/).
Its [manifest](../../klause/src/jvmTest/resources/basis-corpus/manifest.json)
fixes source repository paths and revisions, redistribution licenses, source and
trace SHA-256, dimensions, operation counts, capture limits and exclusions.
The six traces total 219,993 bytes. Preserve that manifest with the traces.

| Trace | Origin and license | Capture route |
|---|---|---|
| `mps-afiro`, `mps-adlittle` | Public MPS test inputs; exact upstream paths/revision in manifest; MIT | Production CP/MIP relaxation |
| `mzn-graph-coloring` | Repository `klause-mzn-lib/test-models/graph_coloring.mzn` | Production CP/MIP relaxation |
| `mzn-timetabling` | Public FlatZinc example; exact upstream path/revision in manifest; Apache-2.0 | Production CP/MIP relaxation |
| `smt-lia-wide-span`, `smt-lia-unsat` | Self-authored `klause-bench/smoke-corpus/smtlib/` inputs | `SMT_SOURCE_DERIVED` |

Traces store matrix/RHS IEEE bits, headings and observed operations, rather than
serialized factors or native pointers. Public source MPS/FlatZinc inputs are not
copied into the corpus. Regeneration requires the upstream revision and exact
source bytes identified by the manifest. Replay uses the current basis backend;
capture identity is not a current dependency requirement.

SMT source-derived traces project the asserted exact linear state into a floating
engine for the harness. They are not complete production theory event captures.
The manifest records the Sudoku no-LP decline and rational-LRA disjunction
exclusion; inventing a Boolean branch would misrepresent capture state.
See [capture commands and checks](../lp/replay.md).

## Smoke corpus

[smoke-corpus](../../klause-bench/smoke-corpus/) contains small parser and solve
fixtures grouped by format. Larger or non-redistributable collections are fetched
through the catalog, with their license and exclusion policy in the collection
declaration. See [corpus management](../benchmarking.md#catalog-and-corpus).

| Directory | Contents and origin |
|---|---|
| `dimacs` | SATLIB-style small random 3-SAT samples; self-authored pigeonhole, implication-chain, bipartite-colouring and weighted MaxSAT cases |
| `opb` | Self-authored set-cover/cardinality; academic PB/MaxSAT competition samples `sporttournament06.opb` and `queens4-soft.wbo` |
| `schema` | Self-authored campaign and roster schemas; regenerate campaign with `:klause-bench:dumpSchema` |
| `smtlib` | Self-authored linear integer/real satisfaction, optimization, infeasibility, disjunction and exact-rational cases |
| `xcsp3` | Self-authored magic-series, sum optimization, magic-square and graph-colouring cases |
| `mps` | Self-authored small integer optimization, equality feasibility, continuous-column and infeasible models |
| `lp-component-split` | Self-authored dense bounded block systems for the explicit measurement suite |

MiniZinc smoke models live under
[klause-mzn-lib/test-models](../../klause-mzn-lib/test-models/) and are referenced
by the `mzn-smoke` suite. They are not duplicated in the smoke corpus.

The width-`n` component-split matrix is `nI + 11^T` with RHS `2n`, giving exact
solution `x = 1`. The linked MiniZinc control adds a redundant row joining blocks.
Regenerate using
[generate.py](../../klause-bench/scripts/lp-component-split/generate.py).
These fixtures are explicit experiments rather than CI smoke selection.

## Saved campaign artifacts

Campaign directories under `klause-bench` retain manifests, raw outputs,
provenance, specs, analyzers and source inputs. Their READMEs describe how to use
those files. Recorded results apply to their pinned revisions and semantics;
they do not establish the current checkout's solve rate, correctness or speed.
Keep raw evidence unchanged when updating system documentation.

## FlatZinc witness

[enigma_248_add_or_multiply.fzn](../../klause/src/jvmTest/resources/flatzinc/enigma_248_add_or_multiply.fzn)
is the source model compiled with MiniZinc 2.9.7 and Klause's globals library at
`58ae735e7b527a9d5e1050a0111b78f38b8590f0`. Local-path comments were removed;
all 129 constraints and 90 scalar range bounds remain. The test adds integer
pins without recompiling or simplifying the fixture.

The source is Hakan Kjellerstrand's model, revision
`cfdfb67f9a22836ab6b9de0ee3940c6947742bec`, path
`minizinc/enigma_248_add_or_multiply/enigma_248_add_or_multiply.mzn`.
Source SHA-256 is
`30663fd9b31d5247bee948ab34ded177684300c9b993dcf0c3120fa11b766484`;
fixture SHA-256 is
`fad0d2404c876709934af756c4b83edc1d0f5ea97ce5d9b5f4f743fa4c30f27f`.

The witness in half-pennies is `(2,4,6)`, `(2,3,10)`, `(1,8,9)`, `(1,6,14)`,
`(1,5,24)`. Tests check returned values against every original predicate and
bound with direct arithmetic; the dyadic values are exactly representable in
binary64. The MIT copyright/permission notice remains in the fixture
[README](../../klause/src/jvmTest/resources/flatzinc/README.md).
