package com.eignex.klause.presolve

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.propagation.PropagationProblem
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.propagation.PropagationState
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
        if (problem.numIntVars == 0) return base
        if (config.probeTotalBudget == 0 || config.probeBudgetPerVar == 0) {
            cancellation.interruptPresolveInvocation()
            return base
        }
        val budget = cancellation.presolveBudget()
        val token = budget?.let { it.slice(minOf(MAX_PROBE_WORK, it.remaining() / 8)) or cancellation } ?: cancellation
        if (token()) return base
        val state = PropagationState(PropagationProblem(problem), base.toAssumptions(), nativeSat = true)
        state.cancelFloor = 0
        val accounting = RootWorkAccounting(token, state)
        return try {
            if (!state.seeded) return PropagationResult.Unsat()
            val conflict = state.runToFixpoint(allFactors = true, cancellation = accounting)
            if (conflict != null) return PropagationResult.Unsat()
            if (state.runCancelled || accounting()) return base
            val session = PropagationSession.prepared(state, accounting, null)
            ProbeRun(problem, base, config, session, accounting).run()
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
                val order = RootBaker.sacProbeOrder(problem, acc, weights, rng, cancellation)
                for (interior in listOf(false, true)) {
                    for (v in order) {
                        cancellation.charge(1)
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

        private fun takeCall(variable: Int): Boolean {
            if (perVar[variable] >= config.probeBudgetPerVar) {
                cancellation.interruptPresolveInvocation()
                return false
            }
            if (calls >= config.probeTotalBudget || credit == 0 || cancellation()) {
                cancellation.interruptPresolveInvocation()
                stopped = true
                return false
            }
            perVar[variable]++
            calls++
            credit--
            cancellation.recordPresolveProbe()
            return true
        }

        private fun probe(variable: Int, value: Long): PropagationResult.Unsat? {
            if (!takeCall(variable)) return null
            val result = session.probeInt(variable, value)
            if (result == null) {
                cancellation.interruptPresolveInvocation()
                stopped = true
            } else if (result is PropagationResult.Unsat) {
                for (factor in result.conflictFactors) if (factor in weights.indices) weights[factor] += 1.0
                credit = minOf(config.probeTotalBudget.toLong() - calls, credit.toLong() + PROBES_PER_REFUTATION).toInt()
                acc = acc.withHole(variable, value)
                if (!takeCall(variable)) {
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
