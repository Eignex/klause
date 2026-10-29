package com.eignex.klause.propagation

import com.eignex.klause.ir.Lit

internal class ArithmeticLearningTrace(
    private val maxEvents: Int = 128,
    private val maxWords: Int = 8192,
) {
    class Event(val kind: String, val data: LongArray)

    val events = ArrayList<Event>()
    var truncated = false
        private set
    var words = 0
        private set
    var omittedPayloads = 0
        private set
    private val storedIds = mutableMapOf<Int, Int>()
    private var nextId = 0
    val accepting: Boolean get() = !truncated

    init {
        require(maxEvents in 1..1024 && maxWords in 1..65536)
    }

    fun record(kind: String, vararg data: Long) {
        if (!accepting) return
        if (events.size == maxEvents || data.size > maxWords - words) {
            truncated = true
            return
        }
        events.add(Event(kind, data.copyOf()))
        words += data.size
    }

    fun clause(kind: String, literals: IntArray, state: PropagationState) {
        if (!accepting) return
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
        if (!accepting) return
        record(kind, row.bound, (trail ?: -1).toLong())
        if (!accepting) return
        record("$kind.guard", row.guard.toLong())
        if (row.guard != Lit.NONE) clause("$kind.guard-literal", intArrayOf(row.guard), state)
        for (index in row.variables.indices) {
            if (!accepting) return
            val variable = row.variables[index]
            val domain = if (trail == null) state.intDomains[variable] else state.domainAt(variable, trail)
            if (domain.holeCount > 64) {
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
        if (!accepting) return
        record("integer.endpoint", variable.toLong(), if (lower) 1 else 0)
        if (marker == null) return
        if (marker.size > 32) {
            truncated = true
            return
        }
        record("integer.marker", *LongArray(marker.size) { marker[it].toLong() })
    }

    fun seedEndpoints(variables: IntArray, state: PropagationState) {
        if (!accepting) return
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
        if (!accepting) return
        if (storedIds.size == 64) {
            truncated = true
            return
        }
        val id = nextId++
        storedIds[factorId] = id
        record("$kind.stored", id.toLong(), factorId.toLong())
    }

    fun pin(factorId: Int, variable: Int, bound: Long, kind: String, level: Int) {
        if (!accepting) return
        val id = storedIds[factorId] ?: return
        record("consumer.$kind", id.toLong(), factorId.toLong(), variable.toLong(), bound, level.toLong())
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
