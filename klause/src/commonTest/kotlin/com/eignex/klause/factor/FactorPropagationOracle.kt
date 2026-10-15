package com.eignex.klause.factor

import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.values
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchProblem
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.propagate
import com.eignex.klause.solver.Sample
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

object FactorPropagationOracle {

    fun assertSound(problem: Problem, label: String = "factor", satisfies: ((Sample) -> Boolean)? = null) {
        val samples = enumerateSat(problem, label, satisfies)
        val result = problem.propagate()
        if (samples.isEmpty()) {
            assertTrue(
                result is PropagationResult.Unsat,
                "$label: propagate returned $result but brute found zero satisfying assignments",
            )
            return
        }
        assertTrue(
            result is PropagationResult.Implied,
            "$label: propagate returned Unsat but brute found ${samples.size} satisfying assignments",
        )

        result.forEachBool { v, b ->
            for (s in samples) {
                assertEquals(
                    b,
                    s.bools[v],
                    "$label: propagate pinned bool $v=$b but a satisfying assignment has ${s.bools[v]}",
                )
            }
        }
        result.forEachInt { v, value ->
            for (s in samples) {
                assertEquals(
                    value,
                    s.ints[v],
                    "$label: propagate pinned int $v=$value but a satisfying assignment has ${s.ints[v]}",
                )
            }
        }
        result.forEachIntMin { v, lo ->
            for (s in samples) {
                assertTrue(
                    s.ints[v] >= lo,
                    "$label: propagate tightened min of int $v to $lo but a satisfying assignment has ${s.ints[v]}",
                )
            }
        }
        result.forEachIntMax { v, hi ->
            for (s in samples) {
                assertTrue(
                    s.ints[v] <= hi,
                    "$label: propagate tightened max of int $v to $hi but a satisfying assignment has ${s.ints[v]}",
                )
            }
        }
        result.forEachIntHole { v, value ->
            for (s in samples) {
                assertTrue(
                    s.ints[v] != value,
                    "$label: propagate declared hole int $v != $value but a satisfying assignment has ${s.ints[v]}",
                )
            }
        }
    }

    fun sourceAssignments(problem: Problem): Sequence<Sample> {
        require(problem !is BakedProblem) { "Pass the original source Problem, before root domains are folded." }
        require(problem.numRealVars == 0) { "The oracle requires a finite Boolean/integer assignment space." }
        require((0 until problem.numIntVars).all { problem.intBounds.hasLower(it) && problem.intBounds.hasUpper(it) }) {
            "The oracle requires closed declared integer domains, not invented finite boxes."
        }
        val domains = problem.finiteIntDomains()
        val cap = 1L shl 18
        var space = 1L
        fun include(radix: Long) {
            require(radix in 1..cap / space) {
                "Problem too large to brute-enumerate (cap 262 144 assignments). Shrink domains or vars."
            }
            space *= radix
        }
        repeat(problem.numBoolVars) { include(2L) }
        domains.forEach { include(it.valueCount) }
        return (0 until space.toInt()).asSequence().map { ordinal ->
            var remaining = ordinal.toLong()
            val bools = BooleanArray(problem.numBoolVars) {
                val value = remaining % 2L == 1L
                remaining /= 2L
                value
            }
            val ints = LongArray(problem.numIntVars) { v ->
                val domain = domains[v]
                val value = domain.values.valueAt((remaining % domain.valueCount).toInt())
                remaining /= domain.valueCount
                value
            }
            Sample(bools, ints)
        }
    }

    fun assertCompleteChecks(problem: Problem, label: String, satisfies: (Sample) -> Boolean) {
        val session = PropagationSession(problem)
        for (sample in sourceAssignments(problem)) {
            session.popToLevel(0)
            val accepted = sample.bools.indices.all {
                session.pinBool(it, sample.bools[it]) !is PropagationResult.Unsat
            } && sample.ints.indices.all { session.pinInt(it, sample.ints[it]) !is PropagationResult.Unsat }
            assertEquals(satisfies(sample), accepted, "$label: complete CP check")
        }
    }

    fun assertScoring(problem: Problem, label: String, degree: (Sample) -> Int) {
        require(problem.numFactors == 1) { "Scoring oracle requires one source factor." }
        val assignments = sourceAssignments(problem)
        val state = declaredState(problem)
        for (sample in assignments) {
            load(state, sample)
            val expected = degree(sample)
            assertEquals(expected == 0, !state.factors[0].isViolated(state, 0), "$label: assignment check")
            assertEquals(expected, state.factorDegree[0], "$label: recomputed degree")
        }
    }

    fun assertMoveScoring(problem: Problem, label: String, degree: (Sample) -> Int) {
        require(problem.numFactors == 1) { "Move scoring oracle requires one source factor." }
        val assignments = sourceAssignments(problem)
        val state = declaredState(problem)
        val domains = problem.finiteIntDomains()
        for (sample in assignments) {
            for (v in sample.bools.indices) {
                load(state, sample)
                val bools = sample.bools
                bools[v] = !bools[v]
                val after = sample.copy(bools = bools)
                val expected = degree(after)
                val delta = state.factors[0].deltaIfBoolFlipped(state, 0, v)
                assertEquals(expected - degree(sample), delta, "$label: predicted bool $v flip")

                state.apply(Move.BoolFlip(v))

                assertEquals(expected, state.factorDegree[0], "$label: committed bool $v flip")
                assertEquals(expected == 0, !state.factors[0].isViolated(state, 0), "$label: bool move check")
            }
            for (v in sample.ints.indices) {
                domains[v].values.forEach { value ->
                    load(state, sample)
                    val ints = sample.ints
                    ints[v] = value
                    val after = sample.copy(ints = ints)
                    val expected = degree(after)
                    val delta = state.factors[0].deltaIfIntSet(state, 0, v, value)
                    assertEquals(expected - degree(sample), delta, "$label: predicted int $v=$value")

                    state.apply(Move.IntSet(v, value))

                    assertEquals(expected, state.factorDegree[0], "$label: committed int $v=$value")
                    assertEquals(expected == 0, !state.factors[0].isViolated(state, 0), "$label: int move check")
                }
            }
        }
    }

    private fun declaredState(problem: Problem): LocalSearchState {
        val domains = problem.finiteIntDomains()
        // Projection checks use declarations so deductions cannot hide rejected assignments.
        return LocalSearchState(
            LocalSearchModel.open(problem, domains),
            Random(0L),
            projection = LocalSearchProblem(problem, domains),
        )
    }

    private fun load(state: LocalSearchState, sample: Sample) {
        sample.bools.forEachIndexed { v, value -> state.assignment.setBool(v, value) }
        sample.ints.forEachIndexed { v, value -> state.assignment.setInt(v, value) }
        state.recompute()
    }

    private fun enumerateSat(problem: Problem, label: String, satisfies: ((Sample) -> Boolean)?): List<Sample> {
        val samples = ArrayList<Sample>()
        val assignments = sourceAssignments(problem)
        val state = declaredState(problem)
        for (sample in assignments) {
            load(state, sample)
            if (satisfies == null) {
                if (state.cost == 0L) samples.add(sample)
            } else {
                val accepted = satisfies(sample)
                assertEquals(
                    accepted,
                    state.cost == 0L,
                    "$label: invariant disagrees with direct semantics at bools=${sample.bools.toList()}, " +
                        "ints=${sample.ints.toList()}",
                )
                if (accepted) samples.add(sample)
            }
        }
        return samples
    }
}
