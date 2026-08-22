package com.eignex.klause.formats.mps

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.formats.ObjectiveSense
import com.eignex.klause.lp.BoundedIntDomains
import com.eignex.klause.solver.Cancellation
import com.eignex.klause.solver.SolveResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MpsLoweringTest {

    private val noObjective = MpsObjective("", IntArray(0), DoubleArray(0), 0.0)

    private fun model(vararg vars: MpsVar) =
        MpsModel("m", ObjectiveSense.MINIMIZE, noObjective, vars.toList(), emptyList())

    // OBBT is deferred to the presolve phase; run it here to assert the effective bounded domains and
    // clamp flag (a fully-finite model has no deferred bounding, so its domains stand and it never clamps).
    private fun MpsCompiled.bounded(cancellation: Cancellation = Cancellation.Never): BoundedIntDomains =
        deferredBounds?.run(cancellation) ?: BoundedIntDomains(
            problem.intDomains,
            false,
            BooleanArray(problem.numIntVars),
            BooleanArray(problem.numIntVars),
        )

    @Test
    fun `keeps an unbounded float column as an open LP-only continuous variable`() {
        val compiled = model(MpsVar("x", integer = false, lower = 0.0, upper = null)).toProblem()
        assertEquals(1, compiled.floatColumns)
        assertEquals(1, compiled.problem.numRealVars)
        assertEquals(0.0, compiled.problem.realLower[0])
        assertTrue(compiled.problem.realUpper[0].isInfinite())
    }

    @Test
    fun `lowers a bounded float column to an LP-only continuous variable with its real bounds`() {
        val compiled = model(MpsVar("x", integer = false, lower = 0.0, upper = 10.0)).toProblem()
        assertEquals(1, compiled.floatColumns)
        assertEquals(1, compiled.problem.numRealVars)
        assertEquals(0, compiled.problem.numIntVars)
        assertEquals(0.0, compiled.problem.realLower[0])
        assertEquals(10.0, compiled.problem.realUpper[0])
    }

    @Test
    fun `keeps an integer column as its declared domain`() {
        val compiled = model(MpsVar("x", integer = true, lower = -3.0, upper = 7.0)).toProblem()
        assertEquals(0, compiled.floatColumns)
        assertEquals(-3L, compiled.problem.intDomains[0].min)
        assertEquals(7L, compiled.problem.intDomains[0].max)
    }

    @Test
    fun `closes an unconstrained unbounded integer column under the small-model box`() {
        // No rows and no objective: any single value is a witness, so the small-model box is
        // equisatisfiable and the model is not flagged clamped.
        val compiled = model(MpsVar("x", integer = true, lower = null, upper = null)).toProblem(searchBound = 1000L)
        assertTrue(compiled.model.intBounds.isOpenLower(0))
        assertTrue(compiled.model.intBounds.isOpenUpper(0))
        assertFalse(compiled.bounded().clamped)

        // Under an objective the box could truncate an unbounded optimum, so the lossy search
        // window applies and the model is flagged.
        val minimized = model(MpsVar("x", integer = true, lower = null, upper = null))
            .copy(objective = MpsObjective("obj", intArrayOf(0), doubleArrayOf(1.0), 0.0))
            .toProblem(searchBound = 1000L)
            .bounded()
        assertTrue(minimized.clamped)
        assertEquals(-1000L, minimized.domains[0].min)
        assertEquals(1000L, minimized.domains[0].max)
    }

    @Test
    fun `OBBT bounds an unbounded integer side a constraint bounds instead of clamping`() {
        val row = MpsConstraint("C1", intArrayOf(0), doubleArrayOf(1.0), lower = null, upper = 5.0)
        val m = MpsModel(
            "m",
            ObjectiveSense.MINIMIZE,
            noObjective,
            listOf(MpsVar("x", integer = true, lower = 0.0, upper = null)),
            listOf(row),
        )
        val d = m.toProblem(searchBound = 1_000_000L).bounded().let {
            assertFalse(it.clamped)
            it.domains[0]
        }
        // x >= 0, x <= 5: exact-certified OBBT tightens the open upper side to exactly 5, no clamp.
        assertEquals(0L, d.min)
        assertEquals(5L, d.max)
    }

    @Test
    fun `a tripped deadline skips OBBT and clamps the open side instead of tightening`() {
        // The huge bound magnitude keeps the small-model box from applying, so the lossy window shows.
        val row = MpsConstraint("C1", intArrayOf(0), doubleArrayOf(1.0), lower = null, upper = 5.0e12)
        val m = MpsModel(
            "m",
            ObjectiveSense.MINIMIZE,
            noObjective,
            listOf(MpsVar("x", integer = true, lower = 0.0, upper = null)),
            listOf(row),
        )
        // With the presolve deadline already tripped, the deferred OBBT runs no LP solves, so the open
        // upper side is clamped to the search bound rather than tightened — this is what bounds the phase.
        val bounded = m.toProblem(searchBound = 1_000L).bounded(Cancellation { true })
        assertTrue(bounded.clamped)
        assertEquals(1_000L, bounded.domains[0].max)
    }

    @Test
    fun `OBBT bounds a doubly-unbounded integer column from a two-sided constraint`() {
        val row = MpsConstraint("C1", intArrayOf(0), doubleArrayOf(1.0), lower = -4.0, upper = 8.0)
        val m = MpsModel(
            "m",
            ObjectiveSense.MINIMIZE,
            noObjective,
            listOf(MpsVar("x", integer = true, lower = null, upper = null)),
            listOf(row),
        )
        val d = m.toProblem(searchBound = 1_000_000L).bounded().let {
            assertFalse(it.clamped)
            it.domains[0]
        }
        assertEquals(-4L, d.min)
        assertEquals(8L, d.max)
    }

    @Test
    fun `OBBT tightens several open variables in one build-once sweep`() {
        // x, y both open above from 0; x <= 5 and x + y <= 12. OBBT re-solves the one relaxation per
        // column (warm-started), tightening x to 5 and y to 12 (at x = 0).
        val cap = MpsConstraint("CAP", intArrayOf(0), doubleArrayOf(1.0), lower = null, upper = 5.0)
        val sum = MpsConstraint("SUM", intArrayOf(0, 1), doubleArrayOf(1.0, 1.0), lower = null, upper = 12.0)
        val m = MpsModel(
            "m",
            ObjectiveSense.MINIMIZE,
            noObjective,
            listOf(
                MpsVar("x", integer = true, lower = 0.0, upper = null),
                MpsVar("y", integer = true, lower = 0.0, upper = null),
            ),
            listOf(cap, sum),
        )
        val bounded = m.toProblem(searchBound = 1_000_000L).bounded()
        assertFalse(bounded.clamped)
        assertEquals(5L, bounded.domains[0].max)
        assertEquals(12L, bounded.domains[1].max)
    }

    @Test
    fun `feasibility-based propagation closes a chain of open variables to a fixpoint`() {
        // x, y, z open above from 0; x <= 3, y - x <= 2, z - y <= 1. Interval propagation must iterate:
        // x <= 3 then y <= 5 then z <= 6 — each pass closes the next, no LP solve needed.
        val capX = MpsConstraint("X", intArrayOf(0), doubleArrayOf(1.0), lower = null, upper = 3.0)
        val yx = MpsConstraint("YX", intArrayOf(1, 0), doubleArrayOf(1.0, -1.0), lower = null, upper = 2.0)
        val zy = MpsConstraint("ZY", intArrayOf(2, 1), doubleArrayOf(1.0, -1.0), lower = null, upper = 1.0)
        val m = MpsModel(
            "m",
            ObjectiveSense.MINIMIZE,
            noObjective,
            listOf(
                MpsVar("x", integer = true, lower = 0.0, upper = null),
                MpsVar("y", integer = true, lower = 0.0, upper = null),
                MpsVar("z", integer = true, lower = 0.0, upper = null),
            ),
            listOf(capX, yx, zy),
        )
        val bounded = m.toProblem(searchBound = 1_000_000L).bounded()
        assertFalse(bounded.clamped)
        assertEquals(3L, bounded.domains[0].max)
        assertEquals(5L, bounded.domains[1].max)
        assertEquals(6L, bounded.domains[2].max)
    }

    @Test
    fun `drops a term-free constraint row instead of emitting an empty sum`() {
        val emptyRow = MpsConstraint("ZBESTROW", IntArray(0), DoubleArray(0), lower = null, upper = 0.0)
        val realRow = MpsConstraint("C1", intArrayOf(0), doubleArrayOf(1.0), lower = null, upper = 5.0)
        val m = MpsModel(
            "m",
            ObjectiveSense.MINIMIZE,
            noObjective,
            listOf(MpsVar("x", integer = true, lower = 0.0, upper = 10.0)),
            listOf(emptyRow, realRow),
        )
        // The `0 <= 0` placeholder row is redundant and dropped; only the real row lowers to a factor.
        assertEquals(1, m.toProblem().problem.factors.size)
    }

    // b (column 0) is binary and x (column 1) ranges over [0, 10]; the gated row `x >= 20` is infeasible on
    // its own, so whether it is enforced or relaxed is directly observable as UNSAT vs SAT. [fixB] pins b.
    private fun indicatedModel(fixB: Double, whenOne: Boolean, indicatorColumn: MpsVar) = MpsModel(
        "m",
        ObjectiveSense.MINIMIZE,
        noObjective,
        listOf(indicatorColumn, MpsVar("x", integer = true, lower = 0.0, upper = 10.0)),
        listOf(
            MpsConstraint("FIX", intArrayOf(0), doubleArrayOf(1.0), lower = fixB, upper = fixB),
            MpsConstraint(
                "GATED",
                intArrayOf(1),
                doubleArrayOf(1.0),
                lower = 20.0,
                upper = null,
                indicator = MpsIndicator(column = 0, whenOne = whenOne),
            ),
        ),
    )

    private val binaryColumn = MpsVar("b", integer = true, lower = 0.0, upper = 1.0)

    private fun MpsCompiled.solve(): SolveResult = BacktrackSolver(problem.bake()).solve(BacktrackParams())

    @Test
    fun `relaxes an indicated row when the indicator column takes the other value`() {
        val compiled = indicatedModel(fixB = 0.0, whenOne = true, indicatorColumn = binaryColumn).toProblem()

        assertIs<SolveResult.Sat>(compiled.solve())
    }

    @Test
    fun `enforces an indicated row when the indicator column takes the trigger value`() {
        val compiled = indicatedModel(fixB = 1.0, whenOne = true, indicatorColumn = binaryColumn).toProblem()

        assertIs<SolveResult.Unsat>(compiled.solve())
    }

    @Test
    fun `enforces an indicated row triggered at zero only when the column is zero`() {
        val onZero = indicatedModel(fixB = 0.0, whenOne = false, indicatorColumn = binaryColumn).toProblem()
        val onOne = indicatedModel(fixB = 1.0, whenOne = false, indicatorColumn = binaryColumn).toProblem()

        assertIs<SolveResult.Unsat>(onZero.solve())
        assertIs<SolveResult.Sat>(onOne.solve())
    }

    @Test
    fun `lowers an indicated row over a continuous column to a reified real atom`() {
        val m = MpsModel(
            "m",
            ObjectiveSense.MINIMIZE,
            noObjective,
            listOf(
                binaryColumn,
                MpsVar("y", integer = true, lower = 0.0, upper = 10.0),
                MpsVar("x", integer = false, lower = 0.0, upper = 10.0),
            ),
            listOf(
                MpsConstraint(
                    "GATED",
                    intArrayOf(1, 2),
                    doubleArrayOf(1.0, 1.5),
                    lower = null,
                    upper = 4.0,
                    indicator = MpsIndicator(column = 0, whenOne = true),
                ),
            ),
        )

        val factors = m.toProblem().problem.factors

        assertEquals(1, factors.count { it is ReifiedRealLinear })
        assertTrue(factors.none { it is Linear }, "the gated row must not be posted unconditionally")
    }

    @Test
    fun `gates on an integer indicator column whose declared bounds are wider than binary`() {
        // An integer column left without an explicit binary bound still gates on the equality test,
        // rather than costing the whole instance.
        val wide = MpsVar("b", integer = true, lower = 0.0, upper = 5.0)

        val triggered = indicatedModel(fixB = 1.0, whenOne = true, indicatorColumn = wide).toProblem()
        val relaxed = indicatedModel(fixB = 0.0, whenOne = true, indicatorColumn = wide).toProblem()

        assertIs<SolveResult.Unsat>(triggered.solve())
        assertIs<SolveResult.Sat>(relaxed.solve())
    }

    @Test
    fun `rejects an indicated row whose indicator column is continuous`() {
        val real = MpsVar("b", integer = false, lower = 0.0, upper = 1.0)
        val m = indicatedModel(fixB = 1.0, whenOne = true, indicatorColumn = real)

        assertFailsWith<MpsFormatException> { m.toProblem() }
    }

    @Test
    fun `keeps a model without indicators free of Boolean variables`() {
        val row = MpsConstraint("C1", intArrayOf(0), doubleArrayOf(1.0), lower = null, upper = 5.0)
        val m = MpsModel(
            "m",
            ObjectiveSense.MINIMIZE,
            MpsObjective("obj", intArrayOf(0), doubleArrayOf(1.0), 0.0),
            listOf(MpsVar("x", integer = true, lower = 0.0, upper = 10.0)),
            listOf(row),
        )

        val compiled = m.toProblem()

        assertEquals(0, compiled.problem.numBoolVars)
        assertTrue(compiled.objective?.boolWeights?.isEmpty() == true)
    }

    @Test
    fun `rejects a term-free row whose bound is infeasible`() {
        val badRow = MpsConstraint("BAD", IntArray(0), DoubleArray(0), lower = 5.0, upper = null)
        val m = MpsModel(
            "m",
            ObjectiveSense.MINIMIZE,
            noObjective,
            listOf(MpsVar("x", integer = true, lower = 0.0, upper = 10.0)),
            listOf(badRow),
        )
        assertFailsWith<MpsFormatException> { m.toProblem() }
    }
}
