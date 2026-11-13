package com.eignex.klause.util

internal interface ArithmeticCancellation : Cancellation {
    fun arithmeticSource(): ArithmeticWorkSource?
}

internal interface ArithmeticWorkSource {
    val metered: Boolean get() = true
    fun reserve(maximum: Int): WorkReservation?
    fun isCancelled(): Boolean
}

internal class WorkReservation(val capacity: Int, private val settlement: (Int) -> Unit) {
    private var settled = false

    init { require(capacity > 0) }

    fun settle(used: Int) {
        require(used in 0..capacity)
        check(!settled)
        settled = true
        settlement(used)
    }
}

internal fun Cancellation.arithmeticSource(): ArithmeticWorkSource? =
    if (this === Cancellation.Never) unmeteredArithmetic { false }
    else (this as? ArithmeticCancellation)?.arithmeticSource()

internal fun ArithmeticWorkSource.withCheckpoint(stopped: () -> Boolean): ArithmeticWorkSource =
    object : ArithmeticWorkSource {
        override val metered: Boolean get() = this@withCheckpoint.metered
        override fun reserve(maximum: Int): WorkReservation? = this@withCheckpoint.reserve(maximum)
        override fun isCancelled(): Boolean = stopped()
    }

internal fun unmeteredArithmetic(stopped: () -> Boolean): ArithmeticWorkSource = object : ArithmeticWorkSource {
    override val metered: Boolean = false
    override fun reserve(maximum: Int): WorkReservation = WorkReservation(maximum) {}
    override fun isCancelled(): Boolean = stopped()
}

// A cursor belongs to one arithmetic owner. Scope exit settles every admitted debit, including exceptional exits.
@Suppress("TooGenericExceptionCaught") // settlement must preserve arbitrary primary and cleanup failures
internal class ArithmeticExecution(
    private val cancellation: Cancellation,
    private val interrupted: () -> Nothing,
) {
    private val source = cancellation.arithmeticSource()
    private var depth = 0
    private var quantum = 32
    private var reservation: WorkReservation? = null
    private var used = 0
    private var stopped = false

    val batching: Boolean get() = depth != 0

    inline fun <T> operation(quantum: Int = 32, body: () -> T): T {
        if (!begin(quantum)) return body()
        var failure: Throwable? = null
        try {
            return body()
        } catch (caught: Throwable) {
            failure = caught
            throw caught
        } finally {
            end(failure)
        }
    }

    fun begin(quantum: Int): Boolean {
        require(quantum > 0)
        if (depth != 0) {
            if (quantum < this.quantum) {
                checkpoint()
                this.quantum = quantum
            }
            return false
        }
        if (source == null || cancellation === Cancellation.Never) return false
        this.quantum = quantum
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

    internal inline fun step(beforeDebit: () -> Unit = {}) {
        if (cancellation === Cancellation.Never) {
            beforeDebit()
            return
        }
        val owner = source
        if (depth == 0 || owner == null) {
            if (cancellation()) interrupted()
            beforeDebit()
            cancellation.charge(1L)
            if (cancellation()) interrupted()
            return
        }
        if (stopped) interrupted()
        if (!owner.metered) {
            beforeDebit()
            if (++used == quantum) checkpoint()
            return
        }
        if (reservation == null) {
            while (reservation == null) {
                if (owner.isCancelled()) {
                    stopped = true
                    interrupted()
                }
                // A sibling can temporarily hold the remaining credit without having spent it.
                reservation = owner.reserve(quantum)
            }
        }
        beforeDebit()
        used++
        if (used == reservation!!.capacity) checkpoint()
    }

    fun checkpoint() {
        settle()
        if (cancellation === Cancellation.Never) return
        val cancelled = source?.isCancelled() ?: cancellation()
        if (depth != 0) stopped = stopped || cancelled
        if (cancelled || (depth != 0 && stopped)) interrupted()
    }

    fun boundary() {
        if (source != null) checkpoint()
    }

    @Suppress("IteratorNotThrowingNoSuchElementException") // next delegates the wrapped iterator's exhaustion contract
    fun <T> iterator(values: Iterator<T>): Iterator<T> = object : Iterator<T> {
        override fun hasNext(): Boolean = operation { values.hasNext() }
        override fun next(): T = operation { values.next() }
    }

    private fun settle() {
        val held = reservation
        val consumed = used
        reservation = null
        used = 0
        held?.settle(consumed)
    }
}
