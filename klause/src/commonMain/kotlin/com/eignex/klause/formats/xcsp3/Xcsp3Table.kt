package com.eignex.klause.formats.xcsp3

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.table.Table
import com.eignex.klause.formats.trueLit
import com.eignex.klause.solver.Lit

// `<extension>` (positive/negative table) lowering for the XCSP3 front-end — split out of Xcsp3.kt.

internal fun Xcsp3.Builder.extension(e: XmlElement) {
    val vars = listVars(e)
    val supports = e.child("supports")?.textContent?.trim()
    val conflicts = e.child("conflicts")?.textContent?.trim()
    when {
        // No allowed tuple ⇒ the constraint (hence the instance) is unsatisfiable.
        supports != null && supports.isEmpty() -> factors.add(Clause(intArrayOf(Lit.negate(trueLit()))))

        // No forbidden tuple ⇒ trivially satisfied; post nothing.
        conflicts != null && conflicts.isEmpty() -> Unit

        supports != null -> factors.add(Table(xs = vars, tuples = parseTuples(supports, vars).widenToLong()))

        conflicts != null -> {
            val allowed = negativeTable(vars, parseTuples(conflicts, vars))
            // Every tuple forbidden ⇒ unsatisfiable; otherwise the allowed-tuple table.
            if (allowed.isEmpty()) {
                factors.add(Clause(intArrayOf(Lit.negate(trueLit()))))
            } else {
                factors.add(Table(xs = vars, tuples = allowed.widenToLong()))
            }
        }

        else -> throw UnsupportedXcsp3Exception("extension without supports or conflicts")
    }
}

@Suppress("ThrowsCount") // arity and expansion-cap guards
internal fun Xcsp3.Builder.parseTuples(text: String, vars: IntArray): IntArray {
    val arity = vars.size
    val t = text.trim()
    val out = ArrayList<Int>()
    // A unary table is written as bare values `0 1 2` (or ranges `0..2`, or `*`), no parentheses.
    if (arity == 1 && '(' !in t) {
        for (tok in t.split(Regex("\\s+")).filter { it.isNotBlank() }) out.addAll(columnValues(tok, vars[0]))
        return out.toIntArray()
    }
    for (m in Regex("""\(([^)]*)\)""").findAll(t)) {
        val row = m.groupValues[1].split(",").map { it.trim() }
        if (row.size != arity) throw UnsupportedXcsp3Exception("tuple arity ${row.size} != $arity")
        appendTupleExpansion(row, vars, out)
        if (out.size / arity > negTableCap) throw UnsupportedXcsp3Exception("table exceeds cap ($negTableCap)")
    }
    return out.toIntArray()
}

/** The concrete values a table-column entry denotes: `*` = the variable's whole domain,
 *  `lo..hi` = the inclusive range, otherwise the single literal value. */
internal fun Xcsp3.Builder.columnValues(tok: String, v: Int): List<Int> = when {
    tok == "*" -> domainValues(v)
    ".." in tok -> tok.split("..").let { (it[0].toInt()..it[1].toInt()).toList() }
    else -> listOf(tok.toInt())
}

/** Append every concrete tuple denoted by [row] to [out], expanding `*`/range columns as a
 *  Cartesian product over the involved variables' domains. */
internal fun Xcsp3.Builder.appendTupleExpansion(row: List<String>, vars: IntArray, out: MutableList<Int>) {
    val cols = row.mapIndexed { i, tok -> columnValues(tok, vars[i]) }
    val cur = IntArray(cols.size)
    fun rec(p: Int) {
        if (p == cols.size) {
            for (x in cur) out.add(x)
            return
        }
        for (value in cols[p]) {
            cur[p] = value
            rec(p + 1)
        }
    }
    rec(0)
}

/** Complement a negative (conflicts) table to the flat allowed-tuple list; empty when every
 *  tuple is forbidden (the constraint is then unsatisfiable). */
internal fun Xcsp3.Builder.negativeTable(vars: IntArray, conflicts: IntArray): IntArray {
    val arity = vars.size
    val valuesPer = vars.map { domainValues(it) }
    var product = 1L
    for (vs in valuesPer) {
        product *= vs.size
        if (product > negTableCap) {
            throw UnsupportedXcsp3Exception(
                "negative table cartesian product exceeds cap ($negTableCap)",
            )
        }
    }
    val forbidden = HashSet<List<Int>>()
    var i = 0
    while (i < conflicts.size) {
        forbidden.add((0 until arity).map { conflicts[i + it] })
        i += arity
    }
    val allowed = ArrayList<Int>()
    val cur = IntArray(arity)
    fun rec(p: Int) {
        if (p == arity) {
            if (cur.toList() !in forbidden) for (v in cur) allowed.add(v)
            return
        }
        for (v in valuesPer[p]) {
            cur[p] = v
            rec(p + 1)
        }
    }
    rec(0)
    return allowed.toIntArray()
}
