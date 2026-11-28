package com.eignex.klause.propagation

import com.eignex.klause.ir.Factor
import com.eignex.util.collections.IntArrayList
import com.eignex.util.collections.IntHashSet

// Append-only store for factors added during an incremental presolve bake. A presolve state never learns and a
// search state never adds presolve factors, so this and [LearnedClauseDb.store] share the tail id range. A
// tombstoned id is never reused: reuse would resurrect a stale propagator payload under a new factor and
// silently corrupt a deduction.
internal class MidlifeFactors {
    val store: ArrayList<Propagator> = ArrayList()

    // [Propagator] exposes no variables, so the conflict and level machinery reads them here.
    val factors: ArrayList<Factor> = ArrayList()

    // A tombstoned factor reads as [NoPropagator], so it is inert without leaving the watcher indices.
    val tombstoned: IntHashSet = IntHashSet()

    // Occurrence-list wakeup for mid-life factors without per-literal watchers; null until the first such factor,
    // so a search state pays a single null check.
    var boolOccurrences: Array<IntArrayList>? = null

    var intOccurrences: Array<IntArrayList>? = null
}
