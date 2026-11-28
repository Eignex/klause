package com.eignex.klause.localsearch

import com.eignex.klause.localsearch.Move
import com.eignex.klause.propagation.Assumptions
import com.eignex.klause.solver.objective.FunctionalObjective
import com.eignex.util.collections.LongArrayList

/**
 * Accumulator that move sources push candidate moves into, filtering moves on variables frozen by [Assumptions],
 * defined by the invariant network or owned by another factor. Primitive moves are packed into a [LongArray] key
 * lane and a value lane, so a fill-clear cycle allocates nothing per move; [list] materializes [Move] objects
 * lazily.
 */
class MoveSink(private var assumptions: Assumptions = Assumptions.None) {
    private val lane = LongArrayList(INITIAL_CAPACITY)

    // A domain value spans the full Long range, so it cannot share the packed key word.
    private val valueLane = LongArrayList(INITIAL_CAPACITY)

    @Suppress("DoubleMutabilityForCollection") // lazily allocated on first compound move
    private var compounds: ArrayList<Move.Compound>? = null
    private var cachedList: List<Move>? = null
    private var elementPreservingProbes: Int = 0
    internal var alternativeRepairs: MoveSink? = null
    internal var compoundTally: CompoundTally? = null
    internal var absRepairDepth: Int = 0

    /** Materialized [Move] view, stable until the sink is mutated; compound moves follow primitives. */
    val list: List<Move> get() = cachedList ?: materialize().also { cachedList = it }

    internal val size: Int get() = lane.size + (compounds?.size ?: 0)

    internal fun moveAt(index: Int): Move = if (index < lane.size) {
        decode(lane[index], valueLane[index])
    } else {
        compounds!![index - lane.size]
    }

    /** Replaces the pins this sink filters against. */
    fun setAssumptions(a: Assumptions) {
        assumptions = a
    }

    private var invariants: InvariantNetwork? = null

    /** Installs the invariant network whose defined vars the sink filters out; a compound drops those parts
     *  individually. */
    fun setInvariants(net: InvariantNetwork?) {
        invariants = net
    }

    private var ownerInt: IntArray? = null

    /**
     * Installs the implicit-solving owner map ([ImplicitSeeding.ownerInt]): an int move on an owned variable is
     * dropped unless [proposer] is its owner, so generic sources never break an implicitly solved global. Null
     * disables ownership.
     */
    fun setOwners(owners: IntArray?) {
        ownerInt = owners
    }

    /** The factor proposing into this sink, so its moves on variables it owns survive the owner filter, or
     *  [NO_PROPOSER]. */
    var proposer: Int = NO_PROPOSER

    private fun ownedByOther(varId: Int): Boolean {
        val owners = ownerInt ?: return false
        val owner = owners[varId]
        return owner >= 0 && owner != proposer
    }

    internal fun allowsBool(varId: Int): Boolean =
        !assumptions.isFrozenBool(varId) && invariants?.isDefinedBool(varId) != true

    internal fun allowsDefinedBool(varId: Int): Boolean = !assumptions.isFrozenBool(varId)

    internal fun allowsInt(varId: Int): Boolean =
        !assumptions.isFrozenInt(varId) && invariants?.isDefinedInt(varId) != true && !ownedByOther(varId)

    internal fun allowsDefinedInt(varId: Int): Boolean =
        !assumptions.isFrozenInt(varId) && !ownedByOther(varId)

    /** Queue a Boolean-flip move on [varId]. */
    fun addBoolFlip(varId: Int) {
        if (!allowsBool(varId)) return
        lane.add(encodeBoolFlip(varId))
        valueLane.add(0L)
        cachedList = null
    }

    internal fun addChannelingBoolFlip(state: LocalSearchState, varId: Int) {
        if (invariants?.isDefinedBool(varId) != true) {
            addBoolFlip(varId)
            return
        }
        if (assumptions.isFrozenBool(varId)) return
        LiteralChannelRepair(state, this).proposePredicate(varId, !state.assignment.boolValue(varId))
    }

    /** Queue an int-set move on [varId]. */
    fun addIntSet(varId: Int, newValue: Long) {
        if (!allowsInt(varId)) return
        lane.add(encodeIntSet(varId))
        valueLane.add(newValue)
        cachedList = null
    }

