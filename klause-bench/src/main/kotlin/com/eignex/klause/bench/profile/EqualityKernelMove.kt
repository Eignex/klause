package com.eignex.klause.bench.profile

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.localsearch.LocalSearchMoveInspection
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.Cancellation
import java.math.BigInteger

internal object EqualityKernelMove {
    private val zero = BigInteger.ZERO
    private val one = BigInteger.ONE

    class Step(val scalar: BigInteger, val move: Move.Compound)
    class Probe(val inspection: LocalSearchMoveInspection, val source: Sample, val accepted: Boolean)
    class Applied(val source: Sample, val value: BigFraction, val inputs: IntArray, val changes: Int)

    fun steps(
        columns: List<Int>,
        rows: List<Array<BigInteger>>,
        direction: Array<BigInteger>,
        current: (Int) -> Long,
        domain: (Int) -> IntDomain,
        allowed: (Int) -> Boolean,
        stop: Cancellation = Cancellation.Never,
    ): List<Step> {
        require(columns.size == direction.size && rows.all { it.size == columns.size })
        if (stop() || rows.any { row ->
                row.indices.fold(zero) { sum, i -> sum + row[i] * direction[i] } != zero
            }
        ) return emptyList()
        val result = ArrayList<Step>(4)
        for (sign in listOf(one, -one)) {
            val g = direction.map { it * sign }
            var maximum: BigInteger? = null
            for ((i, id) in columns.withIndex()) {
                if (stop()) return emptyList()
                if (g[i] == zero) continue
                if (!allowed(id)) return emptyList()
                val value = BigInteger.valueOf(current(id))
                val bounds = domain(id)
                val limit = if (g[i] > zero) {
                    (BigInteger.valueOf(bounds.max) - value) / g[i]
                } else {
                    (value - BigInteger.valueOf(bounds.min)) / -g[i]
                }
                maximum = maximum?.min(limit) ?: limit
            }
            val cap = maximum ?: continue
            for (scalar in listOf(one, cap).distinct().filter { it > zero && it <= cap }) {
                val parts = ArrayList<Move>()
                var admissible = true
                for ((i, id) in columns.withIndex()) {
                    if (stop()) return emptyList()
                    if (g[i] == zero) continue
                    val target = BigInteger.valueOf(current(id)) + scalar * g[i]
                    if (target < BigInteger.valueOf(Long.MIN_VALUE) || target > BigInteger.valueOf(Long.MAX_VALUE) ||
                        target.toLong() !in domain(id)
                    ) {
                        admissible = false
                        break
                    }
                    parts += Move.IntSet(id, target.toLong())
                }
                if (admissible && parts.size >= 2) result += Step(scalar * sign, Move.Compound(parts))
            }
        }
        return if (stop()) emptyList() else result
    }

    fun admit(step: Step, sinkFactory: () -> MoveSink): Move.Compound? {
        val sink = sinkFactory()
        sink.addCompound(step.move.parts)
        val move = sink.list.singleOrNull() as? Move.Compound ?: return null
        return move.takeIf { it.parts == step.move.parts }
    }

    fun honorsPins(assumptions: Assumptions, point: Sample): Boolean {
        var honored = true
        assumptions.forEachInt { id, value -> if (point.intValue(id) != value) honored = false }
        assumptions.forEachBool { id, value -> if (point.boolValue(id) != value) honored = false }
        return honored
    }

    fun probe(
        view: TrajectoryView,
        move: Move.Compound,
        stop: Cancellation,
        verify: (Sample, Cancellation) -> Boolean,
    ): Probe? {
        if (stop()) return null
        val inspection = view.state.inspectMove(move, view.objective)
        if (!honorsPins(view.state.assumptions, inspection.assignment) || stop()) return null
        val source = view.reconstruct(inspection.assignment)
        val accepted = verify(source, stop)
        return if (stop()) null else Probe(inspection, source, accepted)
    }

    fun consume(
        view: TrajectoryView,
        move: Move.Compound,
        center: Sample,
        score: (Sample) -> BigFraction,
        stop: Cancellation,
        verify: (Sample, Cancellation) -> Boolean,
    ): Applied? {
        if (stop()) return null
        try {
            view.state.apply(move)
            val working = view.state.assignment.snapshot()
            if (view.state.cost != 0L || !honorsPins(view.state.assumptions, working) || stop()) return null
            val source = view.reconstruct(working)
            if (!verify(source, stop) || stop()) return null
            return Applied(
                source, score(source), move.parts.map { (it as Move.IntSet).varId }.toIntArray(),
                (0 until center.numIntVars).count { center.intValue(it) != source.intValue(it) },
            )
        } finally {
            check(view.load(center) == null)
        }
    }
}
