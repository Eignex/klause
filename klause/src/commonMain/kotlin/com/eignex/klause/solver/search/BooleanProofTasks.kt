package com.eignex.klause.solver.search

import com.eignex.klause.ir.Lit

/** Outcome supplied by a task's scoped result checker; witnesses and stops never close a region. */
enum class BooleanTaskCheck {
    /** No refutation accepted, including a witness, cancellation or an incomplete check. */
    Unresolved,
    /** Scoped native refutation; independent proof verification remains unavailable. */
    NativeRefutation,
    /** A separately supplied independent checker accepted this scoped refutation. */
    IndependentlyCheckedRefutation,
}

/** Opaque ownership of one Boolean task attempt. Instances belong to exactly one task board. */
class BooleanTaskLease internal constructor(
    internal val board: Any,
    internal val epoch: Long,
    internal val attempt: Long,
    /** Stable task index in the partition. */
    val task: Int,
    /** Worker identity within this board. */
    val worker: Int,
    /** Complete task authority, including exact objective cutoff. */
    val context: BooleanProofContext,
    /** Additional Boolean restrictions; caller restrictions remain in [context]. */
    val cube: BooleanCube,
)

/**
 * Serialized bounded task/lease protocol over an exhaustive Boolean partition. This board grants no
 * solver verdict authority and runs no workers. Its caller owns execution, result checking and loss
 * detection. Lost or cancelled attempts return their whole region; a context change invalidates all
 * attempts and closures, even for a tighter incumbent. At most 4096 tasks are retained.
 * [splitVariables] are unpinned original source Boolean IDs; the executor owns validated translation
 * into its private mapping before asserting the cube together with the complete caller condition.
 */
class BooleanProofTasks(
    context: BooleanProofContext,
    splitVariables: List<Int>,
) {
    private val identity = Any()
    private val variables = splitVariables.also { require(it.size <= 12) }.toList()
    private var epoch = 0L
    private var nextAttempt = 0L
    private val owners: Array<BooleanTaskLease?>
    private val closures: Array<BooleanTaskCheck>
    /** Current authority; changed only through [replaceContext]. */
    var context: BooleanProofContext = context
        private set
    /** Number of disjoint regions in this board. */
    val size: Int get() = owners.size
    /** Regions awaiting an owner. */
    val pending: Int get() = owners.indices.count { owners[it] == null && closures[it] == BooleanTaskCheck.Unresolved }
    /** Regions closed by either native or independently checked refutations. */
    val closed: Int get() = closures.count { it != BooleanTaskCheck.Unresolved }
    /** Exhaustive native coverage only, without an independent UNSAT or optimality claim. */
    val nativeCoverageComplete: Boolean get() = closed == size
    /** Every region has an independently checked scoped refutation. */
    val independentlyCheckedCoverageComplete: Boolean
        get() = closures.all { it == BooleanTaskCheck.IndependentlyCheckedRefutation }

    init {
        require(variables.size <= 12 && variables.distinct().size == variables.size)
        require(variables.all { it in 0..(Int.MAX_VALUE ushr 1) })
        require(variables.none { it in context.scope.condition.bools })
        owners = arrayOfNulls(1 shl variables.size)
        closures = Array(owners.size) { BooleanTaskCheck.Unresolved }
    }

    /** Lease the first pending region; one worker cannot own two simultaneous attempts. */
    fun acquire(worker: Int): BooleanTaskLease? {
        require(worker >= 0)
        require(owners.none { it?.worker == worker })
        val index = owners.indices.firstOrNull {
            owners[it] == null && closures[it] == BooleanTaskCheck.Unresolved
        } ?: return null
        return grant(worker, index)
    }

    internal fun acquire(worker: Int, index: Int): BooleanTaskLease? {
        require(worker >= 0 && index in owners.indices)
        require(owners.none { it?.worker == worker })
        if (owners[index] != null || closures[index] != BooleanTaskCheck.Unresolved) return null
        return grant(worker, index)
    }

    private fun grant(worker: Int, index: Int): BooleanTaskLease {
        check(nextAttempt < Long.MAX_VALUE)
        val cube = BooleanCube(variables.mapIndexed { bit, variable -> Lit.make(variable, index and (1 shl bit) != 0) })
        return BooleanTaskLease(identity, epoch, ++nextAttempt, index, worker, context, cube).also {
            owners[index] = it
        }
    }

    /** Return unclosed coverage after a stop or lease loss. Stale and duplicate releases do nothing. */
    fun release(lease: BooleanTaskLease): Boolean {
        if (!owns(lease)) return false
        owners[lease.task] = null
        return true
    }

    /** Return all coverage owned by a lost worker, without touching siblings or accepted closures. */
    fun loseWorker(worker: Int) {
        for (index in owners.indices) if (owners[index]?.worker == worker) owners[index] = null
    }

    /** Cancel active attempts while retaining the pending partition and accepted closures for resumption. */
    fun cancel() {
        owners.fill(null)
    }

    /**
     * Check one current completion before accepting closure. The checker must validate the actual result
     * against this lease's full source, caller restrictions, cube and cutoff. Reentrant invalidation is
     * checked again after the callback. An unresolved result returns its region for retry.
     */
    fun complete(lease: BooleanTaskLease, checkResult: (BooleanTaskLease) -> BooleanTaskCheck): Boolean {
        if (!owns(lease)) return false
        val checked = checkResult(lease)
        if (!owns(lease)) return false
        owners[lease.task] = null
        closures[lease.task] = checked
        return checked != BooleanTaskCheck.Unresolved
    }

    /** Invalidate all leases and closures for any source, assumptions, mapping or objective change. */
    fun replaceContext(replacement: BooleanProofContext) {
        require(variables.none { it in replacement.scope.condition.bools })
        check(epoch < Long.MAX_VALUE)
        epoch++
        context = replacement
        owners.fill(null)
        closures.fill(BooleanTaskCheck.Unresolved)
    }

    private fun owns(lease: BooleanTaskLease): Boolean = lease.board === identity && lease.epoch == epoch &&
        lease.context == context && lease.task in owners.indices && owners[lease.task] === lease
}
