package com.eignex.klause.propagation

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.util.Cancellation
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertIs

@RunWith(Parameterized::class)
class SeedCoreProjectionTest(private val nativeSat: Boolean, private val pinValue: Boolean) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "nativeSat={0}, pin={1}")
        fun parameters(): List<Array<Boolean>> = listOf(
            arrayOf(false, false), arrayOf(false, true), arrayOf(true, false), arrayOf(true, true),
        )
    }

    @Test
    fun `projection omits an unrelated earlier assumption`() {
        val problem = Problem(3, 0, emptyArray(), arrayOf(
            Clause(intArrayOf(Lit.make(1, !pinValue), Lit.make(2, !pinValue))),
        )).bake()
        val session = PropagationSession(problem, nativeSat = nativeSat)
        val pins = Assumptions(bools = mapOf(0 to pinValue, 1 to pinValue, 2 to pinValue))
        val conflict = assertIs<PropagationResult.Unsat>(session.seed(pins))

        val projected = session.projectSeedCore(pins, conflict, Cancellation.Never)

        assertContentEquals(intArrayOf(2, 3), projected.conflictLevels)
    }

    @Test
    fun `projection resolves the historical transitive premise`() {
        val problem = Problem(4, 0, emptyArray(), arrayOf(
            Clause(intArrayOf(Lit.make(0, !pinValue), Lit.make(2, !pinValue))),
            Clause(intArrayOf(Lit.make(2, pinValue), Lit.make(3, !pinValue))),
        )).bake()
        val session = PropagationSession(problem, nativeSat = nativeSat)
        val pins = Assumptions(bools = mapOf(0 to pinValue, 1 to pinValue, 3 to pinValue))
        val conflict = assertIs<PropagationResult.Unsat>(session.seed(pins))

        val projected = session.projectSeedCore(pins, conflict, Cancellation.Never)

        assertContentEquals(intArrayOf(1, 3), projected.conflictLevels)
    }

    @Test
    fun `an integer pin conflict retains the certified prefix`() {
        val session = PropagationSession(Problem(1, 1, arrayOf(IntDomain(0, 0)), emptyArray()).bake(),
            nativeSat = nativeSat)
        val pins = Assumptions(bools = mapOf(0 to pinValue), ints = mapOf(0 to 1L))
        val conflict = assertIs<PropagationResult.Unsat>(session.seed(pins))

        val projected = session.projectSeedCore(pins, conflict, Cancellation.Never)

        assertContentEquals(conflict.conflictLevels, projected.conflictLevels)
    }

    @Test
    fun `cancellation retains the certified prefix`() {
        val problem = Problem(3, 0, emptyArray(), arrayOf(
            Clause(intArrayOf(Lit.make(1, !pinValue), Lit.make(2, !pinValue))),
        )).bake()
        val session = PropagationSession(problem, nativeSat = nativeSat)
        val pins = Assumptions(bools = mapOf(0 to pinValue, 1 to pinValue, 2 to pinValue))
        val conflict = assertIs<PropagationResult.Unsat>(session.seed(pins))

        val projected = session.projectSeedCore(pins, conflict, Cancellation { true })

        assertContentEquals(conflict.conflictLevels, projected.conflictLevels)
    }

    @Test
    fun `a stopped reason walk retains the complete certified prefix`() {
        val last = 132
        val problem = Problem(last + 1, 0, emptyArray(), Array(last - 1) { index ->
            Clause(if (index == 0) intArrayOf(Lit.make(0, !pinValue), Lit.make(2, !pinValue))
            else intArrayOf(Lit.make(index + 1, pinValue), Lit.make(index + 2, !pinValue)))
        }).bake()
        val session = PropagationSession(problem, nativeSat = nativeSat)
        val pins = Assumptions(bools = mapOf(0 to pinValue, 1 to pinValue, last to pinValue))
        val conflict = assertIs<PropagationResult.Unsat>(session.seed(pins))

        val projected = session.projectSeedCore(pins, conflict, Cancellation.Never)

        assertContentEquals(conflict.conflictLevels, projected.conflictLevels)
    }
}
