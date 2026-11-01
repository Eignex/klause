package com.eignex.klause.solver.result

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.backtrack.selector.IndomainMax
import com.eignex.klause.backtrack.selector.IndomainMin
import com.eignex.klause.backtrack.selector.InputOrder
import com.eignex.klause.compile.compile
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.propagation.bake
import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.schema.allDifferent
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.kumulant.stat.summary.MaxResult
import com.eignex.kumulant.stat.summary.SumResult
import com.eignex.kumulant.stat.summary.WeightedMeanResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SolveStatsTest {

    private class Queens6 : VariableSchema() {
        val q0 by intVar(0, 5)
        val q1 by intVar(0, 5)
        val q2 by intVar(0, 5)
        val q3 by intVar(0, 5)
        val q4 by intVar(0, 5)
        val q5 by intVar(0, 5)

        val rows by constraint { allDifferent(q0, q1, q2, q3, q4, q5) }
    }

    @Test
    fun `backtrack solve should populate node and depth stats`() {
        val schema = Queens6()
        val compiled = schema.compile()
        val result = BacktrackSolver(compiled.problem.bake()).solve(BacktrackParams())
        assertTrue(result is SolveResult.Sat)
        val stats = result.stats
        assertEquals("backtrack", stats.run.backend)
        assertTrue(stats.search.nodes.sum > 0.0)
        assertTrue(stats.search.peakDepth.max >= 1.0)
        assertTrue(stats.search.depthMean.totalWeights >= 1.0)
    }

    // Pairwise != instead of AllDifferent, so refuting it needs branching.
    private fun pigeonhole(n: Int): Problem {
        val factors = buildList {
            for (i in 0 until n) {
                for (j in i + 1 until n) {
                    add(Linear(intArrayOf(1, -1), intArrayOf(i, j), LinearOp.NE, 0))
                }
            }
        }
        return Problem(
            numBoolVars = 0,
            numIntVars = n,
            intDomains = Array(n) { IntDomain(0, (n - 2).toLong()) },
            factors = factors.toTypedArray(),
        )
    }

    @Test
    fun `backtrack should count failures while proving unsat`() {
        val result = BacktrackSolver(pigeonhole(4).bake()).solve(BacktrackParams(randomSeed = 0L))
        assertIs<SolveResult.Unsat>(result)
        assertTrue(result.stats.search.fails.sum > 0.0)
    }

    @Test
    fun `branch and bound should count failures while proving optimality`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = Array(3) { IntDomain(0, 3) },
            factors = arrayOf(
                Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 4),
                Linear(intArrayOf(1, 1), intArrayOf(0, 2), LinearOp.GE, 4),
                Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.GE, 4),
            ),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(1L, 1L, 1L))
        val result = BacktrackSolver(problem.bake()).minimize(
            obj,
            BacktrackParams(randomSeed = 0L, variableSelector = InputOrder, valueSelector = IndomainMax),
        )
        val optimal = assertIs<MinimizeResult.Optimal>(result)
        assertEquals(6.0, optimal.objective)
        assertTrue(optimal.stats.search.fails.sum > 0.0)
    }

    @Test
    fun `branch and bound should count bound-pruned nodes as failures`() {
        // Without constraints, every failure comes from objective-bound pruning.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3)),
            factors = emptyArray(),
        )
        val obj = LinearObjective(intCoefficients = longArrayOf(1L, 1L))
        val result = BacktrackSolver(problem.bake()).minimize(
            obj,
            BacktrackParams(randomSeed = 0L, variableSelector = InputOrder, valueSelector = IndomainMin),
        )
        val optimal = assertIs<MinimizeResult.Optimal>(result)
        assertEquals(0.0, optimal.objective)
        assertTrue(optimal.stats.search.fails.sum > 0.0)
    }

    @Test
    fun `hint usage should keep unobserved fields absent and observed zeros through merges`() {
        val sink = OpenHintUsageSink()
        val absent = sink.snapshot()
        sink.rawConstruction(0, 0, 0, 0, 0)
        val zero = sink.snapshot()

        assertEquals(null, absent.rawClausesMax)
        assertEquals(null, absent.rawLedgerDebits)
        assertEquals(null, zero.sourceBooleansMax)
        assertEquals(null, zero.sourceClausesMax)
        assertEquals(null, zero.sourcePrimitiveEntriesMax)
        assertEquals(0L, zero.rawClausesMax)
        assertEquals(0L, zero.rawLiteralsMax)
        assertEquals(0L, zero.rawIncidencesMax)
        assertEquals(0L, zero.rawVariablesMax)
        assertEquals(0L, zero.rawLedgerDebits)
        assertEquals(zero, zero.copy())
        assertEquals(zero, absent.mergedWith(zero))
        assertEquals(zero, zero.mergedWith(absent))
        assertEquals(absent, absent.mergedWith(absent))
        sink.sourceDimensions(0, 0, 0)
        val observed = sink.snapshot()
        assertEquals(0L, observed.sourceBooleansMax)
        assertEquals(0L, observed.sourceClausesMax)
        assertEquals(0L, observed.sourcePrimitiveEntriesMax)
        assertEquals(observed, observed.copy())
        assertTrue(observed != absent)
    }

    @Test
    fun `construction maxima should survive snapshots copies and merges`() {
        val sink = OpenHintUsageSink()
        sink.sourceDimensions(9, 7, 5)
        sink.rawConstruction(3, 11, 7, 4, 19)
        val first = sink.snapshot()
        sink.sourceDimensions(4, 2, 1)
        sink.rawConstruction(2, 8, 6, 3, 13)
        val second = sink.snapshot()

        val merged = SolveStats(openHints = OpenHintStats(usage = first))
            .mergedWith(SolveStats(openHints = OpenHintStats(usage = second))).openHints.usage
        val saturated = second.copy(rawLedgerDebits = Long.MAX_VALUE).mergedWith(second)

        assertEquals(32L, second.rawLedgerDebits)
        assertEquals(9L, second.sourceBooleansMax)
        assertEquals(11L, second.rawLiteralsMax)
        assertEquals(second, second.copy())
        assertEquals(second.copy(rawLedgerDebits = 51), merged)
        assertEquals(Long.MAX_VALUE, saturated.rawLedgerDebits)
        assertEquals(second.rawLiteralsMax, saturated.rawLiteralsMax)
    }

    @Test
    fun `hint stats copies should keep usage and merge it across workers`() {
        val usage = OpenHintUsageStats(
            indexesAdmitted = 1, componentSelections = 3, gateSelections = 3,
            clauseIndexesAdmitted = 1, clauseConsultations = 3, clauseSelections = 2, clauseDeclines = 1,
            earlyPointUnavailable = 2, forwardUncovered = 3, primitiveProviderNone = 1,
        )
        val original = OpenHintStats(draws = 1, steeredSplits = 2, usage = usage)

        val copied = original.copy(steeredSplits = 7)
        val replaced = copied.copy(usage = OpenHintUsageStats())
        val merged = SolveStats(openHints = original).mergedWith(SolveStats(openHints = copied)).openHints

        assertEquals(usage, copied.usage)
        assertEquals(OpenHintUsageStats(), replaced.usage)
        assertEquals(2L, merged.draws)
        assertEquals(9L, merged.steeredSplits)
        assertEquals(
            OpenHintUsageStats(
                indexesAdmitted = 2, componentSelections = 6, gateSelections = 6,
                clauseIndexesAdmitted = 2, clauseConsultations = 6, clauseSelections = 4, clauseDeclines = 2,
                earlyPointUnavailable = 4, forwardUncovered = 6, primitiveProviderNone = 2,
            ),
            merged.usage,
        )
        assertEquals(OpenHintStats(), OpenHintStats(0, 0, 0, 0, 0))
        assertTrue(original != replaced)
    }

    @Test
    fun `usage snapshots should be immutable and cumulative`() {
        val sink = OpenHintUsageSink()
        sink.index(HintIndexOutcome.ADMITTED)
        sink.componentConsulted()
        sink.componentSelected(primitive = false, gate = true)
        sink.pointUnavailable()
        val first = sink.snapshot()

        sink.index(HintIndexOutcome.STOPPED)
        sink.pointUnavailable()
        val second = sink.snapshot()

        assertEquals(0L, first.stoppedIndexes)
        assertEquals(1L, first.earlyPointUnavailable)
        assertEquals(1L, second.stoppedIndexes)
        assertEquals(1L, second.indexesAdmitted)
        assertEquals(1L, second.componentSelections)
        assertEquals(2L, second.earlyPointUnavailable)
    }

    @Test
    fun `merge should add objective scoring observations across workers`() {
        val left = SolveStats(ls = LocalSearchStats(objectiveScoring = mapOf("indexed" to 7L, "prepared" to 1L)))
        val right = SolveStats(ls = LocalSearchStats(objectiveScoring = mapOf("indexed" to 11L, "plain" to 1L)))

        val merged = left.mergedWith(right)

        assertEquals(mapOf("indexed" to 18L, "prepared" to 1L, "plain" to 1L), merged.ls.objectiveScoring)
    }

    @Test
    fun `merge should add local search phase times by key`() {
        val left = SolveStats(ls = LocalSearchStats(phaseNanos = mapOf("seeding" to 7L, "selection" to 11L)))
        val right = SolveStats(ls = LocalSearchStats(phaseNanos = mapOf("preparation" to 3L, "selection" to 13L)))

        val merged = left.mergedWith(right)

        assertEquals(mapOf("preparation" to 3L, "seeding" to 7L, "selection" to 24L), merged.ls.phaseNanos)
        assertEquals(merged, SolveStats.EMPTY.mergedWith(merged))
        assertEquals(merged, merged.mergedWith(SolveStats.EMPTY))
    }

    @Test
    fun `merge should add ALNS stats`() {
        val left = SolveStats(alns = AlnsStats(13, 7, 17, 5, 120, 9, 8, 3))
        val right = SolveStats(alns = AlnsStats(2, 3, 5, 7, 80, 4, 3, 2))

        val merged = left.mergedWith(right)

        assertEquals(AlnsStats(15, 10, 22, 12, 200, 13, 11, 5), merged.alns)
        assertEquals(merged, SolveStats.EMPTY.mergedWith(merged))
        assertEquals(merged, merged.mergedWith(SolveStats.EMPTY))
        assertEquals(0.0, merged.ls.moves.sum)
        assertEquals(0.0, merged.search.nodes.sum)
    }

    @Test
    fun `merge should keep the lowest residual with its breakdown`() {
        val left = SolveStats(ls = LocalSearchStats(bestResidual = LocalSearchResidual(8, mapOf("Linear" to 8L))))
        val right = SolveStats(
            ls = LocalSearchStats(
                bestResidual = LocalSearchResidual(
                    3,
                    mapOf("Clause" to 3L),
                    listOf(LocalSearchResidualFactor(1, "Clause", 3, mapOf(2 to false), emptyMap())),
                ),
            ),
        )

        val merged = left.mergedWith(right)

        assertEquals(right.ls.bestResidual, merged.ls.bestResidual)
        assertEquals(right.ls.bestResidual, right.mergedWith(left).ls.bestResidual)
        assertEquals(right.ls.bestResidual, SolveStats.EMPTY.mergedWith(right).ls.bestResidual)
    }

    @Test
    fun `merge should add open theory conflict counts`() {
        val left = SolveStats(openTheoryClauses = OpenTheoryClauseStats(assertingConflicts = 7))
        val right = SolveStats(openTheoryClauses = OpenTheoryClauseStats(nonAssertingConflicts = 3))

        val merged = left.mergedWith(right).openTheoryClauses

        assertEquals(7L, merged.assertingConflicts)
        assertEquals(3L, merged.nonAssertingConflicts)
    }

    private fun stats(backend: String, nodes: Double, peak: Double, weights: Double, mean: Double, wallMs: Long) =
        SolveStats(
            run = RunStats(backend = backend, wallMs = wallMs),
            search = SearchStats(
                nodes = SumResult(nodes),
                peakDepth = MaxResult(peak),
                depthMean = WeightedMeanResult(totalWeights = weights, mean = mean),
            ),
        )

    @Test
    fun `merge should sum counters max peaks weight means and mix backends`() {
        val a = stats("backtrack", nodes = 10.0, peak = 4.0, weights = 10.0, mean = 2.0, wallMs = 7L)
        val b = stats("ls", nodes = 5.0, peak = 9.0, weights = 30.0, mean = 6.0, wallMs = 3L)
        val m = a.mergedWith(b)
        assertEquals(15.0, m.search.nodes.sum)
        assertEquals(9.0, m.search.peakDepth.max)
        assertEquals(40.0, m.search.depthMean.totalWeights)
        assertEquals(5.0, m.search.depthMean.mean)
        assertEquals(7L, m.run.wallMs)
        assertEquals("mixed", m.run.backend)
        assertEquals("backtrack", a.mergedWith(a).run.backend)
    }

    @Test
    fun `merge should add LP counters and keep the tightest finite root bound`() {
        val a = SolveStats(lp = LpStats(solves = SumResult(8.0), pruned = SumResult(5.0), rootBound = 12.0, ms = 4L))
        val b = SolveStats(lp = LpStats(solves = SumResult(3.0), pruned = SumResult(1.0), rootBound = 15.0, ms = 6L))
        val unbounded = SolveStats(lp = LpStats(solves = SumResult(1.0)))

        val m = a.mergedWith(b)

        assertEquals(11.0, m.lp.solves.sum)
        assertEquals(6.0, m.lp.pruned.sum)
        assertEquals(15.0, m.lp.rootBound)
        assertEquals(10L, m.lp.ms)
        assertEquals(12.0, a.mergedWith(unbounded).lp.rootBound)
    }

    @Test
    fun `merge should ignore a zero weight mean`() {
        val a = stats("ls", nodes = 0.0, peak = Double.NEGATIVE_INFINITY, weights = 0.0, mean = Double.NaN, wallMs = 5L)
        val b = stats("ls", nodes = 2.0, peak = 3.0, weights = 4.0, mean = 1.5, wallMs = 1L)
        val m = a.mergedWith(b)
        assertEquals(1.5, m.search.depthMean.mean)
        assertTrue(m.run.timedOut.not())
    }

    @Test
    fun `withPresolve should attach presolve LP work once`() {
        val presolve = PresolveStats(lpStats = LpStats(rootPasses = SumResult(2.0)))

        val once = SolveStats.EMPTY.withPresolve(presolve)
        val twice = once.withPresolve(presolve)

        assertEquals(2.0, once.lp.rootPasses.sum)
        assertEquals(2.0, twice.lp.rootPasses.sum)
        assertEquals(presolve, twice.presolve)
    }

    @Test
    fun `merge should add SMT counters and source work`() {
        val left = SolveStats(
            smt = SmtStats(
                reductionNs = 7,
                sourceLp = SourceLpWorkStats(operations = 2, floatWork = 11, modeledWork = 13),
                realGraphOpenings = 2,
                graphPointHintCaptures = 3,
                lpPointHintCaptures = 5,
                defaultGraphHintReleases = 7,
            ),
        )
        val right = SolveStats(
            smt = SmtStats(
                reductionNs = 17,
                sourceLp = SourceLpWorkStats(operations = 3, floatWork = 19, modeledWork = 23),
                realGraphOpenings = 11,
                graphPointHintCaptures = 13,
                lpPointHintCaptures = 17,
                defaultGraphHintReleases = 19,
            ),
        )

        val merged = left.mergedWith(right).smt

        assertEquals(5L, merged.sourceLp.operations)
        assertEquals(24L, merged.reductionNs)
        assertEquals(30L, merged.sourceLp.floatWork)
        assertEquals(36L, merged.sourceLp.modeledWork)
        assertEquals(13L, merged.realGraphOpenings)
        assertEquals(16L, merged.graphPointHintCaptures)
        assertEquals(22L, merged.lpPointHintCaptures)
        assertEquals(26L, merged.defaultGraphHintReleases)
    }
}
