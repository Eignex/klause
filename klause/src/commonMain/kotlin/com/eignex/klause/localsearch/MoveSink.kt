package com.eignex.klause.localsearch

import com.eignex.klause.localsearch.Move
import com.eignex.klause.solver.Assumptions
import com.eignex.klause.util.LongArrayList

/**
 * Mutable accumulator that factors push repair-move suggestions into. Optionally consults
 * an [Assumptions] set so a frozen variable never enters the candidate list. LS-only — the
 * propagation contract doesn't use it.
 *
 * Backs `BoolFlip` / `IntSet` additions with a single resizable [LongArray] lane (one Long
 * per move: bit 63 = kind, bits 0..30 = varId, bits 31..62 = value), so a fill-clear cycle
 * — the dominant pattern during local search — touches no per-move objects. Compound moves
 * are boxed in a small side list (only the AllDifferent and Circuit factors use them, and
 * neither mixes compounds with primitives within a single propose call).
 *
 * [list] materializes [Move] objects lazily on first read and caches them until the next
 * mutation. Callers that never read [list] on an empty sink pay zero per-add allocation.
 */
class MoveSink(private var assumptions: Assumptions = Assumptions.None) {
    private val lane = LongArrayList(INITIAL_CAPACITY)

    @Suppress("DoubleMutabilityForCollection") // lazily allocated on first compound move
    private var compounds: ArrayList<Move.Compound>? = null
    private var cachedList: List<Move>? = null

    /** Materialized [Move] view. Stable across reads until the sink is mutated.
     *  Compound moves follow primitives in the iteration order. */
    val list: List<Move> get() = cachedList ?: materialize().also { cachedList = it }

    /** Replace the [Assumptions] this sink filters against. Called by [LocalSearchState] on
     *  init / restart so per-call assumptions take effect. */
    fun setAssumptions(a: Assumptions) {
        assumptions = a
    }

    private var invariants: InvariantNetwork? = null

    /** Install the per-move invariant index: defined vars are determined, not searched, so moves
     *  targeting them are filtered at the sink — the single choke point all candidate sources go
     *  through. Compound parts on defined vars are dropped individually; a compound whose parts all
     *  drop is skipped. */
    fun setInvariants(net: InvariantNetwork?) {
        invariants = net
    }

    private var ownerInt: IntArray? = null

    /**
     * Install the implicit-solving owner map: `owners[v]` is the factor id that owns int var `v`
     * (`-1` = unowned). An owned variable is a decision variable of an implicit-solving global that
     * was seeded feasible and is kept feasible only by that global's own structure-preserving moves
     * (see [com.eignex.klause.localsearch.LocalSearchState.ownerInt]). The sink drops any int
     * move on an owned variable unless [proposer] is its owner, so the generic repair/jump/swap
     * sources never break an implicitly-solved constraint — the search treats those variables as
     * removed from its neighbourhood, exactly as a defined var is. `null` disables ownership.
     */
    fun setOwners(owners: IntArray?) {
        ownerInt = owners
    }

    /** The factor currently proposing into this sink, or [NO_PROPOSER] for the generic sources that
     *  add moves without a proposing factor. Set around a factor's propose call so its own moves on
     *  the variables it owns survive the owner filter while every other source's do not. */
    var proposer: Int = NO_PROPOSER

    private fun ownedByOther(varId: Int): Boolean {
        val owners = ownerInt ?: return false
        val owner = owners[varId]
        return owner >= 0 && owner != proposer
    }

    /** Queue a Boolean-flip move on `boolVar`. */
    fun addBoolFlip(varId: Int) {
        if (assumptions.isFrozenBool(varId)) return
        if (invariants?.isDefinedBool(varId) == true) return
        lane.add(encodeBoolFlip(varId))
        cachedList = null
    }

    /** Queue an int-set move on `intVar`. */
    fun addIntSet(varId: Int, newValue: Int) {
        if (assumptions.isFrozenInt(varId)) return
        if (invariants?.isDefinedInt(varId) == true) return
        if (ownedByOther(varId)) return
        lane.add(encodeIntSet(varId, newValue))
        cachedList = null
    }

