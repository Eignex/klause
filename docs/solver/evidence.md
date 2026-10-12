# Original-source derivation checking

`solver.evidence` checks typed claims against an immutable original-source context.
The consumer supplies that context independently of the producer's evidence. It
owns the declared coordinate namespaces, stable source-premise identifiers, exact
affine objective and source semantics checker. A claimed revision or a producer's
row snapshot cannot substitute for that original-source binding.

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

The checker is an additive mechanism. Existing completed-result and portfolio
adapters retain their documented in-memory attestation contracts until they supply
original-source contexts and these typed derivations. It does not implement proof
file export. [Portfolio publication](portfolio.md) owns admission and transport;
[LP certification](../lp/certification.md) owns numerical evidence;
[presolve](presolve.md) owns reconstruction and transformation guarantees.
