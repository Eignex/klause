# Nonlinear integer mechanism

`theory.qfnia` searches `ir.NonlinearIntegerProblem`, a neutral polynomial source
snapshot with separate original integer, real and Boolean identities. Integer
coordinates embed into the polynomial coordinate space exactly. The snapshot
retains the source expression DAG; products and fixed powers are not expanded by
the input format. Source integer arithmetic remains arbitrary precision.
The internal typed SMT polynomial parser accepts this metadata; public source
fallback still exposes the pure-real polynomial route.

## Candidate search and completion

Search checks the whole real relaxation first. A completed real refutation also
refutes the integer source. An integral relaxation point is a candidate requiring
an original-source check. Other points supply rounded heuristic seeds only.
Candidate traversal enumerates integer tuples around zero and source constants,
then widens its candidate set. These are search choices, never source bounds or
finite CP domains. Its candidate allowance can stop an incomplete traversal.

Each candidate fixes every original integer coordinate to an exact rational
integer and asks the existing [polynomial service](qfnra.md#conditioned-theory-service)
to complete real coordinates and correlated Booleans. Failure refutes only that
candidate. Numeric conditional auxiliaries remain private to the formula and are
completed under their guarded source definitions.

The heuristic strategies start with an integer candidate, then release one
coordinate for local repair or two for a block repair. Conditioned real completion
supplies a rounded integer proposal. Failed neighborhoods select source-constant
or widening integer moves; repeated candidates trigger an escape move. Every
proposal is checked with all integers fixed. Mixed execution alternates candidate
traversal, single-coordinate repairs and block repairs. All strategies retain the
same formula. Heuristic failure and finite candidate exhaustion produce UNKNOWN.
Only search can publish a whole-source real-relaxation refutation.

## Witnesses and resources

Witness publication requires integral original integer coordinates and exact
completion of the entire formula. A separate iterative evaluator checks the
original expression DAG using exact rational/algebraic arithmetic, including both
sides of every relation and the retained Boolean correlation. An arithmetic stop
or representation decline during this check withholds the witness.
Rounding selects proposals; it cannot replace an exact witness coordinate.

One cancellation and work scope spans hint collection, candidate generation,
condition assembly, all real completion queries and the final source check.
Native work reaches the caller's meter once. Candidate allowances, cancellation,
polynomial work limits and representation declines remain explicit incomplete
causes. No finite-box exhaustion claims UNSAT, and no terminating decision
guarantee is made for nonlinear integer arithmetic. Published telemetry separates
candidates, heuristic moves, block repairs, completion queries and native
polynomial work. The mechanism reads no finite-domain state or linear theory
internals.
