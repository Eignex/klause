package com.eignex.klause.backtrack

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.ir.Lit
import com.eignex.klause.propagation.OWN_ORIGIN
import com.eignex.klause.propagation.PropagationResult
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.solver.result.SearchStatsSink
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.SharingMemoryObservation

internal class RootSimplificationPass : InprocessingPass {
    override val preservesVariables: Boolean get() = true
    private var cursor = 0
    private var scanEnd = 0
    private var scanRoot = -1
    override val spending = InprocessingSpending(InprocessingKind.Root)
    private var dropped = IntHashSet()
    private var revision = -1L
    private var pending: RootCandidate? = null
    private var deferredFactor = -1
    private var pinCursor = 0
    private var occurrenceCursor = 0
    private var preferNeighborhood = true
    private var neighborhoodLeft = 0

    override fun retainedMemory(sample: SharingMemoryObservation, protected: Boolean) {
        sample.owned(dropped, dropped.allocatedBytes, protected)
        pending?.let { candidate ->
            sample.step()
            sample.ownedReferences(candidate, 2L, protected)
            sample.owned(candidate.literals, candidate.literals.size.toLong() * Int.SIZE_BYTES, protected)
            val residual = candidate.residual.backingData
            sample.owned(residual, residual.size.toLong() * Int.SIZE_BYTES, protected)
        }
    }

    override fun reset() {
        cursor = 0
        scanEnd = 0
        scanRoot = -1
        dropped = IntHashSet()
        revision = -1L
        pending = null
        deferredFactor = -1
        pinCursor = 0
        occurrenceCursor = 0
        preferNeighborhood = true
    }

    override fun run(session: PropagationSession, params: BacktrackParams, stats: SearchStatsSink?) {
        if (!session.canSimplifyRoot) return
        spending.run(session, params, stats) { budget ->
            simplify(session, params, budget)
            PrivateRebuildCapture.observe(session, budget, params.rootRebuildCaptureVisits)
        }
    }

