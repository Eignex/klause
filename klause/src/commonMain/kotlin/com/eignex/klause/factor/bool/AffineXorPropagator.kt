package com.eignex.klause.factor.bool

import com.eignex.klause.ir.Lit
import com.eignex.klause.propagation.InterruptiblePropagator
import com.eignex.klause.propagation.PropagationState
import com.eignex.klause.propagation.checkFiniteWork

internal class AffineXorPropagator(private val seed: AffineXorSeed) : InterruptiblePropagator {
    override val expensiveBake: Boolean = true

    internal class Cache(seed: AffineXorSeed) {
        val rows = LongArray(seed.coordinates.size * seed.words)
        val reasons = LongArray(seed.coordinates.size * seed.reasonWords)
        val rhs = IntArray(seed.coordinates.size)
        val nonzeroWords = IntArray(seed.coordinates.size)
        val facts = IntArray(seed.freeColumns + 1)
        val seen = IntArray(seed.coordinates.size) { -1 }
        val query = LongArray(seed.words)
        val support = LongArray(seed.reasonWords)
        var value = 0
        var rank = 0
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
        var skippedExpressions = 0L
        val initializationWork = seed.initializationWork

        fun observations(): Map<String, Long> = mapOf(
            "Fires" to fires, "Resets" to resets, "InsertedRows" to inserted,
            "Reductions" to reductions, "Pins" to pins, "Conflicts" to conflicts,
            "WideReasons" to wideReasons, "InitializationWork" to initializationWork,
            "ExpressionRowsUpdated" to reductions, "ExpressionRowsSkipped" to skippedExpressions,
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
        if (!cache.valid) reset(state, cache)
        for (variable in seed.variables) {
            state.checkFiniteWork()
            if (seed.coordinates[variable] == Int.MIN_VALUE || cache.seen[variable] != -1 ||
                !state.boolAssignedAt(variable)) continue
            expression(state, cache, variable)
            cache.value = cache.value xor if (state.boolValueAt(variable)) 1 else 0
            cache.facts[cache.rank] = variable
            cache.support[cache.rank ushr 6] = cache.support[cache.rank ushr 6] xor (1L shl (cache.rank and 63))
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
            if (seed.coordinates[variable] == Int.MIN_VALUE || state.boolAssignedAt(variable) ||
                cache.nonzeroWords[variable] != 0) continue
            support(state, cache, variable)
            val width = reasonWidth(state, cache)
            if (width > 64) {
                cache.wideReasons++
                continue
            }
            val explanation = reason(state, cache, width)
            val value = cache.rhs[variable] != 0
            if (!state.pinBool(variable, value, explanation)) {
                cache.conflicts++
                return false
            }
            cache.seen[variable] = if (value) 1 else 0
            cache.pins++
        }
        cache.queriedRank = cache.rank
        return true
    }

    override fun interrupted(state: PropagationState, factorId: Int) {
        // A partial row update cannot become the next fire's affine residual system.
        (state.refPayload[factorId] as? Cache)?.valid = false
    }

    override fun conflictReason(state: PropagationState, factorId: Int): IntArray? =
        (state.refPayload[factorId] as? Cache)?.conflict

    private fun reset(state: PropagationState, cache: Cache) {
        for (variable in seed.variables) {
            state.checkFiniteWork()
            cache.seen[variable] = -1
            val coordinate = seed.coordinates[variable]
            if (coordinate == Int.MIN_VALUE) continue
            val free = if (coordinate < 0) -coordinate - 1 else -1
            var nonzero = 0
            for (word in 0 until seed.words) {
                state.checkFiniteWork()
                val bits = if (coordinate >= 0) seed.dependencies[coordinate][word]
                else if (word == (free ushr 6)) 1L shl (free and 63) else 0L
                cache.rows[variable * seed.words + word] = bits
                if (bits != 0L) nonzero++
            }
            for (word in 0 until seed.reasonWords) {
                state.checkFiniteWork()
                cache.reasons[variable * seed.reasonWords + word] = 0L
            }
            cache.rhs[variable] = if (coordinate >= 0) seed.rhs[coordinate] else 0
            cache.nonzeroWords[variable] = nonzero
        }
        cache.rank = 0
        cache.queriedRank = -1
        cache.valid = true
        cache.resets++
    }

    private fun expression(state: PropagationState, cache: Cache, variable: Int) {
        for (word in 0 until seed.words) {
            state.checkFiniteWork()
            cache.query[word] = cache.rows[variable * seed.words + word]
        }
        support(state, cache, variable)
        cache.value = cache.rhs[variable]
    }

    private fun support(state: PropagationState, cache: Cache, variable: Int) {
        for (word in 0 until seed.reasonWords) {
            state.checkFiniteWork()
            cache.support[word] = cache.reasons[variable * seed.reasonWords + word]
        }
    }

    private fun insert(state: PropagationState, cache: Cache): Boolean {
        val column = firstColumn(state, cache)
        if (column < 0) return cache.value == 0
        state.checkFiniteWork()
        check(cache.rank < seed.freeColumns)
        cache.rank++
        cache.inserted++
        for (variable in seed.variables) {
            state.checkFiniteWork()
            if (seed.coordinates[variable] == Int.MIN_VALUE) continue
            val offset = variable * seed.words
            if (cache.rows[offset + (column ushr 6)] and (1L shl (column and 63)) == 0L) {
                cache.skippedExpressions++
                continue
            }
            var nonzero = 0
            for (word in 0 until seed.words) {
                state.checkFiniteWork()
                val bits = cache.rows[offset + word] xor cache.query[word]
                cache.rows[offset + word] = bits
                if (bits != 0L) nonzero++
            }
            for (word in 0 until seed.reasonWords) {
                state.checkFiniteWork()
                val index = variable * seed.reasonWords + word
                cache.reasons[index] = cache.reasons[index] xor cache.support[word]
            }
            state.checkFiniteWork()
            cache.rhs[variable] = cache.rhs[variable] xor cache.value
            cache.nonzeroWords[variable] = nonzero
            cache.reductions++
        }
        return true
    }

    private fun firstColumn(state: PropagationState, cache: Cache): Int {
        for (word in cache.query.indices) {
            state.checkFiniteWork()
            val bits = cache.query[word]
            if (bits != 0L) return word * 64 + bits.countTrailingZeroBits()
        }
        return -1
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
