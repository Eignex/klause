package com.eignex.klause.formats.smtlib

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.LinearObjectiveSpec
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.lowering.PolynomialSourceInterrupted
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.WorkMeter
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SmtLibCompilerTest {
    @Test
    fun `an asserted equality should construct its normalized linear row`() {
        val parsed = SmtLib.parse("(declare-const x Int) (assert (= (+ x 2) 5))")

        assertEquals(mapOf("x" to 0), parsed.intVarNames)
        val row = parsed.model.factors.single() as Linear
        assertContentEquals(intArrayOf(0), row.vars)
        assertContentEquals(longArrayOf(1), row.integerConstants!!.coeffs)
        assertEquals(LinearOp.EQ, row.op)
        assertEquals(3L, row.integerConstants!!.bound)
    }

    @Test
    fun `a duplicate declaration should be rejected`() {
        val error = assertFailsWith<UnsupportedSmtException> {
            SmtLib.parse("(declare-const x Int) (declare-const x Bool)")
        }

        assertTrue(error.message!!.contains("duplicate declaration of 'x'"))
    }

    @Test
    fun `a malformed command should report its expected argument count`() {
        val error = assertFailsWith<UnsupportedSmtException> {
            SmtLib.parse("(declare-const x Int unexpected)")
        }

        assertTrue(error.message!!.contains("declare-const expects 2 arguments"))
    }

    @Test
    fun `a malformed ite should report a format error`() {
        val error = assertFailsWith<UnsupportedSmtException> {
            SmtLib.parse("(declare-const x Int) (assert (= x (ite true 1)))")
        }

        assertTrue(error.message!!.contains("ite"))
    }

    @Test
    fun `a macro should reject unsupported parameter sorts`() {
        val error = assertFailsWith<UnsupportedSmtException> {
            SmtLib.parse("(define-fun f ((x BitVec)) Int x)")
        }

        assertTrue(error.message!!.contains("unsupported sort 'BitVec'"))
    }

    @Test
    fun `an unknown command should be rejected`() {
        val error = assertFailsWith<UnsupportedSmtException> {
            SmtLib.parse("(declare-const x Int) (asert (= x 1))")
        }

        assertTrue(error.message!!.contains("unsupported command 'asert'"))
    }

    @Test
    fun `a let-bound distinct should reject equal values`() {
        val parsed = SmtLib.parse(
            """
                (set-logic QF_LIA)
                (declare-fun x () Int)
                (declare-fun y () Int)
                (assert (= x 0))
                (assert (= y 0))
                (assert (let ((distinct-values (distinct x y))) distinct-values))
                (check-sat)
            """.trimIndent(),
            unboundedIntLo = -1,
            unboundedIntHi = 1,
        )

        assertIs<SolveResult.Unsat>(BacktrackSolver(parsed.model.bake()).solve(BacktrackParams()))
    }

    @Test
    fun `a zero weight alias should preserve the retained objective weight`() {
        val builder = Compiler.Builder(Long.MIN_VALUE, Long.MAX_VALUE, false)
        val target = builder.newBool()
        val alias = builder.newBool()
        val definition = listOf(
            Clause(intArrayOf(Lit.make(alias, false), Lit.make(target, true))),
            Clause(intArrayOf(Lit.make(alias, true), Lit.make(target, false))),
        )
        builder.factors.addAll(definition)
        val objective = LinearObjectiveSpec(boolWeights = longArrayOf(7, 0))

        val compacted = builder.compactConditionalColumns(
            SmtLibConditionalEquality.UnusedColumns(emptySet(), emptySet()),
            objective,
            listOf(SmtLibConditionalEquality.BooleanAlias(alias, target, definition)),
        )

        assertEquals(1, builder.nextBool)
        assertEquals(listOf(7L), assertNotNull(compacted).boolWeights.toList())
    }

    @Test
    fun `an objective comparison output should retain its constrained column`() {
        val builder = Compiler.Builder(Long.MIN_VALUE, Long.MAX_VALUE, false)
        val target = builder.newBool()
        val alias = builder.newBool()
        val definition = listOf(
            Clause(intArrayOf(Lit.make(alias, false), Lit.make(target, true))),
            Clause(intArrayOf(Lit.make(alias, true), Lit.make(target, false))),
        )
        builder.factors.addAll(definition)
        val objective = LinearObjectiveSpec(boolWeights = longArrayOf(0, 7))

        val compacted = builder.compactConditionalColumns(
            SmtLibConditionalEquality.UnusedColumns(emptySet(), emptySet()),
            objective,
            listOf(SmtLibConditionalEquality.BooleanAlias(alias, target, definition)),
        )

        assertEquals(2, builder.nextBool)
        assertEquals(listOf(0L, 7L), assertNotNull(compacted).boolWeights.toList())
        assertEquals(definition, builder.factors)
    }

    @Test
    fun `a declared Boolean output should retain its own definition`() {
        val builder = Compiler.Builder(Long.MIN_VALUE, Long.MAX_VALUE, false)
        val target = builder.newBool()
        val alias = builder.newBool()
        builder.boolNames["r"] = alias
        val definition = listOf(
            Clause(intArrayOf(Lit.make(alias, false), Lit.make(target, true))),
            Clause(intArrayOf(Lit.make(alias, true), Lit.make(target, false))),
        )
        builder.factors.addAll(definition)

        builder.compactConditionalColumns(
            SmtLibConditionalEquality.UnusedColumns(emptySet(), emptySet()),
            null,
            listOf(SmtLibConditionalEquality.BooleanAlias(alias, target, definition)),
        )

        assertEquals(2, builder.nextBool)
        assertEquals(alias, builder.boolNames.getValue("r"))
        assertEquals(definition, builder.factors)
    }

    @Test
    fun `stopped alias compaction should retain spent source work`() {
        var charged = 0L
        val meter = WorkMeter { charged += it }
        val stop = object : Cancellation {
            override fun isCancelled(): Boolean = charged >= 8L
            override fun workMeter(): WorkMeter = meter
        }
        val builder = Compiler.Builder(Long.MIN_VALUE, Long.MAX_VALUE, false, sourceCancellation = stop)
        val target = builder.newBool()
        val alias = builder.newBool()
        val definition = listOf(
            Clause(intArrayOf(Lit.make(alias, false), Lit.make(target, true))),
            Clause(intArrayOf(Lit.make(alias, true), Lit.make(target, false))),
        )
        builder.factors.addAll(definition)
        val aliases = listOf(SmtLibConditionalEquality.BooleanAlias(alias, target, definition))
        val removed = SmtLibConditionalEquality.UnusedColumns(emptySet(), emptySet())

        assertFailsWith<PolynomialSourceInterrupted> { builder.compactConditionalColumns(removed, null, aliases) }

        assertEquals(8L, charged)
        assertEquals(definition, builder.factors)
        assertEquals(2, builder.nextBool)
        assertFailsWith<PolynomialSourceInterrupted> { builder.compactConditionalColumns(removed, null, aliases) }
        assertEquals(8L, charged)
    }
}
