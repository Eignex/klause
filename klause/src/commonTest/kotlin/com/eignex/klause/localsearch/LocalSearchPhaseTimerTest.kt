package com.eignex.klause.localsearch

import com.eignex.klause.formats.dimacs.Dimacs
import com.eignex.klause.formats.dimacs.toProblem
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.objective.FunctionalObjective
import com.eignex.klause.solver.objective.LinearObjective
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class LocalSearchPhaseTimerTest {
    @Test
    fun `inspection snapshots should retain their captured visit count`() {
        var visits = 4L
        val timer = LocalSearchPhaseTimer { 0L }
        timer.inspectionVisits = { visits }
        val first = timer.objectiveScoringSnapshot()

        visits = 9L
        val second = timer.objectiveScoringSnapshot()

        assertEquals(4L, first.getValue("inspection-visits"))
        assertEquals(9L, second.getValue("inspection-visits"))
    }

    @Test
    fun `generic functional scoring should report completed evaluator calls`() {
        val source = Dimacs.parseWcnf("7 1 2 0\n").toProblem()
        val sweep = assertNotNull(DefinitionalSweep.infer(source.problem, boolFolds = source.boolFolds))
        val objective = assertIs<FunctionalObjective>(
            sweep.functionalObjective(LinearObjective(boolWeights = longArrayOf(0L, 0L, 7L))),
        )
        val state = LocalSearchState(source.problem.bake(), Random(0))
        val timer = LocalSearchPhaseTimer { 0L }
        state.phaseTimer = timer

        state.objectiveDelta(objective, Move.BoolFlip(0))
        state.objectiveDelta(objective, Move.BoolFlip(1))

        assertEquals(2L, timer.objectiveScoringSnapshot().getValue("generic"))
    }

    @Test
    fun `objective scoring snapshots should retain completed counts across later observations`() {
        val timer = LocalSearchPhaseTimer { 0L }
        timer.recordObjectiveScoring("indexed")
        val first = timer.objectiveScoringSnapshot()

        timer.recordObjectiveScoring("indexed")
        val second = timer.objectiveScoringSnapshot()

        assertEquals(1L, first.getValue("indexed"))
        assertEquals(2L, second.getValue("indexed"))
        assertEquals(0L, timer.snapshot().values.sum())
    }

    @Test
    fun `suspended sequence time should be excluded from its active phase`() {
        var clock = 0L
        val timer = LocalSearchPhaseTimer { clock }
        val source = sequence {
            timer.inPhase("seeding") {
                clock += 2L
                yield(1)
                clock += 3L
                yield(2)
            }
        }.withActiveLocalSearchTime(timer).iterator()

        assertEquals(1, source.next())
        clock += 1000L
        assertEquals(2L, timer.snapshot().getValue("seeding"))
        assertEquals(2, source.next())
        clock += 1000L
        assertFalse(source.hasNext())
        assertFailsWith<NoSuchElementException> { source.next() }

        assertEquals(5L, timer.snapshot().getValue("seeding"))
        assertEquals(5L, timer.snapshot().values.sum())
    }

    @Test
    fun `nested phases should account elapsed time without counting it twice`() {
        var clock = 0L
        val timer = LocalSearchPhaseTimer { clock }
        timer.resume()

        timer.inPhase("seeding") {
            clock += 2L
            timer.inPhase("selection") { clock += 3L }
            clock += 5L
        }
        timer.pause()

        assertEquals(7L, timer.snapshot().getValue("seeding"))
        assertEquals(3L, timer.snapshot().getValue("selection"))
        assertEquals(10L, timer.snapshot().values.sum())
    }

    @Test
    fun `an interrupted sequence should stop accruing time`() {
        var clock = 0L
        val timer = LocalSearchPhaseTimer { clock }
        val source = sequence<Int> {
            timer.inPhase("preparation") {
                clock += 7L
                error("cancelled")
            }
        }.withActiveLocalSearchTime(timer).iterator()

        assertFailsWith<IllegalStateException> { source.hasNext() }
        clock += 1000L

        assertEquals(7L, timer.snapshot().getValue("preparation"))
        assertEquals(7L, timer.snapshot().values.sum())
    }
}
