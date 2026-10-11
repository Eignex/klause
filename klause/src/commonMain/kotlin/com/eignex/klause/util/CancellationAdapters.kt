package com.eignex.klause.util

import kotlin.time.ComparableTimeMark

/**
 * A token that fires when [cancelled] does and states [deadlineOf] as its deadline, read each time it is asked so a
 * deadline re-armed later stays current. It carries no work meter. An adapter that wraps a token in a predicate builds
 * it from this rather than a bare lambda, which [Cancellation.shorten] would read as having no time limit at all.
 */
internal fun cancelledWhen(deadlineOf: () -> ComparableTimeMark?, cancelled: () -> Boolean): Cancellation =
    object : Cancellation {
        override fun isCancelled(): Boolean = cancelled()

        override fun deadline(): ComparableTimeMark? = deadlineOf()
    }
