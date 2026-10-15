# Source witness validation

`solver.validation` owns reusable checks of proposed source coordinates. Evidence,
backtracking, local search, LP adapters and pipeline/portfolio orchestration call
these services; checking does not require an orchestration owner. Publication
identity, producer and consumer restrictions, objective scope and certificate
admission remain with their existing [evidence](evidence.md) and
[portfolio](portfolio.md#witnesses-and-proof-scope) callers.

## Finite and open coordinates

`composedFixpoint` creates a private propagation session, seeds the caller's
assumptions, pins the full Boolean and finite integer point, and checks the selected
original-source relations. Prepared custom providers retain their selected factories
and required original checks. Explicit enforcing peers cover only their assigned
relations. Rejection refutes the proposed point; it does not refute the source.
Cancellation or unavailable required checking returns indeterminate. Every exit
reports the session's propagation work through the caller's existing accounting hook.
No scratch propagation state survives a check.

`verifyOpenWitness` checks discrete coverage, declared integer bounds and value sets,
then pins singleton integer domains before finite checking. It invents no source
bounds for open declarations. Arbitrary-width integers decline this finite check
and require their existing exact theory checker.

`verifyRealCoordinates` checks authoritative rational coordinates against source
bounds and supported exact factor relations, including strict and reified rows.
Missing rational coordinates or a factor without a complete checker remains
indeterminate. Algebraic, symbolic and arbitrary-width source checks retain their
existing specialized owners; a finite checker does not reinterpret those values.

`finiteWitnessVerifier` combines the finite point check with exact real checking or
the caller's explicit source tolerance policy and retains objective/certificate
admission. Its assumption-aware overload checks exact pins in the private fixpoint
and checks deduced bounds, holes and value sets directly on the completed finite
point. It requires complete finite real coordinates before calling a source tolerance
policy. A tolerance callback does not change exact source evidence semantics.
See [model ownership](../model.md#published-assignments-and-objectives) for immutable
coordinate lifetimes and [resource allowances](resources.md) for containing work
and cancellation scopes.

## Dependencies

The shared services depend on source/factor descriptions, propagation, neutral sample
and verification contracts, exact rational arithmetic and model-facing exact row
views. They do not depend on pipeline, portfolio, backtracking, local-search,
evidence orchestration or concrete theories. The
[architecture gate](../architecture.md#boundary-validation) enforces this closed
list without changing numerical kernel boundaries. Public source and library entry
points retain their signatures and delegate to these internal services. Legacy
internal helper locations also retain thin forwarding functions to preserve their
JVM static/default descriptors; production callers use the shared owner directly.
