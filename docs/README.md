# Klause internals

This book explains how the Klause Kotlin code works, from the shape of the system
down to the contracts each component keeps. Each chapter relies only on the ones
before it, so read it front to back the first time and use the links between
chapters afterwards. User documentation lives at
[eignex.com/docs/klause](https://eignex.com/docs/klause/).

## Part I — Overview

1. [Introduction](01-introduction.md): goals, shared guarantees and the system
   architecture.

## Part II — Models and sources

2. [Model representation and ownership](02-model.md): declarations, source
   relations and engine projections.
3. [Input semantics and the schema DSL](03-input-semantics.md): what every frontend
   preserves, route selection and the Kotlin DSL.
4. [Finite formats and FlatZinc](04-finite-formats.md): DIMACS, WCNF, OPB/WBO,
   XCSP3 and FlatZinc.
5. [MPS and SMT-LIB](05-arithmetic-formats.md): decimal sources, tolerances and
   SMT-LIB logics.

## Part III — Solving

6. [Sessions and resources](06-sessions.md): caller scopes, assumptions and work
   allowances.
7. [Presolve and validation](07-presolve.md): sound reductions and checking
   witnesses against the source.
8. [Search and propagation](08-search.md): traversal, branching and finite
   propagation.
9. [Learning and theory completion](09-learning.md): explanations, conflict
   learning, theory checks and primal heuristics.
10. [Local search](10-local-search.md): strategies, moves, preparation and
    definitions.
11. [Neighborhoods and restarts](11-neighborhoods.md): scoring, global
    neighborhoods, completion and restarts.
12. [Portfolios](12-portfolio.md): witnesses, scheduling and worker recipes.
13. [Original-source derivations](13-evidence.md): typed claims checked against
    the original source.

## Part IV — Theories

14. [Theory services and difference logic](14-theories.md)
15. [Linear arithmetic](15-linear-arithmetic.md): real, integer and mixed.
16. [Nonlinear arithmetic](16-nonlinear-arithmetic.md): nonlinear integer,
    polynomial real and transcendental.

## Part V — Numerical mechanisms

17. [Linear programming](17-linear-programming.md): the simplex engine and exact
    certification.
18. [Exact arithmetic](18-exact-arithmetic.md): exact kernels and numerical
    dependencies.

## Part VI — Using and building Klause

19. [Library API](19-api.md)
20. [Command line and development](20-cli.md)
