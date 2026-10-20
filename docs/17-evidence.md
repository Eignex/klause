# 17. Original-source derivations

The previous chapters established that every published witness is checked against
the original source. Bounds, refutations and optimality claims need more than a
checked point: they rest on reasoning an engine performed, often on a presolved or
relaxed view of the model. This chapter describes how such claims are expressed as
typed derivations over the original source and checked independently of the engine
that produced them. It closes Part III: with it, every result a solve can return,
whether a witness, a bound or a refutation, has a defined authority.

`solver.evidence` checks typed claims against an immutable original-source context.
The consumer supplies that context independently of the producer's evidence. It
owns the declared coordinate namespaces, stable source-premise identifiers, exact
affine objective and source semantics checker. A claimed revision or a producer's
row snapshot cannot substitute for that original-source binding. An omitted affine
objective has no affine bound authority; it does not describe a constant objective.
Source-specific objective derivations belong to the declared semantics checker.
Capture does not implement proof file export; independent proof files remain a
[vision requirement](01-introduction.md#independently-checkable-proof-files).

## Source contexts and scopes

`SourceProofContext.semantics` optionally retains its source owner's typed
interpretation. Original-IR factories capture it before solving; affine, numeric,
quadratic and restriction contexts preserve it. Linear source results retain the
same qualification, including interruption without a witness or claim. This metadata
adds no premises or checking authority. `SourceScope` continues to delimit revision,
conditions and objective; a raw context without a semantic owner remains unbound.

`SourceScope` captures original revision and declared semantics, exact invocation
restrictions, objective identity and direction. Every check compares this scope
before considering the derivation. Imports must name the same revision, semantics
and objective and may assume only restrictions implied by the importing scope.
Their derivations are rechecked against the same original premises. Boolean clauses
require a checked refutation under exactly their negated literals; discharge cannot
hide an additional numeric neighborhood restriction. A conditioned
refutation cannot refute a weaker parent invocation.

`Problem.sourceProofContext` binds the exact original IR's declared bounds and
implied rows. Guarded premises retain Boolean pins and native integer indicator equalities;
source disequality alternatives
are not invented. Open integer sides provide no finite-box premise. Other declared
frontend semantics require their own original-source checker. Explicit invocation
pins are captured; derived restrictions require their own justifications.
The native integer Abs checker evaluates scalar and wide affine magnitudes at exact
source coordinates and checks every accompanying complete linear conjunction or
disjunction, including reified comparisons and Boolean clauses. Declared integer
sets and invocation restrictions remain required. Implied magnitude inequalities
alone supply no complete source check, and this point checker supplies no recession
authority for Abs relations. Wide-coordinate heuristic witness publication selects
this exact source checker before admission; the affine-only checker does not stand
in for the nonlinear source relation.

Original-IR witness contexts call [shared source validation](13-validation.md) directly
for finite factor and rational coordinate checks. Their exact source restrictions
and specialized affine, algebraic and symbolic checks retain their existing scope;
a runtime publication or heuristic score supplies no additional authority.

## Derivations and checks

`SourceDerivation` retains premise references, exact nonnegative linear
combinations, Boolean alternatives and adjacent integer alternatives. Integer
coverage requires an integral threshold and both `x <= threshold` and
`x >= threshold + 1`. Guarded premises are usable only in branches establishing
their Boolean and exact integer equality guards. Integer pins require original
unconditional singleton bounds or explicit branch restrictions, rounded over the
integer lattice. A conditional row cannot establish its own pin, and a witness
value supplies no global premise authority. Guard checking spends the containing
verification allowance. Bounds retain rational or algebraic values and are checked in original objective units and direction;
refutations must derive an exact contradiction. A branch flag, traversal exhaustion
or numerical engine identity supplies no derivation authority.

`SourceSemanticsChecker` owns complete exact witness and recession checks for the
declared source language. Unsupported semantics decline explicitly.
`LinearSourceSemanticsChecker` checks a source consisting entirely of affine
premises and declared coordinates, including rational and algebraic real values.
Its premises must include all declaration bounds and source constraints.
Integer indicator rows are checked exactly when enabled at the source point.
Recession authority requires every integer guard coordinate to remain fixed;
a direction changing one retains unavailable coverage. The original
IR binder uses this complete checker for plain affine conjunctions, including wide
integer and algebraic real witnesses. Declared integer value sets are checked as well;
an open column acquires no invented finite value set. It checks
invocation restrictions, strict rows, feasible bases, recession signs and objective
improvement. Integer directions must have integral steps. Source-specific symbolic
witness semantics belong to their source checker.

A check yields `Checked`, `Rejected` or `Unavailable`. Capture without a supported
derivation uses `SourceDerivation.Unavailable` and cannot gain proof coverage from
the producer's type. Cancellation and traversal limits also withhold coverage;
imports and branches spend one containing verification allowance. Engine
attestations and reference agreement remain distinct from checked derivations.

## Result capture

`SourceResultEvidence` captures derivations separately from `SourceEngineAttestation`.
Checking a completed package requires the actual expected claim and matching source
context; an engine completion label or evidence for another claim cannot substitute.
An unavailable local derivation does not mask a later checkable import for the same
claim. Alternative capture comparison and traversal spend one containing allowance;
invalid proofs and foreign invocation imports remain rejected.
An attained optimum requires a feasible witness, an equal global bound and original
objective evaluation. An unattained limit additionally requires supported approach
and nonattainment derivations; a strict bound alone does not establish convergence.

`SourceResultCapture` attaches optional evidence to ordinary completed satisfaction
and minimization results. It retains source assignments and exact objective values;
engine completion labels are captured separately. A caller can supply actual bound
or traversal derivations. Capture preserves a completed result already carrying a
different source scope; recapture cannot erase its invocation restrictions. Without them, optimum/refutation coverage remains explicitly
unavailable. Existing workers retain their documented engine admission contract;
this optional capture does not convert producer identity into a derivation.

`PreparedSourcePlan.sourceResultCapture` uses the plan's retained original exact IR,
explicit invocation pins and source objective direction. Narrowed execution bounds
are not original premises. Other frontend semantics and unrepresented deductions
withhold this capture rather than relabel their source.

`LpSourceEvidenceCapture` optionally converts retained exact LP dual and Farkas
coefficients into original-premise combinations. Structural coordinates retain their
source declarations and shifts; slack coordinates retain exact row images. Every
selected endpoint must have a compatible original premise or invocation citation.
Integer premise eligibility uses the containing original scope and capture allowance;
an unestablished conditional row cannot hide a later unconditional citation.
Certified LP values use minimized objective units; maximization capture negates the
reported value into the original source upper bound.
Missing cut/lowering justifications, foreign integer certificates, unsupported
column bindings and interrupted capture remain unavailable. Numerical model labels
and supplied multiplier values do not confer authority; the resulting source claim
still requires original-context verification.

## Transformations

[Presolve](12-presolve.md) can attach typed justifications to each mapping stage, so
a claim about the reduced model can be carried back to the original.

`SourceMapping` retains optional typed transformation justifications at each stage.
`SourceTransformationVerifier` requires a total coordinate image, original-source
justifications for substituted target premises and invocation restrictions, and
integral images of target integers. Objective adjustments additionally require
positive scale, matching direction and checked source equalities. A verified affine
projection transfers only a proof using its covered premises; source-specific target
semantic extensions remain unavailable without their own projection justification.
Integer target guards supply no additional source assumptions during projection.
Projection obligations and terminal checks share one containing verification allowance.

Completed mapping capture retains the reduced package, including worker imports, and
every optional stage in `SourceResultEvidence.mappings`. Reconstruction checks the
original witness and removes private target coordinates. Supported refutation and
bound citations are translated into original premise references and checked against
the original context; identity Boolean/integer coverage and compatible imports retain
their actual derivations. This checks the original claim, not an entire projection
contract. Missing stages, source-specific derivations and unsupported coordinate
coverage remain unavailable. Bound selection compares exact target values after every
source objective scale and offset with the actual requested original bound. Unavailable
local capture does not mask a later matching imported bound or refutation; an invalid
matching proof is rejected before considering other alternatives. Imported optima retain
their actual bound derivation. Candidate comparison, normalization and original-context
verification share one sticky cancellation and containing allowance.

`SourceResultEvidence.engineScope` records terminal admission separately from original
derivation scope. A transformed terminal retains reduced or unknown engine scope until
its original claim checks. Completion-taking issuance refuses that foreign or missing
admission scope; original witnesses remain separately checkable. Recapture cannot erase
withheld admission. Mapping capture keeps original exact objective units and source
sense; ordinary minimization telemetry stays in minimized units. Transformation flags
and reduced engine labels provide no independent original-source proof coverage.

## Nonlinear claims

`SourceResultCapture.quadratic` retains exact incumbent, bound, limit and unboundedness
claims on quadratic results. `Problem.quadraticSourceProofContext` reuses the
original constraints and exact quadratic objective evaluation, and derives interval
bounds from original declarations and invocation intervals through shared exact
interval arithmetic. Typed engine extensions retain convex, objective-image,
recession, conditional and branch data without opaque payloads. Capture binds to the
actual original object, objective, direction and explicit original-coordinate pins;
an equal-shaped model, replacement objective or weaker invocation cannot replace
them. Completed verification checks exact incumbent and bound equality, strictness
and direction. General unboundedness requires a source-specific trajectory
derivation; an affine `Ray` retains its explicit direction. An optimum label or a
strict bound alone cannot establish an attained optimum or the approach to an
unattained limit, and a flat branch list is not a traversal proof.

Absent affine data does not become a constant objective. Unsupported traversal,
trajectory or approach mechanisms retain unavailable coverage.

Projected polynomial witness checking fixes the original Boolean and exact real
coordinates in the retained formula and leaves lowering auxiliaries existential.
Rational coordinates use equalities; algebraic coordinates use their defining
polynomial and open isolating interval, excluding a different conjugate root.
The existing complete polynomial theory checks that extension under the caller's
containing cancellation, work meter and representation limits. Approximate-only
coordinates, interruption and incomplete checking remain unavailable. A feasible
extension checks the source point; it supplies no global bound or proof export.
Shared IR checking uses the retained finite projection and polynomial completion;
native nonlinear-integer checking pins exact integers and reals through their original
numeric mapping. Both leave lowering auxiliaries existential without preparing another plan.

## In-search publications

Workers in a [portfolio](16-portfolio.md) share bounds and witnesses while they run.
Those publications carry the runtime binding that lets a consumer check them; the
portfolio's admission rules are in [chapter 16.1](16.1-portfolio-witnesses.md).

`EngineInvocation` retains the prepared plan, its original source and objective
direction, the execution model and objective identity, and owned original and
execution assumption snapshots. It may also retain an exact original condition,
including real windows and arbitrary-precision integer restrictions, which must keep
every explicit original pin and cannot change after binding. `CompletedEngineResult`
pairs an actual completed result with that binding.

`CompletedEnginePublication` retains native root objective floors and integer windows
for sharing during search, with the actual invocation, execution value or
coordinate and both endpoints. Integer windows use execution coordinates and imply no
original-coordinate mapping. Its witness variant retains the immutable assignment and
reported execution value after a native witness check; `QuadraticWitnessPublication`
adds the original source point and exact objective value, and its checker evaluates
the original objective exactly. These are in-search publications: they manufacture no
terminal result. `EvidenceCertificate.published` carries them through the existing
admission path, and a certificate can be built only from an actual completed result
or native publication, never from a model identity and claim kind.

`PortfolioWorker.bindSharingInvocation` binds the containing pool query separately
from the worker's runtime, and a restricted worker cannot change it afterwards.
Consumers compare the prepared plan, execution identity and objective, both sets of
restrictions and the optional capture scope, require exact equality of values,
coordinates and endpoints, and run their installed source checker. A more restricted
worker can supply a feasible point to the containing query but no global bound.
Unbound workers withhold shared root deductions, cancellation or unavailable
validation withholds admission, and uncaptured publications gain no independent
derivation coverage from their native type. The public `PortfolioBuilder.build` and
`buildExplicit` entry points prepare one finite source invocation and bind each
worker's post-recipe assumptions separately, so `Portfolio.thompson` can bind its
default evidence consumer to the shared query.

## Tolerance claim transfer

`LinearToleranceQualification` checks whether an original-unit linear source's
accepted set equals its nominal set. Its private row inputs retain individual
terms, exact declaration bounds and indicator pins; combining cancelling terms
would change relative slack. It uses accepted single-term intervals and integer
activity lattices to justify equivalence, and can derive an independent accepted
objective bound from those intervals. It does not select an engine or turn native
producer metadata into a source derivation. Frontend adapters retain their source
number correspondence; [MPS publication](08-mps.md#tolerance-publication)
uses the checked qualification to scope native terminal and bound output.

## Independent acceptance

Independent acceptance checks the original source bytes and declared semantics,
with separately retained caller conditions and actual claims. Runtime provenance
and engine completion remain separate from mathematical verification, and missing
retained inputs or checker reports cannot supply that acceptance.
[Portfolio publication](16-portfolio.md) owns admission and transport;
[LP certification](27-lp-certification.md) owns numerical evidence.

---

Previous: [16.3 Worker recipes](16.3-portfolio-recipes.md) · [Contents](README.md) · Next: [18. Theory services](18-theories.md)
