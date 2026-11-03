package com.eignex.klause.lp.optimization

import com.eignex.klause.lp.SourceLpBudget
import com.eignex.klause.lp.SourceLpSparseAdmission
import com.eignex.klause.lp.SparseSourceLp
import com.eignex.klause.lp.SparseSourceLpPolicy
import com.eignex.klause.lp.SparseSourceLpRowPolicy
import com.eignex.klause.lp.engine.ExactLpBounds
import com.eignex.klause.lp.engine.ExactLpColumn
import com.eignex.klause.lp.engine.ExactLpModel
import com.eignex.klause.lp.engine.ExactLpNumber
import com.eignex.klause.lp.engine.ExactLpObjective
import com.eignex.klause.lp.engine.LpBoundAssertion
import com.eignex.klause.lp.engine.LpBoundBatchResult
import com.eignex.klause.lp.engine.LpCertificationObserver
import com.eignex.klause.lp.engine.LpExactState
import com.eignex.klause.lp.engine.LpFloatAllowance
import com.eignex.klause.lp.engine.LpFloatTermination
import com.eignex.klause.lp.engine.LpRootGuidance
import com.eignex.klause.lp.engine.LpScopedSolver
import com.eignex.klause.lp.engine.advanceRootGuidance
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.simplex.exact.ExactRationalInequality
import com.eignex.klause.solver.evidence.SourceScope
import com.eignex.klause.util.Cancellation

