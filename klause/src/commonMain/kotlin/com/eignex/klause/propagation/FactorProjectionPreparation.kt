package com.eignex.klause.propagation

import com.eignex.klause.factor.table.Mdd
import com.eignex.klause.factor.table.Table
import com.eignex.klause.factor.table.internals.MddTransitionIndex
import com.eignex.klause.factor.table.internals.TableGroupCache

internal class FactorProjectionPreparation {
    private val tables = HashMap<TableRelation, TableGroupCache>()
    private val diagrams = HashMap<DiagramRelation, MddTransitionIndex>()

    fun tableCache(table: Table): TableGroupCache = tables.getOrPut(
        TableRelation(table.arity, table.tuples, table.hi),
        ::TableGroupCache,
    )

    fun mddIndex(mdd: Mdd): MddTransitionIndex = diagrams.getOrPut(
        DiagramRelation(
            mdd.transitions, mdd.layerStarts, mdd.numStatesPerLayer, mdd.recordStride, mdd.initial, mdd.accepting,
        ),
    ) {
        MddTransitionIndex.build(mdd.transitions, mdd.layerStarts, mdd.numStatesPerLayer, mdd.recordStride)
    }

    // Arrays use reference identity: equal contents alone do not declare shared immutable relation data.
    @Suppress("AvoidReferentialEquality")
    private class TableRelation(val arity: Int, val tuples: LongArray, val hi: LongArray?) {
        override fun equals(other: Any?): Boolean =
            other is TableRelation && arity == other.arity && tuples === other.tuples && hi === other.hi

        override fun hashCode(): Int = arity xor tuples.hashCode() xor (hi?.hashCode() ?: 0)
    }

    @Suppress("AvoidReferentialEquality")
    private class DiagramRelation(
        val transitions: LongArray,
        val layerStarts: IntArray,
        val states: IntArray,
        val stride: Int,
        val initial: Int,
        val accepting: IntArray,
    ) {
        override fun equals(other: Any?): Boolean = other is DiagramRelation &&
            transitions === other.transitions && layerStarts === other.layerStarts && states === other.states &&
            stride == other.stride && initial == other.initial && accepting === other.accepting

        override fun hashCode(): Int = transitions.hashCode() xor layerStarts.hashCode() xor states.hashCode() xor
            stride xor initial xor accepting.hashCode()
    }
}
