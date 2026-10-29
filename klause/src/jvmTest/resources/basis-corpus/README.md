# Real LP basis corpus

This directory holds the version-1 `.kbtrace` corpus. Its [manifest](manifest.json)
fixes source repository paths and revisions, redistribution licenses, source and
trace SHA-256, dimensions, operation counts, capture limits and exclusions.
The six traces total 219,993 bytes. Preserve that manifest with the traces.

| Trace | Origin and license | Capture route |
|---|---|---|
| `mps-afiro`, `mps-adlittle` | Public MPS test inputs; exact upstream paths/revision in manifest; MIT | Production CP/MIP relaxation |
| `mzn-graph-coloring` | Repository `klause-mzn-lib/test-models/klause-test-graph_coloring.mzn` | Production CP/MIP relaxation |
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
See [capture commands and checks](../../../../../klause-bench/README.md#lp-capture-and-replay).
