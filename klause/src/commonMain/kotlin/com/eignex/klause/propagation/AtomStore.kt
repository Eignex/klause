package com.eignex.klause.propagation

import com.eignex.util.collections.IntArrayDeque
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.LongArrayList

// Bound-atom registry for lazy clause generation. An atom `[x ≥ k]`, `[x ≤ k]` or `[x = k]` gets the virtual
// variable id `numBoolVars + atomId`, so its literals share the Boolean literal encoding. Atoms are allocated
// lazily on first reference. Each carries the trail metadata a Boolean variable does: [truth], [lvl] and [ant],
// recorded when the bound crosses the threshold and undone with the domain change that set them.
internal class AtomStore(numIntVars: Int) {
    // The reverse (var, kind, threshold) → atom lookup searches [byIntVar].
    val intVar: IntArrayList = IntArrayList()

    val kind: ArrayList<AtomKind> = ArrayList()

    val threshold: LongArrayList = LongArrayList()

    // Cached truth (0 = uncached, 1 = true, 2 = false) so BCP avoids re-deriving it from the domains on every
    // clause touch. A 0 slot is not proof of "undetermined": an atom materialized after its bound crossed has
    // no cached truth, and readers fall back to the domain-derived `atomTruthOf`.
    val truth: IntArrayList = IntArrayList()

    // -1 when the truth is undetermined or a root/bake fact.
    val lvl: IntArrayList = IntArrayList()

    // Null for a leaf or root fact.
    val ant: ArrayList<IntArray?> = ArrayList()

    // Reason of the bound move `propagateAtomsForVar` is channeling; `wakeAtom` stores it on each crossed atom.
    var pendingMoveAnt: IntArray? = null

    // Factor ids woken when an atom literal becomes false: positive literal at `atomId*2`, negative at
    // `atomId*2+1`. Array-indexed to avoid a boxed hash probe per wake on the BCP hot path.
    val watchersByLit: ArrayList<IntArrayList?> = ArrayList()

    // Dense by int-var id so the post-tighten wake path pays no boxed hash probe.
    val byIntVar: Array<VarAtomIndex?> = arrayOfNulls(numIntVars)

    // Thresholds some factor watches, registered by `markAtomWatched`.
    val watchedByVar: Array<VarAtomIndex?> = arrayOfNulls(numIntVars)

    val dirtyFactors: IntArrayDeque = IntArrayDeque(initialCapacity = 8)

    // Prior truth/level/antecedents of each atom change, so atom assignment backtracks on its own even when
    // a clause forced the literal without a domain move.
    val undoAtomId: IntArrayList = IntArrayList()
    val undoTruth: IntArrayList = IntArrayList()
    val undoLvl: IntArrayList = IntArrayList()
    val undoAnt: ArrayList<IntArray?> = ArrayList()
}
