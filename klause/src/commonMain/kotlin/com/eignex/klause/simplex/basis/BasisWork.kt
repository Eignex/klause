package com.eignex.klause.simplex.basis

internal enum class BasisBuildKind {
    REFACTORIZATION,
    REPAIR,
    EXTENSION,
}

// A unit is one reported entry visit, pivot visit, candidate test, Schur update, or copied entry.
// It is deterministic implementation-relative work, not elapsed time or a backend-comparable total.
internal data class BasisPhaseWork(
    val attempts: Long = 0,
    val successes: Long = 0,
    val units: Long = 0,
    val declines: Long = attempts - successes,
) {
    fun mergedWith(other: BasisPhaseWork) = BasisPhaseWork(
        saturatedAdd(attempts, other.attempts),
        saturatedAdd(successes, other.successes),
        saturatedAdd(units, other.units),
        saturatedAdd(declines, other.declines),
    )
}

internal data class BasisBuildWork(
    val kind: BasisBuildKind,
    val successful: Boolean,
    val builds: Long,
    val orderingAttempts: Long,
    val reusedOrders: Long,
    val fallbacks: Long,
    val units: Long,
    val installedBuildUnits: Long?,
)

// Solve and update phases accumulate from the latest numerical build attempt. A build attempt resets the
// epoch even when it fails. Snapshot restore reinstates the captured epoch for reinversion calibration.
internal data class BasisWork(
    val build: BasisBuildWork? = null,
    val ftran: BasisPhaseWork = BasisPhaseWork(),
    val btran: BasisPhaseWork = BasisPhaseWork(),
    val update: BasisPhaseWork = BasisPhaseWork(),
) {
    val workSinceBuild: Long get() = saturatedAdd(saturatedAdd(ftran.units, btran.units), update.units)
}

// Monotonic owner-lifetime work. Unlike [BasisWork], this is neither reset by a build nor rewound by
// snapshot restore, so a caller can account for rejected operations and their fallbacks.
internal data class BasisOperationWork(
    val refactorization: BasisPhaseWork = BasisPhaseWork(),
    val repair: BasisPhaseWork = BasisPhaseWork(),
    val extension: BasisPhaseWork = BasisPhaseWork(),
    val snapshot: BasisPhaseWork = BasisPhaseWork(),
    val restore: BasisPhaseWork = BasisPhaseWork(),
    val ftran: BasisPhaseWork = BasisPhaseWork(),
    val btran: BasisPhaseWork = BasisPhaseWork(),
    val update: BasisPhaseWork = BasisPhaseWork(),
    val complete: Boolean = true,
) {
    fun mergedWith(other: BasisOperationWork) = BasisOperationWork(
        refactorization.mergedWith(other.refactorization),
        repair.mergedWith(other.repair),
        extension.mergedWith(other.extension),
        snapshot.mergedWith(other.snapshot),
        restore.mergedWith(other.restore),
        ftran.mergedWith(other.ftran),
        btran.mergedWith(other.btran),
        update.mergedWith(other.update),
        complete && other.complete,
    )

    val units: Long
        get() {
            var total = 0L
            total = saturatedAdd(total, refactorization.units)
            total = saturatedAdd(total, repair.units)
            total = saturatedAdd(total, extension.units)
            total = saturatedAdd(total, snapshot.units)
            total = saturatedAdd(total, restore.units)
            total = saturatedAdd(total, ftran.units)
            total = saturatedAdd(total, btran.units)
            return saturatedAdd(total, update.units)
        }
    val saturated: Boolean
        get() = units == Long.MAX_VALUE ||
            refactorization.saturated || repair.saturated || extension.saturated || snapshot.saturated ||
            restore.saturated || ftran.saturated || btran.saturated || update.saturated
}

private val BasisPhaseWork.saturated: Boolean
    get() = attempts == Long.MAX_VALUE || successes == Long.MAX_VALUE ||
        declines == Long.MAX_VALUE || units == Long.MAX_VALUE

internal class BasisOperationMeter {
    private val phases = Array(BasisOperationKind.entries.size) { MutableBasisPhase() }
    private var complete = true

    fun attempt(kind: BasisOperationKind) {
        phases[kind.ordinal].attempts = saturatedAdd(phases[kind.ordinal].attempts, 1)
    }

    fun success(kind: BasisOperationKind, units: Long) {
        val phase = phases[kind.ordinal]
        phase.successes = saturatedAdd(phase.successes, 1)
        phase.units = saturatedAdd(phase.units, units)
    }

    fun decline(kind: BasisOperationKind, units: Long) {
        val phase = phases[kind.ordinal]
        phase.declines = saturatedAdd(phase.declines, 1)
        phase.units = saturatedAdd(phase.units, units)
    }

    fun declineUnknown(kind: BasisOperationKind, units: Long) {
        decline(kind, units)
        complete = false
    }

    fun snapshot(): BasisOperationWork = BasisOperationWork(
        phase(BasisOperationKind.REFACTORIZATION),
        phase(BasisOperationKind.REPAIR),
        phase(BasisOperationKind.EXTENSION),
        phase(BasisOperationKind.SNAPSHOT),
        phase(BasisOperationKind.RESTORE),
        phase(BasisOperationKind.FTRAN),
        phase(BasisOperationKind.BTRAN),
        phase(BasisOperationKind.UPDATE),
        complete,
    )

    private fun phase(kind: BasisOperationKind): BasisPhaseWork = phases[kind.ordinal].let {
        BasisPhaseWork(it.attempts, it.successes, it.units, it.declines)
    }
}

