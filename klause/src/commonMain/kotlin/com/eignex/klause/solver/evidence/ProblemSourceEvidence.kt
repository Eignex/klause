package com.eignex.klause.solver.evidence

import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.impliedLinearRows
import com.eignex.klause.lp.exactComparison
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.solver.AssignmentCheckCapability
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.executionCapabilities
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.verifyOpenWitness
import com.eignex.klause.util.Cancellation

/** Exact explicit invocation pins in original coordinates; derived restrictions need separate justifications. */
fun Assumptions.sourceCondition(): SourceCondition {
    val integers = ints.mapKeys { (id, _) -> SourceCoordinate(SourceCoordinate.Kind.Integer, id) }
        .mapValues { (_, value) -> BigFraction.ofLong(value) }
    return SourceCondition(bools, integers, integers)
}

/** Assemble original-IR affine premises while retaining a complete source witness checker. */
fun Problem.sourceProofContext(
    scope: SourceScope,
    objective: LinearObjective? = null,
    cancellation: Cancellation = Cancellation.Never,
): SourceProofContext? {
    if (scope.revision.semantics != "klause-ir-exact-v1" || cancellation()) return null
    val coordinates = buildSet {
        for (id in 0 until numBoolVars) add(SourceCoordinate(SourceCoordinate.Kind.Boolean, id))
        for (id in 0 until numIntVars) add(SourceCoordinate(SourceCoordinate.Kind.Integer, id))
        for (id in 0 until numRealVars) add(SourceCoordinate(SourceCoordinate.Kind.Real, id))
    }
    val premises = linkedMapOf<String, SourcePremise>()
    fun bound(id: String, coordinate: SourceCoordinate, value: BigFraction, upper: Boolean) {
        val sign = if (upper) BigFraction.ONE else BigFraction.MINUS_ONE
        premises[id] = SourcePremise(SourceInequality(mapOf(coordinate to sign), value * sign))
    }
    for (id in 0 until numBoolVars) {
        if (cancellation()) return null
        val coordinate = SourceCoordinate(SourceCoordinate.Kind.Boolean, id)
        bound("bool/$id/lower", coordinate, BigFraction.ZERO, false)
        bound("bool/$id/upper", coordinate, BigFraction.ONE, true)
        premises["bool/$id/false"] = SourcePremise(
            SourceInequality(mapOf(coordinate to BigFraction.ONE), BigFraction.ZERO), mapOf(id to false),
        )
        premises["bool/$id/true"] = SourcePremise(
            SourceInequality(mapOf(coordinate to BigFraction.MINUS_ONE), BigFraction.MINUS_ONE), mapOf(id to true),
        )
    }
    for (id in 0 until numIntVars) {
        if (cancellation()) return null
        val coordinate = SourceCoordinate(SourceCoordinate.Kind.Integer, id)
        if (intBounds.hasLower(id)) bound("int/$id/lower", coordinate, BigFraction.ofLong(intBounds.lower(id)), false)
        if (intBounds.hasUpper(id)) bound("int/$id/upper", coordinate, BigFraction.ofLong(intBounds.upper(id)), true)
    }
    for (id in 0 until numRealVars) {
        if (cancellation()) return null
        val coordinate = SourceCoordinate(SourceCoordinate.Kind.Real, id)
        BigFraction.ofDouble(realLower[id])?.let { bound("real/$id/lower", coordinate, it, false) }
        BigFraction.ofDouble(realUpper[id])?.let { bound("real/$id/upper", coordinate, it, true) }
    }
    for ((factorId, factor) in factors.withIndex()) {
        for ((rowId, row) in factor.impliedLinearRows.withIndex()) {
            if (cancellation()) return null
            val booleans = linkedSetOf<Int>()
            if (row.activator != LinearRow.ALWAYS) booleans += row.activator
            for (term in 0 until row.size) {
                if (Term.isBool(row.ref(term))) booleans += Lit.variable(Term.lit(row.ref(term)))
            }
            if (booleans.size > 8) continue
            val ids = booleans.sorted()
            for (bits in 0 until (1 shl ids.size)) {
                if (cancellation()) return null
                val guards = ids.withIndex().associate { (bit, id) -> id to (bits and (1 shl bit) != 0) }
                val form = row.exactComparison(numRealVars,
                    row.activator == LinearRow.ALWAYS || guards.getValue(row.activator), guards::getValue)
                if (form.op == LinearOp.NE) continue
                val exact = mutableListOf<ExactRationalInequality>()
                form.rowsInto(exact)
                for ((side, inequality) in exact.withIndex()) {
                    val coefficients = inequality.columns.indices.associate { position ->
                        val column = inequality.columns[position]
                        val coordinate = if (column < numRealVars) SourceCoordinate(SourceCoordinate.Kind.Real, column)
                            else SourceCoordinate(SourceCoordinate.Kind.Integer, column - numRealVars)
                        coordinate to inequality.coefficients[position]
                    }
                    premises["factor/$factorId/row/$rowId/booleans/$bits/side/$side"] = SourcePremise(
                        SourceInequality(coefficients, inequality.rhs, inequality.strict), guards,
                    )
                }
            }
        }
    }
    if ((objective == null) != (scope.objective == null)) return null
    val coefficients = linkedMapOf<SourceCoordinate, BigFraction>()
    objective?.boolWeights?.forEachIndexed { id, value ->
        coefficients[SourceCoordinate(SourceCoordinate.Kind.Boolean, id)] = BigFraction.ofLong(value)
    }
    objective?.intCoefficients?.forEachIndexed { id, value ->
        coefficients[SourceCoordinate(SourceCoordinate.Kind.Integer, id)] = BigFraction.ofLong(value)
    }
    objective?.realCoefficients?.forEachIndexed { id, value ->
        coefficients[SourceCoordinate(SourceCoordinate.Kind.Real, id)] = BigFraction.ofDouble(value) ?: return null
    }
    if (coefficients.keys.any { it !in coordinates }) return null
    val restrictionChecker = LinearSourceSemanticsChecker(coordinates, premises.values.toList(), coefficients,
        BigFraction.ofLong(objective?.constant ?: 0L))
    val source = this
    val checker = object : SourceSemanticsChecker {
        override fun witness(sample: Sample, scope: SourceScope, cancellation: Cancellation): SourceEvidenceCheck {
            val pins = restrictionChecker.witness(sample, scope, cancellation)
            if (pins != SourceEvidenceCheck.Checked) return pins
            if (source.factors.any {
                    it.variables.reals.isEmpty() &&
                        it.executionCapabilities().propagationCheck != AssignmentCheckCapability.EXACT
                }
            ) return SourceEvidenceCheck.Unavailable("original finite factor has no complete source witness checker")
            if (cancellation()) return SourceEvidenceCheck.Unavailable("original-source check cancelled")
            return when (val checked = verifyOpenWitness(source, sample, cancellation)) {
                is Verification.Accepted -> if (cancellation()) {
                    SourceEvidenceCheck.Unavailable("original-source check cancelled")
                } else SourceEvidenceCheck.Checked
                is Verification.Rejected -> SourceEvidenceCheck.Rejected(checked.reason)
                is Verification.Indeterminate -> SourceEvidenceCheck.Unavailable(checked.reason)
            }
        }

        override fun objectiveEquals(
            sample: Sample, value: SourceValue, scope: SourceScope, cancellation: Cancellation,
        ): SourceEvidenceCheck = restrictionChecker.objectiveEquals(sample, value, scope, cancellation)

        override fun ray(claim: SourceClaim.Ray, scope: SourceScope, cancellation: Cancellation): SourceEvidenceCheck =
            SourceEvidenceCheck.Unavailable("original source recession derivation unavailable")
    }
    return SourceProofContext(scope, coordinates, premises, coefficients,
        BigFraction.ofLong(objective?.constant ?: 0L), checker)
}