    private fun simplify(session: PropagationSession, params: BacktrackParams, budget: InprocessingBudget) {
        if (session.problem.numBoolVars == 0 || session.explanationState.boolPinOrder.isEmpty()) {
            budget.note("NoBooleanFacts")
            return
        }
        val count = session.rootClauseCount
        if (revision != session.learnedClauseRevision) {
            dropped = IntHashSet()
            pending = null
            deferredFactor = -1
            cursor = 0
            scanEnd = count
            scanRoot = session.explanationState.undoTop
        }
        revision = session.learnedClauseRevision
        if (count == 0) return
        session.pendingRootSourceRetirement?.let { (factor, literals) ->
            if (!budget.visit(literals.size + 1)) return
            session.retireRootSource(factor, literals)
            budget.note("RetirementWork", literals.size + 1L)
        }
        if (!dropped.isEmpty()) {
            if (budget.stopped()) return
            val before = session.work
            val beforeCount = session.learnedClauseCount
            session.forgetLearnedClauses { index, _ -> index !in dropped }
            budget.note("RetirementWork", session.work - before)
            dropped = IntHashSet()
            revision = session.learnedClauseRevision
            if (session.learnedClauseCount < beforeCount) {
                cursor = 0
                scanEnd = session.rootClauseCount
            }
            return
        }
        if (cursor >= scanEnd && pending == null && deferredFactor < 0) {
            if (scanRoot != session.explanationState.undoTop) {
                cursor = 0
                scanRoot = session.explanationState.undoTop
            }
            scanEnd = count
        }
        var examined = 0
        var literalsVisited = 0
        neighborhoodLeft = ROOT_LITERAL_LIMIT
        while (examined++ < params.rootSimplifyBatch) {
            if (!session.canSimplifyRoot || literalsVisited >= ROOT_LITERAL_LIMIT || budget.stopped()) break
            var candidate = pending
            if (candidate == null) {
                val factor = nextFactor(session, budget) ?: break
                budget.note("Candidates")
                val literals = session.rootClauseLiterals(factor, ROOT_LITERAL_LIMIT - literalsVisited)
                if (literals == null) {
                    if (literalsVisited > 0) {
                        deferredFactor = factor
                        break
                    }
                    continue
                }
                budget.charge(literals.size)
                literalsVisited += literals.size
                if (literals.size < 2) continue
                budget.note("Eligible")
                candidate = RootCandidate(factor, literals)
                pending = candidate
            }
            val factor = candidate.factor
            val literals = candidate.literals
            val residual = candidate.residual
            while (candidate.next < literals.size && !candidate.satisfied) {
                if (budget.stopped()) return
                when (budget.truth(literals[candidate.next++])) {
                    true -> candidate.satisfied = true
                    false -> Unit
                    null -> residual.add(literals[candidate.next - 1])
                }
            }
            val satisfied = candidate.satisfied
            if (!satisfied && (residual.isEmpty() || residual.size == literals.size)) {
                pending = null
                continue
            }
            val source = factor < session.problem.numFactors
            if (!satisfied) {
                val index = factor - session.problem.numFactors
                val origin = if (source) OWN_ORIGIN else session.learnedClauseOrigin(index)
                val lbd = if (source) residual.size else minOf(session.learnedClauseLbd(index), residual.size)
                if (!budget.visit(residual.size * 2 + 1)) break
                val before = session.work
                session.addRootDerivedClause(Clause(residual.toIntArray()), lbd, origin, source)
                pending = null
                if (source) session.retainRootSourceRetirement(factor, literals)
                else dropped.add(index)
                budget.note("AttachmentWork", session.work - before + residual.size * 2 + 1L)
                budget.note("Shortened")
                if (!session.canSimplifyRoot) break
            }
            if (budget.stopped()) break
            if (source) {
                if (!budget.visit(literals.size + 1)) break
                session.retireRootSource(factor, literals)
                budget.note("RetirementWork", literals.size + 1L)
            } else dropped.add(factor - session.problem.numFactors)
            pending = null
        }
        val beforeCompaction = session.learnedClauseCount
        if (!dropped.isEmpty() && !budget.stopped()) {
            val before = session.work
            session.forgetLearnedClauses { index, _ -> index !in dropped }
            budget.note("RetirementWork", session.work - before)
            dropped = IntHashSet()
            revision = session.learnedClauseRevision
        }
        if (session.learnedClauseCount < beforeCompaction) {
            cursor = 0
            scanEnd = session.rootClauseCount
        }
    }

    private fun nextFactor(session: PropagationSession, budget: InprocessingBudget): Int? {
        if (deferredFactor >= 0) {
            if (!budget.visit()) return null
            val factor = deferredFactor
            deferredFactor = -1
            return factor
        }
        val nearbyFirst = preferNeighborhood
        preferNeighborhood = !preferNeighborhood
        if (nearbyFirst) neighborhoodFactor(session, budget)?.let { return it }
        if (budget.stopped()) return null
        if (cursor < scanEnd) {
            if (!budget.visit()) return null
            return cursor++
        }
        return if (nearbyFirst) null else neighborhoodFactor(session, budget)
    }

    private fun neighborhoodFactor(session: PropagationSession, budget: InprocessingBudget): Int? {
        val pins = session.explanationState.boolPinOrder
        while (pinCursor < pins.size && neighborhoodLeft > 0) {
            val variable = pins[pinCursor]
            val occurrences = if (variable < session.problem.numBoolVars) {
                session.projection.preparedBoolOccurrences(variable)
            } else null
            if (variable < session.problem.numBoolVars && occurrences == null) {
                pinCursor = pins.size
                occurrenceCursor = 0
                budget.note("NeighborhoodUnavailable")
                return null
            }
            if (!budget.visit()) return null
            neighborhoodLeft--
            if (occurrences == null || occurrenceCursor >= occurrences.size) {
                pinCursor++
                occurrenceCursor = 0
                continue
            }
            budget.note("NeighborhoodCandidates")
            return occurrences[occurrenceCursor++]
        }
        return null
    }
}

