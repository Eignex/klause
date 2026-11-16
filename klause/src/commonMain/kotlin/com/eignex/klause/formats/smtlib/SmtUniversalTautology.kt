package com.eignex.klause.formats.smtlib

import com.eignex.klause.lowering.polynomialSourceCheckpoint
import com.eignex.klause.util.Cancellation

internal fun smtUniversalTautology(source: SmtTypedSource, root: Int, cancellation: Cancellation): Boolean {
    data class Formula(val operator: String, val arguments: List<Int> = emptyList(), val atom: String = "")
    val formulas = ArrayList<Formula>()
    val interned = HashMap<Formula, Int>()
    fun formula(operator: String, arguments: List<Int> = emptyList(), atom: String = ""): Int {
        cancellation.polynomialSourceCheckpoint(1)
        val value = Formula(operator, arguments, atom)
        return interned.getOrPut(value) { formulas.add(value); formulas.lastIndex }
    }
    val truth = formula("true")
    val falsity = formula("false")
    fun equality(left: Int, right: Int): Int = if (left == right) truth else {
        formula("atom", atom = "equality:${minOf(left, right)}:${maxOf(left, right)}")
    }
    val mapped = HashMap<Int, Int>()
    val pending = ArrayDeque<Pair<Int, Boolean>>()
    pending.addLast(root to false)
    while (pending.isNotEmpty()) {
        cancellation.polynomialSourceCheckpoint(1)
        val (index, combine) = pending.removeLast()
        if (index in mapped) continue
        val node = source.terms[index]
        if (!combine) {
            pending.addLast(index to true)
            for (argument in node.arguments.asReversed()) pending.addLast(argument to false)
            continue
        }
        val arguments = node.arguments.map { mapped.getValue(it) }
        mapped[index] = when (node.operator) {
            "literal:true" -> truth
            "literal:false" -> falsity
            "and", "or", "xor", "=>", "not" -> formula(node.operator, arguments)
            "ite" -> if (node.sort == SmtSort.Bool) formula("ite", arguments)
                else formula("atom", atom = "term:$index")
            "=" -> formula("and", node.arguments.zipWithNext().map { equality(it.first, it.second) })
            "distinct" -> {
                val pairs = ArrayList<Int>()
                for (left in node.arguments.indices) for (right in left + 1 until node.arguments.size) {
                    pairs += formula("not", listOf(equality(node.arguments[left], node.arguments[right])))
                }
                formula("and", pairs)
            }
            else -> formula("atom", atom = "term:$index")
        }
    }
    val atoms = formulas.filter { it.operator == "atom" }.map { it.atom }.distinct()
    val assignments = ArrayDeque<Map<String, Boolean>>()
    assignments.addLast(emptyMap())
    while (assignments.isNotEmpty()) {
        cancellation.polynomialSourceCheckpoint(1)
        val assignment = assignments.removeLast()
        val values = arrayOfNulls<Boolean>(formulas.size)
        for (index in formulas.indices) {
            cancellation.polynomialSourceCheckpoint(1)
            val node = formulas[index]
            val args = node.arguments.map { values[it] }
            values[index] = when (node.operator) {
                "true" -> true
                "false" -> false
                "atom" -> assignment[node.atom]
                "not" -> args.single()?.not()
                "and" -> if (false in args) false else if (args.all { it == true }) true else null
                "or" -> if (true in args) true else if (args.all { it == false }) false else null
                "xor" -> if (args.any { it == null }) null else args.count { it == true } % 2 == 1
                "=>" -> {
                    var result = args.last()
                    for (left in args.dropLast(1).asReversed()) {
                        result = if (left == false || result == true) true
                            else if (left == true) result else null
                    }
                    result
                }
                "ite" -> when (args[0]) { true -> args[1]; false -> args[2]; null ->
                    if (args[1] == args[2]) args[1] else null }
                else -> error("unknown Boolean schema operator")
            }
        }
        when (values[mapped.getValue(root)]) {
            true -> Unit
            false -> return false
            null -> {
                val next = atoms.first { it !in assignment }
                assignments.addLast(assignment + (next to false))
                assignments.addLast(assignment + (next to true))
            }
        }
    }
    return true
}
