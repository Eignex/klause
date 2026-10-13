# Original-source derivation checking

`solver.evidence` checks typed claims against an immutable original-source context.
The consumer supplies that context independently of the producer's evidence. It
owns the declared coordinate namespaces, stable source-premise identifiers, exact
affine objective and source semantics checker. A claimed revision or a producer's
row snapshot cannot substitute for that original-source binding. An omitted affine
objective has no affine bound authority; it does not describe a constant objective.
Source-specific objective derivations belong to the declared semantics checker.

`SourceScope` captures original revision and declared semantics, exact invocation
restrictions, objective identity and direction. Every check compares this scope
before considering the derivation. Imports must name the same revision, semantics
and objective and may assume only restrictions implied by the importing scope.
Their derivations are rechecked against the same original premises. Boolean clauses
require a checked refutation under exactly their negated literals; discharge cannot
hide an additional numeric neighborhood restriction. A conditioned
refutation cannot refute a weaker parent invocation.

`SourceDerivation` retains premise references, exact nonnegative linear
combinations, Boolean alternatives and adjacent integer alternatives. Integer
coverage requires an integral threshold and both `x <= threshold` and
`x >= threshold + 1`. Guarded premises are usable only in branches establishing
their guards. Bounds retain rational or algebraic values and are checked in original objective units and direction;
refutations must derive an exact contradiction. A branch flag, traversal exhaustion
or numerical engine identity supplies no derivation authority.

`SourceSemanticsChecker` owns complete exact witness and recession checks for the
declared source language. Unsupported semantics decline explicitly.
`LinearSourceSemanticsChecker` checks a source consisting entirely of affine
premises and declared coordinates, including rational and algebraic real values.
Its premises must include all declaration bounds and source constraints. The original
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

`Problem.sourceProofContext` binds the exact original IR's declared bounds and
implied rows. Guarded premises retain Boolean pins; source disequality alternatives
are not invented. Open integer sides provide no finite-box premise. Other declared
frontend semantics require their own original-source checker. Explicit invocation
pins are captured; derived restrictions require their own justifications.

`PreparedSourcePlan.sourceResultCapture` uses the plan's retained original exact IR,
explicit invocation pins and source objective direction. Narrowed execution bounds
are not original premises. Other frontend semantics and unrepresented deductions
withhold this capture rather than relabel their source.

`LpSourceEvidenceCapture` optionally converts retained exact LP dual and Farkas
coefficients into original-premise combinations. Structural coordinates retain their
source declarations and shifts; slack coordinates retain exact row images. Every
selected endpoint must have a compatible original premise or invocation citation.
Certified LP values use minimized objective units; maximization capture negates the
reported value into the original source upper bound.
Missing cut/lowering justifications, foreign integer certificates, unsupported
column bindings and interrupted capture remain unavailable. Numerical model labels
and supplied multiplier values do not confer authority; the resulting source claim
still requires original-context verification.

`SourceMapping` retains optional typed transformation justifications at each stage.
`SourceTransformationVerifier` requires a total coordinate image, original-source
justifications for substituted target premises and invocation restrictions, and
integral images of target integers. Objective adjustments additionally require
positive scale, matching direction and checked source equalities. A verified affine
projection transfers only a proof using its covered premises; source-specific target
semantic extensions remain unavailable without their own projection justification.
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

`SourceResultCapture.quadratic` retains exact incumbent, bound, limit and unboundedness
claims on quadratic results. General unboundedness requires a source-specific
trajectory derivation; an affine `Ray` retains its explicit direction. Completed
quadratic verification checks actual exact incumbent/bound equality, strictness and
source objective direction. An optimum label or a strict bound alone cannot establish
an attained optimum or approach to an unattained limit.
An individual quadratic certificate checks its declared completion kind before using
the retained package; unrelated derivations in that package cannot change its claim.

