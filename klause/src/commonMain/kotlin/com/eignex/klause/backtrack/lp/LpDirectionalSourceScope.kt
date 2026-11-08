package com.eignex.klause.backtrack.lp

import com.eignex.klause.lp.engine.ExactLpColumn
import com.eignex.klause.lp.engine.ExactLpObjective
import com.eignex.klause.lp.engine.LpExactState
import com.eignex.klause.lp.relaxation.CutColumnSource
import com.eignex.klause.lp.relaxation.LpRelaxation

internal class LpDirectionalSourceScope private constructor(
    private val source: Any,
    private val columns: List<ExactLpColumn>,
    private val coordinates: List<CutColumnSource?>,
    private val variables: IntArray,
    private val booleans: BooleanArray,
    private var objective: ExactLpObjective,
    private val maxSize: Int,
) {
    fun matches(relaxation: LpRelaxation, charge: (Long) -> Unit): Boolean {
        charge(1L)
        val state = eligible(relaxation, maxSize) ?: return false
        val map = checkNotNull(relaxation.sourceMap)
        val model = state.baseModel
        val nextObjective = model.objective
        val sameObjective = nextObjective === objective
        charge(8L + 8L * model.n + if (sameObjective) 0L else model.m.toLong())
        if (source !== map.model ||
            !variables.contentEquals(relaxation.colVarId) || !booleans.contentEquals(relaxation.colIsBool) ||
            objective.constant !== nextObjective.constant || objective.scale !== nextObjective.scale ||
            objective.externalConstant !== nextObjective.externalConstant || objective.sense != nextObjective.sense
        ) return false
        for (column in columns.indices) {
            if (columns[column] !== model.column(column) || coordinates[column] !== map.column(column) ||
                objective.cost(column) !== nextObjective.cost(column)
            ) return false
        }
        if (!sameObjective && !zeroLogicalCosts(state)) return false
        objective = nextObjective
        return true
    }

    companion object {
        private const val MAX_SIZE = 8192

        fun capture(
            relaxation: LpRelaxation,
            charge: (Long) -> Unit,
            maxSize: Int = MAX_SIZE,
        ): LpDirectionalSourceScope? {
            require(maxSize in 0..MAX_SIZE)
            charge(1L)
            val state = eligible(relaxation, maxSize) ?: return null
            val map = checkNotNull(relaxation.sourceMap)
            val model = state.baseModel
            charge(8L + 8L * model.n + model.m)
            if (!zeroLogicalCosts(state)) return null
            if (relaxation.colVarId.indices.any { relaxation.colVarId[it] >= 0 && map.column(it) == null }) return null
            return LpDirectionalSourceScope(
                map.model, List(model.n) { model.column(it) }, List(model.n) { map.column(it) },
                relaxation.colVarId.copyOf(), relaxation.colIsBool.copyOf(), model.objective, maxSize,
            )
        }

        private fun eligible(relaxation: LpRelaxation, maxSize: Int): LpExactState? {
            val state = relaxation.model.exactState ?: return null
            val map = relaxation.sourceMap ?: return null
            val model = state.baseModel
            if (model.n > maxSize || model.m > maxSize || map.hasAssumptions ||
                model.n != relaxation.colVarId.size || model.n != relaxation.colIsBool.size ||
                map.columnCount != model.n || model.objective.size != model.numVars
            ) return null
            return state
        }

        private fun zeroLogicalCosts(state: LpExactState): Boolean =
            (state.baseModel.n until state.baseModel.numVars).all {
                state.baseModel.objective.cost(it).exactLong() == 0L
            }
    }
}
