package com.eignex.klause.solver.result

import com.eignex.kumulant.stat.summary.MaxResult
import com.eignex.kumulant.stat.summary.SumResult
import com.eignex.kumulant.stat.summary.WeightedMeanResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SolveStatsMergeTest {

    @Test
    fun `unobserved fields remain absent and observed raw zero survives copies and merges`() {
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
    fun `construction maxima survive snapshots copies and independent slice merges`() {
        val sink = OpenHintUsageSink()
        sink.sourceDimensions(9, 7, 5)
        sink.rawConstruction(3, 11, 7, 4, 19)
        val first = sink.snapshot()
        sink.sourceDimensions(4, 2, 1)
        sink.rawConstruction(2, 8, 6, 3, 13)
        val second = sink.snapshot()

        assertEquals(19L, first.rawLedgerDebits)
        assertEquals(32L, second.rawLedgerDebits)
        assertEquals(9L, second.sourceBooleansMax)
        assertEquals(7L, second.sourceClausesMax)
        assertEquals(5L, second.sourcePrimitiveEntriesMax)
        assertEquals(3L, second.rawClausesMax)
        assertEquals(11L, second.rawLiteralsMax)
        assertEquals(7L, second.rawIncidencesMax)
        assertEquals(4L, second.rawVariablesMax)
        assertEquals(second, sink.snapshot())
        assertEquals(second, second.copy())
        assertEquals(second.copy(componentConsultations = 1), second.copy().copy(componentConsultations = 1))
        assertEquals(second, OpenHintStats(usage = second).copy(draws = 2).usage)
        val merged = SolveStats(openHints = OpenHintStats(usage = first))
            .mergedWith(SolveStats(openHints = OpenHintStats(usage = second))).openHints.usage
        assertEquals(second.copy(rawLedgerDebits = 51), merged)
        val saturated = second.copy(rawLedgerDebits = Long.MAX_VALUE).mergedWith(second)
        assertEquals(Long.MAX_VALUE, saturated.rawLedgerDebits)
        assertEquals(second.rawLiteralsMax, saturated.rawLiteralsMax)
        assertEquals(second, OpenHintUsageStats().mergedWith(second))
        assertEquals(second, second.mergedWith(OpenHintUsageStats()))
        sink.rawConstruction(0, 0, 0, 0, Long.MAX_VALUE)
        assertEquals(Long.MAX_VALUE, sink.snapshot().rawLedgerDebits)
        assertEquals(second.rawLiteralsMax, sink.snapshot().rawLiteralsMax)
    }

    @Test
    fun `legacy hint copies preserve immutable usage and merge it across workers`() {
        val usage = OpenHintUsageStats(indexesAdmitted = 1, componentSelections = 3, gateSelections = 3,
            clauseIndexesAdmitted = 1, clauseConsultations = 3, clauseSelections = 2, clauseDeclines = 1,
            earlyPointUnavailable = 2, forwardUncovered = 3, primitiveProviderNone = 1)
        val original = OpenHintStats(draws = 1, steeredSplits = 2, usage = usage)

        val copied = original.copy(steeredSplits = 7)
        val replaced = copied.copy(usage = OpenHintUsageStats())
        val merged = SolveStats(openHints = original).mergedWith(SolveStats(openHints = copied)).openHints

        assertEquals(usage, copied.usage)
        assertEquals(OpenHintUsageStats(), replaced.usage)
        assertEquals(2L, merged.draws)
        assertEquals(9L, merged.steeredSplits)
        assertEquals(OpenHintUsageStats(indexesAdmitted = 2, componentSelections = 6, gateSelections = 6,
            clauseIndexesAdmitted = 2, clauseConsultations = 6, clauseSelections = 4, clauseDeclines = 2,
            earlyPointUnavailable = 4, forwardUncovered = 6, primitiveProviderNone = 2), merged.usage)
        assertEquals(OpenHintStats(), OpenHintStats(0, 0, 0, 0, 0))
        assertTrue(original != replaced)
    }

    @Test
    fun `usage snapshots remain immutable and do not add repeated cumulative observations`() {
        val sink = OpenHintUsageSink()
        sink.index(HintIndexOutcome.ADMITTED)
        sink.componentConsulted()
        sink.componentSelected(primitive = false, gate = true)
        sink.pointUnavailable()
        sink.variableUncovered()
        sink.primitiveUnavailable()
        val first = sink.snapshot()

        assertEquals(first, sink.snapshot())
        sink.index(HintIndexOutcome.STOPPED)
        sink.pointUnavailable()
        val second = sink.snapshot()

        assertEquals(0L, first.stoppedIndexes)
        assertEquals(1L, second.stoppedIndexes)
        assertEquals(1L, second.indexesAdmitted)
        assertEquals(1L, second.componentSelections)
        assertEquals(1L, first.earlyPointUnavailable)
        assertEquals(2L, second.earlyPointUnavailable)
        assertEquals(1L, second.forwardUncovered)
        assertEquals(1L, second.primitiveProviderNone)
        assertEquals(Long.MAX_VALUE,
            OpenHintUsageStats(componentSelections = Long.MAX_VALUE)
                .mergedWith(OpenHintUsageStats(componentSelections = 1)).componentSelections)
        val saturated = OpenHintUsageStats(
            earlyPointUnavailable = Long.MAX_VALUE, forwardUncovered = Long.MAX_VALUE,
            primitiveProviderNone = Long.MAX_VALUE,
        ).mergedWith(OpenHintUsageStats(earlyPointUnavailable = 1, forwardUncovered = 1, primitiveProviderNone = 1))
        assertEquals(Long.MAX_VALUE, saturated.earlyPointUnavailable)
        assertEquals(Long.MAX_VALUE, saturated.forwardUncovered)
        assertEquals(Long.MAX_VALUE, saturated.primitiveProviderNone)
    }

    @Test
    fun `local search phase times add across workers without losing missing phases`() {
        val left = SolveStats(ls = LocalSearchStats(phaseNanos = mapOf("seeding" to 7L, "selection" to 11L)))
        val right = SolveStats(ls = LocalSearchStats(phaseNanos = mapOf("preparation" to 3L, "selection" to 13L)))

        val merged = left.mergedWith(right)

        assertEquals(mapOf("preparation" to 3L, "seeding" to 7L, "selection" to 24L), merged.ls.phaseNanos)
        assertEquals(merged, SolveStats.EMPTY.mergedWith(merged))
        assertEquals(merged, merged.mergedWith(SolveStats.EMPTY))
    }

    @Test
    fun `ALNS phase observations survive portfolio aggregation`() {
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
    fun `local search residual aggregation keeps the breakdown paired with its cost`() {
        val left = SolveStats(ls = LocalSearchStats(bestResidual = LocalSearchResidual(8, mapOf("Linear" to 8L))))
        val right = SolveStats(ls = LocalSearchStats(bestResidual = LocalSearchResidual(
            3, mapOf("Clause" to 3L),
            listOf(LocalSearchResidualFactor(1, "Clause", 3, mapOf(2 to false), emptyMap())),
        )))

        val merged = left.mergedWith(right)

        assertEquals(right.ls.bestResidual, merged.ls.bestResidual)
        assertEquals(right.ls.bestResidual, right.mergedWith(left).ls.bestResidual)
        assertEquals(right.ls.bestResidual, SolveStats.EMPTY.mergedWith(right).ls.bestResidual)
    }

    @Test
    fun `shared conflict counts add across solve rounds`() {
        val left = SolveStats(openTheoryClauses = OpenTheoryClauseStats(assertingConflicts = 7))
        val right = SolveStats(openTheoryClauses = OpenTheoryClauseStats(nonAssertingConflicts = 3))

        val merged = left.mergedWith(right).openTheoryClauses

        assertEquals(7L, merged.assertingConflicts)
        assertEquals(3L, merged.nonAssertingConflicts)
    }

    @Test
    fun `learned database reduction time adds across solve rounds`() {
        val left = SolveStats(openTheoryClauses = OpenTheoryClauseStats(reductionNanos = 13))
        val right = SolveStats(openTheoryClauses = OpenTheoryClauseStats(reductionNanos = 29))

        val merged = left.mergedWith(right).openTheoryClauses

        assertEquals(42L, merged.reductionNanos)
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
    fun `empty is the identity on both sides`() {
        val s = stats("backtrack", nodes = 10.0, peak = 4.0, weights = 10.0, mean = 2.0, wallMs = 7L)
        assertEquals(s, SolveStats.EMPTY.mergedWith(s))
        assertEquals(s, s.mergedWith(SolveStats.EMPTY))
    }

    @Test
    fun `counters add peaks max means weight-combine wall maxes backends mix`() {
        val a = stats("backtrack", nodes = 10.0, peak = 4.0, weights = 10.0, mean = 2.0, wallMs = 7L)
        val b = stats("ls", nodes = 5.0, peak = 9.0, weights = 30.0, mean = 6.0, wallMs = 3L)
        val m = a.mergedWith(b)
        assertEquals(15.0, m.search.nodes.sum)
        assertEquals(9.0, m.search.peakDepth.max)
        assertEquals(40.0, m.search.depthMean.totalWeights)
        // (2*10 + 6*30) / 40 = 5
        assertEquals(5.0, m.search.depthMean.mean)
        assertEquals(7L, m.run.wallMs)
        assertEquals("mixed", m.run.backend)
        assertEquals("backtrack", a.mergedWith(a).run.backend)
    }

    @Test
    fun `lp counters add and root bound takes the tightest finite`() {
        val a = SolveStats(
            run = RunStats(backend = "backtrack"),
            lp = LpStats(
                solves = SumResult(8.0),
                pruned = SumResult(5.0),
                infeasible = SumResult(2.0),
                rootBound = 12.0,
                ms = 4L,
            ),
        )
        val b = SolveStats(
            run = RunStats(backend = "backtrack"),
            lp = LpStats(
                solves = SumResult(3.0),
                pruned = SumResult(1.0),
                infeasible = SumResult(1.0),
                rootBound = 15.0,
                ms = 6L,
            ),
        )
        val m = a.mergedWith(b)
        assertEquals(11.0, m.lp.solves.sum)
        assertEquals(6.0, m.lp.pruned.sum)
        assertEquals(3.0, m.lp.infeasible.sum)
        assertEquals(15.0, m.lp.rootBound) // same root across workers ⇒ tightest finite bound
        assertEquals(10L, m.lp.ms)
        // NaN root bound defers to the finite side.
        assertEquals(
            12.0,
            a.mergedWith(
                SolveStats(run = RunStats(backend = "backtrack"), lp = LpStats(solves = SumResult(1.0))),
            ).lp.rootBound,
        )
    }

    @Test
    fun `zero-weight mean defers to the populated side`() {
        val a = stats("ls", nodes = 0.0, peak = Double.NEGATIVE_INFINITY, weights = 0.0, mean = Double.NaN, wallMs = 5L)
        val b = stats("ls", nodes = 2.0, peak = 3.0, weights = 4.0, mean = 1.5, wallMs = 1L)
        val m = a.mergedWith(b)
        assertEquals(1.5, m.search.depthMean.mean)
        assertTrue(m.run.timedOut.not())
    }

    @Test
    fun `presolve LP work is attached exactly once even when preparation changes nothing`() {
        val presolve = PresolveStats(lpStats = LpStats(rootPasses = SumResult(2.0)))

        val once = SolveStats.EMPTY.withPresolve(presolve)
        val twice = once.withPresolve(presolve)

        assertEquals(2.0, once.lp.rootPasses.sum)
        assertEquals(2.0, twice.lp.rootPasses.sum)
        assertEquals(presolve, twice.presolve)
    }

    @Test
    fun `SMT counters and source work add across solve slices`() {
        val left = SolveStats(
            smt = SmtStats(
                reductionNs = 7,
                sourceLp = SourceLpWorkStats(operations = 2, floatWork = 11, modeledWork = 13),
            ),
        )
        val right = SolveStats(
            smt = SmtStats(
                reductionNs = 17,
                sourceLp = SourceLpWorkStats(operations = 3, floatWork = 19, modeledWork = 23),
            ),
        )

        val merged = left.mergedWith(right).smt

        assertEquals(5L, merged.sourceLp.operations)
        assertEquals(24L, merged.reductionNs)
        assertEquals(30L, merged.sourceLp.floatWork)
        assertEquals(36L, merged.sourceLp.modeledWork)
    }
}