private class RootCandidate(val factor: Int, val literals: IntArray) {
    val residual = IntArrayList(literals.size)
    var next = 0
    var satisfied = false
}

internal class RootProbingPass : InprocessingPass {
    override val preservesVariables: Boolean get() = true
    private var literalCursor = 0L
    private var endpointCursor = 0L
    private var preferEndpoint = false
    private var learnedCursor = 0
    private var revision = -1L
    private var neighborhood: IntArray? = null
    private var neighborhoodPosition = 0
    private var preferNeighborhood = true
    private var preparationLeft = 0
    override val spending = InprocessingSpending(InprocessingKind.Probe)

    override fun reset() {
        literalCursor = 0L
        endpointCursor = 0L
        preferEndpoint = false
        learnedCursor = 0
        revision = -1L
        neighborhood = null
        neighborhoodPosition = 0
        preferNeighborhood = true
    }

    override fun retainedMemory(sample: SharingMemoryObservation, protected: Boolean) {
        neighborhood?.let {
            sample.ownedReferences(this, 1L, protected)
            sample.owned(it, it.size.toLong() * Int.SIZE_BYTES, protected)
        }
    }

    private fun neighborhoodLiteral(session: PropagationSession, budget: InprocessingBudget): Int? {
        if (revision != session.learnedClauseRevision) {
            learnedCursor = 0
            neighborhood = null
            revision = session.learnedClauseRevision
        }
        while (!budget.stopped()) {
            val row = neighborhood
            if (row != null && neighborhoodPosition < row.size) return Lit.negate(row[neighborhoodPosition])
            neighborhood = null
            if (learnedCursor >= session.learnedClauseCount || preparationLeft <= 0 || !budget.visit()) return null
            preparationLeft--
            val index = learnedCursor++
            budget.note("NeighborhoodCandidates")
            if (session.learnedClauseLiteralCount(index) !in 2..PROBE_NEIGHBORHOOD_LITERALS) continue
            val literals = session.rootClauseLiterals(session.problem.numFactors + index,
                PROBE_NEIGHBORHOOD_LITERALS, includePermanent = true, includeAtoms = true) ?: continue
            if (literals.size == 2 && literals[0] == Lit.negate(literals[1])) continue
            if (literals.any { budget.truth(it) == true }) continue
            neighborhood = literals
            neighborhoodPosition = 0
            budget.note("NeighborhoodEligible")
        }
        return null
    }

    override fun run(session: PropagationSession, params: BacktrackParams, stats: SearchStatsSink?) {
        if (!session.canSimplifyRoot || params.rootProbeWork <= 0L) return
        spending.run(session, params, stats) { budget -> probe(session, params, budget) }
    }

