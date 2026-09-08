package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.factor.global.Increasing
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.FactorKind
import com.eignex.klause.ir.IntBounds
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
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which lane can hold a factor is the factor's own statement, not a list of classes the plan keeps. A
 * factor kind the plan has never heard of is placed by what it declares.
 */
class TheoryOwnableTest {

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
    fun `an unknown factor kind that declares an integer theory can hold it is theory-owned`() {
        val plan = specOf(factorDeclaring(complete = true)).componentPlan()

        assertEquals(FactorOwner.THEORY, plan.factorOwner(0))
        assertEquals(IntVariableOwner.THEORY, plan.intOwner(0))
    }

    @Test
    fun `an unknown factor kind that declares nothing is held by CP`() {
        val plan = specOf(factorDeclaring(complete = false)).componentPlan()

        assertEquals(FactorOwner.CP, plan.factorOwner(0))
        assertEquals(IntVariableOwner.CP, plan.intOwner(0), "CP has to own the column of a factor it holds")
    }

    @Test
    fun `a linear row states that an integer theory holds it and a global states that none does`() {
        val row = Linear(intArrayOf(1), intArrayOf(0), LinearOp.LE, 5)
        val global = AllDifferent(intArrayOf(0, 1), domainMin = 0, domainSize = 10)

        assertEquals(true, row.integerTheoryOwnable)
        assertEquals(false, global.integerTheoryOwnable)
        assertEquals(false, global.exactTheoryOwnable)
    }

    @Test
    fun `a cardinality states that the exact lane holds it and CP still owns it without one`() {
        val cardinality = Cardinality.atMostOne(intArrayOf(Lit.make(0, true), Lit.make(1, true)))

        assertEquals(true, cardinality.exactTheoryOwnable)
        assertEquals(
            false,
            cardinality.integerTheoryOwnable,
            "a finite projection keeps its own watched propagator for the constraint",
        )
    }

    @Test
    fun `finite rational coefficients on integer terms are accepted by the exact reader`() {
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
    fun `finite component planning keeps the specialized increasing propagator`() {
        val model = Problem(
            0,
            2,
            Array(2) { com.eignex.klause.ir.IntDomain(0, 9) },
            listOf(Increasing(intArrayOf(0, 1), strict = true)),
        )

        assertEquals(FactorOwner.CP, model.componentPlan(preferFinite = true).factorOwner(0))
        assertEquals(FactorOwner.THEORY, model.componentPlan().factorOwner(0))
    }
}
