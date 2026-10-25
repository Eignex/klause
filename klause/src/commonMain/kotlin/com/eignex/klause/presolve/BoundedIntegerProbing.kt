package com.eignex.klause.presolve

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.RootWorkAccounting
import com.eignex.klause.util.Cancellation
import kotlin.random.Random

private const val INITIAL_PROBES = 128
private const val PROBES_PER_REFUTATION = 32
private const val MAX_PROBE_WORK = 50_000_000L

internal object BoundedIntegerProbing {
    fun bake(
        problem: BakedProblem,
        base: PropagationResult.Implied,
        config: BakeConfig,
        cancellation: Cancellation,
    ): PropagationResult {
        if (problem.numIntVars == 0 || config.probeTotalBudget == 0 || config.probeBudgetPerVar == 0) return base
        val budget = cancellation.presolveBudget()
        val token = budget?.let { it.slice(minOf(MAX_PROBE_WORK, it.remaining() / 8)) or cancellation } ?: cancellation
        if (token()) return base
        val session = PropagationSession(problem, token, propagationCancelFloor = 0, nativeSat = true)
        val accounting = RootWorkAccounting(token, session.explanationState)
        return try {
            session.withCancellation(accounting) {
                if (session.isUnsatAtRoot) return@withCancellation PropagationResult.Unsat()
                if (session.fixpointCancelled || accounting()) return@withCancellation base
                ProbeRun(problem, base, config, session, accounting).run()
            }
        } finally {
            accounting.flush()
        }
    }

    private class ProbeRun(
        private val problem: BakedProblem,
        private var acc: PropagationResult.Implied,
        private val config: BakeConfig,
        private val session: PropagationSession,
        private val cancellation: Cancellation,
    ) {
        private val perVar = IntArray(problem.numIntVars)
        private var calls = 0
        private var credit = INITIAL_PROBES
        private val weights = DoubleArray(problem.numFactors) { 1.0 }
        private val rng = Random(config.probeSeed)
        private var stopped = false

        fun run(): PropagationResult {
            var changed: Boolean
            do {
                val before = acc
                val order = RootBaker.sacProbeOrder(problem, acc, weights, rng)
                for (interior in listOf(false, true)) {
                    for (v in order) {
                        if (cancellation()) return acc
                        if (perVar[v] >= config.probeBudgetPerVar) {
                            cancellation.interruptPresolveInvocation()
                            continue
                        }
                        val domain = session.intDomain(v)
                        if (domain.isFixed) continue
                        if (!interior) {
                            probe(v, domain.min)?.let { return it }
                            if (stopped) return acc
                            if (!session.intDomain(v).isFixed) {
                                probe(v, session.intDomain(v).max)?.let { return it }
                            }
                        } else {
                            var candidate = domain.higher(domain.min)
                            while (candidate < session.intDomain(v).max) {
                                val value = candidate
                                candidate = session.intDomain(v).higher(value)
                                if (value !in session.intDomain(v)) continue
                                probe(v, value)?.let { return it }
                                if (stopped) return acc
                                if (perVar[v] >= config.probeBudgetPerVar) break
                            }
                        }
                        if (stopped) return acc
                    }
                }
                changed = acc !== before
            } while (changed)
            return acc
        }

        private fun probe(variable: Int, value: Long): PropagationResult.Unsat? {
            if (perVar[variable] >= config.probeBudgetPerVar) {
                cancellation.interruptPresolveInvocation()
                return null
            }
            if (calls >= config.probeTotalBudget || credit == 0 || cancellation()) {
                cancellation.interruptPresolveInvocation()
                stopped = true
                return null
            }
            perVar[variable]++
            calls++
            credit--
            cancellation.recordPresolveProbe()
            val result = session.probeInt(variable, value)
            if (result == null) {
                cancellation.interruptPresolveInvocation()
                stopped = true
            } else if (result is PropagationResult.Unsat) {
                for (factor in result.conflictFactors) if (factor in weights.indices) weights[factor] += 1.0
                credit = minOf(config.probeTotalBudget - calls, credit + PROBES_PER_REFUTATION)
                acc = acc.withHole(variable, value)
                if (cancellation()) {
                    stopped = true
                    return null
                }
                val exclusion = session.equalityLit(variable, value, positive = false)
                val repair = session.addLearnedClause(Clause(intArrayOf(exclusion)), lbd = 1, permanent = true)
                if (repair is PropagationResult.Unsat) return repair
                acc = acc.merge(repair as PropagationResult.Implied)
                if (session.fixpointCancelled || cancellation()) stopped = true
            } else {
                session.popLast()
            }
            return null
        }
    }
}
