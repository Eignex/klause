package com.eignex.klause.formats.flatzinc

import com.eignex.klause.ir.DefinitionHint
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FlatZincDefinitionsTest {
    @Test
    fun `plain parsing should retain the same definition coordinates as an execution request`() {
        val source = "var 0..2: x; var 2..4: y; " +
            "constraint int_plus(x, 2, y) :: defines_var(y); solve minimize y;"

        val plain = parseFlatZinc(source)
        val execution = parseFlatZincExecution(source).program
        val hint = assertIs<DefinitionHint.Plus>(plain.definitionHints.single())

        assertEquals(plain.problem.numIntVars, execution.problem.numIntVars)
        assertEquals(plain.problem.numBoolVars, execution.problem.numBoolVars)
        assertEquals(plain.intVarsByName, execution.intVarsByName)
        assertEquals(plain.intVarsByName.getValue("y"), hint.output)
        assertEquals(plain.intVarsByName.getValue("x"), assertIs<DefinitionHint.Operand.Variable>(hint.left).id)
        assertEquals(2L, assertIs<DefinitionHint.Operand.Constant>(hint.right).value)
    }

    @Test
    fun `linear definition hints should preserve repeated source operand roles`() {
        val source = "var 0..2: x; var 0..4: y; " +
            "constraint int_lin_eq([1, 1, -1], [x, x, y], 0) :: defines_var(y); solve satisfy;"

        val program = parseFlatZinc(source)
        val hint = assertIs<DefinitionHint.Linear>(program.definitionHints.single())

        assertEquals(listOf(1L, 1L, -1L), hint.coefficients.toList())
        assertEquals(
            listOf("x", "x", "y").map(program.intVarsByName::getValue),
            hint.inputs.map { assertIs<DefinitionHint.Operand.Variable>(it).id },
        )
    }

    @Test
    fun `an unrelated integer annotation target should retain only its reference`() {
        val source = "var -2..2: x; var 0..2: y; var 0..2: z; " +
            "constraint int_abs(x, y) :: defines_var(z); solve satisfy;"

        val program = parseFlatZinc(source)
        val hint = assertIs<DefinitionHint.IntegerReference>(program.definitionHints.single())

        assertEquals(program.intVarsByName.getValue("z"), hint.output)
    }

    @Test
    fun `an unrelated Boolean annotation target should retain only its reference`() {
        val source = "var 0..2: x; var bool: r; var bool: s; " +
            "constraint int_eq_reif(x, 1, r) :: defines_var(s); solve satisfy;"

        val program = parseFlatZinc(source)
        val hint = assertIs<DefinitionHint.BooleanReference>(program.definitionHints.single())

        assertEquals(program.boolVarsByName.getValue("s"), hint.output)
    }

    @Test
    fun `strict comparison hints should retain the original endpoint`() {
        val source = "var 0..5: x; var bool: b; " +
            "constraint int_lt_reif(x, 3, b) :: defines_var(b); solve satisfy;"

        val hint = assertIs<DefinitionHint.Comparison>(parseFlatZinc(source).definitionHints.single())

        assertEquals(LinearOp.LE, hint.relation)
        assertTrue(hint.strict)
        assertEquals(3L, assertIs<DefinitionHint.Operand.Constant>(hint.inputs[1]).value)
        assertEquals(0L, hint.bound)
    }

    @Test
    fun `unmaterialized operations should retain their source output reference`() {
        val source = "var 0..4: x; var 1..2: y; var 0..4: q; " +
            "constraint int_div(x, y, q) :: defines_var(q); solve satisfy;"

        val program = parseFlatZinc(source)
        val hint = assertIs<DefinitionHint.IntegerReference>(program.definitionHints.single())

        assertEquals(program.intVarsByName.getValue("q"), hint.output)
    }

    @Test
    fun `Boolean value hints should retain their source target and operand`() {
        val source = "var bool: b; var 0..1: y; " +
            "constraint bool2int(b, y) :: defines_var(y); solve satisfy;"

        val program = parseFlatZinc(source)
        val hint = assertIs<DefinitionHint.BooleanValue>(program.definitionHints.single())

        assertEquals(program.intVarsByName.getValue("y"), hint.output)
        assertEquals(Lit.make(program.boolVarsByName.getValue("b"), true), hint.literal)
    }
}