    /** Queue a real-set move on [varId]; real columns are never pinned, defined or owned. */
    fun addRealSet(varId: Int, newValue: Double) {
        lane.add(encodeRealSet(varId))
        valueLane.add(newValue.toRawBits())
        cachedList = null
    }

    /** Adds an atomic multi-variable move, skipped whole when any part touches a frozen variable. Parts on defined
     *  or foreign-owned variables are dropped individually; a single survivor is added as a primitive move. */
    fun addCompound(parts: List<Move>) {
        if (touchesFrozen(parts)) {
            compoundTally?.recordOffer(parts.size, frozen = true, kept = 0)
            return
        }
        val kept = withoutRedundantParts(parts)
        compoundTally?.recordOffer(parts.size, frozen = false, kept = kept.size)
        if (kept.isEmpty()) return
        if (kept.size == 1) {
            // Compound requires two parts; the survivor already passed the frozen/defined filters.
            addPrimitive(kept[0])
            return
        }
        val side = compounds ?: ArrayList<Move.Compound>(2).also { compounds = it }
        side.add(Move.Compound(kept))
        cachedList = null
    }

    private fun touchesFrozen(parts: List<Move>): Boolean {
        for (p in parts) {
            when (p) {
                is Move.BoolFlip -> if (assumptions.isFrozenBool(p.varId)) return true
                is Move.IntSet -> if (assumptions.isFrozenInt(p.varId)) return true
                is Move.RealSet -> {}
                is Move.Compound -> error("Compound parts must be primitive (BoolFlip/IntSet/RealSet)")
            }
        }
        return false
    }

    // Propagation recomputes defined vars, so parts targeting them are dropped individually.
    private fun withoutRedundantParts(parts: List<Move>): List<Move> {
        val net = invariants
        if (net == null && ownerInt == null) return parts
        return parts.filter { p ->
            when (p) {
                is Move.BoolFlip -> net?.isDefinedBool(p.varId) != true
                is Move.IntSet -> net?.isDefinedInt(p.varId) != true && !ownedByOther(p.varId)
                is Move.RealSet, is Move.Compound -> true
            }
        }
    }

    private fun addPrimitive(p: Move) {
        when (p) {
            is Move.BoolFlip -> addBoolFlip(p.varId)
            is Move.IntSet -> addIntSet(p.varId, p.newValue)
            is Move.RealSet -> addRealSet(p.varId, p.newValue)
            is Move.Compound -> error("unreachable: parts are primitive")
        }
    }

    /** Discard all queued moves. */
    fun clear() {
        lane.clear()
        valueLane.clear()
        compounds = null
        cachedList = null
        proposer = NO_PROPOSER
        elementPreservingProbes = 0
        alternativeRepairs = null
    }

    /**
     * Channeling-aware [addIntSet]: sets [varId] together with the consistency-preserving flips of sibling reified
     * equality indicators, so the engine does not chase one indicator at a time. A defined [varId] is instead
     * backsolved through its definition into an atomic repair of its searched inputs.
     */
    fun addChannelingIntSet(state: LocalSearchState, varId: Int, newValue: Long) {
        if (invariants?.isDefinedInt(varId) == true) {
            if (invariants?.intDefinition(varId) is FunctionalObjective.Abs) {
                AbsResultRepair(state, this).propose(varId, newValue)
                return
            }
            val element = invariants?.elementResultDefinition(varId)
            if (element != null) {
                ElementResultRepair(state, this, element).propose(newValue)
                return
            }
            if (invariants?.literalChannel(varId) != null) {
                LiteralChannelRepair(state, this).propose(varId, newValue)
                return
            }
            if (invariants?.intDefinition(varId) is FunctionalObjective.Power) {
                PowerResultRepair(state, this).propose(varId, newValue)
                return
            }
            if (invariants?.hasExtremumRepair(varId) != true) {
                val linear = invariants?.linearDefinition(varId)
                if (linear != null) {
                    val scaled = positiveScaledBinaryDefinition(state, varId)
                    if (scaled != null && invariants?.literalChannel(scaled.first) != null &&
                        allowsDefinedInt(varId) && newValue in state.rootDomains[varId] &&
                        (newValue == 0L || newValue == scaled.second)
                    ) {
                        LiteralChannelRepair(state, this).propose(scaled.first, if (newValue == 0L) 0L else 1L)
                        return
                    }
                    AffineIndexRepair(state, this, linear).propose(newValue)
                    return
                }
            }
            val repair = extremumRepair(state, varId, newValue, ::allowsInt) ?: return
            addChannelingCompound(state, repair.map { (input, target) -> Move.IntSet(input, target) })
            return
        }
        if (assumptions.isFrozenInt(varId)) return
        val move = state.synthesizeChannelingMove(varId, newValue)
        when (val m = move) {
            is Move.IntSet -> addIntSet(varId, newValue)

            is Move.Compound -> addCompound(m.parts)

            is Move.BoolFlip -> addBoolFlip(m.varId)

            is Move.RealSet -> addRealSet(m.varId, m.newValue)
        }
        addElementPreservingVariant(state, move)
    }

