# Exact quadratic certificates

The quadratic kernel checks rational evidence for bounds, attainment, approach
paths and improving rays. It is a numerical mechanism, independent of source
models and search policy. A checked kernel certificate still needs a source-aware
adapter before it can justify a published solver result.

This page defines those primitive checks. [Model representation](../model.md#quadratic-objective-representation)
owns exact source terms; [current coverage](../formats.md#solving-coverage) distinguishes
these primitives from an exposed quadratic optimization route.

## Model and source boundary

`arithmetic.quadratic` owns rational quadratic models and exact certificate checks
in the existing `klause` module. It reads only `simplex.exact` and neutral `util`
controls, independently of factors, source lowering and search. It preserves the
closed `lp.engine` dependency list. Its model denotes
`constant + linear^T x + x^T Hessian x / 2` with an exactly symmetric Hessian.
The source [objective representation](../model.md#quadratic-objective-representation)
retains literal cross coefficients; callers own Hessian conversion, source
mappings, objective direction and invocation assumptions.

## Candidate generation

`QuadraticCandidates` uses floating penalty-gradient iterations only to propose
active rows. Nonfinite arithmetic declines the proposal. Selected rows and all
source equality rows form an exact rational KKT system; rank-revealing elimination
rejects inconsistent systems and chooses zero free coordinates in consistent
singular systems. The reconstructed anchor and multipliers still require the
independent certificate checks below. A numerical active-set selection or a
rational stationary candidate alone supplies no bound or optimum. Both proposal
and reconstruction retain the containing numerical control; their work is not
refunded when a candidate fails certification.

## Certificate checks

Exact Schur elimination checks positive semidefiniteness, including singular
matrices: a zero diagonal with a nonzero remaining row rejects convexity. A bound
certificate supplies a rational anchor and row multipliers. Inequality multipliers
are nonnegative; equality multipliers may have either sign. Exact Lagrangian
stationarity and positive semidefiniteness establish a global lower bound even
when the anchor is infeasible. Feasibility and equality of the objective with the
bound additionally establish an attained optimum.

A strict-boundary anchor alone does not establish nonattainment. An infimum
certificate requires a feasible endpoint and a closure-feasible anchor defining
a feasible straight approach path, objective convergence to the bound, and either
positive strict-row dual weight or a positive-definite Hessian excluding another
feasible minimizer. Other nonattainment cases decline certification. Unboundedness
requires an exactly feasible base and a recession direction preserving every row,
with negative quadratic curvature or zero curvature and negative objective slope.
This check also admits a valid negative-curvature ray in a nonconvex model;
nonconvex stationarity cannot certify a global bound.

## State and resources

Each check polls cancellation and charges its containing native control. Work and
matrix limits interrupt without evidence. Certificates retain their captured
model; numerical evidence cannot establish a source result until its adapter
checks source semantics and scope. The [quadratic source pipeline](../theories/polynomial-real.md#quadratic-strategy-execution)
uses these mechanisms for admitted subproblems and owns optimization traversal,
source reconstruction and result publication.
