package com.eignex.klause.solver.evidence

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.arithmetic.polynomial.PolynomialResourceLimit
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation

/** An original-source inequality, optionally guarded by Boolean source pins. */
class SourcePremise(
    /** Original-source affine inequality. */
    val inequality: SourceInequality,
    guards: Map<Int, Boolean> = emptyMap(),
) {
    private val captured = guards.toMap()
    /** Source pins required to use this premise. */
    val guards: Map<Int, Boolean> get() = captured.toMap()
}

/** Source-specific complete assignment and recession checks; unsupported semantics must decline. */
interface SourceSemanticsChecker {
    /** Check all declarations, constraints and invocation restrictions using exact coordinates. */
    fun witness(sample: Sample, scope: SourceScope, cancellation: Cancellation): SourceEvidenceCheck

    /** Check an exact objective value using the original source objective. */
    fun objectiveEquals(
        sample: Sample, value: SourceValue, scope: SourceScope, cancellation: Cancellation,
    ): SourceEvidenceCheck = SourceEvidenceCheck.Unavailable("original-source objective checker unavailable")

    /** Check typed source-specific derivation data against original semantics. */
    fun derivation(
        claim: SourceClaim, derivation: SourceDerivation.Extension, scope: SourceScope, cancellation: Cancellation,
    ): SourceEvidenceCheck = SourceEvidenceCheck.Unavailable("source-specific derivation checker unavailable")

    /** Check a feasible base, all recession conditions and exact objective improvement. */
    fun ray(claim: SourceClaim.Ray, scope: SourceScope, cancellation: Cancellation): SourceEvidenceCheck
}

/** Immutable consumer-owned original-source premises and objective, independent of a proof producer. */
class SourceProofContext(
    /** Original-source invocation and objective. */
    val scope: SourceScope,
    coordinates: Set<SourceCoordinate>,
    premises: Map<String, SourcePremise>,
    objective: Map<SourceCoordinate, BigFraction>? = null,
    /** Exact original-source objective constant. */
    val objectiveConstant: BigFraction = BigFraction.ZERO,
    internal val semanticsChecker: SourceSemanticsChecker? = null,
) {
    private val capturedCoordinates = coordinates.toSet()
    private val capturedPremises = premises.toMap()
    private val capturedObjective = objective?.filterValues { !it.isZero }?.toMap()
    internal val affineObjectiveAvailable: Boolean get() = capturedObjective != null
    /** Declared source coordinates. */
    val coordinates: Set<SourceCoordinate> get() = capturedCoordinates.toSet()
    /** Original rows and declarations indexed by stable premise identifiers. */
    val premises: Map<String, SourcePremise> get() = capturedPremises.toMap()
    /** Exact affine source objective coefficients; an omitted constructor objective has no affine authority. */
    val objective: Map<SourceCoordinate, BigFraction> get() = capturedObjective.orEmpty().toMap()

    init {
        require(capturedPremises.keys.none { it.isBlank() })
        require(capturedObjective.orEmpty().keys.all { it in capturedCoordinates })
        require(capturedPremises.values.all { premise ->
            premise.inequality.coefficients.keys.all { it in capturedCoordinates } &&
                premise.guards.keys.all { SourceCoordinate(SourceCoordinate.Kind.Boolean, it) in capturedCoordinates }
        })
    }
}

