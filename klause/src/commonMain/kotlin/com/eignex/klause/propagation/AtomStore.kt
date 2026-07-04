package com.eignex.klause.propagation

import com.eignex.klause.util.IntArrayDeque
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.MutableLongIntMap

/**
 * Bound-atom registry for [PropagationState] (LCG with virtual int-bound literals).
 *
 * An "atom" represents a fact like `[x ≥ k]` or `[x ≤ k]`. Each atom gets a virtual variable id
 * past the bool var space (`numBoolVars + atomId`), so atom *literals* — encoded with `Lit.make`
 * using that virtual id — slot into the same array structure the analyzer already understands.
 * Allocation is lazy: an atom only enters the registry when a factor first references it as an
 * antecedent or conflict-reason literal (`allocAtom` in `PropagationState`).
 *
 * Each materialized order literal carries the same trail metadata a bool var does: stored truth
 * ([truth]), the decision level it was established at ([lvl]) and the literal-form antecedents of
 * the force ([ant]) — stored at the moment the bound crosses the threshold rather than re-derived
 * from a bound-change history, and undone on backtrack alongside the int-domain change that set
 * them. The channeling and wake logic lives in `Atoms.kt`.
 */
internal class AtomStore(numIntVars: Int) {
    /** Atom id → int variable. Packed into a single long for the reverse lookup ([byKey]);
     *  stored separately here for fast iteration. */
    val intVar: IntArrayList = IntArrayList()

    /** The relational form of each atom, parallel to [intVar] / [threshold]. */
    val kind: ArrayList<AtomKind> = ArrayList()

    /** Threshold value `k` for the atom. */
    val threshold: IntArrayList = IntArrayList()

    /** Stored truth of this order literal — the canonical, BCP-cheap replacement for deriving it
     *  from the int domains on every clause touch (the #588 profile's dominant cost). 0 =
     *  unassigned, 1 = true, 2 = false. Set by `wakeAtom` the instant a bound move crosses the
     *  threshold (which is exactly when the truth flips), cleared to 0 on backtrack by
     *  `resetAtomTrailFor`. A 0 slot on a determined atom (one materialized *after* its bound
     *  already crossed) falls back to the domain-derived `atomTruthOf` — sound, just not cached. */
    val truth: IntArrayList = IntArrayList()

    /** Decision level at which this atom's current truth was established (-1 = none, i.e. truth
     *  undetermined or a root/bake fact). */
    val lvl: IntArrayList = IntArrayList()

    /** Literal-form antecedents of this atom's current truth (null = leaf / root). */
    val ant: ArrayList<IntArray?> = ArrayList()

    /** The reason of the bound move currently being channeled by `propagateAtomsForVar` — the
     *  literals whose conjunction forced it. `wakeAtom` stores it on each crossed atom's [ant]
     *  slot (the trail-resident reason, recorded at the atom's establishment level). */
    var pendingMoveAnt: IntArray? = null

    /** Reverse lookup: packed key `(intVar << 33) | (kind << 32) | (threshold + INT_MAX)`
     *  → atomId. Allows O(1) re-allocation checks. */
    val byKey: MutableLongIntMap = MutableLongIntMap()

    /** Per-atom-lit watcher list — factor ids that fire when this atom-lit transitions to false.
     *  Mirrors [BoolWatcherIndex.byLit] for atoms. Array-indexed by atom-lit (two slots per atom:
     *  positive at `atomId*2`, negative at `atomId*2+1`), grown two slots per `allocAtom`, so
     *  order literals get the same O(1) array access in the BCP hot path instead of a boxed-Int
     *  hash probe per wake. A null slot means "no watchers". */
    val watchersByLit: ArrayList<IntArrayList?> = ArrayList()

    /** For each int variable, the atoms whose truth depends on it — used to recompute atom truth
     *  and fire watchers after a successful tighten / exclude. Dense-indexed by the int-var id so
     *  the post-tighten wake path pays no boxed-Int hash probe; a null slot means "no atoms
     *  materialised for this var yet". */
    val byIntVar: Array<VarAtomIndex?> = arrayOfNulls(numIntVars)

    /** Per-var sorted thresholds that some factor actually watches (either polarity of the atom's
     *  literal). Bound moves wake watchers by walking only this index — the full atom table grows
     *  with every reason ever materialised, but only watched atoms need eager transition wakeups;
     *  everything else is derived on demand. Dense-indexed like [byIntVar]. */
    val watchedByVar: Array<VarAtomIndex?> = arrayOfNulls(numIntVars)

    /** Factor ids woken by atom-lit transitions during the current propagation step.
     *  Drained alongside dirty-int / dirty-bool processing in `runToFixpoint`. */
    val dirtyFactors: IntArrayDeque = IntArrayDeque(initialCapacity = 8)
}
