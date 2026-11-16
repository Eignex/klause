package com.eignex.klause.solver.validation

import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.Power
import com.eignex.klause.factor.arithmetic.RealAbs
import com.eignex.klause.factor.arithmetic.RealArrayMinMax
import com.eignex.klause.factor.arithmetic.RealFiniteDomain
import com.eignex.klause.factor.arithmetic.RealProduct
import com.eignex.klause.factor.table.RealElement
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.FloatSemantics
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.RealConstants
import com.eignex.klause.ir.RealConsts
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.ir.SourceSemantics
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.solver.Sample
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.bigIntOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CapturedRealToleranceTest {
    private val tenth = SourceTolerance(SemanticRational.of(bigIntOf(1), bigIntOf(10)))

    private fun source(
        vararg factors: Factor,
        tolerance: SourceTolerance = tenth,
        reals: Int = 1,
        ints: Int = 0,
        bools: Int = 0,
        lower: Double = 0.0,
    ) = Problem.of(
        bools, ints, Array(ints) { IntDomain(0, 1) }, arrayOf(*factors), numRealVars = reals,
        realLower = DoubleArray(reals) { lower }, realUpper = DoubleArray(reals) { 1.0 },
        semantics = SourceSemantics(SourceFormat.IR, floats = FloatSemantics.Continuous,
            feasibilityTolerance = tolerance).resolve(),
    )

    private fun row(op: LinearOp, bound: Double, strict: Boolean = false, real: Int = 0) =
        Linear(intArrayOf(), doubleArrayOf(), intArrayOf(real), doubleArrayOf(1.0), op, bound, strict)

    private fun point(vararg reals: Double, ints: LongArray = longArrayOf(), bools: BooleanArray = booleanArrayOf()) =
        Sample(bools, ints, reals)

    @Test
    fun `a binary64 completion within the slack of a linear row should be accepted`() {
        val accepts = assertNotNull(capturedRealAcceptance(source(row(LinearOp.EQ, 0.25)), ExecutionControl.Never))

        assertTrue(accepts(point(0.3)))
    }

    @Test
    fun `a completion beyond the slack of a linear row should be refused`() {
        val accepts = assertNotNull(capturedRealAcceptance(source(row(LinearOp.EQ, 0.25)), ExecutionControl.Never))

        assertFalse(accepts(point(0.5)))
    }

    @Test
    fun `a completion beyond the slack of a real declaration should be refused`() {
        val wide = Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0), doubleArrayOf(1.0), LinearOp.LE, 9.0)
        val accepts = assertNotNull(capturedRealAcceptance(source(wide), ExecutionControl.Never))

        assertFalse(accepts(point(1.5)))
    }

    @Test
    fun `a strict row should keep the absolute slack as its gap without reading the slack`() {
        val accepts = assertNotNull(
            capturedRealAcceptance(source(row(LinearOp.LE, 0.25, strict = true)), ExecutionControl.Never),
        )
        val points = listOf(0.25, 0.2, 0.15, 0.1)

        val accepted = points.associateWith { accepts(point(it)) }

        assertEquals(mapOf(0.25 to false, 0.2 to false, 0.15 to true, 0.1 to true), accepted)
    }

    @Test
    fun `a strict row should hold exactly without an absolute slack`() {
        val relative = SourceTolerance(relative = SemanticRational.of(bigIntOf(1), bigIntOf(10)))
        val accepts = assertNotNull(capturedRealAcceptance(
            source(row(LinearOp.LE, 0.25, strict = true), tolerance = relative), ExecutionControl.Never))
        val points = listOf(0.25, 0.2)

        val accepted = points.associateWith { accepts(point(it)) }

        assertEquals(mapOf(0.25 to false, 0.2 to true), accepted)
    }

    @Test
    fun `a finite membership should admit a value within the slack of a member`() {
        val domain = RealFiniteDomain(0, doubleArrayOf(0.25, 0.75))
        val accepts = assertNotNull(capturedRealAcceptance(source(domain), ExecutionControl.Never))

        assertTrue(accepts(point(0.8)))
    }

    @Test
    fun `a finite membership should refuse a value between its members`() {
        val domain = RealFiniteDomain(0, doubleArrayOf(0.25, 0.75))
        val accepts = assertNotNull(capturedRealAcceptance(source(domain), ExecutionControl.Never))

        assertFalse(accepts(point(0.5)))
    }

    private val residuals = mapOf(
        "abs" to (source(RealAbs(0, 1), reals = 2) to doubleArrayOf(0.5, 0.58, 0.65)),
        "max" to (source(RealArrayMinMax(2, intArrayOf(0, 1), max = true), reals = 3) to
            doubleArrayOf(0.5, 0.25, 0.58, 0.65)),
        "min" to (source(RealArrayMinMax(2, intArrayOf(0, 1), max = false), reals = 3) to
            doubleArrayOf(0.5, 0.25, 0.33, 0.4)),
        "variable selection" to (source(RealElement(0, 2, intArrayOf(0, 1)), reals = 3, ints = 1) to
            doubleArrayOf(0.5, 0.25, 0.58, 0.65)),
        "constant selection" to (source(RealElement(0, 0, doubleArrayOf(0.5, 0.75)), ints = 1) to
            doubleArrayOf(0.58, 0.65)),
        "integer product" to (source(RealProduct(0, 0, 1, 0.0, 1.0), reals = 2, ints = 1) to
            doubleArrayOf(0.5, 0.58, 0.65)),
        "real product" to (source(ContinuousProduct(0, 1, 2), reals = 3) to doubleArrayOf(0.5, 0.5, 0.33, 0.4)),
        "power" to (source(Power(Power.Coordinate.Real(SourceRealId(0)), 2, Power.Coordinate.Real(SourceRealId(1))),
            reals = 2) to doubleArrayOf(0.5, 0.33, 0.4)),
    )

    // Each point puts its last coordinate, the relation's result, at the given value.
    private fun residualAccepted(relation: Pair<Problem, DoubleArray>, result: Double): Boolean {
        val (problem, values) = relation
        val accepts = assertNotNull(capturedRealAcceptance(problem, ExecutionControl.Never))
        val reals = values.copyOf(problem.numRealVars).also { it[problem.numRealVars - 1] = result }
        return accepts(point(*reals, ints = LongArray(problem.numIntVars) { 1L }))
    }

    @Test
    fun `a nonlinear relation should accept a result within the slack of its residual`() {
        val accepted = residuals.mapValues { (_, relation) ->
            residualAccepted(relation, relation.second[relation.second.size - 2])
        }

        assertEquals(residuals.mapValues { true }, accepted)
    }

    @Test
    fun `a nonlinear relation should refuse a result beyond the slack of its residual`() {
        val accepted = residuals.mapValues { (_, relation) -> residualAccepted(relation, relation.second.last()) }

        assertEquals(residuals.mapValues { false }, accepted)
    }

    @Test
    fun `a residual should read the slack relative to its terms`() {
        val relative = SourceTolerance(relative = SemanticRational.of(bigIntOf(1), bigIntOf(10)))
        val accepts = assertNotNull(capturedRealAcceptance(source(RealAbs(0, 1), tolerance = relative, reals = 2),
            ExecutionControl.Never))

        assertTrue(accepts(point(0.5, 0.58)))
    }

    @Test
    fun `a product with finite choices should refuse an operand beyond the slack of every member`() {
        val product = ContinuousProduct(0, 1, 2, 0, doubleArrayOf(0.5, 1.0))
        val accepts = assertNotNull(capturedRealAcceptance(source(product, reals = 3), ExecutionControl.Never))

        assertFalse(accepts(point(0.75, 0.5, 0.375)))
    }

    @Test
    fun `a product with finite choices should accept an operand within the slack of a member`() {
        val product = ContinuousProduct(0, 1, 2, 0, doubleArrayOf(0.5, 1.0))
        val accepts = assertNotNull(capturedRealAcceptance(source(product, reals = 3), ExecutionControl.Never))

        assertTrue(accepts(point(0.55, 0.5, 0.25)))
    }

    private val guardedProduct = ContinuousProduct(0, 1, 2, 0, doubleArrayOf(0.5, 1.0),
        intArrayOf(Lit.make(0, true), Lit.make(0, false)))

    @Test
    fun `a guarded choice product should accept an operand within the slack of the member its guards name`() {
        val accepts = assertNotNull(capturedRealAcceptance(source(guardedProduct, reals = 3, bools = 1),
            ExecutionControl.Never))

        assertTrue(accepts(point(0.55, 0.5, 0.25, bools = booleanArrayOf(true))))
    }

    @Test
    fun `a guarded choice product should refuse an operand beyond the slack of the member its guards name`() {
        val accepts = assertNotNull(capturedRealAcceptance(source(guardedProduct, reals = 3, bools = 1),
            ExecutionControl.Never))

        assertFalse(accepts(point(0.55, 0.5, 0.25, bools = booleanArrayOf(false))))
    }

    @Test
    fun `an odd power of a negative base should keep its sign within the slack`() {
        val cube = Power(Power.Coordinate.Real(SourceRealId(0)), 3, Power.Coordinate.Real(SourceRealId(1)))
        val accepts = assertNotNull(capturedRealAcceptance(source(cube, reals = 2, lower = -1.0),
            ExecutionControl.Never))

        assertEquals(listOf(true, false), listOf(accepts(point(-0.5, -0.2)), accepts(point(-0.5, 0.125))))
    }

    // `0.9999999^1000` is about `0.99990000499`, a power too wide to form exactly.
    @Test
    fun `a wide power should be decided against its slack by a bracket`() {
        val wide = Power(Power.Coordinate.Real(SourceRealId(0)), 1000, Power.Coordinate.Real(SourceRealId(1)))
        val accepts = assertNotNull(capturedRealAcceptance(source(wide, reals = 2), ExecutionControl.Never))
        val results = listOf(0.9999, 0.5, 0.8999000049958865, 0.8999000049938864)

        val accepted = results.map { accepts(point(0.9999999, it)) }

        assertEquals(listOf(true, false, true, false), accepted)
    }

    @Test
    fun `a zeroth power should read its result as the row r = 1`() {
        val unit = Power(Power.Coordinate.Real(SourceRealId(0)), 0, Power.Coordinate.Real(SourceRealId(1)))
        val accepts = assertNotNull(capturedRealAcceptance(source(unit, reals = 2), ExecutionControl.Never))

        assertEquals(listOf(true, false), listOf(accepts(point(0.3, 0.95)), accepts(point(0.3, 0.8))))
    }

    @Test
    fun `a relation without a tolerance reading in every real component should leave completion exact`() {
        assertNull(capturedRealAcceptance(source(unreadRelation(0, 1), reals = 2), ExecutionControl.Never))
    }

    @Test
    fun `a relation without a tolerance reading in another component should leave a row within the slack accepted`() {
        val accepts = assertNotNull(
            capturedRealAcceptance(source(row(LinearOp.EQ, 0.25), unreadRelation(1, 2), reals = 3),
                ExecutionControl.Never),
        )

        assertTrue(accepts(point(0.3, 0.5, 0.5)))
    }

    @Test
    fun `a relation without a tolerance reading should hold exactly in its own component`() {
        val accepts = assertNotNull(
            capturedRealAcceptance(source(row(LinearOp.EQ, 0.25), unreadRelation(1, 2), reals = 3),
                ExecutionControl.Never),
        )

        assertFalse(accepts(point(0.25, 0.5, 0.45)))
    }

    @Test
    fun `a row sharing a component with a relation without a tolerance reading should hold exactly`() {
        val accepts = assertNotNull(
            capturedRealAcceptance(
                source(row(LinearOp.EQ, 0.45), unreadRelation(0, 1), row(LinearOp.EQ, 0.25, real = 2), reals = 3),
                ExecutionControl.Never,
            ),
        )

        assertFalse(accepts(point(0.5, 0.5, 0.25)))
    }

    @Test
    fun `a relation without a tolerance reading that holds exactly should be accepted in its own component`() {
        val accepts = assertNotNull(
            capturedRealAcceptance(source(row(LinearOp.EQ, 0.25), unreadRelation(1, 2), reals = 3),
                ExecutionControl.Never),
        )

        assertTrue(accepts(point(0.25, 0.5, 0.5)))
    }

    @Test
    fun `a row that holds exactly in a component without a tolerance reading should be accepted`() {
        val accepts = assertNotNull(
            capturedRealAcceptance(
                source(row(LinearOp.EQ, 0.5), unreadRelation(0, 1), row(LinearOp.EQ, 0.25, real = 2), reals = 3),
                ExecutionControl.Never,
            ),
        )

        assertTrue(accepts(point(0.5, 0.5, 0.3)))
    }

    @Test
    fun `a declaration should hold exactly in a component without a tolerance reading`() {
        val accepts = assertNotNull(
            capturedRealAcceptance(source(unreadRelation(0, 1), row(LinearOp.EQ, 0.25, real = 2), reals = 3),
                ExecutionControl.Never),
        )

        assertFalse(accepts(point(1.05, 1.05, 0.25)))
    }

    @Test
    fun `a component should include every real a chain of relations reaches`() {
        val link = Linear(intArrayOf(), doubleArrayOf(), intArrayOf(0, 1), doubleArrayOf(1.0, -1.0), LinearOp.EQ, 0.0)
        val accepts = assertNotNull(
            capturedRealAcceptance(
                source(row(LinearOp.EQ, 0.45), link, unreadRelation(1, 2), row(LinearOp.EQ, 0.25, real = 3), reals = 4),
                ExecutionControl.Never,
            ),
        )

        assertFalse(accepts(point(0.5, 0.5, 0.5, 0.25)))
    }

    @Test
    fun `components that share only an integer should be judged separately`() {
        val tolerant = Linear(intArrayOf(0), doubleArrayOf(1.0), intArrayOf(0), doubleArrayOf(1.0), LinearOp.EQ, 0.25)
        val exact = Linear(intArrayOf(0), doubleArrayOf(-1.0), intArrayOf(1), doubleArrayOf(1.0), LinearOp.EQ, 0.0)
        val accepts = assertNotNull(
            capturedRealAcceptance(source(tolerant, exact, unreadRelation(1, 2), reals = 3, ints = 1),
                ExecutionControl.Never),
        )

        assertTrue(accepts(point(0.3, 0.0, 0.0, ints = longArrayOf(0))))
    }

    @Test
    fun `a real in no relation should leave an otherwise exact source without acceptance`() {
        assertNull(capturedRealAcceptance(source(unreadRelation(0, 1), reals = 3), ExecutionControl.Never))
    }

    @Test
    fun `a zero tolerance should leave completion exact`() {
        val exact = source(row(LinearOp.EQ, 0.25), tolerance = SourceTolerance())

        assertNull(capturedRealAcceptance(exact, ExecutionControl.Never))
    }
}

// `abs(x(a)) = abs(x(b))` as a disjunction of two equalities, a relation without a tolerance reading.
internal fun unreadRelation(a: Int, b: Int): Factor = object : Factor by Linear(
    intArrayOf(), doubleArrayOf(), intArrayOf(a, b), doubleArrayOf(1.0, -1.0), LinearOp.EQ, 0.0,
) {
    override val linearForm: LinearForm = LinearForm.Disjunction(listOf(1.0, -1.0).map { sign ->
        LinearRow.ofColumns(intArrayOf(), intArrayOf(a, b),
            RealConstants(RealConsts(doubleArrayOf()), RealConsts(doubleArrayOf(1.0, sign)), 0.0, false),
            LinearOp.EQ)
    })
}