    private fun probe(session: PropagationSession, params: BacktrackParams, budget: InprocessingBudget) {
        val variables = session.rootProbeVariableCount
        val literalCandidates = 2L * variables
        val endpointCandidates = 2L * session.problem.numIntVars
        val candidates = literalCandidates + endpointCandidates
        if (candidates == 0L) return
        preparationLeft = PROBE_NEIGHBORHOOD_CLAUSES
        var attempted = 0
        var examined = 0
        var literalsExamined = 0L
        var endpointsExamined = 0L
        val priorCancelled = session.probeCancelled
        while (attempted < params.rootProbeBatch && examined < minOf(candidates, ROOT_LITERAL_LIMIT.toLong())) {
            if (!session.canSimplifyRoot || !budget.visit()) break
            val nearby = if (preferNeighborhood) neighborhoodLiteral(session, budget) else null
            if (budget.stopped()) break
            val endpoint = nearby == null && endpointsExamined < endpointCandidates &&
                (preferEndpoint || literalsExamined >= literalCandidates)
            val candidate = when {
                nearby != null -> {
                    neighborhoodPosition++
                    nearby.toLong()
                }
                endpoint -> {
                    val next = endpointCursor % endpointCandidates
                    endpointCursor = (next + 1L) % endpointCandidates
                    endpointsExamined++
                    preferEndpoint = false
                    next
                }
                else -> {
                    val next = literalCursor % literalCandidates
                    literalCursor = (next + 1L) % literalCandidates
                    literalsExamined++
                    preferEndpoint = true
                    next
                }
            }
            preferNeighborhood = !preferNeighborhood
            val literal = if (endpoint) 0 else candidate.toInt()
            val variable = if (endpoint) (candidate / 2L).toInt() else Lit.variable(literal)
            val atMost = candidate % 2L == 0L
            examined++
            budget.note("Candidates")
            val bound = if (endpoint) {
                budget.note("EndpointCandidates")
                if (!budget.visit(2)) break
                val domain = session.intDomain(variable)
                if (domain.min == domain.max) continue
                budget.note("EndpointEligible")
                if (atMost) domain.min else domain.max
            } else {
                if (variable < session.problem.numBoolVars) {
                    if (session.boolValue(variable) != null) continue
                } else if (!session.canProbeRootAtom(variable) || budget.truth(literal) != null) continue
                0L
            }
            attempted++
            budget.note("Eligible")
            budget.probe()
            val result = probe(session, params.rootProbeWork) {
                if (endpoint) session.probeRootBound(variable, bound, atMost) else session.probeRootLiteral(literal)
            }
            when (result) {
                null -> {
                    budget.note("Incomplete")
                    if (!params.cancellation()) session.probeCancelled = priorCancelled
                }
                is PropagationResult.Unsat -> {
                    budget.note("Refuted")
                    if (!budget.visit(3)) break
                    val before = session.work
                    val unit = complement(session, literal, if (endpoint) variable else -1, bound, atMost)
                    session.addRootDerivedClause(Clause(intArrayOf(unit)), 1, OWN_ORIGIN, false)
                    budget.note("AttachmentWork", session.work - before + 3L)
                    budget.note("Units")
                    if (endpoint) budget.note("EndpointUnits")
                    if (nearby != null) neighborhood = null
                }
                else -> budget.note("Consistent")
            }
        }
    }

    private fun complement(
        session: PropagationSession,
        literal: Int,
        integer: Int,
        bound: Long,
        atMost: Boolean,
    ): Int {
        if (integer < 0) return Lit.negate(literal)
        val atomSpace = session.rootProbeVariableCount
        val unit = if (atMost) session.boundLeLit(integer, bound, false)
            else session.boundGeLit(integer, bound, false)
        if (session.rootProbeVariableCount > atomSpace) {
            // Conservatively charge parallel registry buffers and threshold-index growth.
            val atoms = session.rootProbeVariableCount - session.problem.numBoolVars
            session.chargeInprocessingWork(16L * atoms)
        }
        return unit
    }

    private inline fun probe(
        session: PropagationSession,
        limit: Long,
        crossinline action: () -> PropagationResult?,
    ): PropagationResult? {
        val start = session.work
        val token = Cancellation { session.work - start >= limit }
        val retracting = session.retractingListener
        // Speculative retractions must not overwrite phases saved by ordinary search.
        session.retractingListener = null
        return try {
            session.withCancellation(token) {
                action()
            }.also {
                if (it != null && it !is PropagationResult.Unsat) session.popLast()
            }
        } finally {
            session.retractingListener = retracting
        }
    }
}

private const val PROBE_NEIGHBORHOOD_CLAUSES = 16
private const val PROBE_NEIGHBORHOOD_LITERALS = 8

internal const val ROOT_LITERAL_LIMIT = 8192
