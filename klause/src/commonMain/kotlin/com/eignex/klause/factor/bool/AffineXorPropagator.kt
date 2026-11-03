package com.eignex.klause.factor.bool

import com.eignex.klause.ir.Lit
import com.eignex.klause.propagation.InterruptiblePropagator
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.checkFiniteWork

internal class AffineXorPropagator(private val seed: AffineXorSeed) : InterruptiblePropagator {
    override val expensiveBake: Boolean = true

    internal class Cache(seed: AffineXorSeed) {
        val mask = LongArray(seed.freeColumns * seed.words)
        val reasons = LongArray(seed.freeColumns * seed.reasonWords)
        val rhs = IntArray(seed.freeColumns)
        val pivotRow = IntArray(seed.freeColumns) { -1 }
        val facts = IntArray(seed.freeColumns + 1)
        val seen = IntArray(seed.coordinates.size) { -1 }
        val query = LongArray(seed.words)
        val support = LongArray(seed.reasonWords)
        val unitPivots = LongArray(seed.words)
        var value = 0
        var rank = 0
        var unitBasis = true
        var valid = false
        var queriedRank = -1
        var conflict: IntArray? = null
        var fires = 0L
        var resets = 0L
        var inserted = 0L
        var reductions = 0L
        var pins = 0L
        var conflicts = 0L
        var wideReasons = 0L
        var unitQueriesSkipped = 0L
        var compositeBases = 0L
        val initializationWork = seed.initializationWork

        fun observations(): Map<String, Long> = mapOf(
            "Fires" to fires, "Resets" to resets, "InsertedRows" to inserted,
            "Reductions" to reductions, "Pins" to pins, "Conflicts" to conflicts,
            "WideReasons" to wideReasons, "InitializationWork" to initializationWork,
            "UnitQueriesSkipped" to unitQueriesSkipped, "CompositeBases" to compositeBases,
        )
    }

    override fun propagate(state: PropagationState, factorId: Int): Boolean {
        state.finiteWork.begin()
        state.finiteWork.checkNow()
        val cache = state.refPayload[factorId] as? Cache ?: run {
            repeat(seed.initializationWork.toInt()) { state.checkFiniteWork() }
            state.finiteWork.checkNow()
            Cache(seed).also { state.refPayload[factorId] = it }
        }
        cache.fires++
        cache.conflict = null
        if (cache.valid) for (variable in seed.variables) {
            state.checkFiniteWork()
            val previous = cache.seen[variable]
            if (previous != -1 && (!state.boolAssignedAt(variable) ||
                    previous != if (state.boolValueAt(variable)) 1 else 0)) {
                cache.valid = false
                break
            }
        }
        if (!cache.valid) {
            for (column in cache.pivotRow.indices) {
                state.checkFiniteWork()
                cache.pivotRow[column] = -1
            }
            for (variable in seed.variables) {
                state.checkFiniteWork()
                cache.seen[variable] = -1
            }
            for (word in cache.unitPivots.indices) {
                state.checkFiniteWork()
                cache.unitPivots[word] = 0L
            }
            cache.unitBasis = true
            cache.rank = 0
            cache.queriedRank = -1
            cache.valid = true
            cache.resets++
        }
        for (variable in seed.variables) {
            state.checkFiniteWork()
            if (seed.coordinates[variable] == Int.MIN_VALUE || cache.seen[variable] != -1 ||
                !state.boolAssignedAt(variable)) continue
            expression(state, cache, variable)
            cache.value = cache.value xor if (state.boolValueAt(variable)) 1 else 0
            cache.facts[cache.rank] = variable
            cache.support[cache.rank ushr 6] = 1L shl (cache.rank and 63)
            if (!insert(state, cache)) {
                cache.conflicts++
                val width = reasonWidth(state, cache)
                cache.conflict = if (width <= 64) reason(state, cache, width) else null
                if (width > 64) cache.wideReasons++
                return false
            }
            cache.seen[variable] = if (state.boolValueAt(variable)) 1 else 0
        }
        if (cache.queriedRank == cache.rank) return true
        for (variable in seed.variables) {
            state.checkFiniteWork()
            if (seed.coordinates[variable] == Int.MIN_VALUE || state.boolAssignedAt(variable)) continue
            if (!mayBeDetermined(state, cache, variable)) {
                cache.unitQueriesSkipped++
                continue
            }
            expression(state, cache, variable)
            if (!reduce(state, cache)) continue
            val width = reasonWidth(state, cache)
            if (width > 64) {
                cache.wideReasons++
                continue
            }
            val explanation = reason(state, cache, width)
            if (!state.pinBool(variable, cache.value != 0, explanation)) {
                cache.conflicts++
                return false
            }
            cache.seen[variable] = if (cache.value != 0) 1 else 0
            cache.pins++
        }
        cache.queriedRank = cache.rank
        return true
    }

