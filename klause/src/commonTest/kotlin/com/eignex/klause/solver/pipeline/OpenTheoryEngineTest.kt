package com.eignex.klause.solver.pipeline

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.formats.smtlib.SmtLib
import com.eignex.klause.solver.IntBounds
import com.eignex.klause.solver.ProblemPipeline
import com.eignex.klause.solver.ProblemSpec
import com.eignex.klause.util.Bits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class OpenTheoryEngineTest {

    @Test
    fun `open difference route executes through the planned shared session`() {
        val openUpper = Bits(1).also { it.set(0) }
        val model = ProblemSpec(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0), longArrayOf(0), null, openUpper),
            factors = emptyArray(),
        )

        val result = OpenTheoryEngine(model, ProblemPipeline.DIFFERENCE_THEORY).solve()

        val assignment = assertIs<OpenTheoryResult.Sat>(result).assignment
        assertEquals(0L, assertIs<OpenTheoryAssignment.Difference>(assignment).sample.ints[0])
    }

    @Test
    fun `open general LIA route assembles its theory assignment`() {
        val openUpper = Bits(2).also {
            it.set(0)
            it.set(1)
        }
        val model = ProblemSpec(
            numBoolVars = 0,
            intBounds = IntBounds.fromModelBounds(longArrayOf(0, 0), longArrayOf(0, 0), null, openUpper),
            factors = arrayOf(Linear(intArrayOf(2, 1), intArrayOf(0, 1), LinearOp.LE, 3)),
        )

        val result = OpenTheoryEngine(model, ProblemPipeline.GENERAL_LIA).solve()

        assertIs<OpenTheoryAssignment.GeneralLia>(assertIs<OpenTheoryResult.Sat>(result).assignment)
    }

    @Test
    fun `open general LIA refutes an integral split through the shared trail`() {
        val parsed = SmtLib.parse(
            """
                (set-logic QF_LIA)
                (declare-const x Int) (declare-const z Int)
                (assert (= (* 2 x) 1))
                (check-sat)
            """.trimIndent(),
        )

        val result = OpenTheoryEngine(parsed.model, parsed.sourcePipeline).solve()

        assertEquals(ProblemPipeline.GENERAL_LIA, parsed.sourcePipeline)
        assertIs<OpenTheoryResult.Unsat>(result)
    }

    @Test
    fun `open exact LRA route assembles its theory assignment`() {
        val model = ProblemSpec(
            numBoolVars = 1,
            intBounds = IntBounds.fromModelBounds(longArrayOf(), longArrayOf(), null, null),
            factors = arrayOf(
                ReifiedRealLinear(
                    aux = 0,
                    vars = intArrayOf(),
                    intCoeffs = doubleArrayOf(),
                    realVars = intArrayOf(0),
                    realCoeffs = doubleArrayOf(1.0),
                    op = LinearOp.LE,
                    bound = 2.0,
                ),
            ),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(3.0),
        )

        val result = OpenTheoryEngine(model, ProblemPipeline.EXACT_LRA).solve()

        assertIs<OpenTheoryAssignment.ExactLra>(assertIs<OpenTheoryResult.Sat>(result).assignment)
    }

    @Test
    fun `open exact LIRA route assembles its theory assignment`() {
        val parsed = SmtLib.parse(
            """
                (set-logic QF_LIRA)
                (declare-const x Int) (declare-const y Real)
                (assert (= y (+ (to_real x) (/ 1.0 3.0))))
                (check-sat)
            """.trimIndent(),
        )

        val result = OpenTheoryEngine(parsed.model, parsed.sourcePipeline).solve()

        assertEquals(ProblemPipeline.EXACT_LIRA, parsed.sourcePipeline)
        assertIs<OpenTheoryAssignment.ExactLira>(assertIs<OpenTheoryResult.Sat>(result).assignment)
    }

    @Test
    fun `open exact LIRA refutes an integral split through the shared trail`() {
        val parsed = SmtLib.parse(
            """
                (set-logic QF_LIRA)
                (declare-const x Int) (declare-const z Int) (declare-const y Real)
                (assert (= y 0.0))
                (assert (= (* 2 x) 1))
                (check-sat)
            """.trimIndent(),
        )

        val result = OpenTheoryEngine(parsed.model, ProblemPipeline.EXACT_LIRA).solve()

        assertIs<OpenTheoryResult.Unsat>(result)
    }
}
