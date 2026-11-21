package com.eignex.klause.portfolio

import com.eignex.klause.ir.Lit
import com.eignex.klause.meta.alns.DestroyOperator
import com.eignex.klause.meta.alns.FreedVars
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.SharedClause
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.ExecutionControl
import kotlin.random.Random

internal class PoolClauseRelatedDestroy(
    private val pool: SharedClausePool,
    private val meter: SharingMeter? = null,
    private val fallback: DestroyOperator = DestroyOperator.AdjacencyRelated,
) : DestroyOperator {
    override fun destroy(rng: Random, problem: BakedProblem, incumbent: Sample,
        objective: LinearObjective, fraction: Double,
    ): FreedVars = destroy(rng, problem, incumbent, objective, fraction, Assumptions.None, ExecutionControl.Never)

    override fun destroy(rng: Random, problem: BakedProblem, incumbent: Sample,
        objective: LinearObjective, fraction: Double, assumptions: Assumptions, cancellation: ExecutionControl,
    ): FreedVars {
        val original = fallback.destroy(rng, problem, incumbent, objective, fraction, assumptions, cancellation)
        val size = original.bools.size.toLong() + original.ints.size
        if (size !in 2L..MAX_COORDINATES.toLong() ||
            problem.numBoolVars.toLong() + problem.numIntVars > Int.MAX_VALUE) return original
        val budget = Budget(cancellation)
        try {
            val drained = pool.drainSinceFunded(0L, budget::cancelled, { budget.pay(1L) },
                maxWork = MAX_WORK, maxScratchEntries = MAX_COORDINATES * 2)
            if (drained == null) {
                meter?.clauseCount("neighborhoodDeclined", 1L)
                return original
            }
            if (drained.clauses.isEmpty()) return original
            budget.pay(MAX_SUPPORT * 2L)
            val candidateSupport = IntArray(MAX_SUPPORT)
            val selected = IntArray(MAX_SUPPORT)
            var selectedSize = 0
            var selectedOrigin = SharedClausePool.NO_ORIGIN
            var eligible = 0
            for (i in drained.clauses.indices) {
                budget.pay(1L)
                val count = support(drained.clauses[i], problem, assumptions, size.toInt(), candidateSupport, budget)
                if (count == 0) continue
                eligible++
                if (eligible == 1 || rng.nextInt(eligible) == 0) {
                    budget.pay(count.toLong())
                    candidateSupport.copyInto(selected, endIndex = count)
                    selectedSize = count
                    selectedOrigin = drained.origins[i]
                }
            }
            if (selectedSize == 0) return original
            budget.pay(size + MAX_SUPPORT)
            val coordinates = IntArray(size.toInt())
            val present = BooleanArray(MAX_SUPPORT)
            budget.pay(selectedSize.toLong())
            selected.copyInto(coordinates, endIndex = selectedSize)
            var next = selectedSize
            fun append(coordinate: Int) {
                for (i in 0 until selectedSize) {
                    budget.pay(1L)
                    if (selected[i] == coordinate) {
                        present[i] = true
                        return
                    }
                }
                if (next < coordinates.size) coordinates[next++] = coordinate
            }
            for (id in original.bools) {
                budget.pay(1L)
                if (id !in 0 until problem.numBoolVars) return original
                append(id)
            }
            for (id in original.ints) {
                budget.pay(1L)
                if (id !in 0 until problem.numIntVars) return original
                append(problem.numBoolVars + id)
            }
            if (next != coordinates.size) return original
            budget.pay(selectedSize.toLong())
            val changed = (0 until selectedSize).any { !present[it] }
            val result = if (changed) {
                // Reservation covers the bounded count, allocation and copy passes before any result escapes.
                budget.pay(size * 3L)
                val boolCount = coordinates.count { it < problem.numBoolVars }
                val bools = IntArray(boolCount)
                val ints = IntArray(coordinates.size - boolCount)
                var bi = 0
                var ii = 0
                for (id in coordinates) {
                    if (id < problem.numBoolVars) bools[bi++] = id else ints[ii++] = id - problem.numBoolVars
                }
                FreedVars(bools, ints)
            } else original
            budget.pay(1L)
            meter?.clauseCount("neighborhoodChoices", 1L)
            if (selectedOrigin >= 0) meter?.clauseCount("neighborhoodOrigin${selectedOrigin}Choices", 1L)
            if (changed) {
                meter?.clauseCount("neighborhoodChanges", 1L)
                if (selectedOrigin >= 0) meter?.clauseCount("neighborhoodOrigin${selectedOrigin}Changes", 1L)
            }
            return result
        } catch (_: Stopped) {
            meter?.clauseCount("neighborhoodDeclined", 1L)
            return original
        } finally {
            meter?.clauseCount("neighborhoodWork", budget.work)
        }
    }

    private fun support(clause: SharedClause, problem: BakedProblem, assumptions: Assumptions,
        size: Int, output: IntArray, budget: Budget,
    ): Int {
        val literals = clause.boolLits.size.toLong() + clause.atomQuads.size / SharedClause.QUAD
        if (literals !in 2L..MAX_SUPPORT.toLong() || clause.atomQuads.size % SharedClause.QUAD != 0) return 0
        var count = 0
        fun add(coordinate: Int) {
            for (i in 0 until count) {
                budget.pay(1L)
                if (output[i] == coordinate) return
            }
            output[count++] = coordinate
        }
        val fixed = problem.rootDeductions as? PropagationResult.Implied
        for (literal in clause.boolLits) {
            // Both pin lookups are binary searches over Int-indexed arrays.
            budget.pay(65L)
            val id = Lit.variable(literal)
            if (literal < 0 || id !in 0 until problem.numBoolVars ||
                assumptions.isFrozenBool(id) || fixed?.boolValueOrNull(id) != null) return 0
            add(id)
        }
        var at = 0
        while (at < clause.atomQuads.size) {
            budget.pay(33L)
            val id = clause.atomQuads[at]
            if (id !in 0L until problem.numIntVars.toLong() || clause.atomQuads[at + 1] !in 0L..2L ||
                clause.atomQuads[at + 3] !in 0L..1L) return 0
            val variable = id.toInt()
            if (assumptions.isFrozenInt(variable) || problem.rootIntDomain(variable).isFixed) return 0
            add(problem.numBoolVars + variable)
            at += SharedClause.QUAD
        }
        return count.takeIf { it in 2..size } ?: 0
    }

    private class Budget(private val stop: ExecutionControl) {
        var work = 0L
            private set

        fun cancelled(): Boolean = work >= MAX_WORK || stop()

        fun pay(units: Long) {
            if (units > MAX_WORK - work || stop()) throw Stopped()
            work += units
            stop.charge(units)
        }
    }

    private class Stopped : RuntimeException()

    private companion object {
        const val MAX_SUPPORT = 16
        const val MAX_COORDINATES = 512
        const val MAX_WORK = 4_096L
    }
}
