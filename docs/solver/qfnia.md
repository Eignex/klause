# Nonlinear integer mechanism

`theory.qfnia` searches `ir.NonlinearIntegerProblem`, a neutral polynomial source
snapshot with separate original integer, real and Boolean identities. Integer
coordinates embed into the polynomial coordinate space exactly. The snapshot
retains the source expression DAG; products and fixed powers are not expanded by
the input format. Source integer arithmetic remains arbitrary precision.
Typed SMT fallback exposes this snapshot through `SmtLibInput.NonlinearInteger`.
Its `pipelineRequest()` uses the common exact verdict and witness surface. Bounded
representable factors compose through the shared polynomial model rather than a
separate constraint API.

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

The public pipeline uses the [shared exact neighborhood hook](local-search.md)
for LS and adaptive ALNS. Centers retain arbitrary-width exact integers. Boolean
pins and integer windows map to original source coordinates, while real coordinates
remain completion-owned. Source equalities supply initial proposals, including wide
values; guarded equalities do not become source bounds. Each callback offers up to
sixteen integer candidate attempts under its exact windows. A failed real
relaxation covers only that neighborhood; integer candidate exhaustion is undecided.

Mixed execution initially offers sixteen whole-source candidate attempts with a
4,096-unit native arithmetic slice, then up to 32 LS moves and the configured
adaptive ALNS moves, before remaining whole-source candidate search. These slices
share the source, node allowance and cumulative native work. A slice's incomplete
stop permits another arm; containing allowance exhaustion stops the invocation.
Default whole-source candidates are 1,024, heuristic moves 256 and ALNS iterations
50. Every definitive result receives the containing scope's final stop check.

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

Conditioned calls retain original Boolean pins and exact numeric windows. Candidate
fixings intersect these bounds; they cannot replace or widen them. A real
refutation from a conditioned call covers only that call's scope. Fresh calls
capture independent conditions and retain the source snapshot unchanged.
