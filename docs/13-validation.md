# 13. Source witness validation

Presolve hands search a transformed model, and the following chapters describe
the engines that look for assignments of it. Before any of those engines can
publish an assignment, it must be checked against the original source. This
chapter describes that check, because every engine in the rest of this part relies
on it: a solver's own view of the model is never the authority on whether a point
is a solution.

`solver.validation` owns reusable checks of proposed source coordinates. Search,
local search, LP adapters, evidence and pipeline or portfolio orchestration call
these services; checking does not require an orchestration owner. A check has three
outcomes. Acceptance means the point satisfies every checked source obligation.
Rejection refutes the proposed point, never the source. Indeterminate means a
required checker was unavailable or the check was interrupted. Publication
identity, restrictions, objective scope and certificate admission remain with the
[portfolio](16.1-portfolio-witnesses.md) and [evidence](17-evidence.md) callers
described later in this part.

## Finite points

`composedFixpoint` creates a private propagation session for the full Boolean and
finite integer point and checks the selected original-source relations. The point is
the session's private root, so incompatible caller pins still refute it. Prepared
custom providers keep their selected factories and required original checks.
Cancellation or unavailable required checking returns indeterminate. Every exit
reports the propagation work through the caller's accounting hook, and no scratch
state survives a check.

Exact point checking also validates integer absolute-value and distinctness
relations on authoritative arbitrary-width values. `AllDifferent` compares every
present position, preserving presence-literal polarity and exempt values, and
missing coordinates reject the point.

## Open and real coordinates

`verifyOpenWitness` checks discrete coverage, declared integer bounds and value sets,
then every original relation touching a real coordinate, then projects the remaining
discrete relations into a singleton-domain finite check. An unavailable real checker
stays indeterminate. It invents no bounds for open declarations, and
arbitrary-width integers go to their exact theory checker instead.

`verifyRealCoordinates` checks authoritative rational coordinates against source
bounds and supported exact relations, including strict and reified rows. Missing
coordinates or a factor without a complete checker stay indeterminate; algebraic,
symbolic and wide-integer checks belong to their specialized owners. Visits are
charged to the caller, and rational powers compare normalized numerators and
denominators, using bit-length bounds to refute impossible equalities before
allocating.

Finite real domains compare their captured binary64 literals as exact rationals. An
isolated algebraic root matches a rational member when the literal lies inside its
isolating interval and zeroes its defining polynomial, without opening a polynomial
solver.

## Projected and tolerance-qualified witnesses

`verifyProjectedSourceWitness` combines original relation checking with integer
declarations and a private composed fixpoint over captured descriptors. A caller
callback can add a restriction but cannot replace relation coverage. Exact policy
uses the full original real graph. Nonzero tolerance has a complete adapter for
unconditional non-strict linear comparisons and active indicator rows; strict or
reified tolerance semantics stay indeterminate. A private-grid relation cannot
certify its own original real relation; see
[private float projections](03.1-source-relations.md#private-float-projections).

`finiteWitnessVerifier` combines the finite point check with exact real checking or
the caller's explicit tolerance policy, checking pins and deduced bounds directly on
the completed point. A tolerance callback requires complete finite real coordinates
and does not change exact evidence semantics. See
[model ownership](03-model.md#published-assignments-and-objectives) for coordinate
lifetimes.

Original linear-source checking evaluates original rational rows and declaration
bounds before normalization. Integrality stays exact; row slack is the larger of the
absolute tolerance and the relative tolerance times the larger of bound magnitude and
the sum of absolute term activities, with cancelling terms kept separate. Accepted
points record whether nominal relations also hold, and tolerance acceptance never
transfers nominal proofs. Unmetered checks poll cancellation at most every 64 visits.
A captured acceptance check carries a separate permission for floating optimum
completion, which zero tolerance withholds.

## Dependencies

The shared services depend on source and factor descriptions, propagation, neutral
sample and verification contracts, exact rational arithmetic and model-facing exact
row views. They do not depend on pipeline, portfolio, backtracking, local-search,
evidence orchestration or concrete theories.

---

Previous: [12. Presolve and reconstruction](12-presolve.md) · [Contents](README.md) · Next: [14. Search and propagation](14-search.md)
