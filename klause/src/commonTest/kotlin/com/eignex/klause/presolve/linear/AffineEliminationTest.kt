package com.eignex.klause.presolve.linear

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.ComparisonClause
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.table.Element
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.presolve.AffinePivotOrder
import com.eignex.klause.presolve.BakeConfig
import com.eignex.klause.presolve.Presolve
import com.eignex.klause.presolve.PresolveShared.withPassDelta
import com.eignex.klause.presolve.PresolveShared.withSourcePassDelta
import com.eignex.klause.presolve.asSampleLift
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.Propagator
import com.eignex.klause.propagation.bake
import com.eignex.klause.propagation.propagate
import com.eignex.klause.propagation.propagatorProjection
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AffineEliminationTest {
    @Test
    fun `unit affine views should retain native comparison disjunctions and source reconstruction`() {
        for (finite in listOf(false, true)) for (scale in listOf(-1, 1)) for (op in LinearOp.entries) {
            val source = Problem(0, 3, Array(3) { IntDomain(-4, 4) }, listOf(
                Linear(intArrayOf(1, -scale), intArrayOf(0, 1), LinearOp.EQ, 2),
                ComparisonClause(intArrayOf(0, 0, 2), arrayOf(op, LinearOp.EQ, LinearOp.EQ),
                    longArrayOf(2, 0, 1)),
            ))
            val reduced: Problem
            val reconstruct: (Sample) -> Sample
            if (finite) {
                val baked = source.bake()
                val delta = AffineSingletons.eliminateAffineSingletons(baked, objectiveIntVars = setOf(1, 2),
                    pivotOrder = AffinePivotOrder.REWRITE_COST)
                reduced = baked.withPassDelta(delta, BakeConfig.NONE)
                reconstruct = assertNotNull(delta.reconstruct)
            } else {
                val delta = AffineSingletons.eliminateSourceAffineSingletons(source, objectiveIntVars = setOf(1, 2),
                    pivotOrder = AffinePivotOrder.REWRITE_COST)
                reduced = assertNotNull(source.withSourcePassDelta(delta))
                reconstruct = assertNotNull(delta.rebuild.asSampleLift())
            }

            assertEquals(1, reduced.factors.count { it is ComparisonClause })
            for (y in listOf(-2L, 0L, 2L)) for (z in listOf(0L, 1L)) {
                val point = Sample(BooleanArray(0), longArrayOf(0, y, z))
                val lifted = reconstruct(point)

                assertEquals(scale * y + 2, lifted.ints[0])
                assertEquals(isFeasible(source, lifted), isFeasible(reduced, point), "$finite $scale $op $y $z")
            }
        }
    }

    @Test
    fun `ordinary pivot policies should retain comparison clause translations`() {
        val source = Problem(0, 2, Array(2) { IntDomain(-4, 4) }, listOf(
            Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 2),
            ComparisonClause(intArrayOf(0), arrayOf(LinearOp.NE), longArrayOf(0)),
        ))

        for (order in listOf(AffinePivotOrder.MARKOWITZ, AffinePivotOrder.STABLE_ID)) {
            val delta = AffineSingletons.eliminateSourceAffineSingletons(source, objectiveIntVars = setOf(1),
                pivotOrder = order)

            assertTrue(delta.isEmpty)
        }
    }

    @Test
    fun `rewriting an asserted definition through a channel should preserve both channel branches`() {
        for (finite in listOf(false, true)) for (op in LinearOp.entries) {
            val source = Problem(1, 3, Array(3) { IntDomain(0, 4) }, listOf(
                Linear(intArrayOf(1, -1, -1), intArrayOf(0, 1, 2), LinearOp.EQ, 0),
                ReifiedLinear(0, intArrayOf(2, -1), intArrayOf(0, 1), op, 2),
            ))
            val reduced: Problem
            val reconstruct: (Sample) -> Sample
            if (finite) {
                val baked = source.bake()
                val delta = AffineSingletons.eliminateAffineSingletons(baked, objectiveIntVars = setOf(1, 2),
                    pivotOrder = AffinePivotOrder.REWRITE_COST)
                assertFalse(delta.isEmpty)
                reduced = baked.withPassDelta(delta, BakeConfig.NONE)
                reconstruct = assertNotNull(delta.reconstruct)
            } else {
                val delta = AffineSingletons.eliminateSourceAffineSingletons(source, objectiveIntVars = setOf(1, 2),
                    pivotOrder = AffinePivotOrder.REWRITE_COST)
                assertFalse(delta.isEmpty)
                reduced = assertNotNull(source.withSourcePassDelta(delta))
                reconstruct = assertNotNull(delta.rebuild.asSampleLift())
            }

            for ((a, b) in listOf(0L to 0L, 0L to 1L, 1L to 1L)) for (truth in listOf(false, true)) {
                val sum = a + 2 * b
                val holds = when (op) {
                    LinearOp.LE -> sum <= 2
                    LinearOp.EQ -> sum == 2L
                    LinearOp.GE -> sum >= 2
                    LinearOp.NE -> sum != 2L
                }
                val point = Sample(booleanArrayOf(truth), longArrayOf(0, a, b))

                assertEquals(holds == truth, isFeasible(reduced, point))
                if (holds == truth) assertTrue(isFeasible(source, reconstruct(point)))
            }
        }
    }

    @Test
    fun `a conditional equality should not supply an asserted affine pivot`() {
        val source = Problem(1, 2, Array(2) { IntDomain(0, 4) },
            listOf(ReifiedLinear(0, intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 1)))

        val delta = AffineSingletons.eliminateSourceAffineSingletons(source,
            pivotOrder = AffinePivotOrder.REWRITE_COST)

        assertTrue(delta.isEmpty)
    }

    @Test
    fun `an overflowing channel coefficient should retain the asserted definition`() {
        val source = Problem(1, 3, Array(3) { IntDomain(0, 4) }, listOf(
            Linear(intArrayOf(1, -2, -1), intArrayOf(0, 1, 2), LinearOp.EQ, 0),
            ReifiedLinear(0, longArrayOf(Long.MAX_VALUE), intArrayOf(0), LinearOp.LE, 1),
        ))

        val delta = AffineSingletons.eliminateSourceAffineSingletons(source, objectiveIntVars = setOf(1, 2),
            pivotOrder = AffinePivotOrder.REWRITE_COST)

        assertTrue(delta.isEmpty)
    }

    @Test
    fun `unnegatable affine constants should retain their source equality`() {
        for ((coefficients, bound) in listOf(
            longArrayOf(1, -1) to Long.MIN_VALUE,
            longArrayOf(1, Long.MIN_VALUE) to 0L,
        )) {
            val source = Problem(0, 2, arrayOf(IntDomain(Long.MIN_VALUE, Long.MIN_VALUE + 1), IntDomain(0, 1)),
                listOf(Linear(coefficients, intArrayOf(0, 1), LinearOp.EQ, bound)))

            val delta = AffineSingletons.eliminateSourceAffineSingletons(source,
                pivotOrder = AffinePivotOrder.REWRITE_COST)

            assertTrue(delta.isEmpty)
        }
    }

    @Test
    fun `overflowing carried bounds should retain the source definition`() {
        val source = Problem(0, 2, arrayOf(IntDomain(-2, 0), IntDomain(Long.MIN_VALUE, -Long.MAX_VALUE)),
            listOf(Linear(longArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, Long.MAX_VALUE)))

        val delta = AffineSingletons.eliminateSourceAffineSingletons(source, objectiveIntVars = setOf(1),
            pivotOrder = AffinePivotOrder.REWRITE_COST)

        assertTrue(delta.isEmpty)
    }

    @Test
    fun `wide residue intermediates should not exclude representable source values`() {
        val source = Problem(0, 2, arrayOf(IntDomain(0, Long.MAX_VALUE), IntDomain(-1, 1)),
            listOf(Linear(longArrayOf(2, Long.MAX_VALUE), intArrayOf(0, 1), LinearOp.EQ, Long.MAX_VALUE)))

        val delta = AffineSingletons.eliminateAffineSingletons(source.bake(),
            pivotOrder = AffinePivotOrder.REWRITE_COST)

        assertTrue(delta.isEmpty)
    }

    @Test
    fun `affine elimination should fold declared rows and reconstruct a feasible assignment`() {
        val rows = listOf(
            Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 1),
            Linear(intArrayOf(2, 1), intArrayOf(0, 1), LinearOp.LE, 7),
        )
        val factors = rows.map { object : Factor by it, Propagator by it.propagatorProjection() {} }
        val model = Problem(0, 2, Array(2) { IntDomain(0, 3) }, factors)

        checkRoundTrip("declared rows", model, expectEliminated = true, expectSat = true)
    }

    private fun isFeasible(problem: Problem, sample: Sample): Boolean {
        var a = Assumptions.None
        for (v in 0 until problem.numBoolVars) a = a.withBool(v, sample.bools[v])
        for (v in 0 until problem.numIntVars) a = a.withInt(v, sample.ints[v])
        return problem.propagate(a) !is PropagationResult.Unsat
    }

    private fun verdictSat(problem: Problem): Boolean =
        BacktrackSolver(problem.bake()).solve(BacktrackParams()) is SolveResult.Sat

    private fun checkRoundTrip(name: String, original: Problem, expectEliminated: Boolean, expectSat: Boolean) {
        val baked = original.bake()
        val delta = Presolve.eliminateAffineSingletons(baked)
        assertEquals(expectEliminated, !delta.isEmpty, "$name: elimination expectation wrong")
        val reduced = baked.withPassDelta(delta, BakeConfig.NONE)
        val reconstruct = delta.reconstruct ?: { it }
        assertEquals(expectSat, verdictSat(original), "$name: original verdict unexpected")
        assertEquals(verdictSat(original), verdictSat(reduced), "$name: verdict changed by elimination")
        if (verdictSat(reduced)) {
            val solved = BacktrackSolver(reduced.bake()).solve(BacktrackParams())
            check(solved is SolveResult.Sat)
            val full = reconstruct(solved.assignment)
            assertTrue(isFeasible(original, full), "$name: reconstructed sample infeasible in original")
        }
    }

    private fun checkFeasibleSetPreserved(name: String, original: Problem) {
        val baked = original.bake()
        val delta = Presolve.eliminateAffineSingletons(baked)
        assertTrue(!delta.isEmpty, "$name: expected an elimination")
        val reduced = baked.withPassDelta(delta, BakeConfig.NONE)
        val reconstruct = delta.reconstruct ?: { it }
        val origFeasible = feasibleSet(original)
        val reconstructed = HashSet<List<Long>>()
        enumerate(reduced.rootIntDomains()) { assign ->
            if (isFeasible(reduced, Sample(BooleanArray(0), assign))) {
                val full = reconstruct(Sample(BooleanArray(0), assign.copyOf()))
                assertTrue(isFeasible(original, full), "$name: reconstructed $full infeasible in original")
                reconstructed.add(full.ints.toList())
            }
        }
        assertEquals(origFeasible, reconstructed, "$name: feasible set not preserved")
    }

    private fun feasibleSet(problem: Problem): Set<List<Long>> {
        val out = HashSet<List<Long>>()
        enumerate(problem.finiteIntDomains()) { assign ->
            if (isFeasible(problem, Sample(BooleanArray(0), assign))) out.add(assign.toList())
        }
        return out
    }

    private fun enumerate(domains: Array<IntDomain>, body: (LongArray) -> Unit) {
        val n = domains.size
        val assign = LongArray(n) { domains[it].min }
        while (true) {
            body(assign)
            var i = 0
            while (i < n) {
                if (assign[i] < domains[i].max) {
                    assign[i]++
                    break
                }
                assign[i] = domains[i].min
                i++
            }
            if (i == n) return
        }
    }

    @Test
    fun `a holed pivot with one partner should keep only partner values that rebuild onto its domain`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(
                IntDomain(3, 7).excludeValues(longArrayOf(4, 5, 6))!!,
                IntDomain(1, 3),
                IntDomain(0, 1),
            ),
            factors = listOf(
                Linear(intArrayOf(1, -2), intArrayOf(0, 1), LinearOp.EQ, 1),
                Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.LE, 4),
            ),
        )
        checkFeasibleSetPreserved("holed pivot, one partner", problem)
    }

    @Test
    fun `a holed pivot with several partners should stay in the model`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(
                IntDomain(0, 4).excludeValues(longArrayOf(1, 2, 3))!!,
                IntDomain(0, 2),
                IntDomain(0, 2),
            ),
            factors = listOf(Linear(intArrayOf(1, -1, -1), intArrayOf(0, 1, 2), LinearOp.EQ, 0)),
        )
        checkFeasibleSetPreserved("holed pivot, two partners", problem)
    }

    @Test
    fun `affine elimination should fold a substitution whose product coefficient exceeds Int range`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 300000), IntDomain(0, 2)),
            factors = listOf(
                Linear(intArrayOf(1, -100000), intArrayOf(0, 1), LinearOp.EQ, 0),
                Linear(intArrayOf(100000, 1), intArrayOf(0, 1), LinearOp.LE, 500000),
            ),
        )
        checkRoundTrip("wide fold", problem, expectEliminated = true, expectSat = true)
    }

    @Test
    fun `affine elimination should skip a fold whose product coefficient would overflow Long`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 10_000_000_000L), IntDomain(0, 2)),
            factors = listOf(
                Linear(longArrayOf(1, -5_000_000_000L), intArrayOf(0, 1), LinearOp.EQ, 0),
                Linear(longArrayOf(5_000_000_000L, 1), intArrayOf(0, 1), LinearOp.LE, 10_000_000_000L),
            ),
        )
        checkRoundTrip("long-overflow fold", problem, expectEliminated = false, expectSat = true)
    }

    @Test
    fun `an equation whose vars both appear in a non-linear factor should not be eliminated`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 5), IntDomain(0, 3), IntDomain(0, 5)),
            factors = listOf(
                Linear(intArrayOf(1, -2), intArrayOf(0, 1), LinearOp.EQ, 1),
                AllDifferent(intArrayOf(0, 1, 2), domainMin = 0, domainSize = 6),
            ),
        )
        checkRoundTrip("vars-in-global", problem, expectEliminated = false, expectSat = true)
    }

    @Test
    fun `an alias x = y should be substituted out of a non-linear factor`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3)),
            factors = listOf(
                Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 0),
                AllDifferent(intArrayOf(0, 2), domainMin = 0, domainSize = 4),
            ),
        )
        checkRoundTrip("alias-into-global", problem, expectEliminated = true, expectSat = true)
    }

    @Test
    fun `an alias should stay when remapping its linear row would overflow`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 1), IntDomain(0, 1)),
            factors = listOf(
                Linear(intArrayOf(1, -1), intArrayOf(0, 1), LinearOp.EQ, 0),
                Linear(longArrayOf(Long.MAX_VALUE, Long.MAX_VALUE), intArrayOf(0, 1), LinearOp.LE, Long.MAX_VALUE),
            ),
        )

        assertTrue(Presolve.eliminateAffineSingletons(problem.bake()).isEmpty)
    }

    @Test
    fun `a scaled element index should not be affine-substituted`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(1, 3), IntDomain(5, 7), IntDomain(0, 1)),
            factors = listOf(
                Linear(intArrayOf(1, -2), intArrayOf(0, 2), LinearOp.EQ, 1),
                Linear(intArrayOf(1), intArrayOf(2), LinearOp.LE, 1), // keeps the residue pass from acting
                Element(idx = 0, result = 1, arr = longArrayOf(5, 6, 7), arrIsVars = false, indexOffset = 1),
            ),
        )
        checkRoundTrip("element-scaled-index", problem, expectEliminated = false, expectSat = true)
    }

    @Test
    fun `chained eliminations should reconstruct correctly`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 20), IntDomain(0, 10), IntDomain(0, 4)),
            factors = listOf(
                Linear(intArrayOf(1, -2), intArrayOf(0, 1), LinearOp.EQ, 1),
                Linear(intArrayOf(1, -1), intArrayOf(1, 2), LinearOp.EQ, 1),
            ),
        )
        checkRoundTrip("chain", problem, expectEliminated = true, expectSat = true)
    }

    @Test
    fun `an n-term equality without a unit pivot should not be eliminated`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 6), IntDomain(0, 4), IntDomain(0, 3)),
            factors = listOf(Linear(intArrayOf(2, 3, 4), intArrayOf(0, 1, 2), LinearOp.EQ, 12)),
        )
        checkRoundTrip("n-term-no-unit", problem, expectEliminated = false, expectSat = true)
    }

    @Test
    fun `a contained non-unit pivot that does not divide a partner should not be eliminated`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 6), IntDomain(0, 3), IntDomain(0, 3)),
            factors = listOf(Linear(intArrayOf(2, 4, 3), intArrayOf(0, 1, 2), LinearOp.EQ, 8)),
        )
        checkRoundTrip("nonunit-partner-indivisible", problem, expectEliminated = false, expectSat = true)
    }

    @Test
    fun `a contained non-unit pivot that does not divide the bound should not be eliminated`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 20), IntDomain(0, 3), IntDomain(0, 3)),
            factors = listOf(Linear(intArrayOf(2, -4, -6), intArrayOf(0, 1, 2), LinearOp.EQ, 7)),
        )
        checkRoundTrip("nonunit-bound-indivisible", problem, expectEliminated = false, expectSat = false)
    }

    @Test
    fun `a non-unit pivot should not be eliminated when neither equation var is contained`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 12), IntDomain(0, 3), IntDomain(0, 12)),
            factors = listOf(
                Linear(intArrayOf(2, -4), intArrayOf(0, 1), LinearOp.EQ, 8),
                AllDifferent(intArrayOf(0, 1, 2), domainMin = 0, domainSize = 13),
            ),
        )
        checkRoundTrip("nonunit-not-contained", problem, expectEliminated = false, expectSat = true)
    }

    @Test
    fun `a non-unit pivot in the objective should not be eliminated`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 20), IntDomain(0, 3), IntDomain(0, 3)),
            factors = listOf(Linear(intArrayOf(2, -4, -6), intArrayOf(0, 1, 2), LinearOp.EQ, 8)),
        )
        val delta = Presolve.eliminateAffineSingletons(problem.bake(), objectiveIntVars = setOf(0))
        assertTrue(delta.isEmpty)
    }

    @Test
    fun `a non-unit doubleton should be eliminated via residue-class restriction`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 6), IntDomain(0, 4)),
            factors = listOf(Linear(intArrayOf(2, 3), intArrayOf(0, 1), LinearOp.EQ, 12)),
        )
        checkRoundTrip("residue", problem, expectEliminated = true, expectSat = true)
        checkFeasibleSetPreserved("residue", problem)
    }

    @Test
    fun `a zero pivot coefficient should never be used as a divisor`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 5), IntDomain(0, 3)),
            factors = listOf(Linear(intArrayOf(0, -2), intArrayOf(0, 1), LinearOp.EQ, 1)),
        )
        checkRoundTrip("zero-pivot", problem, expectEliminated = false, expectSat = false)
    }

    private fun row(vararg terms: Pair<Int, Long>, op: LinearOp, bound: Long) = Linear(
        LongArray(terms.size) { terms[it].second },
        IntArray(terms.size) { terms[it].first },
        op,
        bound,
    )

    private fun model(n: Int, hi: Long, open: Set<Int>, vararg factors: Factor): Problem = Problem(
        numBoolVars = 0,
        intBounds = IntBounds.fromModelBounds(
            LongArray(n),
            LongArray(n) { hi },
            null,
            if (open.isEmpty()) null else Bits(n).also { b -> open.forEach { b.set(it) } },
        ),
        factors = arrayOf(*factors),
    )

    @Test
    fun `a bounded column should be eliminated and rebuilt from its partner`() {
        val problem = model(
            2,
            10L,
            emptySet(),
            row(0 to 1L, 1 to -1L, op = LinearOp.EQ, bound = 0L),
            row(0 to 1L, op = LinearOp.LE, bound = 4L),
        )

        val delta = Presolve.eliminateSourceAffineSingletons(
            problem,
            emptySet(),
            Cancellation.Never,
            AffinePivotOrder.MARKOWITZ,
        )

        assertFalse(delta.isEmpty)
        val reduced = assertNotNull(problem.withSourcePassDelta(delta))
        val ints = longArrayOf(0L, 3L)
        delta.rebuild.rebuildInto(booleanArrayOf(), ints)
        assertEquals(3L, ints[0])
        assertTrue(reduced.factors.none { f -> f.intVars.any { it == 0 } })
    }

    @Test
    fun `a column open on a side should never be a pivot`() {
        val problem = model(
            2,
            10L,
            setOf(0),
            row(0 to 1L, 1 to -1L, op = LinearOp.EQ, bound = 0L),
            row(0 to 1L, op = LinearOp.LE, bound = 4L),
        )

        val delta = Presolve.eliminateSourceAffineSingletons(
            problem,
            emptySet(),
            Cancellation.Never,
            AffinePivotOrder.MARKOWITZ,
        )

        val reduced = assertNotNull(problem.withSourcePassDelta(delta))
        assertTrue(reduced.factors.any { f -> f.intVars.any { it == 0 } })
    }

    @Test
    fun `a column with declared holes should never be a pivot`() {
        // y is an objective column, so x is the only pivot the equality offers.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 4).excludeValues(longArrayOf(1, 2, 3))!!, IntDomain(0, 10)),
            factors = listOf(
                row(0 to 1L, 1 to -1L, op = LinearOp.EQ, bound = 1L),
                row(0 to 1L, 1 to 1L, op = LinearOp.LE, bound = 9L),
            ),
        )

        val delta = Presolve.eliminateSourceAffineSingletons(
            problem,
            setOf(1),
            Cancellation.Never,
            AffinePivotOrder.MARKOWITZ,
        )

        assertTrue(delta.isEmpty)
    }
}