    override fun interrupted(state: PropagationState, factorId: Int) {
        // A partial row update cannot become the next fire's linear basis.
        (state.refPayload[factorId] as? Cache)?.valid = false
    }

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        (state.refPayload[factorId] as? Cache)?.conflict

    private fun mayBeDetermined(state: PropagationState, cache: Cache, variable: Int): Boolean {
        if (!cache.unitBasis) return true
        val coordinate = seed.coordinates[variable]
        if (coordinate < 0) {
            state.checkFiniteWork()
            val free = -coordinate - 1
            return cache.unitPivots[free ushr 6] and (1L shl (free and 63)) != 0L
        }
        for (word in 0 until seed.words) {
            state.checkFiniteWork()
            if (seed.dependencies[coordinate][word] and cache.unitPivots[word].inv() != 0L) return false
        }
        return true
    }

    private fun expression(state: PropagationState, cache: Cache, variable: Int) {
        val coordinate = seed.coordinates[variable]
        for (word in 0 until seed.words) {
            state.checkFiniteWork()
            cache.query[word] = if (coordinate >= 0) seed.dependencies[coordinate][word] else 0L
        }
        if (coordinate < 0) {
            val free = -coordinate - 1
            cache.query[free ushr 6] = 1L shl (free and 63)
        }
        for (word in cache.support.indices) {
            state.checkFiniteWork()
            cache.support[word] = 0L
        }
        cache.value = if (coordinate >= 0) seed.rhs[coordinate] else 0
    }

    private fun insert(state: PropagationState, cache: Cache): Boolean {
        while (true) {
            val column = firstColumn(state, cache)
            if (column < 0) return cache.value == 0
            state.checkFiniteWork()
            val row = cache.pivotRow[column]
            if (row >= 0) {
                combine(state, cache, row)
                continue
            }
            val next = cache.rank
            for (word in 0 until seed.words) {
                state.checkFiniteWork()
                cache.mask[next * seed.words + word] = cache.query[word]
            }
            for (word in 0 until seed.reasonWords) {
                state.checkFiniteWork()
                cache.reasons[next * seed.reasonWords + word] = cache.support[word]
            }
            cache.rhs[next] = cache.value
            cache.pivotRow[column] = next
            if (cache.unitBasis) {
                for (word in cache.query.indices) {
                    state.checkFiniteWork()
                    val expected = if (word == (column ushr 6)) 1L shl (column and 63) else 0L
                    if (cache.query[word] != expected) {
                        cache.unitBasis = false
                        cache.compositeBases++
                        break
                    }
                }
                if (cache.unitBasis) {
                    val word = column ushr 6
                    cache.unitPivots[word] = cache.unitPivots[word] or (1L shl (column and 63))
                }
            }
            cache.rank++
            cache.inserted++
            return true
        }
    }

    private fun reduce(state: PropagationState, cache: Cache): Boolean {
        while (true) {
            val column = firstColumn(state, cache)
            if (column < 0) return true
            state.checkFiniteWork()
            val row = cache.pivotRow[column]
            if (row < 0) return false
            combine(state, cache, row)
        }
    }

    private fun firstColumn(state: PropagationState, cache: Cache): Int {
        for (word in cache.query.indices) {
            state.checkFiniteWork()
            val bits = cache.query[word]
            if (bits != 0L) return word * 64 + bits.countTrailingZeroBits()
        }
        return -1
    }

    private fun combine(state: PropagationState, cache: Cache, row: Int) {
        for (word in 0 until seed.words) {
            state.checkFiniteWork()
            cache.query[word] = cache.query[word] xor cache.mask[row * seed.words + word]
        }
        for (word in 0 until seed.reasonWords) {
            state.checkFiniteWork()
            cache.support[word] = cache.support[word] xor cache.reasons[row * seed.reasonWords + word]
        }
        cache.value = cache.value xor cache.rhs[row]
        cache.reductions++
    }

    private fun reasonWidth(state: PropagationState, cache: Cache): Int {
        var count = 0
        for (bits in cache.support) {
            state.checkFiniteWork()
            count += bits.countOneBits()
        }
        return count
    }

    private fun reason(state: PropagationState, cache: Cache, width: Int): IntArray? {
        if (width == 0) return null
        repeat(width) { state.checkFiniteWork() }
        val result = IntArray(width)
        var next = 0
        for (word in cache.support.indices) {
            state.checkFiniteWork()
            var bits = cache.support[word]
            while (bits != 0L) {
                state.checkFiniteWork()
                val fact = word * 64 + bits.countTrailingZeroBits()
                val variable = cache.facts[fact]
                result[next++] = Lit.make(variable, !state.boolValueAt(variable))
                bits = bits and (bits - 1L)
            }
        }
        return result
    }
}
