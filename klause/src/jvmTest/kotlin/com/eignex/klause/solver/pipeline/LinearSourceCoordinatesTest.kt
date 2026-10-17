package com.eignex.klause.solver.pipeline

import com.eignex.klause.ir.LinearSourceObjective
import com.eignex.klause.ir.LinearSourceProblem
import com.eignex.klause.ir.LinearSourceRow
import com.eignex.klause.ir.LinearSourceTerm
import com.eignex.klause.ir.LinearSourceVariable
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceSemantics
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.evidence.SourceCoordinate
import com.eignex.klause.solver.validation.LinearSourceCheck
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.bigIntOf
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame

@RunWith(Parameterized::class)
class LinearSourceCoordinatesTest(private val realFirst: Boolean, private val exact: Boolean) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "realFirst={0}, exact={1}")
        fun variants(): List<Array<Boolean>> = listOf(
            arrayOf(false, false), arrayOf(false, true), arrayOf(true, false), arrayOf(true, true),
        )
    }

    @Test
    fun `an integral real backing should retain original typed coordinates and declaration order`() {
        val x = LinearSourceVariable("x", true, SemanticRational.ZERO, SemanticRational.ONE)
        val z = LinearSourceVariable("z", false)
        val xColumn = if (realFirst) 1 else 0
        val zColumn = 1 - xColumn
        val source = LinearSourceProblem(
            if (realFirst) listOf(z, x) else listOf(x, z),
            listOf(LinearSourceRow(listOf(LinearSourceTerm(zColumn, SemanticRational.ONE),
                LinearSourceTerm(xColumn, SemanticRational.ofLong(-3))),
                SemanticRational.ofLong(2), SemanticRational.ofLong(2))),
            LinearSourceObjective(listOf(LinearSourceTerm(zColumn, SemanticRational.ONE))),
            SourceSemantics(SourceFormat.MPS, feasibilityTolerance = if (exact) SourceTolerance() else
                SourceTolerance(absolute = SemanticRational.of(BIG_ONE, bigIntOf(10_000_000)))).resolve(),
        )

        val invocation = SourceSetup.prepare(SourceSetupRequest(SourceAttachment(source, ProblemSettings()),
            ExecutionOperation.OPTIMIZE, FiniteEngine.FIXED))
        val plan = assertNotNull(invocation.preparedPlan)
        val working = invocation.finiteShape().problem
        val values = LongArray(2) { if (it == xColumn) 1 else 5 }
        val checked = assertIs<LinearSourceCheck.Accepted>(assertNotNull(invocation.linearSource)
            .check(Sample(booleanArrayOf(), values), Cancellation.Never))

        assertSame(source, plan.originalSource)
        assertEquals(exact, assertNotNull(invocation.linearSource).claimsCoverTolerance)
        assertEquals(1, plan.sourceIntCount)
        assertEquals(1, plan.sourceRealCount)
        assertEquals(2, working.numIntVars)
        assertEquals(0, working.numRealVars)
        assertEquals(0L, working.intBounds.lower(xColumn))
        assertEquals(1L, working.intBounds.upper(xColumn))
        assertEquals(2L, working.intBounds.lower(zColumn))
        assertEquals(5L, working.intBounds.upper(zColumn))
        assertEquals(xColumn, plan.integerCoordinate(0))
        assertEquals(SourceCoordinate(SourceCoordinate.Kind.Integer, zColumn),
            plan.workingCoordinate(SourceCoordinate(SourceCoordinate.Kind.Real, 0)))
        assertFailsWith<IllegalArgumentException> { plan.realCoordinate(0) }
        assertEquals(if (realFirst) listOf(BigFraction.ofLong(5), BigFraction.ONE) else
            listOf(BigFraction.ONE, BigFraction.ofLong(5)), checked.witness.values)
        assertEquals(BigFraction.ofLong(5), checked.witness.objective)
    }
}
