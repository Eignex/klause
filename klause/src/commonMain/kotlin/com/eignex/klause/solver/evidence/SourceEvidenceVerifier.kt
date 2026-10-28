package com.eignex.klause.solver.evidence

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.arithmetic.polynomial.PolynomialResourceLimit
import com.eignex.klause.ir.EffectiveSourceSemantics
import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.magnitudeBitLength

/** An original-source inequality, optionally guarded by Boolean and exact integer source pins. */
class SourcePremise(
    /** Original-source affine inequality. */
    val inequality: SourceInequality,
    guards: Map<Int, Boolean>,
    integerPins: Map<SourceCoordinate, BigFraction>,
) {
    /** Capture an unconditional or Boolean-guarded source inequality. */
    constructor(inequality: SourceInequality, guards: Map<Int, Boolean> = emptyMap()) :
        this(inequality, guards, emptyMap())

    private val captured = guards.toMap()
    private val capturedIntegers = integerPins.toMap()
    internal val guardCount: Int get() = captured.size
    internal val integerPinCount: Int get() = capturedIntegers.size
    /** Boolean source pins required to use this premise. */
    val guards: Map<Int, Boolean> get() = captured.toMap()
    /** Exact integer source equalities required to use this premise. */
    val integerPins: Map<SourceCoordinate, BigFraction> get() = capturedIntegers.toMap()

    init {
        require(capturedIntegers.all { (id, value) ->
            id.kind == SourceCoordinate.Kind.Integer && value.den == BIG_ONE
        })
    }
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
    /** Captured source interpretation, absent when the context has no bound source owner. */
    val semantics: EffectiveSourceSemantics? = null,
) {
    private val capturedCoordinates = coordinates.toSet()
    private val capturedPremises = premises.toMap()
    private val capturedObjective = objective?.filterValues { !it.isZero }?.toMap()
    internal val coordinateCount: Int get() = capturedCoordinates.size
    internal val premiseCount: Int get() = capturedPremises.size
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
                premise.guards.keys.all {
                    SourceCoordinate(SourceCoordinate.Kind.Boolean, it) in capturedCoordinates
                } &&
                premise.integerPins.keys.all { it in capturedCoordinates }
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
    ): SourceEvidenceCheck = checked { Run(cancellation, allowance).verify(evidence, context.scope, 0) }

    internal fun verifyAll(
        evidence: List<SourceEvidence>, cancellation: Cancellation,
    ): List<SourceEvidenceCheck> {
        val run = Run(cancellation, SourceProofAllowance(maximumSteps, maximumDepth))
        return evidence.map { checked { run.verify(it, context.scope, 0) } }
    }

    private inline fun checked(block: () -> SourceEvidenceCheck): SourceEvidenceCheck = try {
        block()
    } catch (stop: ProofStop) {
        stop.verdict
    } catch (_: PolynomialInterrupted) {
        SourceEvidenceCheck.Unavailable("original-source arithmetic check cancelled")
    } catch (_: PolynomialResourceLimit) {
        SourceEvidenceCheck.Unavailable("original-source arithmetic allowance exhausted")
    } catch (_: SourceRoundingLimit) {
        SourceEvidenceCheck.Unavailable("mixed integer rounding scalar width exhausted")
    }

    private inner class Run(
        private val cancellation: Cancellation,
        private val allowance: SourceProofAllowance,
    ) {
        private val integerScopes = mutableMapOf<SourceCondition, SourceIntegerBounds>()
        private val coordinates by lazy { context.coordinates }
        private val premises by lazy { context.premises }
        private val objectiveCoefficients by lazy { context.objective }

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
            condition.bools.keys.all { SourceCoordinate(SourceCoordinate.Kind.Boolean, it) in coordinates } &&
                (condition.lower.keys + condition.upper.keys).all { it in coordinates } &&
                condition.integerRows.size <= 128 && condition.integerRows.all { validIntegerRow(it) }

        private fun validIntegerRow(row: SourceInequality): Boolean =
            !row.strict && row.supportSize in 1..32 && row.rhs.den == BIG_ONE &&
                row.rhs.num.magnitudeBitLength() <= 4096 && row.coefficients.all { (id, value) ->
                    step(0)
                    id.kind == SourceCoordinate.Kind.Integer && id in coordinates && value.den == BIG_ONE &&
                        value.num.magnitudeBitLength() <= 4096
                }

        private fun claim(
            claim: SourceClaim, derivation: SourceDerivation, scope: SourceScope, depth: Int,
        ): SourceEvidenceCheck {
            step(depth)
            if (scope.condition.integerRows.isNotEmpty()) {
                val sample = when (claim) {
                    is SourceClaim.Witness -> claim.sample
                    is SourceClaim.Ray -> claim.sample
                    else -> null
                }
                if (sample != null) {
                    val checked = scope.condition.checkWitness(sample, Cancellation { step(depth); cancellation() })
                    if (checked != SourceEvidenceCheck.Checked) return checked
                }
                if (claim is SourceClaim.Ray) {
                    for (row in scope.condition.integerRows) {
                        var activity = BigFraction.ZERO
                        for ((id, coefficient) in row.coefficients) {
                            step(depth)
                            activity += coefficient * (claim.direction[id] ?: BigFraction.ZERO)
                        }
                        if (activity > BigFraction.ZERO) return reject("ray leaves an integer aggregate restriction")
                    }
                }
            }
            when (derivation) {
                is SourceDerivation.Unavailable -> return SourceEvidenceCheck.Unavailable(derivation.reason)
                is SourceIntegerAggregateSplit -> {
                    val row = derivation.below
                    if (!validIntegerRow(row)) return reject(
                        "aggregate coverage requires declared integer activity and an integral threshold")
                    return both(claim(claim, derivation.whenBelow, scope.copy(condition =
                        scope.condition.integerBound(row)), depth + 1)) {
                        claim(claim, derivation.whenAbove, scope.copy(condition =
                            scope.condition.integerBound(row.adjacentIntegerAbove())), depth + 1)
                    }
                }
                is SourceIntegerRowAssumption -> Unit
                is SourceDerivation.Extension -> {
                    if (scope.condition.integerRows.isNotEmpty() &&
                        context.semanticsChecker !is SourceIntegerAggregateSemantics) {
                        return SourceEvidenceCheck.Unavailable("source-specific checker has no aggregate scope support")
                    }
                    val token = if (scope.condition.integerRows.isEmpty()) cancellation else Cancellation {
                        step(depth)
                        false
                    }
                    return context.semanticsChecker?.derivation(claim, derivation, scope, token)
                        ?: SourceEvidenceCheck.Unavailable("source-specific derivation checker unavailable")
                }
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
                    val matching = if (claim is SourceClaim.Bound && imported.claim == SourceClaim.Refutation) {
                        if (scope.objective == null) return reject("objective bound has no objective scope")
                        SourceEvidenceCheck.Checked
                    } else matchSourceClaims(claim, imported.claim, cancellation)
                    if (matching != SourceEvidenceCheck.Checked) return matching
                    return verify(imported, imported.scope, depth + 1)
                }
                is SourceDerivation.BooleanSplit -> {
                    if (derivation.id < 0) return reject("Boolean coverage references an invalid coordinate")
                    val coordinate = SourceCoordinate(SourceCoordinate.Kind.Boolean, derivation.id)
                    if (coordinate !in coordinates || derivation.id in scope.condition.bools) {
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
                    if (id.kind != SourceCoordinate.Kind.Integer || id !in coordinates ||
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
                    if (derived.coefficients != objectiveCoefficients.mapValues { (_, value) -> sign * value }) {
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
                            SourceCoordinate(SourceCoordinate.Kind.Boolean, it) !in coordinates ||
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
                        it !in coordinates
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
                is SourceIntegerRowAssumption -> {
                    step(depth)
                    if (!validIntegerRow(derivation.row) || derivation.row !in scope.condition.integerRows) {
                        rejectProof("integer aggregate assumption is absent from the invocation")
                    }
                    derivation.row
                }
                is SourceDerivation.Premise -> {
                    val premise = premises[derivation.id]
                        ?: rejectProof("unknown original-source premise ${derivation.id}")
                    if (premise.guards.any { (id, value) -> scope.condition.bools[id] != value }) {
                        rejectProof("original-source premise guard is not established")
                    }
                    if (premise.integerPins.isNotEmpty()) {
                        val bounds = integerScopes[scope.condition] ?: context.integerPinBounds(scope.condition,
                            Cancellation { step(depth); cancellation() })?.also { integerScopes[scope.condition] = it }
                            ?: throw ProofStop(SourceEvidenceCheck.Unavailable("integer premise scope check stopped"))
                        if (bounds.status(premise.integerPins) != IntegerPinStatus.Established) {
                            rejectProof("original-source integer premise guard is not established")
                        }
                    }
                    premise.inequality
                }
                is SourceDerivation.Assumption -> {
                    if (derivation.coordinate !in coordinates) {
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
                is SourceDerivation.IntegerRounding -> {
                    val row = inequality(derivation.inequality, scope, depth + 1)
                    val coefficients = row.coefficients
                    for ((coordinate, coefficient) in coefficients) {
                        step(depth)
                        if (coordinate !in coordinates || coordinate.kind != SourceCoordinate.Kind.Integer ||
                            coefficient.den != BIG_ONE
                        ) rejectProof("integer rounding requires integral activity over declared integers")
                    }
                    val rhs = if (row.strict) BigFraction.of(row.rhs.ceilInteger(), BIG_ONE) - BigFraction.ONE
                        else BigFraction.of(row.rhs.negated().ceilInteger(), BIG_ONE).negated()
                    SourceInequality(coefficients, rhs)
                }
                is SourceDerivation.MixedIntegerRounding -> {
                    val row = inequality(derivation.inequality, scope, depth + 1)
                    val bounds = derivation.bounds.map { inequality(it, scope, depth + 1) }
                    if (row.coefficients.keys.any { it !in coordinates }) {
                        rejectProof("mixed integer rounding references an undeclared coordinate")
                    }
                    mixedIntegerRounding(row, bounds, Cancellation { step(depth); cancellation() })
                        ?: rejectProof("mixed integer rounding requires integral activity and continuous bounds")
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

internal class SourceRoundingLimit : RuntimeException()

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
