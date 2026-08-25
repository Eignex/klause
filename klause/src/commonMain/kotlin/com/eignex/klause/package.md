# Package com.eignex.klause

`backtrack` contains finite CP search policy and its DFS implementation. `propagation` contains the
trailed finite-domain state and explainable propagators. `lp` contains the floating relaxation and its
finite-CP bounding integration.

`arithmetic.difference` contains the shared difference-constraint representation and graph algorithms.
`simplex.exact` contains exact rational simplex primitives. `theory` contains complete open-model
decision routes, and `solver.pipeline` chooses one before a frontend enters the finite CP path.

`ProblemSpec.componentPlan()` chooses one immutable ownership plan before search. `Problem` stores typed
integer columns: `FiniteIntColumns` carry CP domains and `IntColumn.Bounded` remains theory-owned. The
plan builds a compact remapped CP projection plus a theory fragment, so no theory variable is materialized
as a CP domain.

`solver.search` owns the shared component contract, decision levels, branch traversal, retraction,
clause-form learning, complete checks, restart lifecycle, decision budgets, and model assembly. A component may choose private residual state
through `SearchBrancher`, but returns only shared decisions; `PropagationSession` therefore remains CP
state rather than a second search driver. Theories exchange Boolean literals and semantic integer bounds
through the protocol, but never receive a CP domain object. Typed opaque theory decisions also carry
arbitrary-precision and future EUF, array, and quantified-lemma splits through the same trail without
teaching the session their native representations.

Complete models use source-keyed values (`SearchBoolValue`, `SearchIntValue`, and `SearchRealValue`),
so each component contributes only values it owns. CP-learned Boolean clauses are retained by the shared
session with CP as their native owner: their consequences reach theory peers without a duplicate CP pin.
