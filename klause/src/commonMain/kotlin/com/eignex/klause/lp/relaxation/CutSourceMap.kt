package com.eignex.klause.lp.relaxation

import com.eignex.klause.lp.engine.CutExpression
import com.eignex.klause.lp.engine.CutPremise
import com.eignex.klause.lp.engine.CutProvenance
import com.eignex.klause.lp.engine.CutSource
import com.eignex.klause.lp.engine.CutSourceKind
import com.eignex.klause.lp.engine.exactBounds
import com.eignex.klause.lp.engine.exactShift
import com.eignex.klause.lp.engine.LpModel
import com.eignex.klause.simplex.exact.BigFraction

internal data class CutColumnSource(
    val source: CutSource,
    val scale: BigFraction = BigFraction.ONE,
    val offset: BigFraction = BigFraction.ZERO,
) {
    init { require(!scale.isZero) }
    fun expression(): CutExpression = CutExpression(mapOf(source to scale), offset)
}

internal class CutSourceMap(
    val model: Any,
    val epoch: Long,
    columns: List<CutColumnSource?>,
    globalPremises: Set<CutPremise> = emptySet(),
    activePremises: Set<CutPremise> = emptySet(),
    fixed: Map<CutSource, BigFraction> = emptyMap(),
    assumptions: Set<String> = emptySet(),
    parentRows: Map<Int, CutProvenance> = emptyMap(),
) {
    private val columnSnapshot = columns.toList()
    private val globalSnapshot = globalPremises.toSet()
    private val activeSnapshot = activePremises.toSet()
    private val fixedSnapshot = fixed.toMap()
    private val assumptionSnapshot = assumptions.toSet()
    private val parentSnapshot = parentRows.toMap()
    val columns: List<CutColumnSource?> get() = columnSnapshot.toList()
    val fixed: Map<CutSource, BigFraction> get() = fixedSnapshot.toMap()
    val assumptions: Set<String> get() = assumptionSnapshot.toSet()
    fun parent(row: Int): CutProvenance? = parentSnapshot[row]
    fun isGlobal(premise: CutPremise): Boolean = implied(premise, globalSnapshot)
    fun isActive(premise: CutPremise): Boolean = isGlobal(premise) || implied(premise, activeSnapshot)

    private fun implied(premise: CutPremise, facts: Set<CutPremise>): Boolean {
        if (premise in facts) return true
        if (premise is CutPremise.Fixed) {
            val expression = CutExpression(mapOf(premise.source to BigFraction.ONE))
            return implied(CutPremise.Bound(expression, false, premise.value), facts) &&
                implied(CutPremise.Bound(expression, true, premise.value), facts)
        }
        if (premise !is CutPremise.Bound) return false
        return facts.filterIsInstance<CutPremise.Bound>().any {
            it.expression == premise.expression && it.upper == premise.upper &&
                (if (it.upper) it.value < premise.value else it.value > premise.value) ||
                it.expression == premise.expression && it.upper == premise.upper && it.value == premise.value &&
                (!premise.strict || it.strict)
        }
    }

    fun withParents(parents: Map<Int, CutProvenance>): CutSourceMap = CutSourceMap(
        model, epoch, columnSnapshot, globalSnapshot, activeSnapshot, fixedSnapshot, assumptionSnapshot, parents,
    )
}

internal fun cpCutSources(
    model: LpModel,
    problem: com.eignex.klause.ir.Problem,
    vars: IntArray,
    booleans: BooleanArray,
    realIds: IntArray,
    realSigns: IntArray,
    parents: Map<Int, CutProvenance>,
): CutSourceMap {
    val globals = HashSet<CutPremise>()
    val columns = List(model.n) { col ->
        val real = realIds[col]
        val source = when {
            vars[col] >= 0 -> CutSource(if (booleans[col]) CutSourceKind.BOOLEAN else CutSourceKind.INTEGER, vars[col])
            real >= 0 && realSigns[col] == 1 && realIds.count { it == real } == 1 -> CutSource(CutSourceKind.REAL, real)
            else -> null
        }
        source?.let { CutColumnSource(it) }
    }
    for (column in columns.filterNotNull()) {
        val source = column.source
        val expression = column.expression()
        when (source.kind) {
            CutSourceKind.INTEGER -> {
                globals.add(CutPremise.Integral(expression))
                if (problem.intBounds.hasLower(source.id)) {
                    globals.add(CutPremise.Bound(expression, false, BigFraction.ofLong(problem.intBounds.lower(source.id))))
                }
                if (problem.intBounds.hasUpper(source.id)) {
                    globals.add(CutPremise.Bound(expression, true, BigFraction.ofLong(problem.intBounds.upper(source.id))))
                }
            }
            CutSourceKind.BOOLEAN -> {
                globals.add(CutPremise.Integral(expression))
                globals.add(CutPremise.Bound(expression, false, BigFraction.ZERO))
                globals.add(CutPremise.Bound(expression, true, BigFraction.ONE))
            }
            CutSourceKind.REAL -> {
                BigFraction.ofDouble(problem.realLower[source.id])?.let { globals.add(CutPremise.Bound(expression, false, it)) }
                BigFraction.ofDouble(problem.realUpper[source.id])?.let { globals.add(CutPremise.Bound(expression, true, it)) }
            }
            CutSourceKind.TERM -> Unit
        }
    }
    val active = HashSet<CutPremise>()
    for ((index, column) in columns.withIndex()) {
        if (column == null) continue
        val bounds = model.exactBounds(index)
        val origin = model.exactShift(index)
        bounds.lower?.let { active.add(CutPremise.Bound(column.expression(), false, it.number.value + origin, it.strict)) }
        bounds.upper?.let { active.add(CutPremise.Bound(column.expression(), true, it.number.value + origin, it.strict)) }
    }
    return CutSourceMap(problem, 0, columns, globals, active, parentRows = parents)
}
