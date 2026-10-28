# Boolean residue controls

These self-authored Apache-2.0 sources preserve integer-only Boolean consumption
controls, with source hashes and mathematical expectations in the manifest.
Squares modulo four are zero or one, so both alternatives of `covered-unsat`
are impossible over integers although each has real solutions. Source UNSAT needs
both alternatives excluded. `negated-support-sat` has witness `(x,y)=(0,0)`;
modular support for the equality does not prove it true under negation.

The frozen residue suite supplies `guarded-sat`, whose witness is `(1,1)` after
excluding the impossible equality, plus real-supported and inconclusive controls.
Compare identical off/on budgets and retain native deductions and original-source
witness checks separately. These synthetic controls establish no default-policy
or corpus throughput benefit, and native UNSAT supplies no exported certificate.
