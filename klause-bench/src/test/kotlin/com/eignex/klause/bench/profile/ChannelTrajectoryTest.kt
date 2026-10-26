package com.eignex.klause.bench.profile

import com.eignex.klause.formats.flatzinc.parseFlatZinc
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ChannelTrajectoryTest {
    @Test
    fun `searched channel moves report a changed source destination`() {
        val program = parseFlatZinc("var bool: b; var 0..1: y; " +
            "constraint bool2int(b, y) :: defines_var(y); solve satisfy;")
        val sweep = assertNotNull(DefinitionalSweep.fromSource(program.problem, program.definitionHints))
        val left = LocalSearchState(LocalSearchModel.open(program.problem), Random(0))
        val right = LocalSearchState(LocalSearchModel.open(program.problem), Random(0))
        left.invariants = sweep.network(program.problem.numIntVars, program.problem.numBoolVars)
        right.invariants = ChannelView.SearchedValues.sweep(sweep, Cancellation.Never)
            ?.network(program.problem.numIntVars, program.problem.numBoolVars)
        val coordinates = VarRemap(intArrayOf(0), intArrayOf(0))
        val maintained = TrajectoryView("maintained", left, coordinates,
            synchronize = { sweep.sweep(left.assignment, left.rootDomains) })
        val searched = TrajectoryView("searched", right, coordinates)

        val pair = trajectoryPair(Sample(booleanArrayOf(false), longArrayOf(0)),
            listOf(Move.BoolFlip(0)), maintained, searched)

        val candidate = pair.getValue("candidates").jsonArray.single().jsonObject
        assertEquals("false", candidate.getValue("pairedDestination").jsonPrimitive.content)
    }

    @Test
    fun `an inconsistent searched predicate is not silently extended into a paired state`() {
        val program = parseFlatZinc("var -1..2: x; var bool: b; " +
            "constraint int_le_reif(x, 0, b) :: defines_var(b); solve satisfy;")
        val sweep = assertNotNull(DefinitionalSweep.fromSource(program.problem, program.definitionHints))
        val left = LocalSearchState(LocalSearchModel.open(program.problem), Random(0))
        val right = LocalSearchState(LocalSearchModel.open(program.problem), Random(0))
        left.invariants = sweep.network(program.problem.numIntVars, program.problem.numBoolVars)
        right.invariants = ChannelView.SearchedPredicates.sweep(sweep, Cancellation.Never)
            ?.network(program.problem.numIntVars, program.problem.numBoolVars)
        val coordinates = VarRemap(intArrayOf(0), intArrayOf(0))
        val maintained = TrajectoryView("maintained", left, coordinates,
            synchronize = { sweep.sweep(left.assignment, left.rootDomains) })
        val searched = TrajectoryView("searched", right, coordinates)

        val pair = trajectoryPair(Sample(booleanArrayOf(false), longArrayOf(-1)), emptyList(), maintained, searched)

        assertEquals("source-round-trip", pair.getValue("leftLoad").jsonPrimitive.content)
        assertEquals("paired", pair.getValue("rightLoad").jsonPrimitive.content)
    }
}
