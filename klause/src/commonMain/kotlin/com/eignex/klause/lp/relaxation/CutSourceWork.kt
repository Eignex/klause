package com.eignex.klause.lp.relaxation

internal class CutSourceWork(val structural: Long, val exact: Long)

internal class CutSourceWorkMeter {
    private var structural = 0L
    private var exact = 0L

    fun structural(count: Long = 1L) {
        structural += count
    }

    fun exact(count: Long = 1L) {
        exact += count
    }

    fun add(work: CutSourceWork) {
        structural(work.structural)
        exact(work.exact)
    }

    fun report(onWork: ((CutSourceWork) -> Unit)?) {
        if (structural != 0L || exact != 0L) onWork?.invoke(CutSourceWork(structural, exact))
    }
}

@Suppress("TooGenericExceptionCaught") // Reporting cannot replace a source-binding failure.
internal inline fun <T> measureCutSourceWork(
    noinline onWork: ((CutSourceWork) -> Unit)?,
    action: (CutSourceWorkMeter) -> T,
): T {
    val work = CutSourceWorkMeter()
    var failure: Throwable? = null
    return try {
        action(work)
    } catch (primary: Throwable) {
        failure = primary
        throw primary
    } finally {
        if (failure == null) {
            work.report(onWork)
        } else {
            try {
                work.report(onWork)
            } catch (reporting: Throwable) {
                if (reporting !== failure) failure.addSuppressed(reporting)
            }
        }
    }
}
