# Exact analytic real coordinates

`arithmetic.real` retains authoritative real expressions beyond algebraic scalars.
It combines the existing [polynomial kernel](polynomial.md) with certified
[transcendental enclosures](transcendental.md). It owns mathematical values and
operations; the source service owns formula truth, integrality and publication.

## Values and identity

`ExactRealValue` captures an immutable `ExactRealExpression`. Algebraic leaves
retain their original `AlgebraicReal` metadata and rational coordinates. Other
nodes represent pi, exact real exp/log/sin/cos applications, sums, products and
nonnegative integer powers. Operand lists are captured, and getters return
independent lists. Values retain no cancellation token or mutable numerical owner.

Function construction requires a certified positive log argument. Exp, sine and
cosine accept any defined real argument. Algebraic operations keep algebraic
authority. Recognized exact identities include exp/log inverses, zero special
values, rational half-integer multiples of pi, symbolic cancellation and matching
`sin(x)^2+cos(x)^2`. Identity checks compare algebraic leaves exactly rather than
using their defining-polynomial object identities or rounded bounds.

Comparisons first use exact algebraic checks or applicable symbolic identities.
Exp and log preserve exact argument ordering. Otherwise rational enclosures can
certify strict separation. Overlapping nonpoint enclosures supply no equality or
ordering verdict. Refinement can finish without certification; that result retains
unknown and does not introduce a tolerance or altered source semantics.

## Source and publication boundary

Only an original-source check can turn these values into a solver witness. A
symbolic value describes one exact coordinate; it does not itself attest that a
formula holds. Source checks retain Boolean choices, exact integer integrality,
strict inequalities and invocation assumptions.

`Sample.symbolicReals` captures exact expression coordinates alongside its existing
rational and algebraic surfaces. Replacing approximate coordinates discards the
associated exact metadata unless the caller supplies replacement authority.
Approximate coordinates remain heuristic/display data. A rational-only exact
objective evaluator refuses a nonrational symbolic coordinate instead of deriving
an exact objective from its approximate view.

`TranscendentalAssignment` exposes the same `OpenTheoryAssignment` interface and
typed symbolic metadata. Functional text retains exact expressions and algebraic
root metadata. Published values survive later queries, cancellation and disposal
of the invocation that checked them.
