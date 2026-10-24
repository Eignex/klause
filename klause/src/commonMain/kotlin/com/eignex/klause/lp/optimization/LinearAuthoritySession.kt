package com.eignex.klause.lp.optimization

import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.engine.ExactLpColumn
import com.eignex.klause.lp.engine.ExactLpModel
import com.eignex.klause.lp.engine.ExactLpObjective
import com.eignex.klause.lp.engine.LpExactState
import com.eignex.klause.lp.engine.LpScopedSolver
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.util.Cancellation

internal class LinearAuthoritySession(private val budget: SourceLpBudget) : AutoCloseable {
    private var rows: List<ExactRationalInequality> = emptyList()
    private var owner: LpScopedSolver? = null
    private var objective: ExactLpObjective? = null
    private var sides: List<ExactLpColumn>? = null
    private var currentToken: Cancellation = Cancellation.Never
    private val token = object : Cancellation {
        override fun isCancelled(): Boolean = currentToken()
        override fun deadline() = currentToken.deadline()
        override fun workMeter() = currentToken.workMeter()
    }
    var preparations: Long = 0L
        private set
    var reuses: Long = 0L
        private set

    fun bind(
        packed: LinearAuthorityModel,
        costs: ExactLpObjective,
        cancellation: Cancellation,
        build: () -> ExactLpModel,
    ): LpScopedSolver? {
        currentToken = cancellation
        var current = owner
        if (current == null || current.state.model.n != packed.columns.size || !sameRows(rows, packed.rows)) {
            close()
            currentToken = cancellation
            val model = build()
            current = LpScopedSolver(LpExactState(model), token, context = budget.context,
                iterationLimit = 1024, workLimit = 2_000_000L)
            owner = current
            rows = packed.rows
            objective = costs
            preparations++
        } else {
            reuses++
            val previous = checkNotNull(objective)
            if (previous.size != costs.size || previous.constant != costs.constant ||
                (0 until costs.size).any { previous.cost(it) != costs.cost(it) }) {
                if (!current.replaceObjective(costs, token)) return null
                objective = costs
            }
        }
        if (sides != packed.columns) {
            if (!current.pop(0, token) || !current.push(token)) return null
            var witness = 0L
            for ((id, column) in packed.columns.withIndex()) {
                for ((upper, side) in listOf(false to column.bounds.lower, true to column.bounds.upper)) {
                    if (side != null && !current.assertBound(id, upper, side, witness++, token)) return null
                }
            }
            sides = packed.columns
        }
        return current
    }

    override fun close() {
        val current = owner
        owner = null
        rows = emptyList()
        objective = null
        sides = null
        budget.closeOwner(current)
    }
}

private fun sameRows(left: List<ExactRationalInequality>, right: List<ExactRationalInequality>): Boolean =
    left.size == right.size && left.indices.all { id ->
        val a = left[id]
        val b = right[id]
        a.columns.contentEquals(b.columns) && a.coefficients == b.coefficients && a.rhs == b.rhs && a.strict == b.strict
    }
