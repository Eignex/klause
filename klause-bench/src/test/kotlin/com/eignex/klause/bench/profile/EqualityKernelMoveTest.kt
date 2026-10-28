package com.eignex.klause.bench.profile

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.DefinitionHint
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.localsearch.MoveSink
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.Cancellation
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.math.BigInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EqualityKernelMoveTest {
    @Test
    fun `a direction outside the exact integer kernel supplies no steps`() {
        val steps = EqualityKernelMove.steps(
            listOf(0, 1), listOf(arrayOf(BigInteger.ONE, -BigInteger.ONE)),
            arrayOf(BigInteger.ONE, -BigInteger.ONE), { 0L }, { IntDomain(0, 2) }, { true },
        )

        assertTrue(steps.isEmpty())
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1])
    fun `a pin rejects the complete direction`(pinned: Int) {
        val assumptions = Assumptions(ints = mapOf(pinned to 0L))

        val steps = EqualityKernelMove.steps(
            listOf(0, 1), listOf(arrayOf(BigInteger.ONE, -BigInteger.ONE)),
            arrayOf(BigInteger.ONE, BigInteger.ONE), { 0L }, { IntDomain(0, 2) },
            { !assumptions.isFrozenInt(it) },
        )

        assertTrue(steps.isEmpty())
    }

    @Test
    fun `a foreign owner cannot leave a sink trimmed partial direction`() {
        val step = EqualityKernelMove.Step(
            BigInteger.ONE,
            Move.Compound(listOf(Move.IntSet(0, 1), Move.IntSet(1, 1))),
        )

        val admitted = EqualityKernelMove.admit(step) { MoveSink().apply { setOwners(intArrayOf(0, -1)) } }

        assertNull(admitted)
    }

    @Test
    fun `maintained outputs cannot be dropped from an equality direction`() {
        val problem = Problem(
            0, 2, Array(2) { IntDomain(0, 2) },
            listOf(Linear(longArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 0)),
        )
        val sweep = assertNotNull(DefinitionalSweep.fromSource(
            problem,
            listOf(DefinitionHint.Linear(
                1, longArrayOf(1, -1),
                arrayOf(DefinitionHint.Operand.Variable(0), DefinitionHint.Operand.Variable(1)), 0,
            )),
        ))
        val step = EqualityKernelMove.Step(
            BigInteger.ONE,
            Move.Compound(listOf(Move.IntSet(0, 1), Move.IntSet(1, 1))),
        )

        val admitted = EqualityKernelMove.admit(step) { MoveSink().apply { setInvariants(sweep.network(2, 0)) } }

        assertNull(admitted)
    }

    @Test
    fun `domain holes exclude the unit step while admitting its complete endpoint`() {
        val domain = IntDomain(0, 2).excludeValue(1)

        val steps = EqualityKernelMove.steps(
            listOf(0, 1), listOf(arrayOf(BigInteger.ONE, -BigInteger.ONE)),
            arrayOf(BigInteger.ONE, BigInteger.ONE), { 0L }, { domain }, { true },
        )

        assertEquals(listOf(BigInteger.TWO), steps.map { it.scalar })
        assertEquals(listOf(Move.IntSet(0, 2), Move.IntSet(1, 2)), steps.single().move.parts)
    }

    @Test
    fun `step arithmetic can span more than a signed word without wrapping targets`() {
        val center = longArrayOf(Long.MIN_VALUE + 1, Long.MAX_VALUE)

        val steps = EqualityKernelMove.steps(
            listOf(0, 1), listOf(arrayOf(BigInteger.ONE, BigInteger.ONE)),
            arrayOf(BigInteger.ONE, -BigInteger.ONE), { center[it] },
            { IntDomain(Long.MIN_VALUE + 1, Long.MAX_VALUE) }, { true },
        )

        val endpoint = steps.single { it.scalar == BigInteger("18446744073709551614") }
        assertEquals(
            listOf(Move.IntSet(0, Long.MAX_VALUE), Move.IntSet(1, Long.MIN_VALUE + 1)), endpoint.move.parts,
        )
    }

    @Test
    fun `cancellation discards an already computed private step`() {
        var cancelled = false
        var domains = 0
        val stop = Cancellation { cancelled }

        val steps = EqualityKernelMove.steps(
            listOf(0, 1), listOf(arrayOf(BigInteger.ONE, -BigInteger.ONE)),
            arrayOf(BigInteger.ONE, BigInteger.ONE), { 0L },
            {
                if (++domains == 4) cancelled = true
                IntDomain(0, 2)
            }, { true }, stop,
        )

        assertTrue(steps.isEmpty())
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `pins outside the moved coordinates remain binding`(booleanPin: Boolean) {
        val assumptions = if (booleanPin) {
            Assumptions(bools = mapOf(0 to false))
        } else {
            Assumptions(ints = mapOf(2 to 0L))
        }
        val destination = Sample(booleanArrayOf(true), longArrayOf(1, 1, 1))

        val honored = EqualityKernelMove.honorsPins(assumptions, destination)

        assertFalse(honored)
    }

    @Test
    fun `every retained global is graded before a source point is accepted`() {
        val problem = Problem(
            0, 2, Array(2) { IntDomain(0, 2) },
            listOf(
                Linear(longArrayOf(1, 1), intArrayOf(0, 1), LinearOp.EQ, 2),
                AllDifferent(intArrayOf(0, 1)),
            ),
        )
        val state = LocalSearchState(problem.bake(), Random(0))
        val view = TrajectoryView("global", state, VarRemap(intArrayOf(), intArrayOf(0, 1)))
        val center = Sample(booleanArrayOf(), longArrayOf(0, 2))
        assertNull(view.load(center))
        val move = Move.Compound(listOf(Move.IntSet(0, 1), Move.IntSet(1, 1)))

        val probe = assertNotNull(EqualityKernelMove.probe(view, move, Cancellation.Never) { point, _ ->
            point.intValue(0) != point.intValue(1)
        })

        assertTrue(probe.inspection.netDelta > 0)
        assertFalse(probe.accepted)
        assertEquals(center, state.assignment.snapshot())
    }

    @Test
    fun `a complete kernel move is applied and the checked center is restored`() {
        val problem = Problem(
            0, 2, Array(2) { IntDomain(0, 2) },
            listOf(Linear(longArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 0)),
        )
        val state = LocalSearchState(problem.bake(), Random(0))
        val objective = LinearObjective(intCoefficients = longArrayOf(-1, 0))
        val view = TrajectoryView("kernel", state, VarRemap(intArrayOf(), intArrayOf(0, 1)), objective)
        val center = Sample(booleanArrayOf(), longArrayOf(0, 0))
        assertNull(view.load(center))
        val move = Move.Compound(listOf(Move.IntSet(0, 2), Move.IntSet(1, 2)))

        val applied = assertNotNull(EqualityKernelMove.consume(
            view, move, center, objective::evaluateExact, Cancellation.Never,
        ) { _, _ -> true })

        assertEquals(Sample(booleanArrayOf(), longArrayOf(2, 2)), applied.source)
        assertEquals("-2", applied.value.toString())
        assertEquals(center, state.assignment.snapshot())
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `an unsuccessful source check during consumption restores the checked center`(throws: Boolean) {
        val problem = Problem(
            0, 2, Array(2) { IntDomain(0, 2) },
            listOf(Linear(longArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 0)),
        )
        val state = LocalSearchState(problem.bake(), Random(0))
        val objective = LinearObjective(intCoefficients = longArrayOf(-1, 0))
        val view = TrajectoryView("restored", state, VarRemap(intArrayOf(), intArrayOf(0, 1)), objective)
        val center = Sample(booleanArrayOf(), longArrayOf(0, 0))
        assertNull(view.load(center))
        val move = Move.Compound(listOf(Move.IntSet(0, 2), Move.IntSet(1, 2)))
        val verify: (Sample, Cancellation) -> Boolean = { _, _ ->
            if (throws) error("failed source check")
            false
        }

        if (throws) {
            assertFailsWith<IllegalStateException> {
                EqualityKernelMove.consume(view, move, center, objective::evaluateExact, Cancellation.Never, verify)
            }
        } else {
            assertNull(EqualityKernelMove.consume(
                view, move, center, objective::evaluateExact, Cancellation.Never, verify,
            ))
        }

        assertEquals(center, state.assignment.snapshot())
        assertEquals(0L, state.cost)
    }
}
