@file:OptIn(ExperimentalAtomicApi::class)

package com.eignex.klause.lp.relaxation

import com.eignex.klause.factor.arithmetic.ExactLinear
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntegerConstants
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.engine.ExactLpModel
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.lp.rootDomainOf
import com.eignex.klause.lp.statesLowerBound
import com.eignex.klause.lp.statesUpperBound
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.solver.PreparedFactorProjections
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

internal sealed interface LpPreparationReuse

internal class LpEmissionSourceDefinition(
    private val preparationWork: Long = 1L,
    private val storageLimit: Long = 0L,
) {
    private val completed = AtomicReference<ExactLpModel?>(null)

    fun read(cancellation: Cancellation, onWork: (Long) -> Unit): ExactLpModel? {
        cancellation.charge(1L)
        val current = completed.load()
        onWork(1L)
        return current.takeUnless { cancellation() }
    }

    fun publish(model: ExactLpModel, cancellation: Cancellation, onWork: (Long) -> Unit): Boolean {
        try {
            cancellation.charge(preparationWork)
            if (cancellation()) return false
            // Complete the layout memo before the immutable source model crosses consumer boundaries.
            if (model.layoutStorage.total !in 0L..storageLimit) return false
        } finally {
            onWork(preparationWork)
        }
        return !cancellation() && completed.compareAndSet(null, model)
    }
}

internal class LpEmissionScope(private val namespace: Any, problem: Problem) {
    private val semantics = problem.semantics
    private val finite = problem is BakedProblem
    private val integers = problem.numIntVars
    private val booleans = problem.numBoolVars
    private val reals = problem.numRealVars

    fun matches(namespace: Any, problem: Problem): Boolean = this.namespace === namespace &&
        semantics == problem.semantics && finite == (problem is BakedProblem) &&
        integers == problem.numIntVars && booleans == problem.numBoolVars && reals == problem.numRealVars
}

internal class LpEmissionDemand(
    val scope: LpEmissionScope,
    val attempt: Any,
    val paidUnits: Long,
) : LpPreparationReuse

internal class LpObservedMetadata(
    val metadata: LpPreparationTemplates,
    val demand: LpEmissionDemand,
) : LpPreparationReuse {
    companion object {
        const val STORAGE_UNITS = 16L
    }
}

