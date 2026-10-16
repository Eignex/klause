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
then checks every original relation touching a real coordinate before projecting
the remaining discrete relations into a singleton-domain finite check. Retained
factor indices carry captured provider factories through propagation-owned view
baking into that private point view;
checked real relations never require a finite propagation route. An unavailable
real checker remains indeterminate, including cross-domain relations. Both phases
use the containing cancellation and work allowance. It invents no source
bounds for open declarations. Arbitrary-width integers decline this finite check
and require their existing exact theory checker.

`verifyRealCoordinates` checks authoritative rational coordinates against source
bounds and supported exact factor relations, including strict and reified rows.
Missing rational coordinates or a factor without a complete checker remains
indeterminate. Algebraic, symbolic and arbitrary-width source checks retain their
existing specialized owners; a finite checker does not reinterpret those values.
The cancellation-aware overload charges coordinate, factor, row and finite-choice
visits to its caller. Rational powers use the same containing token and do not open
another arithmetic allowance. Interruption withholds acceptance.

`verifyProjectedSourceWitness` combines that exact real check, or the caller's full
effective source acceptance policy, with original integer declarations and a private
composed fixpoint over the original Boolean/integer-only factors using their captured
source descriptors. Every real relation is checked against the full original problem
before this fixpoint; it is not instantiated as a finite propagator. A private-grid relation
cannot certify its own original real relation, and a rejected point supplies no
source refutation. [Private float projections](../model.md#private-float-projections)
own namespace reconstruction and native-result qualification.

The same exact point check validates integer absolute-value and distinctness
relations before returning from a source with no real coordinates. `AllDifferent`
compares authoritative arbitrary-width integer values at every present source
position, preserving repeated roles, presence-literal polarity and exact exempt
values. Missing integer or presence coordinates reject the proposed point. This
relation check does not materialize finite domains or supply bounds, integrality
or whole-source authority for other factors.

`finiteWitnessVerifier` combines the finite point check with exact real checking or
the caller's explicit source tolerance policy and retains objective/certificate
admission. Its assumption-aware overload checks exact pins in the private fixpoint
and checks deduced bounds, holes and value sets directly on the completed finite
point. It requires complete finite real coordinates before calling a source tolerance
policy. A tolerance callback does not change exact source evidence semantics.
See [model ownership](../model.md#published-assignments-and-objectives) for immutable
coordinate lifetimes and [resource allowances](resources.md) for containing work
and cancellation scopes.

Original linear-source checking evaluates original rational rows and declaration
bounds before normalization. Integrality remains exact; row slack uses the maximum
of absolute tolerance and relative tolerance times the larger of bound magnitude and
the sum of absolute original term activities. Repeated canceling terms retain their
individual magnitudes. Inactive indicator rows impose no constraint. Accepted points
retain exact original objective units and whether nominal relations also hold.
Cancellation declines the check. Finite execution installs this check before worker
admission, after reconstructing source coordinates. Pipeline publication checks native
points through the same service and retains native conclusions separately from their
accepted-source qualification; tolerance acceptance does not transfer nominal proofs.
A captured finite acceptance check retains a separate immutable permission for
floating optimum completion. Zero source tolerance withholds that permission even
when an extra caller filter accepts a floating point. Reconstruction and worker
callback adapters preserve both the check and its precision policy. Accepting a
point does not substitute for an equal exact objective bound; binary64 coordinates
can still be valid exact witnesses when the arithmetic checks establish attainment.

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
