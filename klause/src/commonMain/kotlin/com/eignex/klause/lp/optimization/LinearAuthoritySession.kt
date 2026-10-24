package com.eignex.klause.lp.optimization

import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.SourceLpSparseAdmission
import com.eignex.klause.lp.SparseSourceLp
import com.eignex.klause.lp.SparseSourceLpPolicy
import com.eignex.klause.lp.engine.ExactLpBounds
import com.eignex.klause.lp.engine.ExactLpColumn
import com.eignex.klause.lp.engine.ExactLpModel
import com.eignex.klause.lp.engine.ExactLpNumber
import com.eignex.klause.lp.engine.ExactLpObjective
import com.eignex.klause.lp.engine.LpCertificationObserver
import com.eignex.klause.lp.engine.LpExactState
import com.eignex.klause.lp.engine.LpFloatAllowance
import com.eignex.klause.lp.engine.LpRootGuidance
import com.eignex.klause.lp.engine.LpScopedSolver
import com.eignex.klause.lp.engine.advanceRootGuidance
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.util.Cancellation

internal class LinearAuthoritySession(private val budget: SourceLpBudget) : AutoCloseable {
    private var rows: List<ExactRationalInequality> = emptyList()
    private var owner: LpScopedSolver? = null
    private var objective: ExactLpObjective? = null
    private var sides: List<ExactLpColumn>? = null
    private var inactive: Set<Int> = emptySet()
    private var authority: SourceScope? = null
    private var effort: LpFloatAllowance? = null
    private var sparse: SparseSourceLp? = null
    private var admission: SourceLpSparseAdmission? = null
    private var completed: LpRootGuidance? = null
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
        build: () -> ExactLpModel?,
        authority: SourceScope? = null,
        allowance: LpFloatAllowance? = null,
    ): LpScopedSolver? {
        currentToken = cancellation
        var current = owner
        val profile = allowance ?: effort ?: LpFloatAllowance(2_000_000L, 1024)
        val changedObjective = objective?.let { !sameObjective(it, costs) } == true
        if (current == null || current.state.model.n != packed.columns.size || !sameRows(rows, packed.rows) ||
            profile != effort || (changedObjective && sparse != null)) {
            if (current != null) close()
            currentToken = cancellation
            val model = build() ?: return null
            current = LpScopedSolver(LpExactState(model), token, context = budget.context,
                iterationLimit = profile.iterations, workLimit = profile.work)
            owner = current
            rows = packed.rows
            objective = costs
            effort = profile
            preparations++
        } else {
            reuses++
            if (changedObjective) {
                if (!current.replaceObjective(costs, token)) return null
                objective = costs
            }
        }
        if (sides != packed.columns || inactive != packed.inactiveRows || this.authority != authority) {
            if (!current.pop(0, token) || !current.push(token)) return null
            if (!current.suspend(packed.inactiveRows.mapTo(HashSet()) { it.toLong() }, token)) return null
            var witness = 0L
            for ((id, column) in packed.columns.withIndex()) {
                for ((upper, side) in listOf(false to column.bounds.lower, true to column.bounds.upper)) {
                    if (side != null && !current.assertBound(id, upper, side, witness++, token)) return null
                }
            }
            sides = packed.columns
            inactive = packed.inactiveRows
            this.authority = authority
        }
        return current
    }

    fun <T> advance(
        packed: LinearAuthorityModel,
        costs: List<BigFraction>,
        constant: BigFraction,
        scope: SourceScope,
        cancellation: Cancellation,
        policy: SparseSourceLpPolicy,
        observer: LpCertificationObserver,
        consume: (LpRootGuidance, Cancellation) -> T?,
    ): T? {
        if (cancellation()) {
            close()
            return null
        }
        val zero = ExactLpNumber.of(0L)
        val objective = ExactLpObjective(costs.map(ExactLpNumber::of) + List(packed.rows.size) { zero },
            ExactLpNumber.of(constant))
        val compatible = sparse != null && owner?.state?.model?.n == packed.columns.size &&
            effort == policy.floating && this.objective?.let { sameObjective(it, objective) } == true &&
            sameRows(rows, packed.rows)
        if (!compatible) {
            close()
            admission = budget.admitSparse(cancellation) { active ->
                SparseSourceLp.prepare(packed.rows, packed.columns.map { it.copy(bounds = ExactLpBounds()) },
                    costs, constant, policy.preparation, active)
            } ?: return null
            sparse = checkNotNull(admission).prepared
        } else if (authority != scope || sides != packed.columns || inactive != packed.inactiveRows) {
            admission?.close()
            admission = budget.admitSparse(checkNotNull(sparse), cancellation) ?: return null
            completed = null
        }
        val reservation = admission ?: return null
        return budget.resumeSparse(reservation, cancellation) { prepared, active ->
            currentToken = active
            val current = bind(packed, objective, active, { prepared.model }, scope, policy.floating)
                ?: return@resumeSparse null
            completed?.let { return@resumeSparse consume(it, token) }
            val preparation = current.metrics.preparationWork
            try {
                val guidance = current.advanceRootGuidance(policy.quantum, policy.floating,
                    policy.certificate, token, observer)
                    .also {
                        budget.observe(boundCertification = it.certificateMetrics?.work ?: 0L)
                        if (!it.paused) completed = it
                    }
                consume(guidance, token)
            } finally {
                budget.observe(preparation = current.metrics.preparationWork - preparation,
                    floatWork = current.lastMetrics.workOps)
            }
        }.also {
            if (cancellation()) close()
        }
    }

    override fun close() {
        val current = owner
        owner = null
        rows = emptyList()
        objective = null
        sides = null
        inactive = emptySet()
        authority = null
        effort = null
        sparse = null
        admission?.close()
        admission = null
        completed = null
        budget.closeOwner(current)
    }
}

private fun sameObjective(left: ExactLpObjective, right: ExactLpObjective): Boolean =
    left.size == right.size && left.constant == right.constant &&
        (0 until left.size).all { left.cost(it) == right.cost(it) }

private fun sameRows(left: List<ExactRationalInequality>, right: List<ExactRationalInequality>): Boolean =
    left.size == right.size && left.indices.all { id ->
        val a = left[id]
        val b = right[id]
        a.columns.contentEquals(b.columns) && a.coefficients == b.coefficients && a.rhs == b.rhs && a.strict == b.strict
    }
