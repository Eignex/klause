# Internal developer documentation

These pages define Klause's implemented architecture, ownership boundaries,
invariants and solver contracts for developers. Read the relevant contract before
changing a subsystem and update it in the same change as its implementation.
Commands run from the repository root unless a page states otherwise.

User-facing documentation is hosted at
[eignex.com/docs/klause](https://eignex.com/docs/klause/) and is maintained separately.
This tree is the internal implementation contract and developer workflow reference.

| Read | Purpose |
|---|---|
| [Architecture](architecture.md) | Modules, package boundaries and model ownership |
| [Search and propagation](search.md) | Shared search, explanations, learned clauses and portfolio slices |
| [Presolve cancellation](presolve.md) | Fresh rebuild token ownership, phase allowances and probe telemetry |
| [LP architecture](lp/architecture.md) | Model authority, bound trails, sparse basis and consumers |
| [LP certification](lp/certification.md) | Exact bounds, witnesses, conflicts, rays and resource limits |
| [Input semantics](formats.md) | Finite and open routing, float lowering and MPS result contracts |
| [Koblas integration](lp/koblas.md) | Dependency pins, numerical kernel contracts and runtime support |
| [Development](development.md) | Validation, test conventions, documentation maintenance and contribution workflow |
| [Developer setup](developer-setup.md) | Build, exercise the CLI and use the schema entry points |
| [CLI](cli.md) | Frontend registry, engine selection, flags and environment defaults |
| [MiniZinc](minizinc.md) | Solver registration, wrappers and native predicate coverage |
| [Benchmarking](benchmarking.md) | Selection, saved results, source validation, caching and profiling |
| [Paired lab records](benchmark-records.md) | Matched record analysis, timing scopes and external CI evidence |
| [LP capture and replay](lp/replay.md) | Basis traces and retained/fresh consumer diagnostics |
| [Fixture provenance](testing/fixtures.md) | Committed test and benchmark data, identities and licenses |
| [Diagnostic tools](testing/tools.md) | Reusable opt-in probes and profiling |

Architecture contracts constrain implementation: preserve their dependency,
authority, soundness and lifecycle rules when changing code. A deliberate contract
change must update its callers, validation and documentation together. The engine's
closed dependency list remains a hard boundary.

Keep all standalone documentation here, as required by [repository rules](../.rules).
READMEs elsewhere identify their contents and link here. Public API KDoc stays with
source; agent instructions, GitHub templates and license notices retain their
functional locations. Active fixture inputs/manifests and reusable scripts stay
with their consumers; generated measurements and historical campaign bundles stay
outside the repository. Document current Klause behavior without other-solver references or comparisons
or historical campaign narratives.
