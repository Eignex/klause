package com.eignex.klause.theory

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.RealAbs
import com.eignex.klause.factor.arithmetic.RealArrayMinMax
import com.eignex.klause.factor.arithmetic.RealFiniteDomain
import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.factor.table.RealElement
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.FloatSemantics
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceSemantics
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.localsearch.Completion
import com.eignex.klause.lp.engine.LpSolveContext
import com.eignex.klause.portfolio.LeafRealCompletion
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.solver.result.MinimizeResult
import com.eignex.klause.solver.validation.ToleranceSearchResult
import com.eignex.klause.solver.validation.capturedRealAcceptance
import com.eignex.klause.solver.validation.unreadRelation
import com.eignex.klause.util.ExecutionControl
import com.eignex.klause.util.WorkMeter
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AbsRealCompletionTest {
    @Test
    fun `real absolute value completion should share conditioned mixed product encodings`() {
        val source = Problem(
            0,
            1,
            arrayOf(IntDomain(-1, 1)),
            arrayOf(
                RealProduct(0, 0, 2, -2.0, 2.0),
                RealAbs(0, 1),
            ),
            numRealVars = 3,
            realLower = doubleArrayOf(-2.0, 1.0, -1.0),
            realUpper = doubleArrayOf(2.0, 1.0, -1.0),
        )

        val result = BacktrackSolver(source.bake()).solve()

        val witness = assertIs<SolveResult.Sat>(result).assignment
        val coordinates = assertNotNull(witness.exactReals)
        assertTrue(witness.intValue(0) in setOf(-1L, 1L))
        assertEquals(BigFraction.ofLong(-witness.intValue(0)), coordinates[0])
        assertEquals(BigFraction.ONE, coordinates[1])
        assertEquals(BigFraction.MINUS_ONE, coordinates[2])
    }

    @Test
    fun `local search real absolute value completion should refute only the proposed discrete point`() {
        val source = Problem(
            0,
            1,
            arrayOf(IntDomain(-1, 1)),
            arrayOf(
                Linear(intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), doubleArrayOf(-1.0), LinearOp.EQ, 0.0),
                RealAbs(0, 1),
            ),
            numRealVars = 2,
            realLower = doubleArrayOf(-2.0, 1.0),
            realUpper = doubleArrayOf(2.0, 1.0),
        )
        val completion = LeafRealCompletion(source, null)

        val refuted = completion.complete(Sample(BooleanArray(0), longArrayOf(0)), ExecutionControl.Never)
        val sibling = completion.complete(Sample(BooleanArray(0), longArrayOf(-1)), ExecutionControl.Never)

        assertIs<Completion.Refuted>(refuted)
        assertTrue(refuted.work > 0L)
        val witness = assertIs<Completion.Witness>(sibling).sample
        assertEquals(listOf(BigFraction.MINUS_ONE, BigFraction.ONE), witness.exactReals)
    }

    @Test
    fun `finite real completion should enforce the complete magnitude under fixed integer coordinates`() {
        for (integer in listOf(-1L, 0L, 1L)) {
            val source = Problem(
                0,
                1,
                arrayOf(IntDomain(integer, integer)),
                arrayOf(
                    Linear(intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), doubleArrayOf(-1.0), LinearOp.EQ, 0.0),
                    RealAbs(0, 1),
                    Linear(intArrayOf(), doubleArrayOf(), intArrayOf(1), doubleArrayOf(1.0), LinearOp.EQ, 1.0),
                ),
                numRealVars = 2,
                realLower = doubleArrayOf(-2.0, 0.0),
                realUpper = doubleArrayOf(2.0, 2.0),
            )

            val result = BacktrackSolver(source.bake()).solve()

            if (integer == 0L) {
                assertIs<SolveResult.Unsat>(result)
            } else {
                val witness = assertIs<SolveResult.Sat>(result).assignment
                val coordinates = assertNotNull(witness.exactReals)
                assertEquals(BigFraction.ofLong(integer), coordinates[0])
                assertEquals(BigFraction.ONE, coordinates[1])
            }
        }
    }

    @Test
    fun `finite real completion should search private signs instead of accepting an arbitrary relaxation point`() {
        val source = Problem(
            0,
            0,
            emptyArray(),
            arrayOf(RealAbs(0, 1)),
            numRealVars = 2,
            realLower = doubleArrayOf(-2.0, 1.0),
            realUpper = doubleArrayOf(2.0, 1.0),
        )

        val result = BacktrackSolver(source.bake()).solve()

        val witness = assertIs<SolveResult.Sat>(result).assignment
        val coordinates = assertNotNull(witness.exactReals)
        assertTrue(coordinates[0] in listOf(BigFraction.ONE, BigFraction.MINUS_ONE))
        assertEquals(BigFraction.ONE, coordinates[1])
    }

    @Test
    fun `real absolute value completion should retain the caller allowance and discard interrupted scopes`() {
        val source = Problem(
            0,
            0,
            emptyArray(),
            arrayOf(RealAbs(0, 1)),
            numRealVars = 2,
            realLower = doubleArrayOf(-2.0, 1.0),
            realUpper = doubleArrayOf(2.0, 1.0),
        )
        val completion = AbsRealCompletion(source)
        var charged = 0L
        val stop = object : ExecutionControl() {
            override fun isCancelled(): Boolean = charged >= 7L
            override val accounting: WorkMeter get() = WorkMeter { charged += it }
        }

        val interrupted = completion.complete(Sample(BooleanArray(0), LongArray(0)), stop)
        val sibling = completion.complete(Sample(BooleanArray(0), LongArray(0)), ExecutionControl.Never)

        assertIs<AbsRealCompletion.Result.Undecided>(interrupted.result)
        assertEquals(charged, interrupted.work)
        assertTrue(charged >= 7L)
        assertIs<AbsRealCompletion.Result.Witness>(sibling.result)
        assertTrue(sibling.work > 0L)
    }

    private val tenth = SourceTolerance(SemanticRational.of(bigIntOf(1), bigIntOf(10)))

    // Each real is declared at one value, each integer at one value, and every Boolean is true.
    private fun fixed(
        factors: List<Factor>,
        reals: DoubleArray,
        ints: LongArray = longArrayOf(),
        bools: Int = 0,
        tolerance: SourceTolerance = tenth,
    ) = Problem(
        bools, ints.size, Array(ints.size) { IntDomain(ints[it], ints[it]) }, factors.toTypedArray(),
        numRealVars = reals.size, realLower = reals.copyOf(), realUpper = reals.copyOf(),
        semantics = SourceSemantics(SourceFormat.IR, floats = FloatSemantics.Continuous,
            feasibilityTolerance = tolerance).resolve(),
    )

    private fun row(real: Int, op: LinearOp, bound: Double, strict: Boolean = false) =
        Linear(intArrayOf(), doubleArrayOf(), intArrayOf(real), doubleArrayOf(1.0), op, bound, strict)


    private val unitSum =
        Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0, 1), doubleArrayOf(1.0, 1.0), LinearOp.EQ, 1.0)

    // Each relation once with its result within the tolerance of the exact value, and once beyond it.
    private fun relations(within: Boolean): Map<String, Problem> {
        fun pick(near: Double, far: Double) = if (within) near else far
        return mapOf(
            "abs" to fixed(listOf(RealAbs(0, 1)), doubleArrayOf(0.5, pick(0.7, 1.0))),
            "max" to fixed(listOf(RealArrayMinMax(2, intArrayOf(0, 1), max = true)),
                doubleArrayOf(0.5, 0.25, pick(0.7, 1.0))),
            "min" to fixed(listOf(RealArrayMinMax(2, intArrayOf(0, 1), max = false)),
                doubleArrayOf(0.5, 0.25, pick(0.45, 0.75))),
            "membership" to fixed(listOf(RealFiniteDomain(0, doubleArrayOf(0.25, 0.75))),
                doubleArrayOf(pick(0.9, 1.2))),
            "choice product" to fixed(listOf(ContinuousProduct(0, 1, 2, 0, doubleArrayOf(0.5, 1.0))),
                doubleArrayOf(0.5, 1.0, pick(0.6, 1.0))),
            "guarded choice product" to fixed(listOf(ContinuousProduct(0, 1, 2, 0, doubleArrayOf(0.5, 1.0),
                intArrayOf(Lit.make(0, true), Lit.make(0, false)))), doubleArrayOf(0.5, 1.0, pick(0.6, 1.0)),
                bools = 1),
            "selection" to fixed(listOf(RealElement(0, 0, doubleArrayOf(0.5, 2.0))), doubleArrayOf(pick(0.65, 1.0)),
                longArrayOf(1)),
            "variable selection" to fixed(listOf(RealElement(0, 2, intArrayOf(0, 1))),
                doubleArrayOf(0.5, 0.25, pick(0.7, 1.0)), longArrayOf(1)),
            "integer product" to fixed(listOf(RealProduct(0, 0, 1, 0.0, 1.0)), doubleArrayOf(0.25, pick(0.7, 1.2)),
                longArrayOf(2)),
            "row" to fixed(listOf(unitSum), doubleArrayOf(0.6, pick(0.6, 0.8))),
        )
    }

    private fun discrete(problem: Problem) = Sample(BooleanArray(problem.numBoolVars) { true },
        LongArray(problem.numIntVars) { problem.intBounds.lower(it) })

    private fun withinTolerance(problem: Problem) =
        AbsRealCompletion(problem, tenth).complete(discrete(problem), ExecutionControl.Never).result

    @Test
    fun `tolerance completion should find a point the source accepts where the exact search refutes`() {
        val accepted = relations(within = true).mapValues { (_, problem) ->
            val completed = AbsRealCompletion(problem, tenth).complete(discrete(problem), ExecutionControl.Never).result
            val witness = assertIs<AbsRealCompletion.Result.Witness>(completed)
            val reals = DoubleArray(witness.reals.size) { witness.reals[it].toDouble() }
            val sample = discrete(problem).copy(reals = reals, exactReals = witness.reals)
            witness.withinTolerance && assertNotNull(capturedRealAcceptance(problem, ExecutionControl.Never))(sample)
        }

        assertEquals(relations(within = true).mapValues { true }, accepted)
    }

    @Test
    fun `tolerance completion should refute a point beyond the source tolerance`() {
        val results = relations(within = false).mapValues { (_, problem) ->
            AbsRealCompletion(problem, tenth).complete(discrete(problem), ExecutionControl.Never).result
        }

        assertEquals(relations(within = false).mapValues { AbsRealCompletion.Result.Refuted }, results)
    }

    @Test
    fun `exact completion should refute a point only the tolerance admits`() {
        val results = relations(within = true).mapValues { (_, problem) ->
            AbsRealCompletion(problem).complete(discrete(problem), ExecutionControl.Never).result
        }

        assertEquals(relations(within = true).mapValues { AbsRealCompletion.Result.Refuted }, results)
    }

    @Test
    fun `tolerance completion should read a relative slack`() {
        val relative = SourceTolerance(relative = SemanticRational.of(bigIntOf(1), bigIntOf(10)))
        val problem = fixed(listOf(RealAbs(0, 1)), doubleArrayOf(5.0, 5.5), tolerance = relative)

        val completed = AbsRealCompletion(problem, relative).complete(discrete(problem), ExecutionControl.Never).result

        assertIs<AbsRealCompletion.Result.Witness>(completed)
    }

    @Test
    fun `a strict row should hold exactly in the tolerance search`() {
        val problem = Problem(0, 0, emptyArray(), arrayOf(row(0, LinearOp.GE, 0.5), row(0, LinearOp.LE, 0.375, true)),
            numRealVars = 1, realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0))

        assertEquals(AbsRealCompletion.Result.Refuted, withinTolerance(problem))
    }

    @Test
    fun `a row under a false activator should hold its complement exactly in the tolerance search`() {
        val reified = ReifiedRealLinear(0, intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0),
            LinearOp.LE, 0.4)
        val problem = Problem(1, 0, emptyArray(), arrayOf(reified, row(0, LinearOp.LE, 0.3)), numRealVars = 1,
            realLower = doubleArrayOf(0.0), realUpper = doubleArrayOf(1.0))

        val completed = AbsRealCompletion(problem, tenth)
            .complete(Sample(booleanArrayOf(false), longArrayOf()), ExecutionControl.Never).result

        assertEquals(AbsRealCompletion.Result.Refuted, completed)
    }

    @Test
    fun `an exact component should keep its declarations beside a relaxed one`() {
        val problem = fixed(listOf(row(0, LinearOp.LE, 0.6), unreadRelation(1, 2)), doubleArrayOf(0.7, 0.5, 0.55))

        assertEquals(AbsRealCompletion.Result.Refuted, withinTolerance(problem))
    }

    @Test
    fun `a tolerance search should read the source at the discrete point its frame lifts to`() {
        val source = fixed(listOf(RealElement(0, 0, doubleArrayOf(0.5, 2.0))), doubleArrayOf(0.65), longArrayOf(1))
        val search = SourceToleranceSearch(source, tenth)
            .reconstructedBy { it.copy(ints = longArrayOf(it.intValue(0) + 1)) }

        val found = search.complete(Sample(booleanArrayOf(), longArrayOf(0)), ExecutionControl.Never, null,
            LpSolveContext.Production)

        assertIs<ToleranceSearchResult.Witness>(found)
    }

    @Test
    fun `a tolerance search should be unavailable for a source with a free real product`() {
        val source = fixed(listOf(ContinuousProduct(0, 0, 1)), doubleArrayOf(0.5, 0.3))

        val found = SourceToleranceSearch(source, tenth).complete(Sample(booleanArrayOf(), longArrayOf()),
            ExecutionControl.Never, null, LpSolveContext.Production)

        assertEquals(ToleranceSearchResult.Unavailable, found)
    }

    @Test
    fun `exact minimization should complete an absolute value its leaf relaxation leaves off the graph`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 3)), arrayOf(RealAbs(0, 1), row(0, LinearOp.EQ, 0.5)),
            numRealVars = 2, realLower = doubleArrayOf(-1.0, 0.0), realUpper = doubleArrayOf(1.0, 3.0))

        val result = BacktrackSolver(source.bake()).minimize(LinearObjective(intCoefficients = longArrayOf(1)))

        val optimum = assertIs<MinimizeResult.Optimal>(result)
        assertEquals(BigFraction.ofDouble(0.5), optimum.sample.exactReals?.get(1))
    }
}
