# Source witness validation

`solver.validation` owns reusable checks of proposed source coordinates. Evidence,
backtracking, local search, LP adapters and pipeline/portfolio orchestration call
these services; checking does not require an orchestration owner. Publication
identity, producer and consumer restrictions, objective scope and certificate
admission remain with their existing [evidence](evidence.md) and
[portfolio](portfolio.md#witnesses-and-proof-scope) callers.

## Finite and open coordinates

`composedFixpoint` creates a private propagation session for the full Boolean and
finite integer point and checks the selected original-source relations. A complete
native integer point on a baked model without real coordinates seeds every coordinate
before the first fixpoint. The point remains that session's private root when caller
assumptions are applied, so incompatible caller pins still refute the point. Other
representations retain incremental pinning after the caller's assumptions.
Prepared custom providers retain their selected factories
and required original checks. Explicit enforcing peers cover only their assigned
relations. Rejection refutes the proposed point; it does not refute the source.
Cancellation or unavailable required checking returns indeterminate. Every exit
reports the session's propagation work through the caller's existing accounting hook,
including zero work when point preparation is cancelled before constructing the session.
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
another arithmetic allowance. Power equality compares normalized numerator and denominator
powers against the already-held witness components. Bit-length lower bounds refute
impossible equalities before allocation; each remaining product can allocate at most
one bit beyond the corresponding witness component. Zero, units and sign parity are
handled directly. Unproved representation declines and interruption remain indeterminate.

Finite real domains compare their captured binary64 literals as exact rationals.
An isolated algebraic root can match a rational member without point storage: the
literal must lie inside its isolating interval and exactly zero its captured rational
polynomial. Symbolic algebraic leaves retain the same authority. This check uses
rational payload arithmetic and charges literal/coefficient visits to the caller;
it creates no polynomial solver or arithmetic allowance. A matched member can supply
the rational coordinate to the existing row checks. Other nonrational coordinates
and symbolic expressions retain their specialized checking owners.

`verifyProjectedSourceWitness` combines library-owned original relation checking with
original integer declarations and a private composed fixpoint using captured source
descriptors. A caller callback adds a restriction and cannot replace relation coverage.
Exact policy uses the full original real graph. Captured nonzero tolerance has a complete
adapter for unconditional, non-strict linear comparisons and active integer-indicated
rows, including rational slack and source real bounds. Uncovered real relations, strict
or reified tolerance semantics remain indeterminate. Numerically checked rows and real
relations are excluded from the second finite checker; Boolean structure and other
integer constraints remain enforced. Every admitted original obligation is checked
before publication; real relations are not instantiated as finite propagators. A private-grid relation
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
