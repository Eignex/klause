package com.eignex.klause.formats.xcsp3

import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.table.Table
import com.eignex.klause.formats.reifyLinear
import com.eignex.klause.formats.trueLit
import com.eignex.klause.solver.Lit
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.LongArrayList

// `<extension>` (positive/negative table) lowering for the XCSP3 front-end — split out of Xcsp3.kt.
//
// A `*` column is carried as a short-support wildcard rather than expanded to the variable's whole
// domain, and a `<conflicts>` table is lowered to one nogood clause per forbidden tuple rather than
// complemented to its allowed set. Both avoid the Cartesian blow-up that the old expansion capped.

internal fun Xcsp3.Builder.extension(e: XmlElement) {
    val vars = listVars(e)
    val supports = e.child("supports")?.textContent?.trim()
    val conflicts = e.child("conflicts")?.textContent?.trim()
    when {
        // No allowed tuple ⇒ the constraint (hence the instance) is unsatisfiable.
        supports != null && supports.isEmpty() -> factors.add(Clause(intArrayOf(Lit.negate(trueLit()))))

        // No forbidden tuple ⇒ trivially satisfied; post nothing.
        conflicts != null && conflicts.isEmpty() -> Unit

        supports != null -> postSupportTable(vars, supports)

        conflicts != null -> postConflictClauses(vars, conflicts)

        else -> throw UnsupportedXcsp3Exception("extension without supports or conflicts")
    }
}

/** Post a positive `<supports>` table as a [Table] factor, carrying `*` columns as short-support
 *  wildcards (a `*` matches any value, so it costs one row, not one per domain value). A fully
 *  wildcard row matches every assignment, so the whole constraint is trivially satisfied. */
internal fun Xcsp3.Builder.postSupportTable(vars: IntArray, text: String) {
    val arity = vars.size
    val rows = parseShortRows(text, arity)
    val n = rows.vals.size / arity
    for (r in 0 until n) {
        var allWild = true
        for (c in 0 until arity) {
            if (!rows.wild[r * arity + c]) {
                allWild = false
                break
            }
        }
        if (allWild) return
    }
    val hasWild = rows.wild.any { it }
    val mask = if (!hasWild) {
        null
    } else {
        LongArray(((n.toLong() * arity + 63) ushr 6).toInt()).also { m ->
            for (i in rows.wild.indices) if (rows.wild[i]) m[i ushr 6] = m[i ushr 6] or (1L shl (i and 63))
        }
    }
    factors.add(Table(xs = vars, tuples = rows.vals, wildcards = mask))
}

/** Post a negative `<conflicts>` table as one nogood clause per forbidden tuple — `x0 ≠ v0 ∨ … ∨
 *  xk ≠ vk` — omitting any `*` column (it forbids regardless of that variable). This lowers each
 *  tuple directly, never materializing the allowed complement. */
internal fun Xcsp3.Builder.postConflictClauses(vars: IntArray, text: String) {
    val arity = vars.size
    val rows = parseShortRows(text, arity)
    val n = rows.vals.size / arity
    for (r in 0 until n) {
        val lits = IntArrayList()
        for (c in 0 until arity) {
            if (rows.wild[r * arity + c]) continue
            val eq = reifyLinear(intArrayOf(1), intArrayOf(vars[c]), LinearOp.EQ, rows.vals[r * arity + c].toInt())
            lits.add(Lit.negate(eq)) // vars[c] ≠ value
        }
        if (lits.isEmpty()) {
            // An all-wildcard forbidden tuple rules out every assignment ⇒ unsatisfiable.
            factors.add(Clause(intArrayOf(Lit.negate(trueLit()))))
            return
        }
        factors.add(Clause(lits.toIntArray()))
    }
}

/** Flat short-tuple rows: [vals] holds values row-major (a `0` placeholder in wildcard cells) and
 *  [wild] flags which cells are wildcards. */
internal class ShortRows(val vals: LongArray, val wild: BooleanArray)

/** Parse `<supports>`/`<conflicts>` tuple text into short rows. A `*` column becomes a wildcard
 *  cell; a `lo..hi` column expands to its values (a Cartesian product over the range columns only,
 *  so `*` never multiplies the row count); everything else is a single value. Unary tables use the
 *  bare-value form (`0 1 2`, no parentheses). */
internal fun Xcsp3.Builder.parseShortRows(text: String, arity: Int): ShortRows {
    val t = text.trim()
    val vals = LongArrayList()
    val wild = IntArrayList()
    if (arity == 1 && '(' !in t) {
        for (tok in t.split(Regex("\\s+")).filter { it.isNotBlank() }) appendShortRow(listOf(tok), arity, vals, wild)
    } else {
        for (m in Regex("""\(([^)]*)\)""").findAll(t)) {
            val row = m.groupValues[1].split(",").map { it.trim() }
            if (row.size != arity) throw UnsupportedXcsp3Exception("tuple arity ${row.size} != $arity")
            appendShortRow(row, arity, vals, wild)
        }
    }
    return ShortRows(vals.toLongArray(), BooleanArray(wild.size) { wild[it] == 1 })
}

/** Expand one written tuple into short rows: `*` is a single wildcard option, `lo..hi` enumerates
 *  the range, everything else is a fixed value — appending the Cartesian product to [vals]/[wild].
 *  The range-expansion product is capped by `negTableCap`; `*` contributes a single option, so it
 *  never triggers the cap. */
internal fun Xcsp3.Builder.appendShortRow(
    colTokens: List<String>,
    arity: Int,
    vals: LongArrayList,
    wild: IntArrayList,
) {
    val options = colTokens.map { tok ->
        when {
            tok == "*" -> listOf(0L to true)
            ".." in tok -> tok.split("..").let { (it[0].toInt()..it[1].toInt()).map { v -> v.toLong() to false } }
            else -> listOf(tok.toLong() to false)
        }
    }
    val curV = LongArray(arity)
    val curW = IntArray(arity)
    fun rec(p: Int) {
        if (p == arity) {
            for (c in 0 until arity) {
                vals.add(curV[c])
                wild.add(curW[c])
            }
            if (vals.size / arity > negTableCap) {
                throw UnsupportedXcsp3Exception("table range expansion exceeds cap ($negTableCap)")
            }
            return
        }
        for ((v, w) in options[p]) {
            curV[p] = v
            curW[p] = if (w) 1 else 0
            rec(p + 1)
        }
    }
    rec(0)
}
