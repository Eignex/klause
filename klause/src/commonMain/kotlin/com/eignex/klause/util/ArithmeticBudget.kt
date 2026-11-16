package com.eignex.klause.util

internal open class ArithmeticBudget(
    private val parent: ExecutionControl,
    private val limit: Long?,
) : ExecutionControl() {
    var execution: ArithmeticExecution? = null
    private val pool = WorkCreditPool(limit)
    private val pools = listOf(pool)
    val spent: Long get() = pool.spent.let { it + minOf(execution?.admittedWork ?: 0L, Long.MAX_VALUE - it) }
    val limitReached: Boolean get() = limit?.let { spent >= it } == true
    protected open val reservationLimit: Long get() = Long.MAX_VALUE
    protected open val exhausted: Boolean get() = limitReached

    override fun isCancelled(): Boolean = limitReached || parent()
    override fun deadline() = parent.deadline()
    override val accounting: WorkMeter = WorkMeter { units ->
        pool.debit(units)
        parent.charge(units)
    }
    override val admission: ArithmeticWorkSource get() {
        val containing = parent.admission
        val meter = parent.accounting
        return object : ArithmeticWorkSource {
            override val quotaOwner get() = containing?.quotaOwner
            override val quotaRemaining get() = containing?.quotaRemaining
            override val quotaAvailable get() = containing?.quotaAvailable
            override val exhausted get() = this@ArithmeticBudget.exhausted || containing?.exhausted == true
            override fun reserve(maximum: Int): WorkReservation? {
                execution?.flush()
                val capacity = minOf(maximum.toLong(), reservationLimit).toInt()
                if (capacity == 0) return null
                return reserveWork(capacity, pools, containing) {
                    if (containing?.metered != true) meter?.charge(it.toLong())
                }
            }
        }
    }
}