`Problem.quadraticSourceProofContext` reuses original constraints and exact quadratic
objective evaluation. The typed quadratic interval rule derives bounds from original
declarations and invocation intervals through the shared exact interval arithmetic.
Typed engine extensions retain convex, objective-image, recession, conditional and
branch data without opaque payloads. Completed native certificates retain those
payloads and a typed native invocation with the actual source object, objective,
direction and explicit original-coordinate pins. Native invocation metadata is
separate from an optional original-source revision. `Problem.quadraticSourceResultCapture`
binds capture to that actual original object and objective; an unrelated equal-shaped
model or weaker invocation cannot replace it. Recapture preserves the native payload.
Native certificate construction requires its engine identity to name the same source
object. Invocation matching compares that object, objective, direction and explicit
conditions; it supplies no independent derivation coverage.
The integer, branch-completion and finite quadratic producers use these typed records.
Their source checker must justify coordinate
bindings and complete coverage; a flat branch list is not a traversal proof. A
known conditioned result cannot be recaptured under a different original scope.

Absent affine data does not become a constant objective. Unsupported traversal,
trajectory or approach mechanisms retain unavailable coverage.

`CompletedEnginePublication` retains native root objective floors and integer windows
for sharing during search. Each publication keeps the actual `EngineInvocation`,
execution value or coordinate and both endpoints. Integer windows use execution
coordinates and may be empty; they do not imply an original-coordinate mapping.
`EvidenceCertificate.published` carries this typed publication through the existing
admission path. Consumer pools bind their own runtime before accepting publications;
matching a model label cannot replace that binding. Objective values, integer
coordinates and endpoints must match the retained publication exactly. Pool binding
and publication folding are atomic, and a pool containing unscoped deductions cannot
acquire a different invocation. Uncaptured native deductions have explicitly
unavailable independent derivation coverage.

Its witness variant retains the actual immutable assignment, reported execution value
and runtime invocation after a native witness check. It is an in-search publication,
so it does not manufacture a terminal result or termination reason. The consumer
requires the same prepared source and objective, compares the actual point and value,
checks both the producing and containing query restrictions, and runs its installed
source checker. A more restricted worker can supply a feasible point to the containing
query after those checks; its point supplies no global bound. Cancellation or
unavailable source validation withholds admission. Uncaptured publications cannot
gain independent source derivation coverage from their native type.

`PortfolioWorker.bindSharingInvocation` binds the containing pool query separately
from the worker's actual runtime. A restricted worker cannot select the containing
query or change it after binding. Bound and integer-window callbacks on a bound
backtrack worker retain their actual native values and coordinates in typed
publications, including origin and contribution bookkeeping. Unbound compatibility
traffic retains its existing engine admission path until its consumer is bound.
Bound backtrack incumbent callbacks carry their actual witness publication through
the existing shared pool admission with the containing runtime and cancellation.
The exchange's installed source checker still decides whether the point supplies a
valid cutoff; metadata alone cannot replace that check.

`CompletedEngineResult` retains an actual ordinary completed result and its source
binding for the completion-taking worker issuance hook. `EngineInvocation` retains
the existing prepared plan, its original source and objective/direction, the actual
execution model/objective identity, and owned original/execution assumption snapshots.
Pins and deduced bounds, holes and sets cannot change after binding. An optional
`SourceScope` records explicitly requested revision/semantics capture; an uncaptured
runtime does not invent a revision or provide independent original-source coverage.
Matching worker identity alone
cannot issue through that hook. Its engine admission and independent derivation check
are separate: the latter requires actual retained evidence and an expected source
claim. Search exhaustion must retain its actual source cutoff to attest a bound;
an exhausted reason without that value supplies no bound claim. Derivation checks
match the completed coordinates and ray direction, and an attained optimum is
checked at its actual assignment. An unbounded completion's feasible point has a
separate witness check from its ray. A scoped non-witness import requires the same
consumer invocation; a scopeless consumer withholds it. Legacy kind-only worker issuance and opaque quadratic producer arguments remain
until their consumers migrate; these hooks do not assert that migration is complete.

Runtime admission compares the actual prepared plan, execution identity/objective,
original and execution restrictions, and optional capture scope. Execution cutoffs
retain native minimized units separately from an optional original-source bound;
missing conversion withholds independent source checking. The published execution
bound and point must match the retained completion. Mapping capture still withholds
foreign or missing terminal admission until the original claim checks.

Capture does not implement proof file export. [Portfolio publication](portfolio.md)
owns admission and transport; [LP certification](../lp/certification.md) owns numerical
evidence; [presolve](presolve.md) owns reconstruction and transformation guarantees.
