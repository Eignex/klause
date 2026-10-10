package com.eignex.klause.localsearch

import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.Problem
import com.eignex.klause.util.Cancellation
import com.eignex.kumulant.core.Concurrency
import com.eignex.kumulant.stream.Mutex
import com.eignex.kumulant.stream.lock

internal class LocalSearchPreparation(
    problem: Problem,
    domains: Array<IntDomain>?,
    private val lock: Mutex = Concurrency.Strict.lock(),
) {
    private val steps = LocalSearchProblem.preparation(problem, domains)
    private var completed: LocalSearchProblem? = null

    fun get(cancellation: Cancellation): LocalSearchProblem? {
        while (true) {
            // A bounded batch holds the lock; no suspended arm owns it or exposes unfinished indexes.
            val result = lock.withLock {
                completed ?: if (cancellation()) null else steps.next().also { completed = it }
            }
            if (result != null || cancellation()) return result
        }
    }
}
