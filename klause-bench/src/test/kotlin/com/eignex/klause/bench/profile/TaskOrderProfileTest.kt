package com.eignex.klause.bench.profile

import com.eignex.klause.brute.BruteForceParams
import com.eignex.klause.brute.BruteForceSolver
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.bake
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class TaskOrderProfileTest {
    @Test
    fun `order overlay gives each native source solution one extension`() {
        val source = Problem(0, 2, Array(2) { IntDomain(0, 3) },
            arrayOf(Cumulative.unary(intArrayOf(0, 1), longArrayOf(2, 2))))
        val params = BruteForceParams(randomSeed = 1)
        val expected = BruteForceSolver(source.bake()).enumerate(params).map { it.ints.toList() }.toSet()

        val ordered = TaskOrderProfile.withOrderPair(source)
        val extensions = BruteForceSolver(ordered.bake()).enumerate(params).groupingBy { it.ints.toList() }.eachCount()

        assertEquals(expected.associateWith { 1 }, extensions)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `both order alternatives enforce later source bounds`(forward: Boolean) {
        val source = Problem(0, 2, Array(2) { IntDomain(0, 6) },
            arrayOf(Cumulative.unary(intArrayOf(0, 1), longArrayOf(2, 2))))
        val session = PropagationSession(TaskOrderProfile.withOrderPair(source))
        val first = if (forward) 0 else 1
        val second = 1 - first

        assertIs<PropagationResult.Implied>(session.pinBool(0, forward))
        assertIs<PropagationResult.Implied>(session.pinIntAtLeast(first, 2))

        assertEquals(4L, session.intDomain(second).min)
        assertEquals(4L, session.intDomain(first).max)
    }

    @Test
    fun `undo removes the chosen source order`() {
        val source = Problem(0, 2, Array(2) { IntDomain(0, 6) },
            arrayOf(Cumulative.unary(intArrayOf(0, 1), longArrayOf(2, 2))))
        val session = PropagationSession(TaskOrderProfile.withOrderPair(source))
        session.pinBool(0, true)

        session.popToLevel(0)

        assertEquals(IntDomain(0, 6), session.intDomain(0))
        assertEquals(IntDomain(0, 6), session.intDomain(1))
    }

    @ParameterizedTest
    @ValueSource(strings = ["optional", "zero", "variable", "shared"])
    fun `unsupported task shapes retain their native source`(shape: String) {
        val factor = Cumulative.unary(
            starts = if (shape == "shared") intArrayOf(0, 0) else intArrayOf(0, 1),
            durations = longArrayOf(if (shape == "zero") 0 else 2, 2),
            presents = if (shape == "optional") intArrayOf(Lit.make(0, true), Lit.make(0, true)) else intArrayOf(),
            durationVars = if (shape == "variable") intArrayOf(2, 2) else intArrayOf(),
        )
        val source = Problem(1, 3, Array(3) { IntDomain(0, 6) }, arrayOf(factor))

        val ordered = TaskOrderProfile.withOrderPair(source)

        assertSame(source, ordered)
    }
}
