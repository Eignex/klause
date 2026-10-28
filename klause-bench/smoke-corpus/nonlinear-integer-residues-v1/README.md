# Integer residue controls

These ten self-authored Apache-2.0 sources retain the mathematical controls for
mandatory multivariate integer equality residue evaluation. The manifest pins
source bytes and expectations independently of the implementation. They are
synthetic correctness controls, with no corpus throughput or default-policy claim.

Squares modulo four are zero or one. Thus `squares-unsat`, `unbounded-unsat` and
`wide-unsat` have no support modulo four. Exact multiplication by two reduces
`rational-unsat` to the same square-sum obstruction. For `shared-unsat`, product
two modulo three requires opposite nonzero residues, whose sum is zero rather
than the required one. Each equation separately has modular support; identities
must be shared across atoms.

The SAT controls retain witnesses `(x,y)=(1,1)` for `squares-sat` and
`guarded-sat`, and `(2,2)` for `zero-divisors-sat`. The latter keeps nonzero factors
whose product vanishes modulo four. `real-sat` permits `(x,r)=(1,sqrt(2))` and
must decline integer residue reasoning over its real coordinate.
`support-inconclusive` has support modulo every selected ring, but has no integer
solution: divisibility of the square sum by three forces both integers to be
divisible by three, contradicting its value 21 modulo nine. Surviving support
supplies neither a witness nor a refutation.

Compare the same revision with `nia-residues=false` and `true`, keeping all other
controls equal. Retain source admission, native residue rings/tuples/work/declines,
full process time and independent original-source checks separately. The option
defaults off. The twelve frozen `nonlinear-integer-v1` sources provide a separate
existing-source applicability control; their bounded execution need not change
verdicts. Native UNSAT and mathematical expectations are not exported proof
certificates.