internal enum class BasisOperationKind {
    REFACTORIZATION,
    REPAIR,
    EXTENSION,
    SNAPSHOT,
    RESTORE,
    FTRAN,
    BTRAN,
    UPDATE,
}

private class MutableBasisPhase(
    var attempts: Long = 0,
    var successes: Long = 0,
    var declines: Long = 0,
    var units: Long = 0,
)

internal class BasisWorkMeter {
    private var build: BasisBuildWork? = null
    private var ftranAttempts = 0L
    private var ftranSuccesses = 0L
    private var ftranDeclines = 0L
    private var ftranUnits = 0L
    private var btranAttempts = 0L
    private var btranSuccesses = 0L
    private var btranDeclines = 0L
    private var btranUnits = 0L
    private var updateAttempts = 0L
    private var updateSuccesses = 0L
    private var updateDeclines = 0L
    private var updateUnits = 0L

    fun reset(build: BasisBuildWork?) {
        this.build = build
        ftranAttempts = 0
        ftranSuccesses = 0
        ftranDeclines = 0
        ftranUnits = 0
        btranAttempts = 0
        btranSuccesses = 0
        btranDeclines = 0
        btranUnits = 0
        updateAttempts = 0
        updateSuccesses = 0
        updateDeclines = 0
        updateUnits = 0
    }

    fun restore(work: BasisWork) {
        build = work.build
        ftranAttempts = work.ftran.attempts
        ftranSuccesses = work.ftran.successes
        ftranDeclines = work.ftran.declines
        ftranUnits = work.ftran.units
        btranAttempts = work.btran.attempts
        btranSuccesses = work.btran.successes
        btranDeclines = work.btran.declines
        btranUnits = work.btran.units
        updateAttempts = work.update.attempts
        updateSuccesses = work.update.successes
        updateDeclines = work.update.declines
        updateUnits = work.update.units
    }

    fun solveAttempt(transpose: Boolean) {
        if (transpose) {
            btranAttempts = saturatedAdd(btranAttempts, 1)
        } else {
            ftranAttempts = saturatedAdd(ftranAttempts, 1)
        }
    }

    fun solveSuccess(transpose: Boolean, units: Long) {
        if (transpose) {
            btranSuccesses = saturatedAdd(btranSuccesses, 1)
            btranUnits = saturatedAdd(btranUnits, units)
        } else {
            ftranSuccesses = saturatedAdd(ftranSuccesses, 1)
            ftranUnits = saturatedAdd(ftranUnits, units)
        }
    }

    fun solveDecline(transpose: Boolean, units: Long = 0) {
        if (transpose) {
            btranDeclines = saturatedAdd(btranDeclines, 1)
            btranUnits = saturatedAdd(btranUnits, units)
        } else {
            ftranDeclines = saturatedAdd(ftranDeclines, 1)
            ftranUnits = saturatedAdd(ftranUnits, units)
        }
    }

    fun updateAttempt() {
        updateAttempts = saturatedAdd(updateAttempts, 1)
    }

    fun updateSuccess(units: Long) {
        updateSuccesses = saturatedAdd(updateSuccesses, 1)
        updateUnits = saturatedAdd(updateUnits, units)
    }

    fun updateDecline(units: Long) {
        updateDeclines = saturatedAdd(updateDeclines, 1)
        updateUnits = saturatedAdd(updateUnits, units)
    }

    fun snapshot(): BasisWork = BasisWork(
        build,
        BasisPhaseWork(ftranAttempts, ftranSuccesses, ftranUnits, ftranDeclines),
        BasisPhaseWork(btranAttempts, btranSuccesses, btranUnits, btranDeclines),
        BasisPhaseWork(updateAttempts, updateSuccesses, updateUnits, updateDeclines),
    )
}

internal class BasisBuildAccumulator(private val kind: BasisBuildKind) {
    private var builds = 0L
    private var orderingAttempts = 0L
    private var reusedOrders = 0L
    private var fallbacks = 0L
    private var units = 0L

    fun add(report: LuBuildReport) {
        builds = saturatedAdd(builds, 1)
        if (report.proposedOrder) orderingAttempts = saturatedAdd(orderingAttempts, 1)
        if (report.reusedOrder) reusedOrders = saturatedAdd(reusedOrders, 1)
        if (report.fallback) fallbacks = saturatedAdd(fallbacks, 1)
        units = saturatedAdd(units, report.units)
    }

    fun report(successful: Boolean, installed: LuBuildReport? = null): BasisBuildWork = BasisBuildWork(
        kind,
        successful,
        builds,
        orderingAttempts,
        reusedOrders,
        fallbacks,
        units,
        installed?.units,
    )
}

internal val LuBuildWork.units: Long
    get() = saturatedAdd(
        saturatedAdd(inputEntries.toLong(), candidates),
        saturatedAdd(
            saturatedAdd(columnMaximumEntries, schurUpdates),
            factorEntries.toLong() * 2,
        ),
    )

internal val BasisSolveWork.units: Long
    get() = saturatedAdd(
        saturatedAdd(first.units, second.units),
        saturatedAdd(transformEntries, outputSupport.toLong()),
    )

internal val TriangularSolveWork.units: Long
    get() = saturatedAdd(saturatedAdd(reachEntries, pivotVisits.toLong()), arithmeticEntries)

internal val ForrestTomlinWork.units: Long
    get() = saturatedAdd(saturatedAdd(columnProducts, rowProducts), copiedEntries)

internal fun saturatedAdd(left: Long, right: Long): Long {
    require(left >= 0 && right >= 0)
    return if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
}
