package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.global.Increasing
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.FactorKind
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.IntVars
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.StructuralKey
import com.eignex.klause.ir.VarList
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.theory.qflra.exactTheoryOwnable
import com.eignex.klause.util.Bits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComponentPlanTest {

    private fun factorDeclaring(complete: Boolean) = object : Factor {
        override val variables: VarList = IntVars(intArrayOf(0))
        override val linearForm: LinearForm =
            listOf(LinearRow.ofInts(intArrayOf(0), longArrayOf(1), LinearOp.LE, 5)).let { rows ->
                if (complete) LinearForm.Conjunction(rows) else LinearForm.Relaxation(rows)
            }
        override fun remap(mapping: VarRemap): Factor = this
        override fun structuralKey(): StructuralKey = StructuralKey.of(FactorKind.LINEAR) { int(0) }
    }

    private fun specOf(vararg factors: Factor) = Problem(
        numBoolVars = 0,
        intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(9), null, null),
        factors = arrayOf(*factors),
    )

    @Test
    fun `declared complete relation should keep finite variable ownership with CP`() {
        val plan = specOf(factorDeclaring(complete = true)).componentPlan()

        assertEquals(FactorOwner.THEORY, plan.factorOwner(0))
        assertEquals(IntVariableOwner.CP, plan.intOwner(0))
    }

    @Test
    fun `unknown factor kind declaring nothing should be held by CP`() {
        val plan = specOf(factorDeclaring(complete = false)).componentPlan()

        assertEquals(FactorOwner.CP, plan.factorOwner(0))
        assertEquals(IntVariableOwner.CP, plan.intOwner(0))
    }

    @Test
    fun `cardinality should be exact theory ownable but not integer theory ownable`() {
        val cardinality = Cardinality.atMostOne(intArrayOf(Lit.make(0, true), Lit.make(1, true)))

        assertEquals(true, cardinality.exactTheoryOwnable)
        assertEquals(false, cardinality.integerTheoryOwnable)
    }

    @Test
    fun `exact reader should accept finite rational coefficients on integer terms`() {
        val exact = Linear(
            intVars = intArrayOf(0),
            intCoeffs = doubleArrayOf(2.0),
            realVars = intArrayOf(0),
            realCoeffs = doubleArrayOf(1.0),
            op = LinearOp.LE,
            bound = 3.0,
        )
        val fractional = Linear(
            intVars = intArrayOf(0),
            intCoeffs = doubleArrayOf(0.5),
            realVars = intArrayOf(0),
            realCoeffs = doubleArrayOf(1.0),
            op = LinearOp.LE,
            bound = 3.0,
        )

        assertEquals(true, exact.exactTheoryOwnable)
        assertEquals(true, fractional.exactTheoryOwnable)
    }

    @Test
    fun `finite component planning should keep the specialized increasing propagator`() {
        val model = Problem(
            0,
            2,
            Array(2) { com.eignex.klause.ir.IntDomain(0, 9) },
            listOf(Increasing(intArrayOf(0, 1), strict = true)),
        )

        assertEquals(FactorOwner.CP, model.componentPlan(preferFinite = true).factorOwner(0))
        assertEquals(FactorOwner.CP, model.componentPlan().factorOwner(0))
        assertEquals(true, model.componentPlan().factorEncoded(0))
    }

    private fun tighteningFactors(): List<Factor> = listOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 3))

    @Test
    fun `variable partition should mark linear open columns theory eligible`() {
        val openUpper = Bits(1).also { it.set(0) }
        val spec = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(0), null, openUpper),
            factors = arrayOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 4)),
        )

        assertTrue(spec.variablePartition().isTheoryEligible(0))
    }

    @Test
    fun `variable partition should exclude enumerated columns from theory`() {
        val spec = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0, 0), longArrayOf(9, 9, 9), null, null),
            factors = arrayOf(
                AllDifferent(intArrayOf(0, 1), domainMin = 0, domainSize = 10),
                Linear(intArrayOf(1, 1), intArrayOf(1, 2), LinearOp.LE, 5),
            ),
        )

        val partition = spec.variablePartition()

        assertFalse(partition.isTheoryEligible(0))
        assertFalse(partition.isTheoryEligible(1))
        assertTrue(partition.isTheoryEligible(2))
    }

    @Test
    fun `component plan should keep open theory columns out of the CP projection`() {
        val openUpper = Bits(3).also { it.set(1) }
        val spec = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0, 0), longArrayOf(3, 0, 3), null, openUpper),
            factors = arrayOf(
                AllDifferent(intArrayOf(0, 2), domainMin = 0, domainSize = 4),
                Linear(intArrayOf(1), intArrayOf(1), LinearOp.LE, 4),
            ),
        )

        val plan = spec.componentPlan()
        val cp = plan.cpProjection(spec, mapOf(0 to IntDomain(0, 3), 2 to IntDomain(0, 3)))

        assertEquals(IntVariableOwner.CP, plan.intOwner(0))
        assertEquals(IntVariableOwner.THEORY, plan.intOwner(1))
        assertEquals(FactorOwner.CP, plan.factorOwner(0))
        assertEquals(FactorOwner.THEORY, plan.factorOwner(1))
        assertEquals(ProblemPipeline.DIFFERENCE_THEORY, plan.theoryPipeline)
        assertEquals(0L, spec.intBounds.lower(1))
        assertFalse(spec.intBounds.hasUpper(1))
        assertEquals(2, cp.problem.numIntVars)
        assertEquals(0, cp.cpId(0))
        assertEquals(-1, cp.cpId(1))
        assertEquals(0, cp.sourceId(0))
    }

    @Test
    fun `theory fragment should keep declared domain holes and open sides`() {
        val holey = IntDomain(0, 3).excludeValue(1)
        val spec = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(holey, IntDomain(0, 1L shl 40), holey),
            factors = arrayOf(
                AllDifferent(intArrayOf(0, 2), domainMin = 0, domainSize = 4),
                Linear(intArrayOf(1), intArrayOf(1), LinearOp.LE, 4),
            ),
            openIntHi = booleanArrayOf(false, true, false),
        )

        val plan = spec.componentPlan()
        val fragment = plan.theoryFragment(spec)

        assertEquals(IntVariableOwner.CP, plan.intOwner(0))
        assertEquals(IntVariableOwner.THEORY, plan.intOwner(1))
        assertEquals(ProblemPipeline.DIFFERENCE_THEORY, plan.theoryPipeline)
        assertEquals(holey, fragment.intDomainOrNull(0))
        assertFalse(fragment.intBounds.hasUpper(1))
        assertEquals(1, fragment.numFactors)
    }

    @Test
    fun `component plan should keep a bounded column on CP beside fractional theory rows`() {
        val spec = Problem(
            numBoolVars = 1,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(10), null, null),
            factors = arrayOf(
                ReifiedRealLinear(
                    aux = 0,
                    vars = intArrayOf(0),
                    intCoeffs = doubleArrayOf(0.5),
                    realVars = intArrayOf(0),
                    realCoeffs = doubleArrayOf(1.0),
                    op = LinearOp.LE,
                    bound = 3.0,
                ),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(3.0),
        )

        val plan = spec.componentPlan()

        assertEquals(IntVariableOwner.CP, plan.intOwner(0))
        assertEquals(FactorOwner.THEORY, plan.factorOwner(0))
    }

    @Test
    fun `component plan should reject a different model with the same shape`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 3)), tighteningFactors())
        val unrelated = source.withFactors(source.factors.copyOf())
        val plan = source.componentPlan(preferFinite = true)

        assertFailsWith<IllegalArgumentException> { plan.cpSourceDomains(unrelated) }
        assertFailsWith<IllegalArgumentException> { plan.theoryFragment(unrelated) }
    }

    @Test
    fun `withoutAppendedFactor should reject an unrelated factor prefix`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 3)), tighteningFactors())
        val appended = source.withFactors(source.factors + Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 2))
        val unrelated = source.withFactors(arrayOf(Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 1)))
        val plan = appended.componentPlan(preferFinite = true)

        assertFailsWith<IllegalArgumentException> { plan.withoutAppendedFactor(unrelated) }
    }

    @Test
    fun `forObjectiveRound should reject changed objective row coefficients`() {
        val source = Problem(0, 1, arrayOf(IntDomain(0, 3)), tighteningFactors())
        val planned = source.withFactors(source.factors + Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 2))
        val changed = source.withFactors(source.factors + Linear(intArrayOf(2), intArrayOf(0), LinearOp.LE, 2))
        val plan = planned.componentPlan(preferFinite = true)

        assertFailsWith<IllegalArgumentException> { plan.forObjectiveRound(changed, appended = true) }
    }

    @Test
    fun `forObjectiveRound should reject holes in an open declaration`() {
        val source = Problem(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(
                LongArray(1),
                LongArray(1),
                Bits(1).also { it.set(0) },
                Bits(1).also { it.set(0) },
            ),
            factors = tighteningFactors().toTypedArray(),
        )
        val holed = Problem(0, 1, arrayOf(IntDomain(0, 3).excludeValue(1)), source.factors)
        val plan = source.componentPlan()

        assertFailsWith<IllegalArgumentException> { plan.forObjectiveRound(holed, appended = false) }
    }
}
