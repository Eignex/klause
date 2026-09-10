# Real LP basis corpus provenance

These version-1 `.kbtrace` files are portable observations of basis-owner matrices and operation streams,
not serialized backend factors. `manifest.json` fixes source and artifact hashes, dimensions, operation counts,
limits and exclusions. The total committed payload is 219,956 bytes.

`mps-afiro` and `mps-adlittle` come from the HiGHS repository at
`73cac48c5340d775a477087198611862559be250`. HiGHS distributes these test instances under its MIT license.
Klause parsed the source MPS and built the same CP/MIP relaxation used by its production frontend. The source
files are not copied here; a local HiGHS checkout at that revision regenerates the traces with the documented
capture command.

`mzn-graph-coloring` is compiled from this repository's own
`klause-mzn-lib/test-models/graph_coloring.mzn`. `mzn-timetabling` is the existing OR-Tools FlatZinc example
`examples/flatzinc/timetabling.fzn` at `98c165af62df62b3056c2ee0fca66b24e79097cb`, distributed under
Apache-2.0. Both are parsed by klause and assembled through the production CP/MIP relaxation path. Sudoku was
attempted but has no LP rows under current relaxation policy; that decline remains in the manifest.

`smt-lia-wide-span` and `smt-lia-unsat` are self-authored source files already committed under
`klause-bench/smoke-corpus/smtlib/`. Their traces are deliberately labelled `SMT_SOURCE_DERIVED`: klause's
SMT frontend and exact QF_LRA asserted-state builder provide the authoritative linear model, which is projected
into the float engine only for this comparison harness. The production SMT theory does not use these float
factors. `lra-rational.smt2` is excluded because choosing a side of its Boolean disjunction would invent branch
state rather than capture it.

The initial captures used the isolated comparison artifacts retained for basis work: koblas source revision
`d554656e3715bf1a332c13caadfa4dee641f3a5d`, JVM artifact SHA-256
`dfe920d31c36229309ea172d0773e1ccf7b593615efe353ed3cc509875503d79`, and HFactor artifact SHA-256
`1576fb462255c30d3dc0b1758d62fd8b1e35d8851e3e430c789ca61c981d807e`. Gradle resolves both as timestamped
snapshot `20260909.114713-1` of version `0.1.1-b3-d554656e-SNAPSHOT`. They were consumed through the existing
isolated repository; no shared snapshot was installed or replaced. Every benchmark result also emits the
actually resolved artifact hashes when the runtime exposes jar locations.
