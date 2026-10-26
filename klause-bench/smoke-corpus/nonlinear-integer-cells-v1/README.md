# Integer cell controls

These self-authored Apache-2.0 sources exercise consumption of exact univariate
predicate cells by incomplete integer search. The manifest pins source bytes,
mathematical expectations and core/holdout membership. The combined named set has
seventeen cases: eight core cases and nine cases from separate held-out families.
These controls add no exclusion authority or integer-completeness claim.

The `powers` cases keep `n*n = 2` and declare one, two, four or eight original
integer coordinates. Additional coordinates are unconstrained. Different tuples
can therefore repeat the same impossible value of `n`; a source explanation
independent of the other coordinates can exclude an unvisited tuple. No integer
satisfies the equation, although its real relaxation has two roots. The `degree`
cases use powers four, eight, sixteen and seventeen, each with an unrelated integer.
They exercise degree scaling and the frozen producer's
[degree-sixteen admission limit](https://github.com/Eignex/klause/issues/3633#issuecomment-6425314899).
Multiplication keeps these sources within ordinary SMT arithmetic syntax.

The other families are reserved for consumer validation rather than core tuning:

- `cubic` retains the integer root 2 beside two irrational roots.
- `repeated` has double roots at 0 and 1; its strict predicate accepts -1 and 2.
- `rational` has real windows `(1/4, 3/4)` and `(-3/4, -1/4)`, each without an integer.
- `annulus` requires `2 < n*n < 4`, with no integer, or permits the SAT neighbors
  -2 and 2 at a weak upper endpoint.
- `boolean` permits the square-root hole under one Boolean branch and the SAT neighbors under the other.
- `mixed` declares a real before its integers and combines the square-root hole
  with the singular completion `r*r = 0`.
- `wide` has the exact integer witness `100000000000000000001`, beyond machine words.

Conditional predicates supply no source-global exclusion. A real repair query can
admit fractional points even when the corresponding integer window is empty;
integer-hull coverage cannot refute that real query. The mixed fixture preserves
distinct original real and integer identities. Cursor tests separately cover
condition replacement, slicing, cancellation and disposal.

Ordinary admission can select finite, linear or nonlinear execution. Record actual
routes, execution dimensions and cursor entry before crediting exclusion reuse.
Measure avoided completions of unvisited tuples and repeated checked support with
the same producer, together with total work, memory and checked SAT outcomes.
UNKNOWN is permitted for mathematically UNSAT sources when only bounded proposals
are exhausted. Native UNSAT reports require their own proof authority; this
manifest is not an independent solver certificate.
