package com.eignex.klause.schema

import com.eignex.klause.model.AllDifferent
import com.eignex.klause.model.BoolExpr
import com.eignex.klause.model.IntTerm
import com.eignex.klause.model.TableConstraint

/*
 * Constraint builders that validate their operands against *declared* variable domains. That's a
 * schema-layer concern: only an [IntHandle] carries a declared `min`/`max`, so these guards reach
 * for it. The pure (domain-agnostic) expression and constraint builders stay in `ast.Builders`.
 */

/** All of [xs] take pairwise-distinct values (with a pigeonhole UNSAT guard on bare handles). */
fun allDifferent(vararg xs: IntTerm): BoolExpr {
    require(xs.size >= 2) { "allDifferent(): need at least two terms" }
    // Pigeonhole guard: if all operands are bare schema handles, check that the union of their
    // domains is large enough to host one distinct value per term.
    val handles = xs.mapNotNull { it as? IntHandle }
    if (handles.size == xs.size) {
        val unionSize = unionDomainSize(handles.map { it.min to it.max })
        require(unionSize >= xs.size) {
            "allDifferent: union of operand domains has only $unionSize distinct values, " +
                "cannot host ${xs.size} all-different terms (pigeonhole UNSAT)."
        }
    }
    return AllDifferent(xs.map { it.toIntExpr() })
}

private fun unionDomainSize(ranges: List<Pair<Int, Int>>): Long {
    if (ranges.isEmpty()) return 0L
    val sorted = ranges.sortedBy { it.first }
    var total = 0L
    var curLo = sorted[0].first
    var curHi = sorted[0].second
    for (i in 1 until sorted.size) {
        val (lo, hi) = sorted[i]
        if (lo <= curHi + 1) {
            if (hi > curHi) curHi = hi
        } else {
            total += (curHi - curLo + 1).toLong()
            curLo = lo
            curHi = hi
        }
    }
    total += (curHi - curLo + 1).toLong()
    return total
}

/** Extensional positive table: `vars` must equal one of the listed `allowed` tuples. */
fun table(vars: List<IntTerm>, allowed: List<List<Int>>): BoolExpr {
    validateTableTuples(vars, allowed)
    return TableConstraint(vars.map { it.toIntExpr() }, allowed, negative = false)
}

/** Extensional negative table: `vars` must not equal any of the listed `forbidden` tuples. */
fun notTable(vars: List<IntTerm>, forbidden: List<List<Int>>): BoolExpr {
    validateTableTuples(vars, forbidden)
    return TableConstraint(vars.map { it.toIntExpr() }, forbidden, negative = true)
}

private fun validateTableTuples(vars: List<IntTerm>, tuples: List<List<Int>>) {
    require(vars.isNotEmpty()) { "table: vars must not be empty" }
    require(tuples.isNotEmpty()) { "table: tuples must not be empty" }
    require(tuples.all { it.size == vars.size }) {
        "table: every tuple must have arity ${vars.size}"
    }
    for ((i, term) in vars.withIndex()) {
        val handle = term as? IntHandle ?: continue
        for ((tIdx, tup) in tuples.withIndex()) {
            val v = tup[i]
            require(v in handle.min..handle.max) {
                "table: tuple #$tIdx value $v at position $i is outside operand " +
                    "${handle.name}'s domain [${handle.min}..${handle.max}]"
            }
        }
    }
}
