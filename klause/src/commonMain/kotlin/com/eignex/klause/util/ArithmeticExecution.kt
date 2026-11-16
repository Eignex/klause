package com.eignex.klause.util

import kotlin.jvm.JvmField

internal interface ArithmeticWorkSource {
    val metered: Boolean get() = true
    val quotaOwner: Any? get() = null
    val quotaRemaining: Long? get() = null
    val quotaAvailable: Long? get() = quotaRemaining
    val exhausted: Boolean
    fun reserve(maximum: Int): WorkReservation?
}

internal class WorkReservation(@JvmField val capacity: Int, private val settlement: (Int) -> Unit) {
    private var settled = false

    init {
        require(capacity > 0)
    }

    fun settle(used: Int) {
        require(used in 0..capacity)
        check(!settled)
        settled = true
        settlement(used)
    }
}

private interface QuotaSource {
    val underlying: ArithmeticWorkSource
}

internal fun ArithmeticWorkSource.withoutQuotaViews(): ArithmeticWorkSource {
    var source = this
    while (source is QuotaSource) source = source.underlying
    return source
}

internal fun ArithmeticWorkSource.withQuota(bound: ArithmeticWorkSource): ArithmeticWorkSource =
    object : ArithmeticWorkSource, QuotaSource {
        override val underlying = this@withQuota
        override val metered: Boolean get() = this@withQuota.metered
        override val quotaOwner get() = this@withQuota.quotaOwner
        override val quotaRemaining: Long? get() {
            val own = this@withQuota.quotaRemaining
            val remaining = bound.quotaRemaining
            return if (own == null) {
                remaining
            } else if (remaining == null) {
                own
            } else {
                minOf(own, remaining)
            }
        }
        override val quotaAvailable: Long? get() {
            val own = this@withQuota.quotaAvailable
            val available = bound.quotaAvailable
            return if (own == null) {
                available
            } else if (available == null) {
                own
            } else {
                minOf(own, available)
            }
        }
        override val exhausted: Boolean get() = this@withQuota.exhausted || quotaRemaining?.let { it <= 0L } == true
        override fun reserve(maximum: Int): WorkReservation? {
            val capacity = minOf(maximum.toLong(), quotaAvailable?.coerceAtLeast(0L) ?: Long.MAX_VALUE).toInt()
            return if (capacity == 0) null else this@withQuota.reserve(capacity)
        }
    }

internal fun serialArithmeticSource(
    parent: ExecutionControl,
    remaining: () -> Long,
    record: (Int) -> Unit,
): ArithmeticWorkSource {
    val containing = parent.admission?.takeIf { it.metered }
    val accounting = parent.accounting
    return object : ArithmeticWorkSource {
        override val quotaOwner get() = containing?.quotaOwner
        override val quotaRemaining get() = containing?.quotaRemaining
        override val quotaAvailable get() = containing?.quotaAvailable
        override val exhausted get() = remaining() <= 0L || containing?.exhausted == true
        override fun reserve(maximum: Int): WorkReservation? {
            val capacity = minOf(maximum.toLong(), remaining().coerceAtLeast(0L)).toInt()
            if (capacity == 0) return null
            return reserveWork(capacity, emptyList(), containing) { used ->
                record(used)
                if (containing == null) accounting?.charge(used.toLong())
            }
        }
    }
}