    internal fun addChannelingCompound(state: LocalSearchState, parts: List<Move>) {
        addCompound(parts)
        if (parts.isNotEmpty()) {
            addElementPreservingVariant(state, if (parts.size == 1) parts[0] else Move.Compound(parts))
        }
    }

    private fun addElementPreservingVariant(state: LocalSearchState, move: Move) {
        if (elementPreservingProbes >= MAX_ELEMENT_PRESERVING_PROBES) return
        val parts = if (move is Move.Compound) move.parts else listOf(move)
        if (touchesFrozen(parts)) return
        val kept = withoutRedundantParts(parts)
        if (kept.isEmpty()) return
        elementPreservingProbes++
        val base = if (kept.size == 1) kept[0] else Move.Compound(kept)
        val variant = state.elementPreservingMove(base, this) as? Move.Compound ?: return
        addCompound(variant.parts)
    }

    internal fun addElementIndexSet(state: LocalSearchState, varId: Int, newValue: Long): Boolean {
        if (assumptions.isFrozenInt(varId)) return false
        val linear = if (invariants?.hasExtremumRepair(varId) == true) null else invariants?.linearDefinition(varId)
        if (linear != null) return AffineIndexRepair(state, this, linear).propose(newValue)
        val before = size
        addChannelingIntSet(state, varId, newValue)
        return size > before
    }

    private fun materialize(): List<Move> {
        val compoundsRef = compounds
        val total = lane.size + (compoundsRef?.size ?: 0)
        if (total == 0) return emptyList()
        val out = ArrayList<Move>(total)
        for (i in 0 until lane.size) out.add(decode(lane[i], valueLane[i]))
        if (compoundsRef != null) out.addAll(compoundsRef)
        return out
    }

    /** Move encoding and the [NO_PROPOSER] sentinel. */
    companion object {
        /** [proposer] of the generic sources, whose moves on any owned variable are filtered. */
        const val NO_PROPOSER: Int = -1

        private const val INITIAL_CAPACITY: Int = 16
        private const val MAX_ELEMENT_PRESERVING_PROBES: Int = 4
        private const val KIND_BIT: Long = 1L shl 63
        private const val REAL_BIT: Long = 1L shl 62
        private const val VAR_MASK: Long = (1L shl 31) - 1L

        internal fun encodeBoolFlip(varId: Int): Long = varId.toLong() and VAR_MASK

        // Bit 63 marks an IntSet, bit 62 a RealSet; bits 0..30 hold the var id.
        internal fun encodeIntSet(varId: Int): Long = KIND_BIT or (varId.toLong() and VAR_MASK)

        internal fun encodeRealSet(varId: Int): Long = REAL_BIT or (varId.toLong() and VAR_MASK)

        internal fun decode(packed: Long, value: Long): Move = when {
            packed and KIND_BIT != 0L -> Move.IntSet((packed and VAR_MASK).toInt(), value)
            packed and REAL_BIT != 0L -> Move.RealSet((packed and VAR_MASK).toInt(), Double.fromBits(value))
            else -> Move.BoolFlip((packed and VAR_MASK).toInt())
        }
    }
}
