package com.eignex.klause.theory.qflra

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.solver.search.ComponentCheck
import com.eignex.klause.solver.search.ComponentResult
import com.eignex.klause.solver.search.SearchDecision
import com.eignex.klause.solver.search.SearchSession
import com.eignex.klause.util.Bits
import com.eignex.klause.util.Cancellation
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ExactLiraSearchComponentTest {

    @Test
    fun `decided reified row conflicts with unconditional rows and explains assigned literals`() {
        val component = ExactLiraSearchComponent(partialModel())
        val session = SearchSession(listOf(component))

        assertIs<ComponentResult.Consistent>(session.initialize())
        val conflict = assertIs<ComponentResult.Conflict>(
            session.push(SearchDecision.Bool(Lit.make(0, positive = true))),
        )

        assertContentEquals(intArrayOf(Lit.make(0, positive = false)), conflict.explanation?.literals)
        assertNull(session.model().valueOf<ExactLiraAssignment>(component))
    }

    @Test
    fun `undecided reified row is relaxed and retraction checks its decided complement`() {
        val session = SearchSession(listOf(ExactLiraSearchComponent(partialModel())))

        assertIs<ComponentResult.Consistent>(session.initialize())
        assertIs<ComponentResult.Conflict>(session.push(SearchDecision.Bool(Lit.make(0, positive = true))))
        session.popTo(0)

        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(0, positive = false))))
    }

    @Test
    fun `feasible partial check does not contribute a witness`() {
        val model = Problem(
            numBoolVars = 2,
            intBounds = openBounds(),
            factors = arrayOf(
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 0),
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 10),
            ),
        )
        val component = ExactLiraSearchComponent(model)
        val session = SearchSession(listOf(component))

        assertIs<ComponentResult.Consistent>(session.initialize())
        assertIs<ComponentResult.Consistent>(
            session.push(SearchDecision.Bool(Lit.make(0, positive = true))),
        )

        assertIs<ComponentCheck.Indeterminate>(session.check())
        assertNull(session.model().valueOf<ExactLiraAssignment>(component))
    }

    @Test
    fun `partial check observes cancellation`() {
        val session = SearchSession(
            components = listOf(ExactLiraSearchComponent(partialModel())),
            cancellation = Cancellation { true },
        )

        assertIs<ComponentResult.Indeterminate>(session.initialize())
    }

    @Test
    fun `strict affine ray is refuted without exhausting the split budget`() {
        val model = Problem(
            numBoolVars = 3,
            intBounds = openBounds(2),
            factors = arrayOf(
                ReifiedLinear(0, intArrayOf(2, -2), intArrayOf(0, 1), LinearOp.EQ, 1),
                ReifiedRealLinear(
                    aux = 1,
                    vars = intArrayOf(),
                    intCoeffs = doubleArrayOf(),
                    realVars = intArrayOf(0),
                    realCoeffs = doubleArrayOf(1.0),
                    op = LinearOp.GE,
                    bound = 0.0,
                    strict = true,
                ),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(Double.NEGATIVE_INFINITY),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
        )
        val session = SearchSession(listOf(ExactLiraSearchComponent(model)), maxChecks = 2)

        assertIs<ComponentResult.Consistent>(session.initialize())
        assertIs<ComponentResult.Consistent>(session.push(SearchDecision.Bool(Lit.make(1, positive = true))))

        assertIs<ComponentResult.Conflict>(
            session.push(SearchDecision.Bool(Lit.make(0, positive = true))),
        )
    }

    private fun partialModel(): Problem = Problem(
        numBoolVars = 2,
        intBounds = openBounds(),
        factors = arrayOf(
            Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 1),
            ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 0),
        ),
    )

    private fun openBounds(size: Int = 1): IntBounds {
        val open = Bits(size).also { bits -> repeat(size, bits::set) }
        return IntBounds.fromModelBounds(LongArray(size), LongArray(size), open, open)
    }
}
