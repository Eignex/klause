package com.eignex.klause.formats.flatzinc

import com.eignex.klause.ir.BoolFoldDefinition
import com.eignex.klause.ir.DefinitionHint
import com.eignex.klause.ir.DefinitionHint.Operand
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit

internal fun FlatZincCompiler.collectDefinitionHints(): List<DefinitionHint> {
    val hints = ArrayList<DefinitionHint>()
    for (constraint in model.constraints) {
        val annotation = constraint.annotations.firstOrNull { it.name == "defines_var" } ?: continue
        val target = annotation.args.firstOrNull() ?: continue
        val integer = definitionIntId(target)
        if (integer != null) {
            hints.add(integerDefinition(constraint, integer) ?: DefinitionHint.IntegerReference(integer))
        } else {
            val literal = runCatching { resolveBoolLit(target) }.getOrNull() ?: continue
            val boolean = Lit.variable(literal)
            val definition = if (Lit.isPositive(literal)) booleanDefinition(constraint, boolean) else null
            hints.add(definition ?: DefinitionHint.BooleanReference(boolean, literal))
        }
    }
    return hints
}

@Suppress("CyclomaticComplexMethod", "LongMethod")
private fun FlatZincCompiler.integerDefinition(c: FznConstraint, output: Int): DefinitionHint.Integer? {
    val position = when (c.name) {
        "int_abs", "bool2int" -> 1
        "int_min", "int_max", "int_times", "int_plus", "int_pow", "int_pow_fixed", "klause_int_pow",
        "array_int_element", "array_var_int_element",
        -> 2
        "array_int_minimum", "array_int_maximum" -> 0
        else -> null
    }
    if (position != null && definitionIntId(c.args[position]) != output) return null
    return when (c.name) {
        "int_abs" -> definitionOperand(c.args[0])?.let { DefinitionHint.Abs(output, it) }

        "int_max", "int_min" -> {
            val a = definitionOperand(c.args[0])
            val b = definitionOperand(c.args[1])
            if (a == null || b == null) null else DefinitionHint.Extreme(output, arrayOf(a, b), c.name == "int_max")
        }

        "array_int_maximum", "array_int_minimum" -> definitionOperands(c.args[1])?.let {
            DefinitionHint.Extreme(output, it, c.name.endsWith("maximum"))
        }

        "int_pow", "int_pow_fixed", "klause_int_pow" -> {
            val exponent = evalIntConstOrNull(c.args[1])
            val base = definitionOperand(c.args[0])
            if (base == null || exponent == null || exponent !in 0L..Int.MAX_VALUE.toLong()) null
            else DefinitionHint.Power(output, base, exponent.toInt())
        }

        "int_times", "int_plus" -> {
            val a = definitionOperand(c.args[0])
            val b = definitionOperand(c.args[1])
            when {
                a == null || b == null -> null
                c.name == "int_times" -> DefinitionHint.Product(output, a, b)
                else -> DefinitionHint.Plus(output, a, b)
            }
        }

        "int_lin_eq" -> {
            val coefficients = runCatching { evalIntConstArray(c.args[0]) }.getOrNull()
            val inputs = definitionOperands(c.args[1])
            val bound = evalIntConstOrNull(c.args[2])
            if (coefficients == null || inputs == null || bound == null) null
            else DefinitionHint.Linear(
                output, LongArray(coefficients.size) { coefficients[it].toLong() }, inputs, bound,
            )
        }

        "bool2int" -> runCatching { resolveBoolLit(c.args[0]) }.getOrNull()?.let {
            DefinitionHint.BooleanValue(output, it)
        }

        "array_int_element", "array_var_int_element" -> {
            val index = definitionIntId(c.args[0])
            if (index == null) null else if (c.name == "array_int_element") {
                runCatching { evalIntConstArray(c.args[1]) }.getOrNull()?.let {
                    DefinitionHint.Element(output, index, null, LongArray(it.size) { k -> it[k].toLong() }, 1)
                }
            } else {
                runCatching { evalIntVarArray(c.args[1]) }.getOrNull()?.let {
                    DefinitionHint.Element(output, index, it, null, 1)
                }
            }
        }

        else -> null
    }
}

