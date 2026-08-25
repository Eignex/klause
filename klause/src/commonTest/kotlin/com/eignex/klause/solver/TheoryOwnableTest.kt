package com.eignex.klause.solver

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.localsearch.Invariant
import com.eignex.klause.localsearch.NoInvariant
import com.eignex.klause.propagation.Propagator
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which lane can hold a factor is the factor's own statement, not a list of classes the plan keeps. A
 * factor kind the plan has never heard of is placed by what it declares.
 */
class TheoryOwnableTest {

    private fun factorDeclaring(integer: Boolean, exact: Boolean) = object : Factor {
        override val variables: VarList = IntVars(intArrayOf(0))
        override val integerTheoryOwnable: Boolean get() = integer
        override val exactTheoryOwnable: Boolean get() = exact
        override fun remap(mapping: VarRemap): Factor = this
        override fun structuralKey(): StructuralKey = StructuralKey.of(FactorKind.LINEAR) { int(0) }
        override fun asPropagator(): Propagator = object : Propagator {}
        override fun asInvariant(): Invariant = NoInvariant
    }

    private fun specOf(vararg factors: Factor) = ProblemSpec(
        numBoolVars = 0,
        intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(9), null, null),
        factors = arrayOf(*factors),
    )

    @Test
    fun `an unknown factor kind that declares an integer theory can hold it is theory-owned`() {
        val plan = specOf(factorDeclaring(integer = true, exact = false)).componentPlan()

        assertEquals(FactorOwner.THEORY, plan.factorOwner(0))
        assertEquals(IntVariableOwner.THEORY, plan.intOwner(0))
    }

    @Test
    fun `an unknown factor kind that declares nothing is held by CP`() {
        val plan = specOf(factorDeclaring(integer = false, exact = false)).componentPlan()

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
    fun `a row whose coefficients no double states exactly is not exact`() {
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
        assertEquals(false, fractional.exactTheoryOwnable, "a fractional integer-side coefficient is not exact")
    }
}
