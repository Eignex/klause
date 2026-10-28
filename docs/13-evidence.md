# 13. Original-source derivations

`solver.evidence` checks typed witness, bound, refutation and optimum claims against
an immutable original-source context supplied independently of the producer. That
context holds coordinate namespaces, stable premise IDs, an exact affine objective
and a semantics checker. A producer's revision label or row snapshot cannot replace
it; an omitted objective supplies no bound authority.

Evidence capture is optional and retained in memory. Engine attestations remain
separate from checked derivations, and the
[proof-file boundary](01-introduction.md#independently-checkable-proof-files) applies.

## Source contexts and scopes

`SourceScope` captures the original revision and declared semantics, exact
invocation restrictions, objective identity and direction. Every check compares
the scope before considering the derivation. Imports must name the same revision,
semantics and objective, may assume only restrictions implied by the importing
scope, and are rechecked against the same premises. A conditioned refutation cannot
refute a weaker parent invocation.
An imported refutation can discharge an objective bound only within that compatible
scope and after its contradiction is freshly checked. It supplies no witness or
attainment; an optimum still needs both a source witness and complete bound coverage.

Opt-in numerical refutation reuse belongs to one immutable traversal invocation.
Its source rows, semantics and objective remain bound to that opening. A proof's
numeric restrictions can shrink only after a fresh exact check; the original
invocation restrictions and complete Boolean substitution remain present. Implied
integer activation bounds can require restrictions absent from the explicit proof
support, so an unsuccessful reduction retains only the checked original scope.
Each compatible import is rechecked before it closes a leaf.
Unit-prefix reuse checks the import with its one missing integral source bound
asserted. Its exact integer complement is then justified relative to the established
prefix and opening restrictions. Both integer alternatives remain in the source
derivation; the implied literal alone supplies no coverage.

`Problem.sourceProofContext` binds the original IR's declared bounds and rows.
Guarded premises keep their Boolean pins and integer indicator equalities; open
integer sides provide no finite-box premise, and derived restrictions need their own
justification. Other frontend semantics require their own checker. The native integer
Abs checker evaluates affine magnitudes at exact source coordinates together with
their accompanying linear constraints; implied magnitude inequalities alone are not
a complete source check. Witness contexts call
[shared source validation](07-presolve.md#source-witness-validation) for finite and rational coordinate checks.

## Derivations and checks

`SourceDerivation` retains premise references, exact nonnegative linear
combinations, Boolean alternatives and adjacent integer alternatives. Integer
coverage requires an integral threshold with both `x <= threshold` and
`x >= threshold + 1`. Guarded premises are usable only in branches that establish
their guards, and a conditional row cannot establish its own pin. Bounds are checked
in original objective units and direction; refutations must derive an exact
contradiction. A branch flag, traversal exhaustion or engine identity supplies no
authority.

A common inequality proved with `BooleanSplit` can enter another nonnegative
combination through `Import` of its checked `Consequence`. The import keeps its
original scope and rechecks both alternatives against the consumer's source
premises. The LP partial-refutation adapter retains dormant numeric premises for
this reconstruction; their guards still require establishment inside each proof
branch.

An active-linear refutation substitutes only established Boolean coefficients and
uses rows with established Boolean guards and independently established integer
pins. Its checker reconstructs that subset from the original source in the claimed
scope, preserving strictness and bound premises. It establishes only a scoped
contradiction; omitted rows and unresolved objective terms supply neither a witness
nor an objective bound. Complete pinned derivations retain their complete-scope
requirement.

`IntegerRounding` first checks its cited inequality, then requires integral
coefficients over declared integer coordinates. It rounds a closed right side
down, or a strict right side to `ceil(rhs) - 1`. Source guards and integer pins
remain premises of the nested derivation; rounding cannot establish them.

`MixedIntegerRounding` checks integral integer activity and one cited coordinate
bound for each continuous term. After exact shifts or upper-bound complements,
positive continuous terms drop and negative terms scale by `1 / (1 - frac(rhs))`;
the right side rounds down. The closed row is valid for strict input rows too.
Every bound and row keeps its source guards and invocation scope. Affine mappings
decline transfer without an integrality-preserving coordinate justification.

`SourceSemanticsChecker` owns complete exact witness and recession checks for the
declared source language and declines unsupported semantics.
`LinearSourceSemanticsChecker` handles a source made entirely of affine premises and
declared coordinates, including rational and algebraic reals, wide integers and
indicator rows. It checks invocation restrictions, strict rows, feasible bases,
recession signs and objective improvement; integer directions need integral steps,
and recession requires guard coordinates to stay fixed.

A check yields `Checked`, `Rejected` or `Unavailable`. Missing support, cancellation
and traversal limits all give `Unavailable`, never proof coverage from the producer's
type. Imports and branches spend one containing verification allowance. Engine
attestations and reference agreement stay distinct from checked derivations.
Each verification run lazily captures the immutable context's coordinate, premise
and objective views once, so individual proof lookups do not copy the full source.

## Result capture

`SourceResultEvidence` keeps derivations separate from `SourceEngineAttestation`.
Checking a package requires the actual expected claim and matching context; an
engine label or evidence for another claim cannot substitute. An attained optimum
requires a feasible witness, an equal global bound and original objective evaluation.
An unattained limit also requires approach and nonattainment derivations.

`SourceResultCapture` attaches optional evidence to completed satisfaction and
minimization results, keeping source assignments and exact objective values. A
caller can supply bound or traversal derivations; without them optimum and
refutation coverage stays unavailable. Recapture cannot erase a result's existing
scope or restrictions. `PreparedSourcePlan.sourceResultCapture` uses the plan's
original IR, explicit pins and objective direction; narrowed execution bounds are
not original premises.

`LpSourceEvidenceCapture` converts exact LP dual and Farkas coefficients into
original-premise combinations. Structural coordinates keep their declarations and
shifts, slack coordinates keep exact row images, and every endpoint needs a
compatible original premise or invocation citation. Maximization capture negates the
minimized value into the original upper bound. Missing cut or lowering
justifications, foreign integer certificates and interrupted capture stay
unavailable, and the resulting claim still requires original-context verification.

## Transformations

[Presolve](07-presolve.md) can attach typed justifications to each mapping stage, so
a claim about the reduced model can be carried back to the original.

`SourceTransformationVerifier` requires a total coordinate image, original-source
justifications for substituted premises and restrictions, and integral images of
target integers. Objective adjustments also require positive scale, matching
direction and checked equalities. A verified affine projection transfers only a proof
using its covered premises.

Completed mapping capture keeps the reduced package, including worker imports, and
every stage in `SourceResultEvidence.mappings`. Reconstruction checks the original
witness and removes private target coordinates. Supported citations are translated
into original premise references and checked against the original context; this
checks the original claim, not the whole projection contract. Bound selection
compares exact target values after every objective scale and offset.

`SourceResultEvidence.engineScope` records terminal admission separately from
derivation scope. A transformed terminal keeps reduced or unknown engine scope until
its original claim checks, and recapture cannot erase withheld admission.

## Nonlinear claims

`SourceResultCapture.quadratic` keeps exact incumbent, bound, limit and
unboundedness claims on quadratic results. `Problem.quadraticSourceProofContext`
reuses the original constraints and exact objective evaluation and derives interval
bounds through shared exact interval arithmetic. Capture binds the actual original
object, objective, direction and pins. General unboundedness needs a trajectory
derivation; an affine `Ray` keeps its explicit direction. An optimum label or a
strict bound alone cannot establish an attained optimum or an approach to a limit.

Projected polynomial witness checking fixes the original Boolean and exact real
coordinates in the retained formula and leaves lowering auxiliaries existential.
Algebraic coordinates use their defining polynomial and isolating interval, which
excludes conjugate roots. The complete polynomial theory checks that extension under
the caller's limits. A feasible extension checks the point; it supplies no global
bound.

## In-search publications

[Portfolio admission](12-portfolio.md#witnesses-and-proof-scope) checks runtime
publications while workers execute.

`EngineInvocation` keeps the prepared plan, its original source and objective
direction, the execution model and owned assumption snapshots, plus an optional
exact original condition that cannot change after binding.
`CompletedEnginePublication` carries native root objective floors, integer windows
and checked witnesses with their invocation and both endpoints;
`QuadraticWitnessPublication` adds the original point and exact objective. These
publications manufacture no terminal result, and a certificate can be built only
from an actual completed result or publication.

`PortfolioWorker.bindSharingInvocation` binds the pool query separately from the
worker's runtime. Consumers compare plan, execution identity, objective,
restrictions and capture scope, require exact equality of values and endpoints, and
run their installed source checker. A more restricted worker can supply a feasible
point but no global bound, and unbound workers withhold shared root deductions.

Linear runtime terminal admission checks the captured typed terminal directly under
the bounded `SourceEvidenceVerifier` policy, after checking its invocation and
retained evidence scopes. Consumer-selected result packages use claim matching
under their containing replay allowance.

## Tolerance claim transfer

`LinearToleranceQualification` checks whether a linear source's tolerance-accepted
set equals its nominal set. It keeps individual terms, since combining cancelling
terms would change relative slack, and uses single-term intervals and integer
activity lattices to justify equivalence or derive an accepted objective bound.
[MPS publication](05-arithmetic-formats.md#tolerance-publication) uses the checked qualification to
scope native terminal and bound output.

Independent acceptance additionally needs original source bytes, declared semantics,
caller conditions and actual claims. Runtime provenance or missing checker reports
cannot substitute for mathematical verification.

---

Previous: [12. Portfolios](12-portfolio.md) · [Contents](README.md) · Next: [14. Theory services and difference logic](14-theories.md)