@Suppress("CyclomaticComplexMethod", "LongMethod")
private fun FlatZincCompiler.booleanDefinition(c: FznConstraint, output: Int): DefinitionHint.Boolean? {
    val position = if (c.name == "array_bool_and" || c.name == "array_bool_or") 1 else c.args.lastIndex
    if (position < 0 || definitionBoolId(c.args[position]) != output) return null
    val comparison = when (c.name) {
        "int_eq_reif", "int_lin_eq_reif" -> LinearOp.EQ to false
        "int_ne_reif", "int_lin_ne_reif" -> LinearOp.NE to false
        "int_le_reif", "int_lin_le_reif" -> LinearOp.LE to false
        "int_lt_reif", "int_lin_lt_reif" -> LinearOp.LE to true
        "int_ge_reif", "int_lin_ge_reif" -> LinearOp.GE to false
        "int_gt_reif", "int_lin_gt_reif" -> LinearOp.GE to true
        else -> null
    }
    if (comparison != null) {
        val coefficients: LongArray
        val inputs: Array<Operand>
        val bound: Long
        if (c.name.startsWith("int_lin_")) {
            val values = runCatching { evalIntConstArray(c.args[0]) }.getOrNull() ?: return null
            coefficients = LongArray(values.size) { values[it].toLong() }
            inputs = definitionOperands(c.args[1]) ?: return null
            bound = evalIntConstOrNull(c.args[2]) ?: return null
        } else {
            coefficients = longArrayOf(1L, -1L)
            inputs = arrayOf(
                definitionOperand(c.args[0]) ?: return null, definitionOperand(c.args[1]) ?: return null,
            )
            bound = 0L
        }
        return DefinitionHint.Comparison(output, coefficients, inputs, bound, comparison.first, comparison.second)
    }
    return when (c.name) {
        "set_in_reif" -> {
            val input = definitionIntId(c.args[0]) ?: return null
            val values = runCatching { resolveSetLiteral(c.args[1]) }.getOrNull() ?: return null
            DefinitionHint.Membership(output, input, LongArray(values.size) { values[it].toLong() })
        }

        "array_bool_and", "array_bool_or" -> {
            val inputs = runCatching { evalBoolVarArray(c.args[0]) }.getOrNull() ?: return null
            DefinitionHint.Fold(BoolFoldDefinition(output, inputs, c.name == "array_bool_and"))
        }

        else -> null
    }
}

private fun FlatZincCompiler.definitionIntId(e: FznExpr): Int? {
    if (evalIntConstOrNull(e) != null) return null
    return runCatching { resolveIntVar(e) }.getOrNull()
}

private fun FlatZincCompiler.definitionBoolId(e: FznExpr): Int? = runCatching {
    resolveBoolLit(e).takeIf { Lit.isPositive(it) }?.let(Lit::variable)
}.getOrNull()

private fun FlatZincCompiler.definitionOperand(e: FznExpr): Operand? =
    evalIntConstOrNull(e)?.let { Operand.Constant(it) }
        ?: runCatching { Operand.Variable(resolveIntVar(e)) }.getOrNull()

private fun FlatZincCompiler.definitionOperands(e: FznExpr): Array<Operand>? = when (e) {
    is FznExpr.ArrayLit -> {
        val operands = ArrayList<Operand>(e.elements.size)
        for (element in e.elements) operands.add(definitionOperand(element) ?: return null)
        operands.toTypedArray()
    }

    else -> runCatching {
        val variables = evalIntVarArray(e)
        Array<Operand>(variables.size) { Operand.Variable(variables[it]) }
    }.getOrNull()
}