// A cursor belongs to one arithmetic owner. Scope exit settles every admitted debit, including exceptional exits.
@Suppress("TooGenericExceptionCaught") // settlement must preserve arbitrary primary and cleanup failures
internal class ArithmeticExecution(
    @JvmField val cancellation: ExecutionControl,
    private val interrupted: () -> Nothing,
) {
    @JvmField var source: ArithmeticWorkSource? = null
    @JvmField var accounting: WorkMeter? = null
    @JvmField var metered = false
    @JvmField val unchecked: Boolean = cancellation === ExecutionControl.Never
    @JvmField var depth = 0
    @JvmField var quantum = 8192
    @JvmField var untilPoll = quantum
    @JvmField var admittedUntil = 0
    @JvmField var reservation: WorkReservation? = null
    @JvmField var used = 0
    @JvmField var stopped = false

    val batching: Boolean get() = depth != 0
    val admittedWork: Long get() = if (reservation == null) 0L else used.toLong()

    inline fun <T> operation(quantum: Int = 8192, body: () -> T): T {
        if (unchecked) {
            require(quantum > 0)
            return body()
        }
        val previousQuantum = this.quantum
        val entered = begin(quantum)
        var failure: Throwable? = null
        try {
            return body()
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            if (entered) {
                end(failure)
            } else {
                this.quantum = previousQuantum
                untilPoll = minOf(untilPoll, previousQuantum)
                reservation?.let { admittedUntil = minOf(admittedUntil, used + minOf(previousQuantum, it.capacity - used)) }
            }
        }
    }

    inline fun <T> handoff(body: () -> T): T {
        if (unchecked) return body()
        checkpoint()
        var failure: Throwable? = null
        try {
            return body()
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            finishHandoff(failure)
        }
    }

    fun finishHandoff(failure: Throwable?) {
        if (unchecked) return
        try {
            settle()
            bind()
            poll()
        } catch (cleanup: Throwable) {
            if (failure == null) throw cleanup
            if (cleanup !== failure) failure.addSuppressed(cleanup)
        }
    }

    fun begin(quantum: Int): Boolean {
        require(quantum > 0)
        if (depth != 0) {
            if (quantum < this.quantum) {
                checkpoint()
                this.quantum = quantum
                untilPoll = quantum
            }
            return false
        }
        if (cancellation === ExecutionControl.Never) return false
        this.quantum = quantum
        untilPoll = quantum
        stopped = false
        checkpoint()
        depth = 1
        return true
    }

    fun end(failure: Throwable?) {
        check(depth == 1)
        try {
            checkpoint()
        } catch (cleanup: Throwable) {
            if (failure == null) throw cleanup
            if (cleanup !== failure) failure.addSuppressed(cleanup)
        } finally {
            depth = 0
        }
    }

    internal inline fun step(crossinline beforeDebit: () -> Unit = {}) {
        if (cancellation === ExecutionControl.Never) {
            beforeDebit()
            return
        }
        if (depth == 0) {
            stepOutsideOperation { beforeDebit() }
            return
        }
        if (stopped) interrupted()
        if (!metered) {
            beforeDebit()
            accounting?.charge(1L)
            if (--untilPoll == 0) poll()
            return
        }
        if (reservation == null) reserve()
        beforeDebit()
        used++
        if (used == admittedUntil) admittedBoundary()
    }

    private fun reserve() {
        while (reservation == null) {
            // A sibling can temporarily hold the remaining credit without having spent it.
            reservation = source!!.reserve(32768)
            if (reservation == null) checkpoint()
        }
        admittedUntil = minOf(checkNotNull(reservation).capacity, untilPoll)
    }

    private fun admittedBoundary() {
        if (used == checkNotNull(reservation).capacity) checkpoint() else poll()
    }

    private fun stepOutsideOperation(beforeDebit: () -> Unit) {
        stopped = false
        checkpoint()
        if (!metered) {
            beforeDebit()
            accounting?.charge(1L)
            checkpoint()
            return
        }
        val owner = checkNotNull(source)
        var grant = owner.reserve(1)
        while (grant == null) {
            checkpoint()
            grant = owner.reserve(1)
        }
        try {
            beforeDebit()
        } catch (failure: Throwable) {
            grant.settle(0)
            throw failure
        }
        grant.settle(1)
        checkpoint()
    }

    internal inline fun advance(units: Int, beforeDebit: (Int) -> Unit) {
        require(units >= 0)
        if (unchecked) {
            beforeDebit(units)
            return
        }
        check(depth != 0)
        var remaining = units
        while (remaining > 0) {
            if (stopped) interrupted()
            if (metered && reservation == null) reserve()
            val count = minOf(remaining, if (metered) admittedUntil - used else untilPoll)
            beforeDebit(count)
            remaining -= count
            if (metered) {
                used += count
                if (used == admittedUntil) admittedBoundary()
            } else {
                accounting?.charge(count.toLong())
                untilPoll -= count
                if (untilPoll == 0) poll()
            }
        }
    }

    fun pollingCost(units: Int) {
        if (unchecked || depth == 0 || units == 0) return
        if (metered && reservation != null) {
            if (units >= admittedUntil - used) poll() else admittedUntil -= units
        } else {
            if (units >= untilPoll) poll() else untilPoll -= units
        }
    }

    fun checkpoint() {
        if (unchecked) return
        settle()
        if (depth == 0) bind()
        poll()
    }

    private fun bind() {
        source = cancellation.admission
        metered = source?.metered == true
        accounting = cancellation.accounting
    }

    private fun poll() {
        untilPoll = quantum
        admittedUntil = reservation?.let { used + minOf(quantum, it.capacity - used) } ?: 0
        val cancelled = cancellation() || source?.exhausted == true
        if (depth != 0) stopped = stopped || cancelled
        if (cancelled || (depth != 0 && stopped)) interrupted()
    }

    fun boundary() {
        if (!unchecked) checkpoint()
    }

    fun flush() = settle()

    fun resume() {
        stopped = false
        checkpoint()
    }

    @Suppress("IteratorNotThrowingNoSuchElementException") // next delegates the wrapped iterator's exhaustion contract
    fun <T> iterator(values: Iterator<T>): Iterator<T> = object : Iterator<T> {
        override fun hasNext(): Boolean = operation { values.hasNext() }
        override fun next(): T = operation { values.next() }
    }

    private fun settle() {
        val held = reservation
        val consumed = used
        if (held != null) untilPoll = minOf(untilPoll, (admittedUntil - used).coerceAtLeast(1))
        reservation = null
        used = 0
        held?.settle(consumed)
    }
}
