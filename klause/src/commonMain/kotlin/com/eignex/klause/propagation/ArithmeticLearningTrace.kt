package com.eignex.klause.propagation

import com.eignex.klause.ir.Lit

internal class ArithmeticLearningTrace(
    private val maxEvents: Int = 128,
    private val maxWords: Int = 8192,
) {
    class Event(val kind: String, val data: LongArray)

    val events = ArrayList<Event>()
    var retainBoundaries = false
    val attached: Boolean get() = accepting || retainBoundaries || auditLinearBounds
    val boundaries = ArrayList<Event>(64)
    var discardedBoundaries = 0L
        private set
    var observedCutoffBits: Long? = null
        private set
    var firstFiniteRead: Event? = null
        private set
    var auditLinearBounds = false
    var linearAuditChecks = 0
        private set
    var linearAuditVisits = 0L
        private set
    var linearAuditDeclines = 0L
        private set
    var linearAuditLimitReached = false
        private set
    var firstLinearDisagreement: Event? = null
        private set

    var truncated = false
        private set
    var truncationCause: String? = null
        private set
    var words = 0
        private set
    var omittedPayloads = 0
        private set
    var captureFirstInteger = false
    var focusedIntegerId: Int? = null
        private set
    var discardedWindows = 0L
        private set
    var discardedEvents = 0L
        private set
    var discardedWords = 0L
        private set
    var omittedObservations = 0L
        private set
    private var pendingPick = false
    private val storedIds = mutableMapOf<Int, Int>()
    internal val reclamationEntries: Int get() = storedIds.size
    private var nextId = 0
    val accepting: Boolean get() = !truncated

    init {
        require(maxEvents in 1..1024 && maxWords in 1..65536)
    }

    fun boundary(kind: String, vararg data: Long) {
        if (!retainBoundaries) return
        require(data.size <= 16)
        if (boundaries.size == 64) {
            boundaries.removeAt(0)
            discardedBoundaries++
        }
        boundaries.add(Event(kind, data.copyOf()))
    }

    fun observeCutoff(bits: Long, nativeLevel: Int, sharedLevel: Int, localBestBits: Long) {
        if (!retainBoundaries) return
        if (firstFiniteRead == null && Double.fromBits(bits).isFinite()) {
            firstFiniteRead = Event("minimize.first-finite-cutoff",
                longArrayOf(bits, localBestBits, nativeLevel.toLong(), sharedLevel.toLong()))
        }
        if (observedCutoffBits == bits) return
        observedCutoffBits = bits
        boundary("minimize.cutoff-read", bits, nativeLevel.toLong(), sharedLevel.toLong())
    }

    fun observeLinearPrune(entries: Long, cutoff: Double, level: Int, freshDominates: () -> Boolean) {
        if (!auditLinearBounds || firstLinearDisagreement != null || linearAuditLimitReached) return
        if (entries !in 1L..8193L) {
            linearAuditDeclines++
            return
        }
        if (linearAuditChecks == 65536 || entries > 4_000_000L - linearAuditVisits) {
            linearAuditLimitReached = true
            return
        }
        val agrees = freshDominates()
        linearAuditVisits += entries
        linearAuditChecks++
        if (!agrees) firstLinearDisagreement = Event("objective.linear-prune-disagreement",
            longArrayOf(cutoff.toBits(), level.toLong(), linearAuditChecks.toLong(), linearAuditVisits))
    }

    fun beginConflict(factorId: Int, level: Int, trail: Int) {
        if (captureFirstInteger && focusedIntegerId == null && events.isNotEmpty()) {
            discardedWindows++
            discardedEvents += events.size
            discardedWords += words
            events.clear()
            words = 0
            truncated = false
            truncationCause = null
            storedIds.clear()
            nextId = 0
        }
        record("analysis.begin", factorId.toLong(), level.toLong(), trail.toLong())
    }

    private fun omitFocusedDetails(): Boolean {
        if (focusedIntegerId == null) return false
        omittedObservations++
        return true
    }

    fun record(kind: String, vararg data: Long) {
        if (!accepting) return
        if (focusedIntegerId != null) {
            if ((kind == "traversal.pick" || kind == "traversal.attempt") && pendingPick) pendingPick = false
            else if (!kind.startsWith("consumer.") && kind != "store.remap" &&
                kind != "traversal.finished" && kind != "traversal.retirement-checked"
            ) {
                omittedObservations++
                return
            }
        }
        if (events.size == maxEvents || data.size > maxWords - words) {
            truncationCause = if (events.size == maxEvents) "events" else "words"
            truncated = true
            return
        }
        events.add(Event(kind, data.copyOf()))
        words += data.size
    }

    fun clause(kind: String, literals: IntArray, state: PropagationState) {
        if (!accepting || omitFocusedDetails()) return
        if (literals.size > 64) {
            omittedPayloads++
            record("$kind.omitted", literals.size.toLong())
            return
        }
        record(kind, *LongArray(literals.size * 5) { index ->
            val literal = literals[index / 5]
            val variable = Lit.variable(literal)
            val atom = variable - state.problem.numBoolVars
            val knownAtom = atom in 0 until state.atoms.intVar.size
            when (index % 5) {
                0 -> literal.toLong()
                1 -> when {
                    atom < 0 -> state.boolLevel[variable].toLong()
                    knownAtom -> state.atoms.lvl[atom].toLong()
                    else -> -1L
                }
                2 -> if (knownAtom) state.atoms.intVar[atom].toLong() else -1L
                3 -> if (knownAtom) state.atoms.kind[atom].ordinal.toLong() else -1L
                else -> when {
                    atom < 0 -> state.boolReason[variable].toLong()
                    knownAtom -> state.atoms.threshold[atom]
                    else -> -1L
                }
            }
        })
    }

    fun row(kind: String, row: IntegerConflictRow, state: PropagationState, trail: Int? = null) {
        if (!accepting || omitFocusedDetails()) return
        record(kind, row.bound, (trail ?: -1).toLong())
        if (!accepting) return
        record("$kind.guard", row.guard.toLong())
        if (row.guard != Lit.NONE) clause("$kind.guard-literal", intArrayOf(row.guard), state)
        for (index in row.variables.indices) {
            if (!accepting) return
            val variable = row.variables[index]
            val domain = if (trail == null) state.intDomains[variable] else state.domainAt(variable, trail)
            if (domain.holeCount > 64) {
                truncationCause = "holes"
                truncated = true
                return
            }
            val holes = ArrayList<Long>()
            domain.forEachHole {
                if (trail == null || !state.inDomainAt(variable, it, trail, domain)) {
                    holes.add(it)
                }
            }
            record("$kind.endpoint", variable.toLong(), row.coefficients[index], domain.min, domain.max,
                holes.size.toLong(), state.intMinReason[variable].toLong(), state.intMaxReason[variable].toLong())
            for (hole in holes) record("$kind.hole", variable.toLong(), hole)
        }
    }

    fun marker(variable: Int, lower: Boolean, marker: IntArray?) {
        if (!accepting || omitFocusedDetails()) return
        record("integer.endpoint", variable.toLong(), if (lower) 1 else 0)
        if (marker == null) return
        if (marker.size > 32) {
            omittedPayloads++
            record("integer.marker.omitted", marker.size.toLong())
            return
        }
        record("integer.marker", *LongArray(marker.size) { marker[it].toLong() })
    }

    fun seedEndpoints(variables: IntArray, state: PropagationState) {
        if (!accepting || omitFocusedDetails()) return
        if (variables.size > 16) {
            record("analysis.seed-endpoints-declined", variables.size.toLong())
            return
        }
        for (variable in variables) {
            if (!accepting) return
            val domain = state.intDomains[variable]
            record("analysis.seed-endpoint", variable.toLong(), domain.min, domain.max,
                state.intMinReason[variable].toLong(), state.intMaxReason[variable].toLong())
        }
    }

    fun stored(factorId: Int, kind: String) {
        if (!accepting || omitFocusedDetails()) return
        if (storedIds.size == 64) {
            truncationCause = "stored-identities"
            truncated = true
            return
        }
        val id = nextId++
        storedIds[factorId] = id
        record("$kind.stored", id.toLong(), factorId.toLong())
        if (!truncated && kind == "integer" && captureFirstInteger) {
            focusedIntegerId = id
            pendingPick = true
        }
    }

    fun pin(factorId: Int, variable: Int, bound: Long, kind: String, level: Int) {
        if (!accepting) return
        val id = storedIds[factorId] ?: return
        record("consumer.$kind", id.toLong(), factorId.toLong(), variable.toLong(), bound, level.toLong())
        pendingPick = true
    }

    fun remap(base: Int, remap: IntArray) {
        if (!accepting) return
        val retained = mutableMapOf<Int, Int>()
        for ((factor, id) in storedIds) {
            val next = remap.getOrNull(factor - base) ?: -1
            record("store.remap", id.toLong(), factor.toLong(), if (next < 0) -1 else (base + next).toLong())
            if (next >= 0) retained[base + next] = id
        }
        storedIds.clear()
        storedIds.putAll(retained)
    }
}
