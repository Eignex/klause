package com.eignex.klause.bench.profile

import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.ir.DefinitionHint
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.localsearch.LocalSearchModel
import com.eignex.klause.localsearch.LocalSearchState
import com.eignex.klause.localsearch.Move
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.Cancellation
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class ChannelTrajectoryTest {
    @Test
    fun `reconstructed source columns can load outside placeholder execution domains`() {
        val problem = Problem(0, 2, arrayOf(IntDomain(0, 0), IntDomain(0, 2)), emptyArray())
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(0))
        val coordinates = VarRemap(intArrayOf(), intArrayOf(-1, 1))
        val reconstruct = { sample: Sample ->
            Sample(booleanArrayOf(), longArrayOf(sample.intValue(1) + 10, sample.intValue(1)))
        }

        val view = TrajectoryView("reconstructed", state, coordinates, reconstruct = reconstruct)

        val decline = view.load(Sample(booleanArrayOf(), longArrayOf(11, 1)))

        assertNull(decline)
        assertEquals(Sample(booleanArrayOf(), longArrayOf(0, 1)), state.assignment.snapshot())
    }

    @Test
    fun `searched channel moves report a changed source destination`() {
        val problem = Problem(1, 1, arrayOf(IntDomain(0, 1)),
            arrayOf(ReifiedLinear(0, longArrayOf(1), intArrayOf(0), LinearOp.EQ, 1)))
        val sweep = assertNotNull(DefinitionalSweep.fromSource(problem,
            listOf(DefinitionHint.BooleanValue(0, Lit.make(0, true)))))
        val state = LocalSearchState(LocalSearchModel.open(problem), Random(0))
        state.invariants = ChannelView.SearchedValues.sweep(sweep, Cancellation.Never)
            ?.network(problem.numIntVars, problem.numBoolVars)
        val coordinates = VarRemap(intArrayOf(0), intArrayOf(0))
        val searched = TrajectoryView("searched", state, coordinates)
        assertNull(searched.load(Sample(booleanArrayOf(false), longArrayOf(0))))

        val candidate = searched.probe(Move.BoolFlip(0))

        assertNull(candidate["decline"])
        assertEquals("true", candidate.getValue("source").jsonObject.getValue("bools")
            .jsonArray.single().jsonPrimitive.content)
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `source loading should reject synchronization that changes a supplied predicate`(maintained: Boolean) {
        val problem = Problem(1, 1, arrayOf(IntDomain(-1, 2)), emptyArray())
        val model = LocalSearchModel.open(problem)
        val state = LocalSearchState(model, Random(0))
        val coordinates = VarRemap(intArrayOf(0), intArrayOf(0))
        val view = TrajectoryView(if (maintained) "maintained" else "searched", state, coordinates,
            synchronize = { if (maintained) state.assignment.setBool(0, state.assignment.intValue(0) <= 0L) })

        val source = Sample(booleanArrayOf(false), longArrayOf(-1))

        assertEquals(if (maintained) "source-round-trip" else null, view.load(source))
        assertEquals(-1L, state.assignment.intValue(0))
        assertEquals(maintained, state.assignment.boolValue(0))
    }
}
