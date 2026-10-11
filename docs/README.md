# Internal developer documentation

These pages define Klause's implemented architecture, ownership, invariants and
developer workflows. Read [system architecture](architecture.md) first, then the
contract for the subsystem you are changing. Commands run from the repository root
unless a page states otherwise.

User-facing documentation is maintained separately at
[eignex.com/docs/klause](https://eignex.com/docs/klause/).

## Development and frontends

| Page | Owns |
|---|---|
| [Development](development.md) | Build smoke commands, Kotlin entry points, tests, validation and contribution rules |
| [CLI](cli.md) | Runtime setup, format dispatch, engine selection, flags and environment defaults |
| [MiniZinc](minizinc.md) | Solver registration, wrappers and native predicate coverage |
| [Input semantics](formats.md) | Source lowering, finite/open routing and frontend result guarantees |

## Solver contracts

| Page | Owns |
|---|---|
| [System architecture](architecture.md) | Modules, dependency boundaries and source-model ownership |
| [Library API](api.md) | Supported surfaces, migration, typed mappings and snapshot/buffer ownership |
| [Sessions and streams](solver/sessions.md) | Assumptions, conditioned queries, cursor ownership and retained caller state |
| [Search and propagation](solver/search.md) | Shared traversal, finite fixpoints, explanations, learned clauses, open real feasibility and clausal primal preparation |
| [Presolve](solver/presolve.md) | Transformation guarantees, source mappings, cancellation and phase accounting |
| [Local search](solver/local-search.md) | Projections, resumable state, initialization, definitions, moves and scoring |
| [Resource allowances](solver/resources.md) | Scope composition, units, sentinels, overspend and parallel quotas |
| [Portfolios](solver/portfolio.md) | Witness/proof admission, slice scheduling, arm selection, reseeding and failures |
| [LP architecture](lp/architecture.md) | Exact model authority, bound trails, basis owners, consumers and cuts |
| [LP certification](lp/certification.md) | Bounds, witnesses, conflicts, rays and resource limits |
| [Numerical dependencies](lp/dependencies.md) | Koblas/Kumulant pins, kernel contracts and runtime support |

## Measurement and fixtures

| Page | Owns |
|---|---|
| [Benchmark README](../klause-bench/README.md) | Harness and Klause-lab usage, records, provenance, source validation, comparisons and profiling |
| [LP capture and replay](lp/replay.md) | Basis traces and retained/fresh consumer diagnostics |
| [Fixture provenance](testing/fixtures.md) | Committed input identities, hashes, licenses and capture exclusions |
| [Portfolio slice evidence](evidence/slice-policy.md) | Bounded selection and validation supporting the common slice defaults |
| [Numerical kernel evidence](evidence/numerical-kernels.md) | Bounded evidence for current dense-basis, rational and polling decisions |

Keep one authoritative page for each contract; link to it from related workflows.
Update the affected contract in the same change as ownership, dependency,
soundness, lifecycle or resource-policy changes. The LP engine's closed dependency
list is a hard boundary.

[Development rules](development.md#documentation) define documentation and evidence
placement. System guides live here; benchmark workflows live in
`klause-bench/README.md`. Other READMEs are short entry points.
Fixture inputs and reusable tools stay with their consumers. Generated measurements
belong outside the repository. Remove superseded pages and repair their links when
consolidating; document the current system and preserve fixture attribution.
