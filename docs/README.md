# Internal developer documentation

Klause uses one source model across finite search, local search and arithmetic
services. These docs explain that system from the overall solving flow down to
component contracts and diagnostic tools. User-facing documentation is maintained
separately at [eignex.com/docs/klause](https://eignex.com/docs/klause/).

## Start here

1. Read [system architecture](architecture.md) for the source-to-result flow and
   the responsibilities of each layer.
2. Read [solver vision](vision.md) for the intended model breadth, paired portfolios
   and independent proof verification, with [active planned work](vision.md#active-work-toward-the-vision)
   tracked separately from [current coverage](formats.md#solving-coverage).
3. Use [development](development.md) to build and contribute, or follow the
   subsystem links below for the contract you are changing.

## Models and inputs

| Page | Purpose |
|---|---|
| [Model representation and ownership](model.md) | Source declarations, engine views, immutable results and configuration capture |
| [Library API](api.md) | Supported entry points, extension contracts, mappings and public value ownership |
| [Input semantics](formats.md) | Shared routing, current coverage and a directory of source-format contracts |
| [CLI](cli.md) | Build and run, format dispatch, engine selection and control flags |
| [MiniZinc integration](minizinc.md) | Solver registration, wrappers and native predicate coverage |

## Solving lifecycle

| Stage | Contract |
|---|---|
| Caller scope | [Sessions and streams](solver/sessions.md): assumptions, active operations and retained state |
| Preparation | [Presolve](solver/presolve.md): sound reductions, reconstruction and phase accounting |
| Complete traversal | [Search and propagation](solver/search.md): decisions, finite fixpoints, theory checks and explanations |
| Heuristic search | [Local search](solver/local-search.md): assignments, definitions, moves, scoring and restarts |
| Combined execution | [Portfolios](solver/portfolio.md): result admission, scheduling, shared incumbents and worker lifetimes |
| Evidence | [Original-source derivations](solver/evidence.md): typed claims, premise checking, imports and branch coverage |
| Interruption | [Resource allowances](solver/resources.md): scope lifetimes, work currencies and parallel quotas |

## Theory contracts

[Theory services](theories.md) defines the shared composition and result contract.
The theories below are peers, with the same overview structure: scope, solving and
composition, result authority, state/resources and implementation.

| Theory | Contract |
|---|---|
| [Difference logic](theories/difference.md) | Integer and real difference constraints, graph checks and explanations |
| [Linear integer arithmetic](theories/linear-integer.md) | Exact integer constraints, open bounds and integer branching |
| [Linear real arithmetic](theories/linear-real.md) | Rational feasibility, Boolean structure and strict inequalities |
| [Linear mixed arithmetic](theories/linear-mixed.md) | Coupled integer/real constraints, integrality and reconstruction |
| [Nonlinear integer arithmetic](theories/nonlinear-integer.md) | Exact integer candidate search, real completion and source witness validation |
| [Polynomial real arithmetic](theories/polynomial-real.md) | Exact nonlinear completion, finite composition and algebraic witnesses |

## Numerical mechanisms

These mechanisms supply the theory and search layers. Their implementation detail
does not change the relative place of each theory in the model or support contract.

| Page | Purpose |
|---|---|
| [LP architecture](lp/architecture.md) | Linear model authority, numerical engine boundaries, basis state and consumers |
| [LP certification](lp/certification.md) | Exact checks for witnesses, bounds, conflicts and rays |
| [Exact quadratic certificates](arithmetic/quadratic.md) | Rational quadratic bounds, attainment, approach paths and rays |
| [Exact polynomial arithmetic](arithmetic/polynomial.md) | Polynomial operations, algebraic scalars, root isolation and arithmetic limits |
| [Certified transcendental enclosures](arithmetic/transcendental.md) | Rational enclosures and affine relaxations; source/theory integration remains planned |
| [Certified transcendental enclosures](arithmetic/transcendental.md) | Rational function enclosures, range reduction and affine relaxations |
| [Numerical dependencies](lp/dependencies.md) | Kernel provenance, integration contracts and runtime support |

## Validation and diagnostics

| Page | Purpose |
|---|---|
| [Development](development.md#validation) | Local checks, CI responsibilities and test conventions |
| [Benchmark README](../klause-bench/README.md) | Harness, corpus sets, source validation and measurement workflows |
| [LP capture and replay](lp/replay.md) | Basis traces and retained/fresh consumer diagnostics |
| [Fixture provenance](testing/fixtures.md) | Input identities, hashes, licenses and capture exclusions |
| [Portfolio slice evidence](evidence/slice-policy.md) | Evidence supporting scheduling defaults |
| [Numerical kernel evidence](evidence/numerical-kernels.md) | Evidence supporting arithmetic implementation choices |
| [State ownership evidence](evidence/coordinator-ownership.md) | Evidence supporting concrete lifecycle owners |

## Maintaining this documentation

Give each page a short explanation of its role before its detailed contract. Keep
architecture, intended requirements, source semantics and mechanism details in
their respective owners; link across boundaries instead of repeating them.
Update the affected contracts whenever implementation changes those guarantees.

[Development rules](development.md#documentation) define placement and maintenance.
Standalone system documentation lives under `docs/`; benchmark workflows live in
`klause-bench/README.md`. Preserve fixture attribution and keep generated measurement
archives outside the repository. Commands run from the repository root unless
stated otherwise.
