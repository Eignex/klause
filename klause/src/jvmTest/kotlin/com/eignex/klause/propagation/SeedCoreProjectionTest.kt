package com.eignex.klause.propagation

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertIs
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class SeedCoreProjectionTest(private val nativeSat: Boolean) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "nativeSat={0}")
        fun parameters(): List<Array<Boolean>> = listOf(arrayOf(false), arrayOf(true))
    }
    @Test
    fun `projection omits an unrelated earlier assumption`() {
        val problem = Problem(3, 0, emptyArray(), arrayOf(
            Clause(intArrayOf(Lit.make(1, true), Lit.make(2, true))),
        )).bake()
        val session = PropagationSession(problem, nativeSat = nativeSat)
        val pins = Assumptions(bools = mapOf(0 to false, 1 to false, 2 to false))
        val conflict = assertIs<PropagationResult.Unsat>(session.seed(pins))

        val projected = session.projectSeedCore(pins, conflict, Cancellation.Never)

        assertContentEquals(intArrayOf(2, 3), projected.conflictLevels)
    }

    @Test
    fun `projection resolves the historical transitive premise`() {
        val problem = Problem(4, 0, emptyArray(), arrayOf(
            Clause(intArrayOf(Lit.make(0, true), Lit.make(2, true))),
            Clause(intArrayOf(Lit.make(2, false), Lit.make(3, true))),
        )).bake()
        val session = PropagationSession(problem, nativeSat = nativeSat)
        val pins = Assumptions(bools = mapOf(0 to false, 1 to false, 3 to false))
        val conflict = assertIs<PropagationResult.Unsat>(session.seed(pins))

        val projected = session.projectSeedCore(pins, conflict, Cancellation.Never)

        assertContentEquals(intArrayOf(1, 3), projected.conflictLevels)
    }

    @Test
    fun `an integer pin conflict retains the certified prefix`() {
        val session = PropagationSession(Problem(1, 1, arrayOf(IntDomain(0, 0)), emptyArray()).bake(),
            nativeSat = nativeSat)
        val pins = Assumptions(bools = mapOf(0 to false), ints = mapOf(0 to 1L))
        val conflict = assertIs<PropagationResult.Unsat>(session.seed(pins))

        val projected = session.projectSeedCore(pins, conflict, Cancellation.Never)

        assertContentEquals(conflict.conflictLevels, projected.conflictLevels)
    }

    @Test
    fun `cancellation retains the certified prefix`() {
        val problem = Problem(3, 0, emptyArray(), arrayOf(
            Clause(intArrayOf(Lit.make(1, true), Lit.make(2, true))),
        )).bake()
        val session = PropagationSession(problem, nativeSat = nativeSat)
        val pins = Assumptions(bools = mapOf(0 to false, 1 to false, 2 to false))
        val conflict = assertIs<PropagationResult.Unsat>(session.seed(pins))

        val projected = session.projectSeedCore(pins, conflict, Cancellation { true })

        assertContentEquals(conflict.conflictLevels, projected.conflictLevels)
    }
}
