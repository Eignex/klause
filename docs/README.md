# Klause internals

This book explains how the Klause Kotlin code works, from the shape of the system
down to the contracts each component keeps. Each chapter relies only on the ones
before it, so read it front to back the first time and use the links between
chapters afterwards. User documentation lives at
[eignex.com/docs/klause](https://eignex.com/docs/klause/).

## Part I — Overview

1. [Introduction](01-introduction.md): result guarantees, execution stages and package boundaries.

## Part II — Models and sources

2. [Model representation and ownership](02-model.md): declarations, source relations and engine projections.
3. [Input semantics and the schema DSL](03-input-semantics.md): what every frontend preserves, route selection and the Kotlin DSL.
4. [Finite formats and FlatZinc](04-finite-formats.md): DIMACS, WCNF, OPB/WBO, XCSP3 and FlatZinc.
5. [MPS and SMT-LIB](05-arithmetic-formats.md): decimal sources, tolerances and SMT-LIB logics.

## Part III — Solving

6. [Sessions and resources](06-sessions.md): caller scopes, assumptions and work allowances.
7. [Presolve preparation](07-presolve.md): preservation guarantees, reconstruction and private worker ownership.
8. [Presolve reductions and validation](08-presolve-reductions.md): selected reductions, source routing and witness checks.
9. [Search and propagation](09-search.md): traversal, branching and finite propagation.
10. [Learning](10-learning.md): explanations, conflict analysis and learned-clause retention.
11. [Search policies and completion](11-search-policies.md): restarts, inprocessing, theory checks and primal heuristics.
12. [Local search](12-local-search.md): strategies, moves, preparation and definitions.
13. [Neighborhoods and restarts](13-neighborhoods.md): scoring, global neighborhoods, completion and restarts.
14. [Portfolios](14-portfolio.md): invocations, admission, witnesses and shared channels.
15. [Portfolio scheduling and workers](15-portfolio-scheduling.md): slice allocation, preparation revisits, numerical companions and worker recipes.
16. [Original-source derivations](16-evidence.md): typed claims checked against the original source.

## Part IV — Theories

17. [Theory services and difference logic](17-theories.md): component roles and difference logic.
18. [Linear arithmetic](18-linear-arithmetic.md): real, integer and mixed linear theories.
19. [Nonlinear arithmetic](19-nonlinear-arithmetic.md): nonlinear integer, polynomial real and transcendental theories.

## Part V — Numerical mechanisms

20. [Linear programming](20-linear-programming.md): source rows, consumers, relaxation assembly and retained bounds.
21. [Simplex and LP certification](21-lp-engine.md): floating bases, exact certificates and numerical authority.
22. [Exact arithmetic](22-exact-arithmetic.md): exact kernels and numerical dependencies.

## Part VI — Library and command line

23. [Library API](23-api.md)
24. [Command line](24-cli.md)
