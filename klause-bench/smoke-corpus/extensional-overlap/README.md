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

`cyclic-mod3-unsat` uses two ternary sum-zero-modulo-three relations sharing their
first two columns, and disequality over their last columns. Every pair retains
all rows and singleton GAC retains support; the three-relation join is empty.
`cyclic-mod3-sat` adds the last-column pair `(0,0)` and requires the first two
coordinates to differ. Its two solutions are `(1,2,0,0)` and `(2,1,0,0)`;
the latter minimizes the stated weighted objective to 4. Both sources protect
all four objective coordinates with coefficients 1, 2, 4 and 8.

| Source | SHA-256 |
|---|---|
| cyclic-mod3-unsat.xml | 98ecff0e5242b99830d2086795edf5a670e1d34867494c1485f2b2c3dc4373d5 |
| cyclic-mod3-sat.xml | b96cabed47e552377e5a7481ecf236d283a0082546a8e515015e0620de04d377 |