internal class LinearAuthoritySession(
    private val budget: SourceLpBudget,
    private val onLifecycle: (String, Long) -> Unit = { _, _ -> },
) : AutoCloseable {
    private data class Query(val scope: SourceScope, val columns: List<ExactLpColumn>, val inactive: Set<Int>)
    private var rows: List<ExactRationalInequality> = emptyList()
    private var owner: LpScopedSolver? = null
    private var objective: ExactLpObjective? = null
    private var sides: List<ExactLpColumn>? = null
    private var inactive: Set<Int> = emptySet()
    private var authority: SourceScope? = null
    private var effort: LpFloatAllowance? = null
    private var sparse: SparseSourceLp? = null
    private var rowPolicy: SparseSourceLpRowPolicy? = null
    private var admission: SourceLpSparseAdmission? = null
    private var query: Query? = null
    private var completed: LpRootGuidance? = null
    private var completedPolicy: SparseSourceLpPolicy? = null
    private var lifetime: LpFloatAllowance? = null
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
            (changedObjective && sparse != null)) {
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
            onLifecycle("OwnerPrepared", 1L)
        } else {
            reuses++
            onLifecycle("OwnerReused", 1L)
            if (changedObjective) {
                if (!current.replaceObjective(costs, token)) return null
                objective = costs
            }
        }
        effort = profile
        if (sides != packed.columns || inactive != packed.inactiveRows || this.authority != authority) {
            var mappingWork = 0L
            var mappingAllocation = 0L
            val suspended = if (sparse == null) packed.inactiveRows else checkNotNull(sparse)
                .emittedInactiveRows(packed.inactiveRows, token) { work, allocation ->
                    mappingWork += work
                    mappingAllocation += allocation
                    budget.observe(preparation = work)
                    budget.funding?.charge(0L, allocation)
                    budget.funding?.paused != true
                }
            onLifecycle("InactiveRowMappingWork", mappingWork)
            onLifecycle("InactiveRowMappingAllocation", mappingAllocation)
            if (suspended == null) {
                close()
                return null
            }
            if (!current.pop(0, token) || !current.push(token)) return null
            if (!current.suspend(suspended.mapTo(HashSet()) { it.toLong() }, token)) return null
            val assertions = ArrayList<LpBoundAssertion>()
            for ((id, column) in packed.columns.withIndex()) {
                if (token()) return null
                for ((upper, side) in listOf(false to column.bounds.lower, true to column.bounds.upper)) {
                    if (side != null) assertions += LpBoundAssertion(id, upper, side,
                        assertions.size.toLong(), current.state.depth)
                }
            }
            val batch = current.assertBounds(assertions, token)
            if (batch is LpBoundBatchResult.Declined) {
                close()
                return null
            }
            for (index in batch.count until assertions.size) {
                val side = assertions[index]
                if (!current.assertBound(side.column, side.upper, side.side, side.witness, token)) return null
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
        consume: (LpRootGuidance, SparseSourceLp, Cancellation) -> T?,
    ): T? {
        if (cancellation()) {
            close()
            return null
        }
        val zero = ExactLpNumber.of(0L)
        val objective = ExactLpObjective(costs.map(ExactLpNumber::of) +
            List(sparse?.emittedRowCount ?: packed.rows.size) { zero },
            ExactLpNumber.of(constant))
        val compatible = sparse?.model?.n == packed.columns.size &&
            rowPolicy == policy.rowPolicy &&
            this.objective?.let { sameObjective(it, objective) } == true &&
            sameRows(rows, packed.rows)
        val query = Query(scope, packed.columns, packed.inactiveRows)
        if (!compatible) {
            close()
            admission = budget.admitSparse(cancellation) { active ->
                SparseSourceLp.prepare(packed.rows, packed.columns.map { it.copy(bounds = ExactLpBounds()) },
                    costs, constant, policy, active)
            } ?: return null
            sparse = checkNotNull(admission).prepared
            rowPolicy = policy.rowPolicy
            rows = packed.rows
            this.objective = objective.takeIf { it.size == checkNotNull(sparse).model.numVars }
                ?: ExactLpObjective(costs.map(ExactLpNumber::of) +
                    List(checkNotNull(sparse).emittedRowCount) { zero }, ExactLpNumber.of(constant))
        } else if (admission == null || this.query != query) {
            admission?.close()
            admission = budget.admitSparse(checkNotNull(sparse), cancellation) ?: return null
            completed = null
            lifetime = null
        }
        this.query = query
        val reservation = admission ?: return null
        return budget.resumeSparse(reservation, cancellation) { prepared, active ->
            currentToken = active
            val funding = budget.funding
            if (funding?.paused == true) return@resumeSparse null
            val profile = if (funding == null) policy.floating else lifetime ?: policy.funded(funding.remainingWork)
                .also { lifetime = it }
            val emittedObjective = objective.takeIf { it.size == prepared.model.numVars } ?: ExactLpObjective(
                costs.map(ExactLpNumber::of) + List(prepared.emittedRowCount) { zero }, ExactLpNumber.of(constant))
            val current = bind(packed, emittedObjective, active, { prepared.model }, scope, profile)
                ?: return@resumeSparse null
            completed?.let {
                val usable = it.primal != null || it.bound != null || it.conflict != null
                if (completedPolicy?.certificate == policy.certificate &&
                    (usable || completedPolicy?.floating == profile)) {
                    onLifecycle("QueryReused", 1L)
                    return@resumeSparse consume(it, prepared, token)
                }
                completed = null
            }
            val quantum = minOf(policy.quantum, funding?.availableWork ?: policy.quantum)
            if (quantum <= 0L) return@resumeSparse null
            val preparation = current.metrics.preparationWork
            try {
                val guidance = current.advanceRootGuidance(quantum, profile,
                    policy.certificate, token, observer)
                    .also {
                        val work = it.certificateMetrics?.work ?: 0L
                        if (it.termination == LpFloatTermination.INFEASIBLE_CANDIDATE) {
                            budget.observe(conflictCertification = work)
                        } else {
                            budget.observe(boundCertification = work)
                        }
                        if (!it.paused) {
                            completed = it
                            completedPolicy = policy.copy(floating = profile)
                        }
                    }
                consume(guidance, prepared, token)
            } finally {
                observer?.observeSolve(current.lastMetrics, component = false)
                budget.observe(preparation = current.metrics.preparationWork - preparation,
                    floatWork = current.lastMetrics.workOps)
            }
        }.also {
            if (cancellation()) close()
        }
    }

    fun invalidateGuidance() {
        onLifecycle("GuidanceInvalidated", 1L)
        if (owner?.floatPaused == true) {
            close()
        } else {
            admission?.close()
            admission = null
            query = null
            completed = null
            lifetime = null
        }
    }

    fun <T> withGuidance(cancellation: Cancellation, block: (Cancellation) -> T?): T? {
        val reservation = admission ?: return null
        return budget.resumeSparse(reservation, cancellation) { _, active -> block(active) }
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
        rowPolicy = null
        admission?.close()
        admission = null
        query = null
        completed = null
        completedPolicy = null
        lifetime = null
        if (current != null) onLifecycle("OwnerClosed", 1L)
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
