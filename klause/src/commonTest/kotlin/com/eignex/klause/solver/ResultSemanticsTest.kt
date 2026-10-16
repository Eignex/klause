package com.eignex.klause.solver

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.brute.BruteForceParams
import com.eignex.klause.brute.BruteForceSolver
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.BoundOverride
import com.eignex.klause.ir.BoundsOverride
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SemanticOverrides
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceSemantics
import com.eignex.klause.ir.UndeclaredIntegerBounds
import com.eignex.klause.localsearch.LocalSearchParams
import com.eignex.klause.localsearch.LocalSearchSolver
import com.eignex.klause.propagation.bake
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
        assertSame(semantics, assertIs<SolveResult.Sat>(results.first()).assignment.semantics)
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
            result.assignment?.let { assertSame(semantics, it.semantics) }
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
            assertSame(semantics, witness.assignment.semantics)
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
            assertSame(semantics, incumbent.sample.semantics)
            assertEquals(8.0, incumbent.objective)
        }
    }
}