internal class LpEmissionTemplates private constructor(
    val scope: LpEmissionScope,
    capacity: Int,
) : LpPreparationReuse {
    private class Usage(val units: Long)
    private val slots = Array(capacity) { AtomicReference<Entry?>(null) }
    private val usage = AtomicReference(Usage(indexUnits(capacity)))
    val storageUnits: Long get() = usage.load().units

    private class Entry(
        val factorId: Int,
        val factor: Factor,
        val emission: LpEmission,
        val storageUnits: Long,
        problem: Problem,
    ) {
        private val integers = factor.intVars
        private val lower = LongArray(integers.size) { problem.rootDomainOf(integers[it]).min }
        private val upper = LongArray(integers.size) { problem.rootDomainOf(integers[it]).max }
        private val sides = ByteArray(integers.size) { statedSides(problem, integers[it]) }

        fun matches(problem: Problem, objective: LinearObjective?, visit: (Long) -> Boolean): Boolean {
            for (index in integers.indices) {
                if (!visit(1L)) return false
                val variable = integers[index]
                val domain = problem.rootDomainOf(variable)
                if (domain.min != lower[index] || domain.max != upper[index] ||
                    statedSides(problem, variable) != sides[index]
                ) return false
            }
            val relaxation = emission.relaxation
            for (column in 0 until relaxation.model.n) {
                if (!visit(1L)) return false
                val variable = relaxation.colVarId[column]
                val cost = if (relaxation.colIsBool[column]) {
                    objective?.boolWeightValues?.getOrElse(variable) { 0L } ?: 0L
                } else objective?.intCoefficientValues?.getOrElse(variable) { 0L } ?: 0L
                if (cost != relaxation.model.cost[column]) return false
            }
            return true
        }
    }

    fun find(
        selection: PreparedFactorProjections,
        factorId: Int,
        objective: LinearObjective?,
        domains: RelaxationDomains,
        cancellation: Cancellation,
        onWork: (Long) -> Unit,
    ): LpEmission? {
        val problem = selection.problem
        var work = 0L
        val found = try {
            fun visit(units: Long): Boolean {
                work += units
                cancellation.charge(units)
                return !cancellation()
            }
            if (!visit(1L) || !scope.matches(selection.lpEmissionNamespace, problem)) return null
            val entry = slots[factorId and (slots.size - 1)].load() ?: return null
            if (entry.factorId != factorId || entry.factor !== problem.factors[factorId] ||
                !visit(entry.emission.dependencies.validationUnits) ||
                !entry.emission.dependencies.matchesEndpoints(domains) || !entry.matches(problem, objective, ::visit)
            ) return null
            entry.emission
        } finally {
            onWork(work)
        }
        if (cancellation()) return null
        val constant = objective?.constant ?: 0L
        return if (found.relaxation.objectiveConstant == constant) found else LpEmission(
            found.region, found.relaxation.withModel(found.relaxation.model, reboundObjectiveConstant = constant),
            found.dependencies, found.sourceDefinition,
        )
    }

    fun offer(
        problem: Problem,
        emission: LpEmission,
        minimumUnits: Long,
        cancellation: Cancellation,
        onWork: (Long) -> Unit,
    ): LpEmission? {
        val factorId = emission.region.index
        val slot = slots[factorId and (slots.size - 1)]
        val previous = slot.load()
        // Collisions retain an admitted coordinate instead of thrashing during ordered source scans.
        if (previous != null && previous.factorId != factorId) return null
        var delta = 0L
        var reserved = false
        var published = false
        try {
            var work = 0L
            val next = try {
                fun visit(count: Long): Boolean {
                    work += count
                    cancellation.charge(count)
                    return !cancellation()
                }
                val units = validatedExtent(emission, minimumUnits, ::visit) ?: return null
                if (!visit(1L + problem.factors[factorId].intVars.size * 3L +
                        emission.dependencies.validationUnits * 4L)
                ) return null
                delta = units - (previous?.storageUnits ?: 0L)
                if (delta > 0L) {
                    if (!reserve(delta)) return null
                    reserved = true
                }
                val captured = LpEmission(
                    emission.region, emission.relaxation, emission.dependencies.endpointSnapshot(),
                    emission.relaxation.model.let {
                        LpEmissionSourceDefinition(1L + it.n + it.m + it.csc.rowIdx.size, units - minimumUnits)
                    },
                )
                Entry(factorId, problem.factors[factorId], captured, units, problem)
            } finally {
                onWork(work)
            }
            if (cancellation()) return null
            published = slot.compareAndSet(previous, next)
            if (published && delta < 0L) release(-delta)
            return next.emission.takeIf { published }
        } finally {
            if (reserved && !published) release(delta)
        }
    }

    private fun reserve(units: Long): Boolean {
        val previous = usage.load()
        return units <= MAX_UNITS - previous.units &&
            usage.compareAndSet(previous, Usage(previous.units + units))
    }

    private fun release(units: Long) {
        while (true) {
            val previous = usage.load()
            check(previous.units >= units)
            if (usage.compareAndSet(previous, Usage(previous.units - units))) return
        }
    }

    companion object {
        const val MAX_UNITS = 262_144L
        private const val MAX_ENTRY_UNITS = 8_192L
        private const val MIN_ENTRY_UNITS = 192L

        private fun statedSides(problem: Problem, variable: Int): Byte =
            ((if (problem.statesLowerBound(variable)) 1 else 0) +
                (if (problem.statesUpperBound(variable)) 2 else 0)).toByte()

        fun capacity(problem: Problem): Int {
            var capacity = 16
            while (capacity < problem.numFactors && capacity < 1_024) capacity *= 2
            return capacity
        }

        fun indexUnits(capacity: Int): Long = capacity * 2L + 16L

        fun create(
            scope: LpEmissionScope,
            capacity: Int,
            cancellation: Cancellation,
            onWork: (Long) -> Unit,
        ): LpEmissionTemplates? {
            val work = indexUnits(capacity)
            val prepared = try {
                cancellation.charge(work)
                if (cancellation()) return null
                LpEmissionTemplates(scope, capacity)
            } finally {
                onWork(work)
            }
            return prepared.takeUnless { cancellation() }
        }

        fun eligible(factor: Factor): Boolean = when (factor) {
            is Linear -> factor.intVars.size >= 2
            is ExactLinear -> !factor.strict && factor.activator == LinearRow.ALWAYS &&
                factor.variables.reals.isEmpty() && factor.constants is IntegerConstants && factor.intVars.size >= 2
            is ReifiedLinear -> !(factor.op == LinearOp.EQ && factor.intVars.size == 1)
            else -> false
        }

        fun extent(problem: Problem, emission: LpEmission, visit: (Long) -> Boolean): Long? {
            if (!visit(1L)) return null
            val relaxation = emission.relaxation
            val model = relaxation.model
            val factor = problem.factors[emission.region.index]
            if (model.n == 0 || model.m == 0 || model.doubleView != null || model.exactState != null ||
                relaxation.preparationTemplates != null || relaxation.sourceMap != null || !eligible(factor)
            ) return null
            val units = 64L + model.n * 40L + model.m * 32L + model.csc.rowIdx.size * 4L +
                factor.intVars.size * 3L + emission.dependencies.validationUnits * 8L
            if (units < MIN_ENTRY_UNITS || units + sourceDefinitionUnits(model) > MAX_ENTRY_UNITS) return null
            return units
        }

        private fun validatedExtent(emission: LpEmission, minimumUnits: Long, visit: (Long) -> Boolean): Long? {
            val relaxation = emission.relaxation
            val model = relaxation.model
            if (!visit(model.n.toLong() + model.m)) return null
            for (column in 0 until model.n) {
                if (relaxation.colVarId[column] < 0 || relaxation.colRealId[column] >= 0 ||
                    relaxation.colReq[column] != null || relaxation.colPresence[column] != null ||
                    model.colContinuous[column]
                ) return null
            }
            var units = minimumUnits + sourceDefinitionUnits(model)
            for (premise in model.rowPremises) {
                units += (premise?.vars?.size ?: 0) * 8L + (premise?.boolLits?.size ?: 0) * 2L
                if (units > MAX_ENTRY_UNITS) return null
            }
            return units
        }

        private fun sourceDefinitionUnits(model: LpModel): Long =
            64L + model.numVars * 24L + model.n * 3L + model.m * 12L + model.csc.rowIdx.size * 6L
    }
}
