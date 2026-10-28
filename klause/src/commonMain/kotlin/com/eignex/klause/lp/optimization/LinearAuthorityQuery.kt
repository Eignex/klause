package com.eignex.klause.lp.optimization

import com.eignex.klause.ir.ObjectiveSense
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.solver.evidence.IntegerPinStatus
import com.eignex.klause.solver.evidence.SourceClaim
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.evidence.SourceDerivation
import com.eignex.klause.solver.evidence.SourceEvidence
import com.eignex.klause.solver.evidence.SourceEvidenceCheck
import com.eignex.klause.solver.evidence.SourceEvidenceVerifier
import com.eignex.klause.solver.evidence.SourceInequality
import com.eignex.klause.solver.evidence.SourceIntegerRowAssumption
import com.eignex.klause.solver.evidence.SourceProofContext
import com.eignex.klause.solver.evidence.integerPinBounds
import com.eignex.klause.util.Cancellation
internal class LinearAuthorityQuery private constructor(
    private val source: SourceProofContext,
    val coordinates: List<SourceCoordinate>,
    val cited: List<Pair<SourceInequality, SourceDerivation>>,
    val rows: List<ExactRationalInequality>,
    val inactive: Set<Int>,
    val unconditionalRows: Boolean,
    val costs: List<BigFraction>,
    val constant: BigFraction,
    val sign: BigFraction,
    val cuts: Boolean,
    val activeCuts: Int,
    val activeMixedCuts: Int,
    val cutMask: Set<Int>?,
    val checkedCuts: Set<Int>,
    val installedCuts: Set<Int>,
) {
    val context: SourceProofContext = source.withLinearAuthority()
    val closureRows: List<ExactRationalInequality> = rows.map {
        ExactRationalInequality(it.columns, it.coefficients, it.rhs)
    }

    fun matches(other: SourceProofContext, cuts: Boolean = false, cutMask: Set<Int>? = null): Boolean =
        this.cuts == cuts && this.cutMask == cutMask && (source === other ||
        (source.scope == other.scope &&
            source.coordinates == other.coordinates &&
            source.premises == other.premises &&
            source.affineObjectiveAvailable == other.affineObjectiveAvailable &&
            source.objective == other.objective && source.objectiveConstant == other.objectiveConstant &&
            source.semantics == other.semantics && source.semanticsChecker === other.semanticsChecker))

    companion object {
        fun prepare(
            source: SourceProofContext, template: LinearAuthorityTemplate?, cancellation: Cancellation,
            maximumCutProofSteps: Int = 100_000,
            onCutWork: (Long) -> Unit = {},
            cutMask: Set<Int>? = null,
            consequences: List<SourceEvidence> = emptyList(),
        ): LinearAuthorityQuery? {
            val coordinates = template?.coordinates ?: source.numericCoordinates()
            val numeric = template?.numeric ?: source.numericContext()
            val premises = numeric.premises
            val integerBounds = if (premises.values.any { it.integerPins.isNotEmpty() }) {
                numeric.integerPinBounds(source.scope.condition, cancellation) ?: return null
            } else null
            val cited = ArrayList<Pair<SourceInequality, SourceDerivation>>()
            val inactive = HashSet<Int>()
            var unconditional = true
            for ((id, premise) in premises) {
                if (cancellation()) return null
                val guards = premise.guards
                val pins = premise.integerPins
                if (guards.isNotEmpty() || pins.isNotEmpty()) unconditional = false
                val active = guards.all { (bool, value) -> source.scope.condition.bools[bool] == value } &&
                    (pins.isEmpty() || integerBounds?.status(pins) == IntegerPinStatus.Established)
                if (active || template != null) {
                    if (!active) inactive += cited.size
                    cited += premise.inequality to SourceDerivation.Premise(id)
                }
            }
            var activeCuts = 0
            var activeMixedCuts = 0
            var cutVerificationWork = 0L
            val cuts = template?.cuts?.rows.orEmpty()
            val active = cuts.indices.filter { id -> cuts[id].owners.none { it in inactive } }
            val checks = if (active.isEmpty()) emptyList() else {
                SourceEvidenceVerifier(numeric.atScope(source.scope), maximumSteps = maximumCutProofSteps)
                    .verifyAll(active.map { id ->
                        val cut = cuts[id]
                        SourceEvidence(source.scope, SourceClaim.Consequence(cut.inequality), cut.derivation)
                    }, Cancellation {
                        cutVerificationWork++
                        cancellation()
                    })
            }
            onCutWork(cutVerificationWork)
            val accepted = active.indices.filter { checks[it] == SourceEvidenceCheck.Checked }
                .mapTo(HashSet()) { active[it] }
            for ((id, cut) in cuts.withIndex()) {
                if (cancellation()) return null
                if (cutMask != null && id !in cutMask) continue
                if (id in accepted && (cutMask == null || id in cutMask)) {
                    activeCuts++
                    if (cut.mixed) activeMixedCuts++
                } else inactive += cited.size
                cited += cut.inequality to cut.derivation
            }
            for (evidence in consequences) {
                if (cancellation()) return null
                require(evidence.scope == source.scope)
                val claim = evidence.claim as SourceClaim.Consequence
                cited += claim.inequality to SourceDerivation.Import(evidence)
            }
            val condition = source.scope.condition
            for (row in condition.integerRows) {
                if (cancellation()) return null
                cited += row to SourceIntegerRowAssumption(row)
            }
            for ((upper, bounds) in listOf(false to condition.lower, true to condition.upper)) {
                val sign = if (upper) BigFraction.ONE else BigFraction.MINUS_ONE
                for ((id, value) in bounds) cited += SourceInequality(mapOf(id to sign), value * sign) to
                    SourceDerivation.Assumption(id, upper)
            }
            val indices = template?.indices ?: coordinates.withIndex().associate { it.value to it.index }
            val rows = if (template == null) cited.map { it.first.row(indices) }
                else template.rows + cited.drop(template.rows.size).map { it.first.row(indices) }
            val sign = if (source.scope.objective?.sense == ObjectiveSense.MINIMIZE) BigFraction.ONE
                else BigFraction.MINUS_ONE
            val costs = coordinates.map { sign * (numeric.objective[it] ?: BigFraction.ZERO) }
            return LinearAuthorityQuery(source, coordinates, cited, rows, inactive, unconditional && inactive.isEmpty(),
                costs, numeric.objectiveConstant * sign, sign, template?.cuts != null, activeCuts, activeMixedCuts,
                cutMask?.toSet(), accepted.toSet(), accepted.filterTo(HashSet()) { cutMask == null || it in cutMask })
        }
    }
}
