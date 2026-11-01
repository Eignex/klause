package com.eignex.klause.bench.profile

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.DefinitionHint
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class TrajectoryViewTest {
    @Test
    fun `paired candidate grades distinguish equivalent residual scales`() {
        val source = Sample(booleanArrayOf(), longArrayOf(0, 2))
        val left = view(1, 1)
        val right = view(2, 1)

        val pair = trajectoryPair(source, listOf(Move.IntSet(0, 1)), left, right)

        val candidate = pair.getValue("candidates").jsonArray.single().jsonObject
        assertEquals("true", candidate.getValue("pairedDestination").jsonPrimitive.content)
        assertEquals("-1", candidate.getValue("left").jsonObject.getValue("netDelta").jsonPrimitive.content)
        assertEquals("-2", candidate.getValue("right").jsonObject.getValue("netDelta").jsonPrimitive.content)
    }

    @Test
    fun `changed initialization and random streams do not change paired grades`() {
        val left = view(1, 1)
        val right = view(1, 2)
        left.state.assignment.setInt(0, 4)
        right.state.assignment.setInt(1, 3)
        right.state.rng.nextInt()

        val pair = trajectoryPair(
            Sample(booleanArrayOf(), longArrayOf(0, 2)), listOf(Move.IntSet(0, 1)), left, right,
        )

        assertNotEquals(trajectoryRandomBoundary(left.state), trajectoryRandomBoundary(right.state))
        val candidate = pair.getValue("candidates").jsonArray.single().jsonObject
        assertEquals(candidate["left"], candidate["right"])
    }

    @Test
    fun `inconsistent maintained output has no paired source state`() {
        val view = view(1, 1)
        val sweep = requireNotNull(DefinitionalSweep.fromSource(view.state.problem, listOf(
            DefinitionHint.Linear(
                1, longArrayOf(1, -1),
                arrayOf(DefinitionHint.Operand.Variable(0), DefinitionHint.Operand.Variable(1)), 0,
            ),
        )))
        view.state.invariants = sweep.network(2, 0)
        val maintained = TrajectoryView(
            "maintained", view.state, view.coordinates,
            synchronize = { sweep.sweep(view.state.assignment, view.state.rootDomains) },
        )

        val unsupported = maintained.load(Sample(booleanArrayOf(), longArrayOf(0, 2)))
        val supported = maintained.load(Sample(booleanArrayOf(), longArrayOf(2, 2)))

        assertEquals("source-round-trip", unsupported)
        assertNull(supported)
    }

    @Test
    fun `linear and functional objectives agree on maintained affine candidate destinations`() {
        val linear = LinearObjective(intCoefficients = longArrayOf(0, 1))
        val views = List(2) { index ->
            val original = view(1, 1)
            val sweep = requireNotNull(DefinitionalSweep.fromSource(original.state.problem, listOf(
                DefinitionHint.Linear(1, longArrayOf(1, -1), arrayOf(
                    DefinitionHint.Operand.Variable(0), DefinitionHint.Operand.Variable(1)), 0),
            )))
            original.state.invariants = sweep.network(2, 0)
            TrajectoryView(
                "objective-$index", original.state, original.coordinates,
                if (index == 0) linear else requireNotNull(sweep.functionalObjective(linear)),
                synchronize = { sweep.sweep(original.state.assignment, original.state.rootDomains) },
            )
        }

        val pair = trajectoryPair(Sample(booleanArrayOf(), longArrayOf(2, 2)),
            listOf(Move.IntSet(0, 1)), views[0], views[1])

        val candidate = pair.getValue("candidates").jsonArray.single().jsonObject
        assertEquals("true", candidate.getValue("pairedDestination").jsonPrimitive.content)
        assertEquals(candidate["left"], candidate["right"])
        assertEquals("-1.0", candidate.getValue("left").jsonObject.getValue("objectiveDelta").jsonPrimitive.content)
    }

    @Test
    fun `captured implicit owners reject foreign primitive candidates`() {
        val original = view(1, 1)
        val owned = TrajectoryView(
            "owned", original.state, original.coordinates, owners = intArrayOf(0, -1),
        )
        assertNull(owned.load(Sample(booleanArrayOf(), longArrayOf(0, 2))))

        val candidate = owned.probe(Move.IntSet(0, 1))

        assertEquals("candidate-filter", candidate.getValue("decline").jsonPrimitive.content)
    }

    @ParameterizedTest
    @ValueSource(ints = [0, 1, 31, 10000])
    fun `random boundary measures underlying draws without advancing the stream`(draws: Int) {
        val state = view(1, 1).state
        val before = trajectoryRandomBoundary(state)
        val again = trajectoryRandomBoundary(state)

        repeat(draws) { state.rng.nextInt() }
        val after = trajectoryRandomBoundary(state)

        assertEquals(before, again)
        val key = "nextIntDrawBoundaryModulo2To32"
        val first = before.getValue(key).jsonPrimitive.content.toLong()
        val second = after.getValue(key).jsonPrimitive.content.toLong()
        assertEquals(draws.toLong(), (second - first) and 0xffffffffL)
    }

    private fun view(scale: Long, seed: Long): TrajectoryView {
        val problem = Problem(0, 2, Array(2) { IntDomain(0, 4) }, arrayOf(
            Linear(longArrayOf(scale, -scale), intArrayOf(0, 1), LinearOp.EQ, 0),
        ))
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(seed))
        return TrajectoryView("scale-$scale", state, VarRemap(intArrayOf(), intArrayOf(0, 1)))
    }
}
