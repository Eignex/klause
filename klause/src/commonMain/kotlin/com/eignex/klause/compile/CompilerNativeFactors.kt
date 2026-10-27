package com.eignex.klause.compile

import com.eignex.klause.factor.global.LexLess
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.ir.intdomain.intDomainFromRuns
import com.eignex.klause.model.And
import com.eignex.klause.model.BoolExpr
import com.eignex.klause.model.FactorConstraint
import com.eignex.klause.model.IntCmpOp
import com.eignex.klause.model.IntCompare
import com.eignex.klause.model.IntDomainSpec
import com.eignex.klause.model.IntExpr
import com.eignex.klause.model.IntLit
import com.eignex.klause.model.LexExpr
import com.eignex.klause.model.Or
import com.eignex.klause.model.VariableCumulativeExpr
import com.eignex.klause.util.LongArrayList

/**
 * Allocate the column [spec] declares. An open side keeps the full signed-word box as its finite
 * fallback, so no lane reads an invented endpoint as tighter than the representable range.
 */
internal fun Lowering.declareIntDomain(spec: IntDomainSpec): Int {
    val runs = spec.values
    val domain = if (runs == null) {
        IntDomain(spec.lower ?: Long.MIN_VALUE, spec.upper ?: Long.MAX_VALUE)
    } else {
        intDomainFromRuns(
            LongArrayList(runs.size * 2).apply {
                for (run in runs) {
                    add(run[0])
                    add(run[1])
                }
            },
        )
    }
    val id = newIntVar(domain)
    if (spec.lower == null || spec.upper == null) markOpenInt(id, spec.lower == null, spec.upper == null)
    return id
}

internal fun Lowering.assertLex(expr: LexExpr) {
    val xs = IntArray(expr.xs.size) { varIdOfLifted(expr.xs[it]) }
    val ys = IntArray(expr.ys.size) { varIdOfLifted(expr.ys[it]) }
    factors += LexLess(xs, ys, expr.strict)
}

/**
 * `lex(x, y) ⟺ x(0) < y(0) ∨ (x(0) = y(0) ∧ lex(x(1..), y(1..)))`, built from the last index inward. A
 * reified order has no native factor, so it lowers through these comparisons.
 */
internal fun lexDecomposition(expr: LexExpr): BoolExpr {
    val last = expr.xs.lastIndex
    var result: BoolExpr = IntCompare(expr.xs[last], if (expr.strict) IntCmpOp.LT else IntCmpOp.LE, expr.ys[last])
    for (i in last - 1 downTo 0) {
        val less = IntCompare(expr.xs[i], IntCmpOp.LT, expr.ys[i])
        val equal = IntCompare(expr.xs[i], IntCmpOp.EQ, expr.ys[i])
        result = Or(listOf(less, And(listOf(equal, result))))
    }
    return result
}

/**
 * Post one native cumulative. An array of literals stays constant; any other array becomes columns, with
 * each column's upper bound as the constant the factor keeps beside it.
 */
internal fun Lowering.assertVariableCumulative(expr: VariableCumulativeExpr) {
    val starts = IntArray(expr.starts.size) { varIdOfLifted(expr.starts[it]) }
    val (durations, durationVars) = constantsOrColumns(expr.durations)
    val (demands, demandVars) = constantsOrColumns(expr.demands)
    val capacity = expr.capacity
    val capacityVar = if (capacity is IntLit) -1 else varIdOfLifted(capacity)
    factors += Cumulative(
        starts = starts,
        durations = durations,
        resources = demands,
        capacity = if (capacity is IntLit) capacity.value.toLong() else intDomains[capacityVar].max,
        durationVars = durationVars,
        resourceVars = demandVars,
        capacityVar = capacityVar,
    )
}

private fun Lowering.constantsOrColumns(exprs: List<IntExpr>): Pair<LongArray, IntArray> {
    if (exprs.all { it is IntLit }) {
        return LongArray(exprs.size) { (exprs[it] as IntLit).value.toLong() } to IntArray(0)
    }
    val columns = IntArray(exprs.size) { varIdOfLifted(exprs[it]) }
    return LongArray(columns.size) { intDomains[columns[it]].max } to columns
}

/**
 * Decode [expr]'s factor with this compilation's codecs, rename its local ids to the bound columns and
 * post it unchanged.
 */
internal fun Lowering.assertFactorConstraint(expr: FactorConstraint) {
    val local = factorCodecs.decodeFromJsonElement(expr.node)
    val bools = IntArray(expr.bools.size) {
        boolVarIdByName[expr.bools[it]] ?: error("Unknown Boolean variable '${expr.bools[it]}'")
    }
    val ints = IntArray(expr.ints.size) { intVarOf(expr.ints[it]) }
    val reals = IntArray(expr.reals.size) {
        realVarIdByName[expr.reals[it]] ?: error("Unknown float variable '${expr.reals[it]}'")
    }
    val variables = local.variables
    require(variables.boolVars.all { it in bools.indices }) { "factor: a Boolean id has no bound name" }
    require(variables.ints.all { it in ints.indices }) { "factor: an integer id has no bound name" }
    require(variables.reals.all { it in reals.indices }) { "factor: a real id has no bound name" }
    factors += local.remap(VarRemap(bools, ints, reals))
}
