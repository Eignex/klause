package com.eignex.klause.formats.flatzinc

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.propagation.bake
import com.eignex.klause.solver.SolveResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FlatZincEnigmaWitnessTest {
    @Test
    fun `pinned Enigma witness satisfies every original flattened constraint and bound`() {
        val source = checkNotNull(javaClass.getResourceAsStream("/flatzinc/enigma_248_add_or_multiply.fzn"))
            .bufferedReader().use { it.readText() }
        val original = FlatZincParser(FlatZincLexer(source)).parse()
        val witness = listOf(2, 4, 6, 2, 3, 10, 1, 8, 9, 1, 6, 14, 1, 5, 24)
        val pins = witness.mapIndexed { index, value -> "constraint int_eq(X_INTRODUCED_${22 + index}_, $value);" }
        val program = parseFlatZinc(source.replace("solve ::", pins.joinToString("\n") + "\nsolve ::"))

        val result = BacktrackSolver(program.problem.bake()).solve(BacktrackParams(randomSeed = 1L))

        val assignment = assertIs<SolveResult.Sat>(result).assignment
        val values = mutableMapOf<String, List<Double>>()
        fun evaluate(expression: FznExpr): List<Double> = when (expression) {
            is FznExpr.Ident -> values.getValue(expression.name)
            is FznExpr.ArrayAccess -> listOf(values.getValue(expression.name)[expression.index - 1])
            is FznExpr.ArrayLit -> expression.elements.flatMap(::evaluate)
            is FznExpr.IntLit -> listOf(expression.value.toDouble())
            is FznExpr.FloatLit -> listOf(expression.value)
            is FznExpr.BoolLit -> listOf(if (expression.value) 1.0 else 0.0)
            else -> error("Unexpected witness expression: $expression")
        }
        var checkedBounds = 0
        for (declaration in original.varDecls) {
            val value = when {
                declaration.value != null -> evaluate(declaration.value)
                declaration.name in program.intVarsByName -> listOf(assignment.ints[program.intVarsByName.getValue(declaration.name)].toDouble())
                declaration.name in program.boolVarsByName -> listOf(if (assignment.bools[program.boolVarsByName.getValue(declaration.name)]) 1.0 else 0.0)
                else -> listOf(assignment.approximateRealValue(program.floatVarsByName.getValue(declaration.name).varId))
            }
            values[declaration.name] = value
            when (val type = declaration.type) {
                is FznType.IntRange -> {
                    assertTrue(value.single() >= type.lo && value.single() <= type.hi, declaration.name)
                    checkedBounds++
                }
                is FznType.FloatRange -> {
                    assertTrue(value.single() >= type.lo && value.single() <= type.hi, declaration.name)
                    checkedBounds++
                }
                else -> Unit
            }
        }
        for (constraint in original.constraints) {
            val args = constraint.args.map(::evaluate)
            fun scalar(index: Int): Double = args[index].single()
            val linear = if (constraint.name.contains("lin_")) {
                args[0].zip(args[1]).sumOf { (coefficient, value) -> coefficient * value }
            } else 0.0
            val holds = when (constraint.name) {
                "float_lin_eq", "int_lin_eq" -> linear == scalar(2)
                "float_lin_ne" -> linear != scalar(2)
                "float_lin_le" -> linear <= scalar(2)
                "int_lin_eq_reif" -> (linear == scalar(2)) == (scalar(3) == 1.0)
                "int_eq_reif" -> (scalar(0) == scalar(1)) == (scalar(2) == 1.0)
                "int_mod" -> scalar(0) % scalar(1) == scalar(2)
                "bool2int", "int2float" -> scalar(0) == scalar(1)
                "float_times" -> scalar(0) * scalar(1) == scalar(2)
                else -> error("Unexpected witness predicate: ${constraint.name}")
            }
            assertTrue(holds, constraint.toString())
        }
        assertEquals(129, original.constraints.size)
        assertEquals(90, checkedBounds)
        assertEquals(listOf(6.0, 7.5, 9.0, 10.5, 15.0), values.getValue("total"))
    }
}
