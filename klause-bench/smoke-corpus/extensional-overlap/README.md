# Extensional overlap controls

Self-authored XCSP3 sources by Eignex Klause contributors, Apache-2.0.
The `xcsp3-core` suite retains these identities for bounded joint-relation controls.

`overlap-tables-unsat` intersects diagonal and off-diagonal binary tables.
`overlap-mdd-unsat` expresses the off-diagonal relation as an MDD. Separate GAC
retains both values in both cases, while exact shared-tuple consistency refutes
them. `overlap-mdd-sat` restricts the first and last symbols of an independent
three-layer MDD to equality, retaining both values and an unrestricted middle
symbol. It exercises reordered shared positions and reconverging states.

Expected verdicts describe the source mathematics. Independent lab witness checks
and native solver refutations remain separate evidence.
