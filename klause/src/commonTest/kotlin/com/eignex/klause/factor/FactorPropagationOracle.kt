package com.eignex.klause.factor

import com.eignex.klause.brute.BruteForceParams
import com.eignex.klause.brute.BruteForceSolver
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Brute-force oracle for [com.eignex.klause.solver.Factor.propagate]. Builds the ground-truth
 * set of satisfying assignments via [BruteForceSolver] and asserts the propagator's deductions
 * are consistent.
 *
 * Two strength levels:
 *  - [assertSound] (default): every pin/bound/hole the propagator emits must hold on *all*
 *    satisfying assignments — no over-pruning. Propagation weaker than GAC passes this.
 *  - [assertGac]: stronger — the propagator must additionally prune every value with no
 *    supporting assignment. Use only when the factor claims GAC.
 */
object FactorPropagationOracle {

    fun assertSound(problem: Problem, label: String = "factor") {
        checkSound(problem, label)
    }

    /**
     * The ground truth a soundness check ran against: [samples] is empty exactly when the problem
     * is unsatisfiable, in which case [result] is [PropagationResult.Unsat]; otherwise [result] is
     * [PropagationResult.Implied].
     */
    private class SoundnessCheck(val samples: List<Sample>, val result: PropagationResult)

    /** [assertSound]'s checks, handing the enumeration and propagation back so [assertGac] adds its
     *  own on the same data instead of brute-enumerating the space a second time. */
    private fun checkSound(problem: Problem, label: String): SoundnessCheck {
        val samples = enumerateSat(problem)
        val result = problem.propagate()
        if (samples.isEmpty()) {
            assertTrue(
                result is PropagationResult.Unsat,
                "$label: propagate returned $result but brute found zero satisfying assignments",
            )
            return SoundnessCheck(samples, result)
        }
        assertTrue(
            result is PropagationResult.Implied,
            "$label: propagate returned Unsat but brute found ${samples.size} satisfying assignments",
        )

        // Bool pins
        result.forEachBool { v, b ->
            for (s in samples) {
                assertEquals(
                    b,
                    s.bools[v],
                    "$label: propagate pinned bool $v=$b but a satisfying assignment has ${s.bools[v]}",
                )
            }
        }
        // Int pins
        result.forEachInt { v, value ->
            for (s in samples) {
                assertEquals(
                    value,
                    s.ints[v],
                    "$label: propagate pinned int $v=$value but a satisfying assignment has ${s.ints[v]}",
                )
            }
        }
        // Bound tightenings
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
        // Interior holes
        result.forEachIntHole { v, value ->
            for (s in samples) {
                assertTrue(
                    s.ints[v] != value,
                    "$label: propagate declared hole int $v != $value but a satisfying assignment has ${s.ints[v]}",
                )
            }
        }
        return SoundnessCheck(samples, result)
    }

    fun assertGac(problem: Problem, label: String = "factor") {
        val check = checkSound(problem, label)
        val samples = check.samples
        if (samples.isEmpty()) return
        val result = check.result as PropagationResult.Implied

        // For each bool var, every supported value must remain allowed by propagation.
        for (v in 0 until problem.numBoolVars) {
            val pinned = result.boolValueOrNull(v)
            for (b in listOf(false, true)) {
                val supported = samples.any { it.bools[v] == b }
                if (!supported && pinned == null) {
                    fail("$label: GAC violation — bool $v=$b has no satisfying assignment but is not pruned")
                }
                if (supported && pinned != null && pinned != b) {
                    // Caught by assertSound, but keep the message localized.
                    fail("$label: GAC violation — bool $v=$b supported but propagator pinned $pinned")
                }
            }
        }
        // For each int var, every supported value must remain allowed by propagation.
        for (v in 0 until problem.numIntVars) {
            val pinned = result.intValueOrNull(v)
            val orig = problem.requireFiniteIntDomains()[v]
            for (value in orig.min..orig.max) {
                if (value !in orig) continue
                val supported = samples.any { it.ints[v] == value }
                val allowed = isAllowed(result, problem, v, value, pinned)
                if (supported && !allowed) {
                    fail("$label: GAC violation — int $v=$value supported but ruled out by propagator")
                }
                if (!supported && allowed) {
                    fail("$label: GAC violation — int $v=$value unsupported but not pruned")
                }
            }
        }
    }

    private fun isAllowed(r: PropagationResult.Implied, problem: Problem, v: Int, value: Long, pinned: Long?): Boolean {
        if (pinned != null) return pinned == value
        val lo = r.intMinOrNullCompat(v) ?: problem.requireFiniteIntDomains()[v].min
        val hi = r.intMaxOrNullCompat(v) ?: problem.requireFiniteIntDomains()[v].max
        if (value < lo || value > hi) return false
        var hole = false
        r.forEachIntHole { id, k -> if (id == v && k == value) hole = true }
        return !hole
    }

    private fun enumerateSat(problem: Problem): List<Sample> {
        require(BruteForceSolver.fits(problem, cap = 1L shl 18)) {
            "Problem too large to brute-enumerate (cap 262 144 assignments). Shrink domains or vars."
        }
        return BruteForceSolver(problem.bake()).enumerate(BruteForceParams(randomSeed = 0L)).toList()
    }

    private fun fail(msg: String): Nothing = throw AssertionError(msg)
}
