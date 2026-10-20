# Klause internals

This book explains how Klause works, from the shape of the system down to the
contracts each component keeps. Read it front to back the first time: every
chapter relies only on the ones before it. Later, use it as a reference and follow
the links between chapters. User-facing documentation is maintained separately at
[eignex.com/docs/klause](https://eignex.com/docs/klause/).

Klause uses one source model across finite search, local search and arithmetic
services. Part I gives the overall picture. Part II describes the model and the
input formats that produce it. Part III follows a solve through its stages, Part
IV covers the arithmetic theories search consults, and Part V the numerical
kernels underneath them. Part VI describes the public surfaces built on all of
this, and Part VII how the code base is built and tested.

## Part I — Overview

1. [Introduction](01-introduction.md): what Klause sets out to do and which
   guarantees every route shares.
2. [System architecture](02-architecture.md): the flow from source to result,
   the layers and the package boundaries.

## Part II — Models and sources

3. [Model representation and ownership](03-model.md): source declarations,
   factors, engine views and immutable results.
   - [3.1 Source relations](03.1-source-relations.md)
   - [3.2 Engine projections](03.2-engine-projections.md)
4. [Input semantics](04-input-semantics.md): what every frontend preserves,
   how routes are selected and the current solving coverage.
5. [Schema lowering](05-schema.md): the Kotlin DSL frontend.
6. [Finite input formats](06-finite-formats.md): DIMACS, WCNF, OPB/WBO and XCSP3.
7. [FlatZinc](07-flatzinc.md): FlatZinc semantics, lowering and output rendering.
8. [MPS source semantics](08-mps.md): decimal sources, tolerances and claims.
9. [SMT-LIB source semantics](09-smtlib.md): logics, lowering and model output.

## Part III — Solving

10. [Sessions and streams](10-sessions.md): caller scopes, assumptions and
    active operations.
11. [Resource allowances](11-resources.md): deadlines, work currencies and how
    scopes share them.
12. [Presolve and reconstruction](12-presolve.md): sound reductions and mapping
    results back to the source.
13. [Source witness validation](13-validation.md): checking a proposed
    assignment against the source.
14. [Search and propagation](14-search.md): complete traversal, finite
    propagation, theory checks and learning.
    - [14.1 Traversal and branching](14.1-traversal.md)
    - [14.2 Finite propagation](14.2-propagation.md)
    - [14.3 Explanations and conflict learning](14.3-learning.md)
    - [14.4 Theory completion](14.4-theory-completion.md)
    - [14.5 Primal heuristics and statistics](14.5-primal-and-statistics.md)
15. [Local search](15-local-search.md): incremental scores, moves and restarts.
    - [15.1 Strategies and moves](15.1-strategies.md)
    - [15.2 Preparation and definitions](15.2-preparation.md)
    - [15.3 Scoring and neighborhoods](15.3-scoring.md)
    - [15.4 Completion, restarts and ownership](15.4-lifetime.md)
16. [Portfolios](16-portfolio.md): running workers together and admitting their
    results.
    - [16.1 Witnesses and proof scope](16.1-portfolio-witnesses.md)
    - [16.2 Scheduling](16.2-portfolio-scheduling.md)
    - [16.3 Worker recipes](16.3-portfolio-recipes.md)
17. [Original-source derivations](17-evidence.md): typed claims checked against
    the original source.

## Part IV — Theories

18. [Theory services](18-theories.md): the contract every theory shares.
19. [Difference logic](19-difference.md)
20. [Linear real arithmetic](20-linear-real.md)
21. [Linear integer arithmetic](21-linear-integer.md)
22. [Linear mixed arithmetic](22-linear-mixed.md)
23. [Nonlinear integer arithmetic](23-nonlinear-integer.md)
24. [Polynomial real arithmetic](24-polynomial-real.md)
25. [Transcendental real arithmetic](25-transcendental.md)

## Part V — Numerical mechanisms

26. [LP architecture](26-lp-architecture.md): the simplex engine, its state and
    its consumers.
27. [LP certification](27-lp-certification.md): exact checks for witnesses,
    bounds, conflicts and rays.
28. [Exact arithmetic kernels](28-exact-arithmetic.md): quadratic certificates,
    polynomials, transcendental enclosures and analytic reals.
29. [Numerical dependencies](29-numerical-dependencies.md): external kernels and
    their integration contracts.

## Part VI — Using and extending Klause

30. [Library API](30-api.md): entry points, extension roles and value ownership.
31. [Command-line interface](31-cli.md): building, dispatch and control flags.

## Part VII — Working on the code

32. [Development](32-development.md): modules, building, tests and source style.

Commands run from the repository root unless stated otherwise.