/** Checks retained derivations against a consumer's original source, with bounded traversal and cancellation. */
class SourceEvidenceVerifier(
    private val context: SourceProofContext,
    private val maximumSteps: Int = 100_000,
    private val maximumDepth: Int = 128,
) {
    init { require(maximumSteps > 0 && maximumDepth > 0) }

    /** Verify scope, premises, imports, branch coverage and the mathematical claim. */
    fun verify(evidence: SourceEvidence, cancellation: Cancellation = Cancellation.Never): SourceEvidenceCheck =
        verify(evidence, cancellation, SourceProofAllowance(maximumSteps, maximumDepth))

    internal fun verify(
        evidence: SourceEvidence, cancellation: Cancellation, allowance: SourceProofAllowance,
    ): SourceEvidenceCheck = try {
        Run(cancellation, allowance).verify(evidence, context.scope, 0)
    } catch (stop: ProofStop) {
        stop.verdict
    } catch (_: PolynomialInterrupted) {
        SourceEvidenceCheck.Unavailable("original-source arithmetic check cancelled")
    } catch (_: PolynomialResourceLimit) {
        SourceEvidenceCheck.Unavailable("original-source arithmetic allowance exhausted")
    }

    private inner class Run(
        private val cancellation: Cancellation,
        private val allowance: SourceProofAllowance,
    ) {
        private fun step(depth: Int) {
            if (cancellation() || !allowance.consume(depth)) {
                throw ProofStop(SourceEvidenceCheck.Unavailable("source proof verification allowance exhausted"))
            }
        }

        fun verify(evidence: SourceEvidence, expected: SourceScope, depth: Int): SourceEvidenceCheck {
            step(depth)
            if (evidence.scope != expected) {
                return reject("evidence belongs to a different source invocation or objective")
            }
            if (!validCondition(expected.condition)) {
                return reject("invocation references undeclared source coordinates")
            }
            return claim(evidence.claim, evidence.derivation, expected, depth + 1).also { step(depth) }
        }

        private fun validCondition(condition: SourceCondition): Boolean =
            condition.bools.keys.all { SourceCoordinate(SourceCoordinate.Kind.Boolean, it) in context.coordinates } &&
                (condition.lower.keys + condition.upper.keys).all { it in context.coordinates }

        private fun claim(
            claim: SourceClaim, derivation: SourceDerivation, scope: SourceScope, depth: Int,
        ): SourceEvidenceCheck {
            step(depth)
            when (derivation) {
                is SourceDerivation.Unavailable -> return SourceEvidenceCheck.Unavailable(derivation.reason)
                is SourceDerivation.Extension -> return context.semanticsChecker?.derivation(claim,
                    derivation, scope, cancellation)
                    ?: SourceEvidenceCheck.Unavailable("source-specific derivation checker unavailable")
                is SourceDerivation.Import -> {
                    val imported = derivation.evidence
                    if (imported.scope.revision != scope.revision || imported.scope.objective != scope.objective ||
                        !scope.condition.implies(imported.scope.condition)
                    ) return reject("import has a foreign source, objective or stronger assumptions")
                    if (claim is SourceClaim.Witness || claim is SourceClaim.Ray ||
                        claim is SourceClaim.Optimal || claim is SourceClaim.Limit || claim is SourceClaim.Unbounded ||
                        claim is SourceClaim.Extension) {
                        return reject("import does not establish the requested claim")
                    }
                    val matching = matchSourceClaims(claim, imported.claim, cancellation)
                    if (matching != SourceEvidenceCheck.Checked) return matching
                    return verify(imported, imported.scope, depth + 1)
                }
                is SourceDerivation.BooleanSplit -> {
                    if (derivation.id < 0) return reject("Boolean coverage references an invalid coordinate")
                    val coordinate = SourceCoordinate(SourceCoordinate.Kind.Boolean, derivation.id)
                    if (coordinate !in context.coordinates || derivation.id in scope.condition.bools) {
                        return reject("Boolean coverage must split an unpinned declared coordinate")
                    }
                    return both(
                        claim(claim, derivation.whenFalse,
                            scope.copy(condition = scope.condition.pin(derivation.id, false)), depth + 1),
                    ) {
                        claim(claim, derivation.whenTrue,
                            scope.copy(condition = scope.condition.pin(derivation.id, true)), depth + 1)
                    }
                }
                is SourceDerivation.IntegerSplit -> {
                    val id = derivation.coordinate
                    if (id.kind != SourceCoordinate.Kind.Integer || id !in context.coordinates ||
                        derivation.threshold.den != BIG_ONE
                    ) return reject("integer coverage requires a declared integer and integral threshold")
                    return both(
                        claim(claim, derivation.below, scope.copy(condition =
                            scope.condition.bound(id, derivation.threshold, upper = true)), depth + 1),
                    ) {
                        claim(claim, derivation.above, scope.copy(condition =
                            scope.condition.bound(id, derivation.threshold + BigFraction.ONE,
                                upper = false)), depth + 1)
                    }
                }
                else -> Unit
            }
            return when (claim) {
                is SourceClaim.Witness -> if (derivation != SourceDerivation.SourceWitness) {
                    reject("a witness requires an original-source assignment check")
                } else context.semanticsChecker?.witness(claim.sample, scope, cancellation)
                    ?: SourceEvidenceCheck.Unavailable("original-source witness checker unavailable")
                is SourceClaim.Ray -> if (derivation != SourceDerivation.SourceRay) {
                    reject("unboundedness requires a source point and recession check")
                } else context.semanticsChecker?.ray(claim, scope, cancellation)
                    ?: SourceEvidenceCheck.Unavailable("original-source recession checker unavailable")
                is SourceClaim.Bound -> {
                    val objective = scope.objective ?: return reject("objective bound has no objective scope")
                    val sign = if (objective.sense == ObjectiveSense.MINIMIZE) BigFraction.MINUS_ONE
                        else BigFraction.ONE
                    val derived = inequality(derivation, scope, depth + 1)
                    if (derived.contradicts()) return SourceEvidenceCheck.Checked
                    if (!context.affineObjectiveAvailable) {
                        return SourceEvidenceCheck.Unavailable("original affine objective derivation unavailable")
                    }
                    if (derived.coefficients != context.objective.mapValues { (_, value) -> sign * value }) {
                        return reject("derivation does not establish the source objective coefficients")
                    }
                    val algebraic = AlgebraicArithmetic(PolynomialArithmetic(cancellation))
                    val exact = when (val value = claim.value) {
                        is SourceValue.Rational -> algebraic.rational(value.value)
                        is SourceValue.Algebraic -> value.value
                        is SourceValue.Extension -> {
                            return SourceEvidenceCheck.Unavailable("symbolic objective checker unavailable")
                        }
                    }
                    val rhs = algebraic.multiply(algebraic.rational(sign),
                        algebraic.subtract(exact, algebraic.rational(context.objectiveConstant)))
                    val comparison = algebraic.compare(algebraic.rational(derived.rhs), rhs)
                    if (comparison < 0 || (comparison == 0 && (derived.strict || !claim.strict))) {
                        SourceEvidenceCheck.Checked
                    } else reject("derivation does not establish the objective bound")
                }
                is SourceClaim.Optimal -> {
                    val pair = derivation as? SourceDerivation.Optimum
                        ?: return reject("optimality requires a witness and matching global bound")
                    both(claim(SourceClaim.Witness(claim.sample), pair.witness, scope, depth + 1)) {
                        both(claim(SourceClaim.Bound(claim.value), pair.bound, scope, depth + 1)) {
                            context.semanticsChecker?.objectiveEquals(claim.sample, claim.value, scope, cancellation)
                                ?: SourceEvidenceCheck.Unavailable("original-source objective checker unavailable")
                        }
                    }
                }
                is SourceClaim.Clause -> {
                    val discharged = derivation as? SourceDerivation.Discharge
                        ?: return reject("clause requires a checked conditional refutation")
                    if (claim.literals.keys.any {
                            SourceCoordinate(SourceCoordinate.Kind.Boolean, it) !in context.coordinates ||
                                it in scope.condition.bools
                        }
                    ) return reject("clause discharge requires unpinned declared source Booleans")
                    var condition = scope.condition
                    for ((id, value) in claim.literals) {
                        step(depth)
                        condition = condition.pin(id, !value)
                    }
                    if (discharged.refutation.claim != SourceClaim.Refutation) {
                        return reject("clause discharge has no refutation claim")
                    }
                    verify(discharged.refutation, scope.copy(condition = condition), depth + 1)
                }
                is SourceClaim.Limit, is SourceClaim.Unbounded, is SourceClaim.Extension ->
                    SourceEvidenceCheck.Unavailable("source-specific claim requires a supported derivation")
                SourceClaim.Refutation -> if (inequality(derivation, scope, depth + 1).contradicts()) {
                    SourceEvidenceCheck.Checked
                } else reject("derivation is not a contradiction")
                is SourceClaim.Consequence -> if (claim.inequality.coefficients.keys.any {
                        it !in context.coordinates
                    }
                ) {
                    reject("consequence references undeclared source coordinates")
                } else {
                    val row = inequality(derivation, scope, depth + 1)
                    if (row.entails(claim.inequality) || row.contradicts()) SourceEvidenceCheck.Checked
                    else reject("derivation does not establish the consequence")
                }
            }
        }

        private fun inequality(derivation: SourceDerivation, scope: SourceScope, depth: Int): SourceInequality {
            step(depth)
            return when (derivation) {
                is SourceDerivation.Premise -> {
                    val premise = context.premises[derivation.id]
                        ?: rejectProof("unknown original-source premise ${derivation.id}")
                    if (premise.guards.any { (id, value) -> scope.condition.bools[id] != value }) {
                        rejectProof("original-source premise guard is not established")
                    }
                    premise.inequality
                }
                is SourceDerivation.Assumption -> {
                    if (derivation.coordinate !in context.coordinates) {
                        rejectProof("assumption references an undeclared coordinate")
                    }
                    val restrictions = if (derivation.upper) scope.condition.upper else scope.condition.lower
                    val value = restrictions[derivation.coordinate]
                        ?: rejectProof("assumption is absent from the invocation")
                    val sign = if (derivation.upper) BigFraction.ONE else BigFraction.ofLong(-1)
                    SourceInequality(mapOf(derivation.coordinate to sign), value * sign)
                }
                is SourceDerivation.Combination -> {
                    val coefficients = mutableMapOf<SourceCoordinate, BigFraction>()
                    var rhs = BigFraction.ZERO
                    var strict = false
                    for (term in derivation.terms) {
                        step(depth)
                        if (term.weight < BigFraction.ZERO) rejectProof("negative inequality multiplier")
                        val row = inequality(term.derivation, scope, depth + 1)
                        for ((id, value) in row.coefficients) {
                            step(depth)
                            coefficients[id] = (coefficients[id] ?: BigFraction.ZERO) + term.weight * value
                        }
                        rhs += term.weight * row.rhs
                        strict = strict || (!term.weight.isZero && row.strict)
                    }
                    SourceInequality(coefficients, rhs, strict)
                }
                is SourceDerivation.Import -> {
                    val consequence = derivation.evidence.claim as? SourceClaim.Consequence
                        ?: rejectProof("inequality import requires a proved consequence")
                    val check = claim(consequence, derivation, scope, depth + 1)
                    if (check != SourceEvidenceCheck.Checked) stopProof(check)
                    consequence.inequality
                }
                is SourceDerivation.Unavailable -> stopProof(SourceEvidenceCheck.Unavailable(derivation.reason))
                else -> rejectProof("derivation does not produce a source inequality")
            }
        }
    }
}

private class ProofStop(val verdict: SourceEvidenceCheck) : RuntimeException()

private fun reject(reason: String): SourceEvidenceCheck.Rejected = SourceEvidenceCheck.Rejected(reason)

private inline fun both(first: SourceEvidenceCheck, second: () -> SourceEvidenceCheck): SourceEvidenceCheck =
    if (first == SourceEvidenceCheck.Checked) second() else first

private fun rejectProof(reason: String): Nothing = stopProof(reject(reason))

private fun stopProof(verdict: SourceEvidenceCheck): Nothing = throw ProofStop(verdict)

internal class SourceProofAllowance(private val maximumSteps: Int, private val maximumDepth: Int) {
    private var steps = 0L

    fun consume(depth: Int): Boolean = ++steps <= maximumSteps && depth <= maximumDepth
}
