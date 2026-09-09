package com.eignex.klause.theory.qflra

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.arithmetic.ReifiedRealLinear
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.IntBounds
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.Problem
import com.eignex.klause.ir.RealConstants
import com.eignex.klause.ir.RealConsts
import com.eignex.klause.ir.TaggedLinearRow
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.linearRows
import com.eignex.klause.solver.result.SmtStatsSink
import com.eignex.klause.theory.TheoryCheck
import com.eignex.klause.theory.TheoryContext
import com.eignex.klause.util.Bits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QfLraExplanationTest {
    @Test
    fun `frozen real conflicts retain their support across repeated checks`() {
        for ((name, model, values) in realConflicts()) {
            repeat(3) { repetition ->
                val stats = SmtStatsSink()
                val solver = ExactLraSolver(model).also { it.observeWith(stats) }
                val conflict = assertIs<TheoryCheck.Infeasible>(solver.check(values, context))
                println("FARKAS_COST,$name,$repetition,${stats.snapshot().simplexAttempts}")
                println("FARKAS_SLICE,$name,$repetition,${values.size},${conflict.explanation?.literals?.size ?: -1}")
                val literals = assertNotNull(conflict.explanation).literals
                assertEquals(if (name == "declared-bound" || name == "substitution") 1 else 2, literals.size)
                println("FARKAS_LITERALS,$name,$repetition,${literals.joinToString(";")}")
                literals.forEach { literal ->
                    assertTrue(values[Lit.variable(literal)] != Lit.isPositive(literal))
                }
            }
        }
    }

    @Test
    fun `frozen mixed conflicts distinguish LP and integer refutations`() {
        for ((name, factors) in listOf(
            "mixed-lp" to arrayOf<Factor>(
                ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 0),
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.GE, 1),
            ),
            "integer-only" to arrayOf<Factor>(ReifiedLinear(0, intArrayOf(2), intArrayOf(0), LinearOp.EQ, 1)),
        )) {
            val model = Problem(3, intBounds = openBounds(1), factors = factors)
            repeat(3) { repetition ->
                val stats = SmtStatsSink()
                val solver = ExactLiraSolver(model).also { it.observeWith(stats) }
                val conflict = assertIs<TheoryCheck.Infeasible>(
                    solver.check(booleanArrayOf(true, true, true), context),
                )
                println("FARKAS_COST,$name,$repetition,${stats.snapshot().simplexAttempts}")
                println("FARKAS_SLICE,$name,$repetition,3,${conflict.explanation?.literals?.size ?: -1}")
                if (name == "mixed-lp") {
                    assertEquals(listOf(Lit.make(0, false)), assertNotNull(conflict.explanation).literals.toList())
                } else {
                    assertNull(conflict.explanation)
                }
            }
        }
    }

    @Test
    fun `flipping uncited Booleans preserves a real conflict`() {
        val (_, model, values) = realConflicts().first()
        val conflict = assertIs<TheoryCheck.Infeasible>(ExactLraSolver(model).check(values, context))
        val cited = assertNotNull(conflict.explanation).literals.map(Lit::variable)
        val completion = BooleanArray(values.size) { if (it in cited) values[it] else !values[it] }

        assertIs<TheoryCheck.Infeasible>(ExactLraSolver(model).check(completion, context))
    }

    @Test
    fun `each cited premise is needed in the directed real conflicts`() {
        for ((_, model, values) in realConflicts()) {
            val conflict = assertIs<TheoryCheck.Infeasible>(ExactLraSolver(model).check(values, context))
            for (literal in assertNotNull(conflict.explanation).literals) {
                val released = values.copyOf()
                val variable = Lit.variable(literal)
                released[variable] = !released[variable]
                assertIs<TheoryCheck.Sat<ExactLraAssignment>>(ExactLraSolver(model).check(released, context))
            }
        }
    }

    @Test
    fun `shared integer bounds cannot become a Boolean-only explanation`() {
        val model = Problem(
            1,
            intBounds = openBounds(1),
            factors = arrayOf(ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 0)),
        )
        val boundedContext = object : TheoryContext by context {
            override fun intLowerBound(variable: Int): Long = 1L
        }

        val result = ExactLiraSolver(model).check(booleanArrayOf(true), boundedContext)

        assertNull(assertIs<TheoryCheck.Infeasible>(result).explanation)
        assertIs<TheoryCheck.Sat<ExactLiraAssignment>>(ExactLiraSolver(model).check(booleanArrayOf(true), context))
    }

    @Test
    fun `a private disjunction conflict cannot refute its feasible sibling`() {
        val first = atom(0, intArrayOf(0), doubleArrayOf(1.0), LinearOp.LE, 0.0)
        val second = atom(1, intArrayOf(0), doubleArrayOf(1.0), LinearOp.GE, 1.0)
        val disjunction = object : Factor by first {
            override val linearForm: LinearForm = LinearForm.Disjunction(
                first.linearRows + second.linearRows,
            )
        }
        val model = Problem(
            2,
            intBounds = openBounds(0),
            factors = arrayOf(disjunction),
            numRealVars = 1,
            realLower = doubleArrayOf(1.0),
            realUpper = doubleArrayOf(1.0),
        )

        assertIs<TheoryCheck.Sat<ExactLraAssignment>>(ExactLraSolver(model).check(booleanArrayOf(true, true), context))
    }

    @Test
    fun `activated Boolean substitution cites both premises`() {
        val source = atom(0, intArrayOf(0), doubleArrayOf(1.0), LinearOp.LE, 1.0)
        val factor = object : Factor by source {
            override val linearForm: LinearForm = LinearForm.Conjunction(
                listOf(
                    TaggedLinearRow(
                        intArrayOf(Term.ofLit(Lit.make(1, false)), Term.ofRealVar(0)),
                        RealConstants(RealConsts(doubleArrayOf(2.0)), RealConsts(doubleArrayOf(1.0)), 1.0, false),
                        LinearOp.LE,
                        activator = 0,
                    ),
                ),
            )
        }
        val model = Problem(
            3,
            intBounds = openBounds(0),
            factors = arrayOf(factor),
            numRealVars = 1,
            realLower = doubleArrayOf(0.0),
            realUpper = doubleArrayOf(Double.POSITIVE_INFINITY),
        )

        val result = ExactLraSolver(model).check(booleanArrayOf(true, false, true), context)

        assertEquals(
            listOf(Lit.make(0, false), Lit.make(1, true)),
            assertNotNull(assertIs<TheoryCheck.Infeasible>(result).explanation).literals.toList(),
        )
        for (values in listOf(booleanArrayOf(false, false, true), booleanArrayOf(true, true, true))) {
            assertIs<TheoryCheck.Sat<ExactLraAssignment>>(ExactLraSolver(model).check(values, context))
        }
    }

    @Test
    fun `both sides of an equality retain the same atom premise`() {
        val source = atom(0, intArrayOf(0), doubleArrayOf(1.0), LinearOp.LE, 0.0)
        val equality = object : Factor by source {
            override val linearForm: LinearForm = LinearForm.Conjunction(
                listOf(
                    TaggedLinearRow(
                        intArrayOf(Term.ofRealVar(0)),
                        RealConstants(RealConsts(doubleArrayOf()), RealConsts(doubleArrayOf(1.0)), 0.0, false),
                        LinearOp.EQ,
                        activator = 0,
                    ),
                ),
            )
        }
        for (fixed in listOf(-1.0, 1.0)) {
            val model = Problem(
                2,
                intBounds = openBounds(0),
                factors = arrayOf(equality),
                numRealVars = 1,
                realLower = doubleArrayOf(fixed),
                realUpper = doubleArrayOf(fixed),
            )

            val result = ExactLraSolver(model).check(booleanArrayOf(true, true), context)

            assertEquals(
                listOf(Lit.make(0, false)),
                assertNotNull(assertIs<TheoryCheck.Infeasible>(result).explanation).literals.toList(),
            )
            assertIs<TheoryCheck.Sat<ExactLraAssignment>>(
                ExactLraSolver(model).check(booleanArrayOf(false, true), context),
            )
        }
    }

    @Test
    fun `crossed global bounds need no Boolean premise`() {
        val model = Problem(
            1,
            intBounds = openBounds(0),
            factors = arrayOf(),
            numRealVars = 1,
            realLower = doubleArrayOf(2.0),
            realUpper = doubleArrayOf(1.0),
        )

        val result = ExactLraSolver(model).check(booleanArrayOf(true), context)

        assertTrue(assertNotNull(assertIs<TheoryCheck.Infeasible>(result).explanation).literals.isEmpty())
    }

    private fun realConflicts(): List<Triple<String, Problem, BooleanArray>> {
        val irrelevant = atom(3, intArrayOf(1), doubleArrayOf(1.0), LinearOp.LE, 10.0)
        val substitution = object : Factor by irrelevant {
            override val linearForm: LinearForm = LinearForm.Conjunction(
                listOf(
                    TaggedLinearRow(
                        intArrayOf(Term.ofLit(Lit.make(0, false)), Term.ofRealVar(0)),
                        RealConstants(RealConsts(doubleArrayOf(2.0)), RealConsts(doubleArrayOf(1.0)), 1.0, false),
                        LinearOp.LE,
                    ),
                ),
            )
        }
        return listOf(
            Triple(
                "crossed",
                listOf(
                    atom(0, intArrayOf(0), doubleArrayOf(1.0), LinearOp.LE, 0.0),
                    atom(1, intArrayOf(0), doubleArrayOf(1.0), LinearOp.GE, 1.0),
                ),
                booleanArrayOf(true, true, true, true),
            ),
            Triple(
                "strict",
                listOf(
                    atom(0, intArrayOf(0), doubleArrayOf(1.0), LinearOp.LE, 0.0, strict = true),
                    atom(1, intArrayOf(0), doubleArrayOf(1.0), LinearOp.GE, 0.0),
                ),
                booleanArrayOf(true, true, true, true),
            ),
            Triple(
                "mixed-sign",
                listOf(
                    atom(0, intArrayOf(0, 1), doubleArrayOf(2.0, -3.0), LinearOp.LE, 0.0),
                    atom(1, intArrayOf(0, 1), doubleArrayOf(-2.0, 3.0), LinearOp.LE, -1.0),
                ),
                booleanArrayOf(true, true, true, true),
            ),
            Triple(
                "complement",
                listOf(
                    atom(0, intArrayOf(0), doubleArrayOf(1.0), LinearOp.LE, 0.0),
                    atom(1, intArrayOf(0), doubleArrayOf(1.0), LinearOp.LE, 0.0),
                ),
                booleanArrayOf(false, true, true, true),
            ),
            Triple(
                "declared-bound",
                listOf(
                    atom(0, intArrayOf(0), doubleArrayOf(1.0), LinearOp.LE, -1.0),
                ),
                booleanArrayOf(true, true, true, true),
            ),
            Triple("substitution", listOf(substitution), booleanArrayOf(false, true, true, true)),
        ).map { (name, factors, values) ->
            Triple(
                name,
                Problem(
                    4,
                    intBounds = openBounds(0),
                    factors = (factors + irrelevant).toTypedArray(),
                    numRealVars = 2,
                    realLower = doubleArrayOf(
                        if (name == "declared-bound" || name == "substitution") 0.0 else Double.NEGATIVE_INFINITY,
                        Double.NEGATIVE_INFINITY,
                    ),
                    realUpper = DoubleArray(2) { Double.POSITIVE_INFINITY },
                ),
                values,
            )
        }
    }

    private fun atom(
        activator: Int,
        columns: IntArray,
        coefficients: DoubleArray,
        op: LinearOp,
        bound: Double,
        strict: Boolean = false,
    ): Factor = ReifiedRealLinear(activator, intArrayOf(), doubleArrayOf(), columns, coefficients, op, bound, strict)

    private fun openBounds(size: Int): IntBounds {
        val open = Bits(size).also { bits -> repeat(size, bits::set) }
        return IntBounds.fromModelBounds(LongArray(size), LongArray(size), open, open)
    }

    private val context = object : TheoryContext {
        override fun consumeCheck(): Boolean = true
        override fun cancelled(): Boolean = false
    }
}
