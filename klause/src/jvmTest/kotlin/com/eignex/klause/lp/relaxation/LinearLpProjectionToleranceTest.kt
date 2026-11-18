package com.eignex.klause.lp.relaxation

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.SemanticRational
import com.eignex.klause.ir.SourceFormat
import com.eignex.klause.ir.SourceSemantics
import com.eignex.klause.ir.SourceTolerance
import com.eignex.klause.lp.engine.LpBoundSlack
import com.eignex.klause.lp.engine.authoritativeModel
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.incumbent.Verification
import com.eignex.klause.solver.validation.verifyCapturedRealTolerance
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.bigIntOf
import com.eignex.util.numbers.minus
import com.eignex.util.numbers.parseBigInt
import com.eignex.util.numbers.times
import com.eignex.util.numbers.unaryMinus
import org.junit.BeforeClass
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class LinearLpProjectionToleranceTest(private val upper: Boolean) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "upper={0}")
        fun variants(): List<Array<Boolean>> = listOf(arrayOf(false), arrayOf(true))

        @JvmStatic
        @BeforeClass
        fun initializeRelaxation() = LinearSourceRowToleranceTest.initializeRelaxation()
    }

    @Test
    fun `rounded wide rows should cover a tolerance crossing of a declaration sign`() {
        val coefficient = parseBigInt("18446744073709555711")
        val three = bigIntOf(3)
        val policy = SourceTolerance(SemanticRational.of(three, BIG_ONE))
        val domain = if (upper) IntDomain(-3, 0) else IntDomain(0, 3)
        val point = if (upper) 3L else -3L
        val problem = Problem(0, 1, arrayOf(domain), listOf(
            Linear(intArrayOf(0), arrayOf(if (upper) -coefficient else coefficient),
                LinearOp.LE, -(coefficient * three) - three),
        ), semantics = SourceSemantics(SourceFormat.MPS, feasibilityTolerance = policy).resolve())
        assertTrue(point >= domain.min - 3L && point <= domain.max + 3L)
        assertTrue(verifyCapturedRealTolerance(problem, Sample(booleanArrayOf(), longArrayOf(point)),
            policy, ExecutionControl.Never) is Verification.Accepted)
        val base = CpToLpRelaxation(problem, null).build(RootDomains(problem))
        val model = assertNotNull(base.model.authoritativeModel())
        val column = base.intColOf[0]
        val entry = model.entries(column).single()
        val value = BigFraction.ofLong(point) - model.column(column).origin.value
        val activity = entry.number.value * value

        val movement = model.row(entry.row).slack?.at(LpBoundSlack(3.0, 0.0)) ?: 3.0

        assertTrue(movement.isFinite())
        assertTrue(activity <= model.rhs(entry.row).value + assertNotNull(BigFraction.ofDouble(movement)))
    }
}