    /** Add a multi-variable atomic transition. Skips the move entirely if any part
     *  would touch a frozen variable — a Compound is all-or-nothing. */
    fun addCompound(parts: List<Move>) {
        for (p in parts) {
            when (p) {
                is Move.BoolFlip -> if (assumptions.isFrozenBool(p.varId)) return
                is Move.IntSet -> if (assumptions.isFrozenInt(p.varId)) return
                is Move.Compound -> error("Compound parts must be primitive (BoolFlip/IntSet)")
            }
        }
        // Under per-move invariants, parts targeting defined vars are redundant (propagation
        // recomputes them) — drop them individually rather than the whole compound.
        val net = invariants
        val kept = if (net == null && ownerInt == null) {
            parts
        } else {
            parts.filter { p ->
                when (p) {
                    is Move.BoolFlip -> net?.isDefinedBool(p.varId) != true
                    is Move.IntSet -> net?.isDefinedInt(p.varId) != true && !ownedByOther(p.varId)
                    is Move.Compound -> true
                }
            }
        }
        if (kept.isEmpty()) return
        if (kept.size == 1) {
            // A compound reduced to one survivor is just a primitive move (Compound requires
            // two parts); the part already passed the frozen/defined filters above.
            when (val p = kept[0]) {
                is Move.BoolFlip -> addBoolFlip(p.varId)
                is Move.IntSet -> addIntSet(p.varId, p.newValue)
                is Move.Compound -> error("unreachable: parts are primitive")
            }
            return
        }
        val side = compounds ?: ArrayList<Move.Compound>(2).also { compounds = it }
        side.add(Move.Compound(kept))
        cachedList = null
    }

    /** Discard all queued moves. */
    fun clear() {
        lane.clear()
        compounds = null
        cachedList = null
        proposer = NO_PROPOSER
    }

    /** Channeling-aware variant of [addIntSet]. Asks [state] to synthesize the coordinated
     *  move that sets [varId] to [newValue] *and* atomically updates any sibling reified
     *  single-var equality factors mentioning [varId] (their indicator bools get
     *  consistency-preserving flips). Falls back to the plain [Move.IntSet] when no
     *  sibling indicators need updating, so the cost on non-channeling problems is just
     *  the occurrence-list walk.
     *
     *  Use this in any factor's `proposeRepairMoves` when proposing an int-set move on a
     *  variable that could be part of a value-to-indicator channeling cluster (the common
     *  decomposition of `x in S` / per-period choice / `course(i) = p` over int vars). Without
     *  channeling synthesis, the engine chases one indicator flip at a time after every int change.
     */
    fun addChannelingIntSet(state: LocalSearchState, varId: Int, newValue: Int) {
        if (assumptions.isFrozenInt(varId)) return
        when (val m = state.synthesizeChannelingMove(varId, newValue)) {
            is Move.IntSet -> addIntSet(varId, newValue)
            is Move.Compound -> addCompound(m.parts)
            is Move.BoolFlip -> addBoolFlip(m.varId) // shouldn't happen but stay total
        }
    }

    private fun materialize(): List<Move> {
        val compoundsRef = compounds
        val total = lane.size + (compoundsRef?.size ?: 0)
        if (total == 0) return emptyList()
        val out = ArrayList<Move>(total)
        for (i in 0 until lane.size) out.add(decode(lane[i]))
        if (compoundsRef != null) out.addAll(compoundsRef)
        return out
    }

    /** Shared [MoveSink] helpers. */
    companion object {
        /** [proposer] value for the generic sources that add moves without a proposing factor — no
         *  factor owns their moves, so any owned variable is filtered. */
        const val NO_PROPOSER: Int = -1

        private const val INITIAL_CAPACITY: Int = 16
        private const val KIND_BIT: Long = 1L shl 63
        private const val VAR_MASK: Long = (1L shl 31) - 1L

        internal fun encodeBoolFlip(varId: Int): Long = varId.toLong() and VAR_MASK

        /** Layout: bit 63 = 1, bits 31..62 = value (32 bits), bits 0..30 = varId (31 bits). */
        internal fun encodeIntSet(varId: Int, newValue: Int): Long = KIND_BIT or
            (varId.toLong() and VAR_MASK) or
            ((newValue.toLong() and 0xFFFF_FFFFL) shl 31)

        internal fun decode(packed: Long): Move = if (packed and KIND_BIT == 0L) {
            Move.BoolFlip((packed and VAR_MASK).toInt())
        } else {
            val varId = (packed and VAR_MASK).toInt()
            val value = (packed ushr 31).toInt()
            Move.IntSet(varId, value)
        }
    }
}
