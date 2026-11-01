package com.eignex.klause.portfolio

import com.eignex.klause.backtrack.BacktrackRecipe
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.propagation.SharedClause
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.lp.bounding.LpConfig
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.SharingChannel
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BacktrackWorkerConfigTest {

    private fun signalsOf(recipe: BacktrackRecipe, objective: LinearObjective?, pools: SharedPools): Set<Signal> {
        val problem = Problem(
            numBoolVars = 3,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(Cardinality(IntArray(3) { Lit.make(it, true) }, min = 1, max = 3)),
        ).bake()
        return BacktrackWorkerConfig(recipe).materialize(
            problem,
            index = 0,
            armId = 0,
            seed = 0L,
            lsLambda = 1.0,
            objective = objective,
            lsObjective = null,
            definitionalSweep = null,
            onEvent = null,
            pools = pools,
        ).use { it.signals }
    }

    private fun allPools() = SharedPools(
        clauses = SharedClausePool(),
        cuts = SharedCutPool(),
        bounds = SharedObjectiveBound(),
        varBounds = SharedVarBounds(0),
    )

    @Test
    fun `a binary cardinality pool should select one arithmetic worker across repeated recipes`() {
        val facts = ProblemFacts(ProblemFacts.assumed(Kind.COP).profile,
            binaryIntegers = true, integerLinear = true, integerColumnsOnly = true,
            compactCardinalityEquality = true, compactIntegerNeighborhood = true) { true }
        val count = BacktrackCatalog.ranked(Kind.COP, facts).size * 2

        val arms = BacktrackWorkerConfig.diverse(Kind.COP, count, facts = facts,
            automaticIntegerLearning = true)

        assertEquals(listOf("conflictDriven"),
            arms.filter { it.recipe.build(1L, null).integerRowLearning }.map { it.label })
    }

    @Test
    fun `nonbinary mixed or nonlinear models should retain clausal recipes`() {
        for ((binary, columns, linear) in listOf(Triple(false, true, true),
            Triple(true, false, true), Triple(true, true, false))) {
            val facts = ProblemFacts(ProblemFacts.assumed(Kind.COP).profile,
                binaryIntegers = binary, integerLinear = linear, integerColumnsOnly = columns,
                compactCardinalityEquality = true, compactIntegerNeighborhood = true) { true }

            val arms = BacktrackWorkerConfig.diverse(Kind.COP, 12, facts = facts,
                automaticIntegerLearning = true)

            assertTrue(arms.none { it.recipe.build(1L, null).integerRowLearning },
                "binary=$binary columns=$columns linear=$linear")
        }
    }

    @Test
    fun `an isolated compact equality should retain clausal recipes`() {
        val facts = ProblemFacts(ProblemFacts.assumed(Kind.COP).profile,
            binaryIntegers = true, integerLinear = true, integerColumnsOnly = true,
            compactCardinalityEquality = true) { true }

        val arms = BacktrackWorkerConfig.diverse(Kind.COP, 6, facts = facts, automaticIntegerLearning = true)

        assertTrue(arms.none { it.recipe.build(1L, null).integerRowLearning })
    }

    @Test
    fun `binary inequality models should retain clausal recipes`() {
        val facts = ProblemFacts(ProblemFacts.assumed(Kind.COP).profile,
            binaryIntegers = true, integerLinear = true, integerColumnsOnly = true) { true }

        val arms = BacktrackWorkerConfig.diverse(Kind.COP, 12, facts = facts,
            automaticIntegerLearning = true)

        assertTrue(arms.none { it.recipe.build(1L, null).integerRowLearning })
    }

    @Test
    fun `satisfaction pools should retain clausal recipes`() {
        val facts = ProblemFacts(ProblemFacts.assumed(Kind.CSP).profile,
            binaryIntegers = true, integerLinear = true, integerColumnsOnly = true,
            compactCardinalityEquality = true, compactIntegerNeighborhood = true) { true }

        val arms = BacktrackWorkerConfig.diverse(Kind.CSP, 12, facts = facts,
            automaticIntegerLearning = true)

        assertTrue(arms.none { it.recipe.build(1L, null).integerRowLearning })
    }

    @Test
    fun `an LP off ceiling should retain clausal recipes`() {
        val facts = ProblemFacts(ProblemFacts.assumed(Kind.COP).profile,
            binaryIntegers = true, integerLinear = true, integerColumnsOnly = true,
            compactCardinalityEquality = true, compactIntegerNeighborhood = true) { true }

        val arms = BacktrackWorkerConfig.diverse(Kind.COP, 12, lpCeiling = LpConfig.OFF, facts = facts,
            automaticIntegerLearning = true)

        assertTrue(arms.none { it.recipe.build(1L, null).integerRowLearning })
    }

    @Test
    fun `explicit arithmetic settings should override automatic diversity`() {
        for ((cardinality, enabled) in listOf(false to false, false to true, true to false, true to true)) {
            val facts = ProblemFacts(ProblemFacts.assumed(Kind.COP).profile,
                binaryIntegers = true, integerLinear = true, integerColumnsOnly = true,
                compactCardinalityEquality = cardinality, compactIntegerNeighborhood = true) { true }

            val arms = BacktrackWorkerConfig.diverse(Kind.COP, 12, facts = facts,
                automaticIntegerLearning = true, edit = { it.copy(integerRowLearning = enabled) })

            assertTrue(arms.all { it.recipe.build(1L, null).integerRowLearning == enabled })
        }
    }

    @Test
    fun `a scalar edit should preserve the arithmetic worker`() {
        val facts = ProblemFacts(ProblemFacts.assumed(Kind.COP).profile,
            binaryIntegers = true, integerLinear = true, integerColumnsOnly = true,
            compactCardinalityEquality = true, compactIntegerNeighborhood = true) { true }

        val arms = BacktrackWorkerConfig.diverse(Kind.COP, 12, facts = facts,
            automaticIntegerLearning = true, edit = { it.copy(maxDecisions = 20) })

        assertEquals(listOf("conflictDriven"),
            arms.filter { it.recipe.build(1L, null).integerRowLearning }.map { it.label })
    }

    @Test
    fun `an arm should earn cut uses only when it runs a cut separator`() {
        val cases = listOf(
            "lp-aggressive" to true,
            "lp-default" to false,
            "conflictDriven" to false,
            "lp-aggressive capped without separators" to false,
        )
        for ((case, earnsCuts) in cases) {
            val recipe = if (case.endsWith("without separators")) {
                BacktrackCatalog.byLabel("lp-aggressive").capLp(LpConfig.parse("aggressive,-cuts,-circuit"))
            } else {
                BacktrackCatalog.byLabel(case)
            }

            val signals = signalsOf(recipe, LinearObjective(boolWeights = longArrayOf(1L, 2L, 3L)), allPools())

            assertEquals(earnsCuts, Signal.CutUses in signals, case)
        }
    }

    @Test
    fun `an optimizing arm should earn the signal of every pool it shares through`() {
        val signals = signalsOf(
            BacktrackCatalog.byLabel("lp-aggressive"),
            LinearObjective(boolWeights = longArrayOf(1L, 2L, 3L)),
            allPools(),
        )

        assertEquals(SEARCH_SIGNALS + setOf(Signal.ClauseUses, Signal.CutUses, Signal.Floor, Signal.BoundUses), signals)
    }

    @Test
    fun `a satisfying arm should earn no objective floor or bound uses`() {
        val signals = signalsOf(BacktrackCatalog.byLabel("conflictDriven"), objective = null, allPools())

        assertEquals(SEARCH_SIGNALS + Signal.ClauseUses, signals)
    }

    @Test
    fun `a materialized arm should record the clause traffic of its search`() {
        val problem = Problem(
            numBoolVars = 6,
            numIntVars = 0,
            intDomains = emptyArray(),
            factors = arrayOf<Factor>(Cardinality(IntArray(6) { Lit.make(it, true) }, min = 3, max = 6)),
        ).bake()
        val worker = BacktrackWorkerConfig(BacktrackCatalog.byLabel("conflictDriven")).materialize(
            problem,
            index = 0,
            armId = 0,
            seed = 0L,
            lsLambda = 1.0,
            objective = LinearObjective(boolWeights = longArrayOf(4L, 1L, 6L, 2L, 5L, 3L)),
            lsObjective = null,
            definitionalSweep = null,
            onEvent = null,
            pools = allPools(),
        )

        worker.use { it.improvements({ Double.POSITIVE_INFINITY }, Cancellation.Never).last() }

        val sharing = assertNotNull(worker.sharingMeter).snapshot()
        assertTrue(SharingChannel.Clauses in sharing.channels)
        assertTrue(sharing.clauses.getValue("sessions") > 0L)
    }
    @Test
    fun `a worker should account for a consumed peer import and release its filter`() {
        for (objective in listOf(null, LinearObjective(boolWeights = longArrayOf(1L, 1L)))) {
            val problem = Problem(
                numBoolVars = 2,
                numIntVars = 0,
                intDomains = emptyArray(),
                factors = listOf(
                    Clause(intArrayOf(Lit.make(0, true), Lit.make(1, true))),
                    Clause(intArrayOf(Lit.make(0, true), Lit.make(1, false))),
                ),
            ).bake()
            val pool = SharedClausePool(measureMemory = true)
            pool.publish(listOf(SharedClause(intArrayOf(Lit.make(0, true)), LongArray(0), 1)), origin = 1)
            val worker = BacktrackWorkerConfig(BacktrackCatalog.byLabel("conflictDriven")).materialize(
                problem, index = 0, armId = 0, seed = 0L, lsLambda = 1.0,
                objective = objective,
                lsObjective = null, definitionalSweep = null, onEvent = null,
                pools = SharedPools(clauses = pool, cuts = null, bounds = null, varBounds = null),
            )

            worker.use {
                if (objective == null) it.solve(Cancellation.Never)
                else it.improvements({ Double.POSITIVE_INFINITY }, Cancellation.Never).last()
            }

            val stats = assertNotNull(worker.sharingMeter).snapshot()
            assertTrue(stats.channels.getValue(SharingChannel.Clauses).imported > 0L)
            assertTrue(stats.clauses.getValue("uses") > 0L)
            assertTrue(stats.clauses.getValue("peakLearnedPayloadBytes") > 0L)
            assertEquals(1L, stats.clauses.getValue("peakSharedLearningObserved"))
            assertTrue(stats.clauses.getValue("peakReasonIndexCapacityBytes") > 0L)
            assertTrue(stats.clauses.getValue("peakReasonJournalCapacityBytes") > 0L)
            assertTrue(stats.clauses.getValue("peakSharedLearnedIndexCapacityBytes") > 0L)
            assertTrue(stats.clauses.getValue("peakSharedReasonIndexCapacityBytes") > 0L)
            assertEquals(0L, stats.clauses.getValue("seenCapacityBytes"))
            assertEquals(1L, stats.clauses.getValue("memorySampleStale"))
        }
    }
}
