package com.eignex.klause.lp.engine

import com.eignex.klause.simplex.exact.ExactContinuationLimits
import com.eignex.klause.simplex.exact.ExactContinuationMetrics
import com.eignex.klause.util.Cancellation

internal class LpWorkingModel(val source: LpExactState, auxiliary: ExactLpModel) {
    val state = LpExactState(auxiliary)

    companion object {
        fun overrides(
            source: LpExactState,
            objective: ExactLpObjective = source.model.objective,
            bounds: List<ExactLpBounds> = List(source.model.numVars) { source.model.column(it).bounds },
            rhs: List<ExactLpNumber> = List(source.model.m) { source.model.rhs(it) },
        ): LpWorkingModel {
            val model = source.model
            require(bounds.size == model.numVars && rhs.size == model.m && objective.size == model.numVars)
            require(
                (0 until model.m).all {
                    source.rows.row(it).active || objective.cost(model.n + it).value.isZero
                },
            ) { "inactive logical costs require explicit auxiliary authority" }
            return LpWorkingModel(
                source,
                ExactLpModel(
                    List(model.n) { model.entries(it) },
                    rhs,
                    List(model.numVars) { model.column(it).copy(bounds = bounds[it]) },
                    List(model.m) { model.row(it) },
                    objective,
                ),
            )
        }
    }
}

internal data class LpWorkingMetrics(
    val attempts: Long,
    val solves: LpSolveMetrics,
    val owners: LpScopedMetrics,
    val certificationAttempts: Long,
    val basisWork: Long,
    val continuationWork: Long,
    val continuationAllocation: Long,
    val children: List<LpWorkingMetrics>,
) {
    // Cheap certifiers and owner construction expose no complete modeled-work counter.
    val measuredWork: Long get() = solves.workOps + owners.preparationWork + basisWork + continuationWork +
        children.sumOf { it.measuredWork }
}

internal class LpWorkingScope internal constructor(
    model: LpWorkingModel,
    private val owner: LpScopedSolver,
    private val cancellation: Cancellation,
) {
    var model: LpWorkingModel = model
        private set
    private var closed = false
    private var attempts = 0L
    private var solves = LpSolveMetrics()
    private var certifications = 0L
    private var basisWork = 0L
    private var continuationWork = 0L
    private var continuationAllocation = 0L
    private val children = ArrayList<LpWorkingMetrics>()

    val state: LpExactState get() = model.state
    val metrics: LpWorkingMetrics get() = LpWorkingMetrics(
        attempts,
        solves,
        owner.metrics,
        certifications,
        basisWork,
        continuationWork,
        continuationAllocation,
        children.toList(),
    )

    @Suppress("TooGenericExceptionCaught") // Failed adoption must retire the child for every exception type.
    fun replaceState(next: LpWorkingModel): Boolean {
        check(!closed) { "working scope is closed" }
        owner.requireAvailable()
        require(next.source === model.source) { "working revision belongs to another source" }
        return try {
            if (!owner.resetRoot(next.state, cancellation)) return false
            model = next
            true
        } catch (primary: Throwable) {
            close(primary)
            throw primary
        }
    }

    fun solveFloat(warm: Basis? = null, allowance: LpFloatAllowance? = null): Pair<LpSolver, FloatLpResult?>? {
        check(!closed) { "working scope is closed" }
        owner.requireAvailable()
        attempts++
        return try {
            owner.solveFloat(warm, cancellation, allowance)
        } finally {
            solves += owner.lastMetrics
        }
    }

    fun solve(
        warm: Basis? = null,
        token: Cancellation = cancellation,
        continuationLimits: ExactContinuationLimits = ExactContinuationLimits(),
        fullContinuation: Boolean = true,
        observer: LpCertificationObserver? = null,
    ): CertifiedLpResult? {
        check(!closed) { "working scope is closed" }
        owner.requireAvailable()
        attempts++
        val accounting = object : LpCertificationObserver {
            override fun observe(certifier: LpCertifier, success: Boolean) {
                certifications++
                observer?.observe(certifier, success)
            }

            override fun observeExactInput(accepted: Boolean) {
                observer?.observeExactInput(accepted)
            }

            override fun observeSolve(metrics: LpSolveMetrics, component: Boolean) {
                observer?.observeSolve(metrics, component)
            }

            override fun observeBasisVerification(metrics: ExactBasisMetrics) {
                basisWork += metrics.work
                observer?.observeBasisVerification(metrics)
            }

            override fun observeContinuation(metrics: ExactContinuationMetrics) {
                continuationWork += metrics.work
                continuationAllocation += metrics.allocation
                observer?.observeContinuation(metrics)
            }
        }
        return try {
            owner.solve(
                warm,
                Cancellation { cancellation() || token() },
                continuationLimits = continuationLimits,
                fullContinuation = fullContinuation,
                observer = accounting,
            )
        } finally {
            solves += owner.lastMetrics
        }
    }

    fun <T> withWorkingModel(
        next: LpWorkingModel,
        allowance: LpFloatAllowance? = null,
        block: (LpWorkingScope) -> T,
    ): T {
        check(!closed) { "working scope is closed" }
        owner.requireAvailable()
        require(next.source === state) { "nested working model belongs to another authority" }
        return try {
            owner.withWorkingModel(next, cancellation, allowance, block)
        } finally {
            owner.lastWorkingMetrics?.let(children::add)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    internal fun close(primary: Throwable? = null) {
        if (closed) return
        closed = true
        try {
            owner.close()
        } catch (cleanup: Throwable) {
            if (primary == null) throw cleanup
            primary.addSuppressed(cleanup)
        }
    }
}
