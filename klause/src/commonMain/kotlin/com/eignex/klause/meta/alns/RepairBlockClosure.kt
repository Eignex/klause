package com.eignex.klause.meta.alns

import com.eignex.klause.localsearch.InvariantNetwork
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.propagation.BakedProblem
import com.eignex.klause.util.Cancellation
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet

internal enum class RepairBlockDecline {
    NoShape, Unavailable, UnsupportedShape, InvalidFrame, BlockedPin, Oversized, WorkLimit, Cancelled, Arithmetic,
}

internal object RepairBlockInterrupted : RuntimeException()

internal class RepairBlockWork(private val cancellation: Cancellation, private val limit: Int) {
    var visits = 0
        private set
    var decline: RepairBlockDecline? = null
        private set

    fun charge() {
        if (visits >= limit) stop(RepairBlockDecline.WorkLimit)
        cancellation.charge(1)
        if (visits++ % 64 == 0 && cancellation()) stop(RepairBlockDecline.Cancelled)
    }

    fun stop(reason: RepairBlockDecline): Nothing {
        decline = reason
        throw RepairBlockInterrupted
    }
}

internal data class ClosedRepairBlock(val freed: FreedVars, val searchedInputs: Int)

internal object RepairBlockClosure {
    fun close(
        problem: BakedProblem,
        requested: FreedVars,
        definitions: InvariantNetwork?,
        assumptions: Assumptions,
        maxInputs: Int,
        maxVariables: Int,
        work: RepairBlockWork,
    ): ClosedRepairBlock {
        val bools = problem.numBoolVars
        val total = bools.toLong() + problem.numIntVars
        work.charge()
        if (total > Int.MAX_VALUE) work.stop(RepairBlockDecline.InvalidFrame)
        val upstream = IntArrayList()
        val visited = IntHashSet()
        val mutable = IntHashSet()
        val leaves = IntArrayList()

        fun visit(slot: Int) {
            work.charge()
            if (slot < 0 || slot.toLong() >= total) work.stop(RepairBlockDecline.InvalidFrame)
            if (!visited.add(slot)) return
            if (visited.size > maxVariables) work.stop(RepairBlockDecline.Oversized)
            upstream.add(slot)
        }

        fun free(slot: Int): Boolean {
            work.charge()
            if (slot >= bools && problem.rootIntDomain(slot - bools).isFixed) return false
            val pinned = if (slot < bools) assumptions.isFrozenBool(slot) else assumptions.isFrozenInt(slot - bools)
            if (pinned) work.stop(RepairBlockDecline.BlockedPin)
            mutable.add(slot)
            if (mutable.size > maxVariables) work.stop(RepairBlockDecline.Oversized)
            return true
        }

        for (variable in requested.bools) {
            if (variable < 0 || variable >= bools) work.stop(RepairBlockDecline.InvalidFrame)
            visit(variable)
        }
        for (variable in requested.ints) {
            if (variable < 0 || variable >= problem.numIntVars) work.stop(RepairBlockDecline.InvalidFrame)
            visit(bools + variable)
        }
        var position = 0
        while (position < upstream.size) {
            val slot = upstream[position++]
            val movable = free(slot)
            val writer = if (slot < bools) definitions?.boolWriter(slot) ?: -1
            else definitions?.intWriter(slot - bools) ?: -1
            if (writer < 0) {
                if (movable) {
                    leaves.add(slot)
                    if (leaves.size > maxInputs) work.stop(RepairBlockDecline.Oversized)
                }
            } else {
                val node = checkNotNull(definitions).node(writer)
                for (variable in node.boolInputs) visit(variable)
                for (variable in node.intInputs) visit(bools + variable)
            }
        }

        val downSeen = IntHashSet()
        val downstream = IntArrayList()
        for (index in 0 until leaves.size) {
            val slot = leaves[index]
            downSeen.add(slot)
            downstream.add(slot)
        }
        position = 0
        while (position < downstream.size && definitions != null) {
            val slot = downstream[position++]
            val readers = if (slot < bools) definitions.boolReaders(slot) else definitions.intReaders(slot - bools)
            for (reader in readers) {
                work.charge()
                val node = definitions.node(reader)
                val output = if (node.outIsBool) node.out else bools + node.out
                if (free(output) && downSeen.add(output)) downstream.add(output)
            }
        }
        val slots = mutable.toIntArray().also { it.sort() }
        return ClosedRepairBlock(FreedVars(slots.filter { it < bools }.toIntArray(),
            slots.filter { it >= bools }.map { it - bools }.toIntArray()), leaves.size)
    }
}
