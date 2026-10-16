package com.eignex.klause.solver

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.brute.BruteForceParams
import com.eignex.klause.brute.BruteForceSolver
import com.eignex.klause.count.SampleQuality
import com.eignex.klause.count.SamplingConfig
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.BoundOverride
import com.eignex.klause.ir.BoundsOverride
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SemanticOverrides
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceSemantics
import com.eignex.klause.ir.UndeclaredIntegerBounds
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.meta.alns.Alns
import com.eignex.klause.meta.coreguided.CoreGuidedSolver
import com.eignex.klause.portfolio.Portfolio
import com.eignex.klause.portfolio.PortfolioWorker
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.incumbent.ModelIdentity
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.pipeline.OpenTheoryResult
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.result.TerminationReason
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ResultSemanticsTest {
    @Test
    fun `satisfaction verdicts should retain the effective caller interpretation`() {
        val semantics = SourceSemantics(
            SourceFormat.FLATZINC, undeclaredIntegers = UndeclaredIntegerBounds(-10, 10),
        ).resolve(SemanticOverrides(undeclaredIntegers = BoundsOverride(upper = BoundOverride.Value(20))))
        val verdicts = listOf(
            SolveResult.Sat(Sample(booleanArrayOf(true), longArrayOf())),
            SolveResult.Unsat(),
            SolveResult.Unknown(TerminationReason.Cancelled),
        )

        val results = verdicts.map { it.withSourceSemantics(semantics) }

        for (result in results) assertSame(semantics, result.semantics)
        assertSame(
            assertIs<SolveResult.Sat>(verdicts.first()).assignment,
            assertIs<SolveResult.Sat>(results.first()).assignment,
        )
    }

    @Test
    fun `optimization verdicts should retain their source interpretation`() {
        val semantics = SourceSemantics(SourceFormat.IR).resolve()
        val point = Sample(booleanArrayOf(), longArrayOf())
        val verdicts = listOf(
            MinimizeResult.Optimal(point, 7.0),
            MinimizeResult.BestFound(point, 7.0, TerminationReason.Cancelled),
            MinimizeResult.Unbounded(point, 7.0, emptyList()),
            MinimizeResult.Infeasible(),
            MinimizeResult.Unknown(TerminationReason.Cancelled),
        )

        val results = verdicts.map { it.withSourceSemantics(semantics) }

        for (result in results) {
            assertSame(semantics, result.semantics)
            result.assignment?.let { assertSame(point, it) }
        }
    }

    @Test
    fun `a qualified result should reject a foreign interpretation`() {
        val semantics = SourceSemantics(SourceFormat.DIMACS).resolve()
        val result = SolveResult.Unsat().withSourceSemantics(semantics)

        assertFailsWith<IllegalArgumentException> {
            result.withSourceSemantics(SourceSemantics(SourceFormat.WCNF).resolve())
        }

        assertSame(semantics, result.semantics)
    }

    @Test
    fun `an unchanged point copy should retain ordinary result qualification`() {
        val semantics = SourceSemantics(SourceFormat.DIMACS).resolve()
        val point = Sample(booleanArrayOf(true), longArrayOf()).withSourceSemantics(semantics)

        val copied = point.copy()

        assertSame(semantics, copied.semantics)
    }

    @Test
    fun `replacing point coordinates should discard ordinary result qualification`() {
        val point = Sample(booleanArrayOf(true), longArrayOf())
            .withSourceSemantics(SourceSemantics(SourceFormat.DIMACS).resolve())

        val replaced = point.copy(bools = booleanArrayOf(false))

        assertNull(replaced.semantics)
    }

    @Test
    fun `finite satisfaction engines should qualify ordinary witnesses`() {
        val semantics = SourceSemantics(SourceFormat.DIMACS).resolve()
        val source = Problem(
            1, 0, emptyArray(), arrayOf(Clause(intArrayOf(Lit.make(0, true)))), semantics = semantics,
        )
        val model = source.bake()

        val results = listOf(
            BacktrackSolver(model).solve(BacktrackParams(randomSeed = 0)),
            LocalSearchSolver(model).solve(LocalSearchParams(randomSeed = 0, maxFlips = 1)),
            BruteForceSolver(model).solve(BruteForceParams(randomSeed = 0)),
        )

        for (result in results) {
            val witness = assertIs<SolveResult.Sat>(result)
            assertSame(semantics, witness.semantics)
        }
    }

    @Test
    fun `finite root refutations should retain source qualification`() {
        val semantics = SourceSemantics(SourceFormat.DIMACS).resolve()
        val model = Problem(1, 0, emptyArray(), arrayOf(
            Clause(intArrayOf(Lit.make(0, true))), Clause(intArrayOf(Lit.make(0, false))),
        ), semantics = semantics).bake()

        val results = listOf(
            BacktrackSolver(model).solve(BacktrackParams()),
            LocalSearchSolver(model).solve(LocalSearchParams(maxFlips = 1)),
            BruteForceSolver(model).solve(BruteForceParams()),
        )

        for (result in results) assertSame(semantics, assertIs<SolveResult.Unsat>(result).semantics)
    }

    @Test
    fun `source strategy cancellation should retain interpretation without an assignment`() {
        val semantics = SourceSemantics(SourceFormat.DIMACS).resolve()
        val source = Problem(1, 0, emptyArray(), emptyArray(), semantics = semantics)

        val result = BacktrackSolver.source(source).solve(BacktrackParams(cancellation = Cancellation { true }))

        assertSame(semantics, assertIs<OpenTheoryResult.Unknown>(result).semantics)
    }

    @Test
    fun `finite optimization engines should qualify objective results and incumbents`() {
        val semantics = SourceSemantics(SourceFormat.WCNF).resolve()
        val model = Problem(
            1, 0, emptyArray(), arrayOf(Clause(intArrayOf(Lit.make(0, true)))), semantics = semantics,
        ).bake()
        val objective = LinearObjective(boolWeights = longArrayOf(1), constant = 7)

        val results = listOf(
            BacktrackSolver(model).minimize(objective, BacktrackParams(randomSeed = 0)),
            LocalSearchSolver(model).minimize(objective, LocalSearchParams(randomSeed = 0, maxFlips = 1)),
            BruteForceSolver(model).minimize(objective, BruteForceParams(randomSeed = 0)),
        )

        for (result in results) {
            val incumbent = assertIs<MinimizeResult.WithSample>(result)
            assertSame(semantics, incumbent.semantics)
            assertEquals(8.0, incumbent.objective)
        }
    }

    @Test
    fun `a session cursor should retain policy after closing its operation`() {
        val semantics = SourceSemantics(SourceFormat.DIMACS).resolve()
        val model = Problem(1, 0, emptyArray(), emptyArray(), semantics = semantics).bake()
        val session = BacktrackSolver(model).session()

        val point = session.use { owner ->
            owner.openEnumerate(BacktrackParams(randomSeed = 0)).use { it.first() }
        }

        assertSame(semantics, point.semantics)
    }

    @Test
    fun `a retained optimization terminal should keep policy and object identity`() {
        val semantics = SourceSemantics(SourceFormat.WCNF).resolve()
        val model = Problem(1, 0, emptyArray(), emptyArray(), semantics = semantics).bake()
        val callbacks = mutableListOf<MinimizeResult.WithSample>()
        val search = BacktrackSolver(model).resumable(LinearObjective(longArrayOf(1)), BacktrackParams(randomSeed = 0))

        val result = search.use { it.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L, callbacks::add) }
        val repeated = search.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L) { error("completed search") }

        assertSame(semantics, assertIs<MinimizeResult.Optimal>(result).semantics)
        assertSame(result, repeated)
        assertTrue(callbacks.isNotEmpty())
        for (incumbent in callbacks) assertSame(semantics, incumbent.semantics)
    }

    @Test
    fun `core guided optimization should qualify reconstructed incumbents`() {
        val semantics = SourceSemantics(SourceFormat.WCNF).resolve()
        val model = Problem(1, 0, emptyArray(), emptyArray(), semantics = semantics).bake()
        val callbacks = mutableListOf<MinimizeResult.WithSample>()
        val search = CoreGuidedSolver(model).resumable(LinearObjective(longArrayOf(1)), BacktrackParams())

        val result = search.use { it.runSlice(Cancellation.Never, Long.MAX_VALUE, -1L, callbacks::add) }

        assertSame(semantics, assertIs<MinimizeResult.Optimal>(result).semantics)
        assertTrue(callbacks.isNotEmpty())
        for (incumbent in callbacks) assertSame(semantics, incumbent.semantics)
    }

    @Test
    fun `alns cancellation should retain policy without an incumbent`() {
        val semantics = SourceSemantics(SourceFormat.WCNF).resolve()
        val model = Problem(1, 0, emptyArray(), emptyArray(), semantics = semantics).bake()
        val solver = Alns(LocalSearchSolver(model), maxIterations = 0)

        val result = solver.minimize(
            LinearObjective(longArrayOf(1)), LocalSearchParams(cancellation = Cancellation { true }),
        )

        assertSame(semantics, assertIs<MinimizeResult.Unknown>(result).semantics)
    }

    @Test
    fun `a bound portfolio should qualify an aggregated cancellation`() {
        val semantics = SourceSemantics(SourceFormat.DIMACS).resolve()
        val source = Problem(1, 0, emptyArray(), emptyArray(), semantics = semantics)
        val worker = PortfolioWorker.ofSolve("source", 0) { _, _ -> error("cancelled worker") }
            .bindEvidence(ModelIdentity.of(source))

        val result = Portfolio.thompson(listOf(worker)).use { it.solve(Cancellation { true }) }

        assertSame(semantics, assertIs<SolveResult.Unknown>(result).semantics)
    }

    @Test
    fun `a portfolio without source identity should leave an aggregate unbound`() {
        val worker = PortfolioWorker.ofSolve("unbound", 0) { _, _ -> error("cancelled worker") }

        val result = Portfolio.thompson(listOf(worker)).use { it.solve(Cancellation { true }) }

        assertNull(assertIs<SolveResult.Unknown>(result).semantics)
    }

    @Test
    fun `accurate sampling should retain policy through integer channel decoding`() {
        val semantics = SourceSemantics(SourceFormat.XCSP3).resolve()
        val model = Problem(0, 1, arrayOf(IntDomain(0, 1)), emptyArray(), semantics = semantics).bake()
        val solver = BacktrackSolver(model)

        val point = solver.samples(
            SamplingConfig(quality = SampleQuality.ACCURATE, seed = 0), BacktrackParams(),
        ).first()

        assertSame(semantics, point.semantics)
        assertEquals(0, point.numBoolVars)
        assertEquals(1, point.numIntVars)
    }
}
