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
Its premises must include all declaration bounds and source constraints. It checks
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
An attained optimum requires a feasible witness, an equal global bound and original
objective evaluation. An unattained limit additionally requires supported approach
and nonattainment derivations; a strict bound alone does not establish convergence.

`SourceResultCapture` attaches optional evidence to ordinary completed satisfaction
and minimization results. It retains source assignments and exact objective values;
engine completion labels are captured separately. A caller can supply actual bound
or traversal derivations. Without them, optimum/refutation coverage remains explicitly
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

Capture does not implement proof file export. [Portfolio publication](portfolio.md)
owns admission and transport; [LP certification](../lp/certification.md) owns numerical
evidence; [presolve](presolve.md) owns reconstruction and transformation guarantees.
