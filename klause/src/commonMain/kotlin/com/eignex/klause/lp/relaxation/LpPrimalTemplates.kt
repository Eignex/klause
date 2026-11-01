@file:OptIn(ExperimentalAtomicApi::class)

package com.eignex.klause.lp.relaxation

import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.IntegerConstants
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.values
import com.eignex.klause.lp.rootDomainOf
import com.eignex.klause.lp.statesLowerBound
import com.eignex.klause.lp.statesUpperBound
import com.eignex.klause.solver.PreparedFactorProjections
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.CheckedLongOverflowException
import com.eignex.klause.util.IntHashSet
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

internal class LpPrimalDemand(val scope: LpEmissionScope, val paidUnits: Long) : LpPreparationReuse

internal class LpPrimalTemplates private constructor(
    val scope: LpEmissionScope,
    val storageUnits: Long,
    private val relaxation: LpRelaxation,
    problem: Problem,
    domains: RelaxationDomains,
    private val holes: IntArray,
    private val rootValues: Array<LongArray>,
    private val liveValues: Array<LongArray>,
) : LpPreparationReuse {
    private class PaidUnits(val units: Long)
    private val noDemand = PaidUnits(0L)
    private val replacementDemand = AtomicReference(noDemand)
    private val settings = problem.settings
    private val factors = problem.factors.copyOf()
    private val lower = LongArray(problem.numIntVars) { problem.rootDomainOf(it).min }
    private val upper = LongArray(problem.numIntVars) { problem.rootDomainOf(it).max }
    private val sides = ByteArray(problem.numIntVars) { statedSides(problem, it) }
    private val liveLower = LongArray(problem.numIntVars) { domains.intDomain(it).min }
    private val liveUpper = LongArray(problem.numIntVars) { domains.intDomain(it).max }
    private val booleans = ByteArray(problem.numBoolVars) { pin(domains.boolValue(it)) }
    private val honorsOpenSides = domains.honorsOpenSides
    private val columns = IntArray(relaxation.model.n) { it }

    fun replacementWork(completed: Long, token: Cancellation): Long {
        token.charge(1L)
        if (token()) return 0L
        val previous = replacementDemand.load()
        val earned = previous.units + completed.coerceIn(0L, LpEmissionTemplates.MAX_UNITS)
        val next = PaidUnits(earned.coerceAtMost(LpEmissionTemplates.MAX_UNITS))
        return if (!token() && replacementDemand.compareAndSet(previous, next)) previous.units else 0L
    }

    fun find(
        selection: PreparedFactorProjections,
        objective: LinearObjective?,
        domains: RelaxationDomains,
        token: Cancellation,
    ): LpRelaxation? {
        val problem = selection.problem
        fun visit(units: Long = 1L): Boolean {
            token.charge(units)
            return !token()
        }
        if (!visit() || !scope.matches(selection.lpEmissionNamespace, problem) ||
            settings != problem.settings || honorsOpenSides != domains.honorsOpenSides ||
            factors.size != problem.numFactors || !objectiveFits(problem, objective)
        ) return null
        for (index in factors.indices) {
            if (!visit() || factors[index] !== problem.factors[index]) return null
        }
        for (variable in lower.indices) {
            if (!visit(2L)) return null
            val root = problem.rootDomainOf(variable)
            val live = domains.intDomain(variable)
            if (root.min != lower[variable] || root.max != upper[variable] ||
                statedSides(problem, variable) != sides[variable] ||
                live.min != liveLower[variable] || live.max != liveUpper[variable]
            ) return null
            if (intCost(objective, variable) != 0L && relaxation.intColOf[variable] < 0) return null
        }
        for (variable in booleans.indices) {
            if (!visit() || pin(domains.boolValue(variable)) != booleans[variable] ||
                (boolCost(objective, variable) != 0L && relaxation.boolColOf[variable] < 0)
            ) return null
        }
        for (index in holes.indices) {
            val variable = holes[index]
            if (!matchesValues(problem.rootDomainOf(variable), rootValues[index], token) ||
                !matchesValues(domains.intDomain(variable), liveValues[index], token)
            ) return null
        }
        val previous = relaxation.model
        var changed = false
        for (column in columns) {
            if (!visit()) return null
            if (cost(objective, column) != previous.cost[column]) changed = true
        }
        val model = if (changed) {
            if (!visit(previous.n.toLong() * 2L + previous.numVars)) return null
            try {
                previous.withRowObjective(columns, LongArray(previous.n) { cost(objective, it) })
            } catch (_: CheckedLongOverflowException) {
                return null
            }
        } else previous
        if (!visit()) return null
        val demand = replacementDemand.load()
        if (demand.units != 0L) {
            if (!visit()) return null
            replacementDemand.compareAndSet(demand, noDemand)
        }
        if (token()) return null
        val constant = objective?.constant ?: 0L
        return if (model === previous && constant == relaxation.objectiveConstant) relaxation else {
            relaxation.withModel(model, sources = null, reboundObjectiveConstant = constant,
                reusedPrimalDefinition = true).takeUnless { token() }
        }
    }

    private fun cost(objective: LinearObjective?, column: Int): Long =
        if (relaxation.colIsBool[column]) boolCost(objective, relaxation.colVarId[column])
        else intCost(objective, relaxation.colVarId[column])

    companion object {
        const val DEMAND_UNITS = 16L

        fun minimumUnits(problem: Problem, relaxation: LpRelaxation): Long = relaxation.model.let {
            128L + it.n * 32L + it.m * 16L + it.csc.rowIdx.size * 2L +
                problem.numIntVars * 6L + problem.numBoolVars * 2L + problem.numFactors
        }

        fun captureWork(problem: Problem, relaxation: LpRelaxation): Long =
            2L + problem.numFactors * 2L + problem.numIntVars * 5L + problem.numBoolVars +
                relaxation.model.n * 2L + relaxation.model.m

        fun capture(
            selection: PreparedFactorProjections,
            relaxation: LpRelaxation,
            objective: LinearObjective?,
            domains: RelaxationDomains,
            token: Cancellation,
            workLimit: Long,
        ): LpPrimalTemplates? {
            val problem = selection.problem
            val model = relaxation.model
            var units = minimumUnits(problem, relaxation)
            var work = 0L
            fun visit(count: Long = 1L): Boolean {
                if (count > workLimit - work) return false
                work += count
                token.charge(count)
                return !token()
            }
            if (!visit() || units > LpEmissionTemplates.MAX_UNITS || model.n == 0 ||
                model.doubleView != null || model.exactState != null || problem.numRealVars != 0 ||
                !selection.builtInRelaxation || !objectiveFits(problem, objective) ||
                relaxation.circuitArcs.isNotEmpty() || relaxation.gatedRows.isNotEmpty() ||
                relaxation.realUpperRows.isNotEmpty()
            ) return null
            val holeVariables = IntHashSet()
            for (factor in problem.factors) {
                if (!visit() || !eligible(factor)) return null
                if (factor is ReifiedLinear && factor.op == LinearOp.EQ && factor.intVars.size == 1) {
                    holeVariables.add(factor.intVars[0])
                }
            }
            for (column in 0 until model.n) {
                if (!visit() || relaxation.colVarId[column] < 0 || relaxation.colRealId[column] >= 0 ||
                    relaxation.colReq[column] != null || relaxation.colPresence[column] != null ||
                    model.colContinuous[column]
                ) return null
            }
            for (row in 0 until model.m) {
                if (!visit()) return null
                val premise = model.rowPremises[row] ?: continue
                units += 8L + premise.vars.size * 4L + premise.boolLits.size * 2L
                if (units > LpEmissionTemplates.MAX_UNITS ||
                    !visit(premise.vars.size.toLong() + premise.boolLits.size)
                ) return null
            }
            val holes = holeVariables.toIntArray()
            for (variable in holes) {
                if (!visit(2L)) return null
                val rootCount = problem.rootDomainOf(variable).valueCount
                val liveCount = domains.intDomain(variable).valueCount
                if (rootCount > LpEmissionTemplates.MAX_UNITS || liveCount > LpEmissionTemplates.MAX_UNITS) return null
                units += 24L + rootCount + liveCount
                if (units > LpEmissionTemplates.MAX_UNITS) return null
            }
            fun snapshot(domain: IntDomain): LongArray? {
                val values = domain.values
                val result = LongArray(values.size)
                for (index in result.indices) {
                    if (!visit()) return null
                    result[index] = values.valueAt(index)
                }
                return result
            }
            val roots = ArrayList<LongArray>(holes.size)
            val live = ArrayList<LongArray>(holes.size)
            for (variable in holes) {
                roots.add(snapshot(problem.rootDomainOf(variable)) ?: return null)
                live.add(snapshot(domains.intDomain(variable)) ?: return null)
            }
            if (!visit(1L + problem.numFactors + problem.numIntVars * 5L + problem.numBoolVars + model.n)) return null
            val detached = relaxation.withModel(model, sources = null, reusedPrimalDefinition = true)
            return LpPrimalTemplates(LpEmissionScope(selection.lpEmissionNamespace, problem), units,
                detached, problem, domains, holes, roots.toTypedArray(), live.toTypedArray()).takeUnless { token() }
        }

        internal fun eligible(factor: Factor): Boolean = when (factor) {
            is Linear, is ReifiedLinear, is Clause, is Cardinality, is PseudoBoolean -> true
            is ExactLinear -> !factor.strict && factor.activator == LinearRow.ALWAYS &&
                factor.variables.reals.isEmpty() && factor.constants is IntegerConstants
            else -> false
        }

        private fun objectiveFits(problem: Problem, objective: LinearObjective?): Boolean = objective == null ||
            (objective.intCoefficientValues.size <= problem.numIntVars &&
                objective.boolWeightValues.size <= problem.numBoolVars && objective.realCoefficientValues.isEmpty())

        private fun intCost(objective: LinearObjective?, variable: Int): Long =
            objective?.intCoefficientValues?.getOrElse(variable) { 0L } ?: 0L

        private fun boolCost(objective: LinearObjective?, variable: Int): Long =
            objective?.boolWeightValues?.getOrElse(variable) { 0L } ?: 0L

        private fun pin(value: Boolean?): Byte = when (value) { true -> 1; false -> -1; null -> 0 }

        private fun statedSides(problem: Problem, variable: Int): Byte =
            ((if (problem.statesLowerBound(variable)) 1 else 0) +
                (if (problem.statesUpperBound(variable)) 2 else 0)).toByte()

        private fun matchesValues(domain: IntDomain, expected: LongArray, token: Cancellation): Boolean {
            token.charge(1L)
            if (token() || domain.valueCount != expected.size.toLong()) return false
            val values = domain.values
            for (index in expected.indices) {
                token.charge(1L)
                if (token() || values.valueAt(index) != expected[index]) return false
            }
            return true
        }
    }
}
