package com.eignex.klause.solver.factor.bool

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.factor.litVars
import com.eignex.klause.solver.factor.remapLits
import com.eignex.klause.util.IntIntMap

/**
 * Disjunction of Boolean literals.
 *
 * Uses two-watched-literal scheme (Zhang–Stickel 1996, ported to local search). Two indices
 * into [literals] are watched at any time; the clause is satisfied iff at least one watched
 * literal evaluates to true. When an accepted flip turns a watched literal false, we scan the
 * unwatched literals for a true one to rewatch, finishing in O(1) amortized when most flips
 * leave the watches alone. Single-literal clauses have only one watch (`w2 = -1`).
 *
 * Tautologies (a variable appearing as both `+v` and `-v`) are detected at construction; we
 * pick those two indices as the watches and the clause is permanently satisfied.
 */
class Clause(override val literals: IntArray) :
    Factor,
    ClausePropagator,
    ClauseInvariant {

    init {
        require(literals.isNotEmpty()) { "Clause must have at least one literal" }
    }

    override fun structuralKey(): String = "clause:" + literals.sorted().joinToString(",")

    override fun remap(boolMap: IntArray, intMap: IntArray): Factor = Clause(literals.remapLits(boolMap))

    override val boolVars: IntArray = literals.litVars()
    override val intVars: IntArray = EmptyIntArray

    /** Initial two-watched-literal wakeup positions. Unit clauses watch their single
     *  literal so they fire when it becomes false; longer clauses watch literals[0] and
     *  literals[1] to start. The CP engine routes per-literal wakeups through this set
     *  via [com.eignex.klause.solver.propagation.PropagationState.boolWatchersByLit]; as
     *  watches drift during propagation, [propagate] keeps the index in sync by calling
     *  `state.moveBoolWatcher`. */
    override val initialBoolWatchers: IntArray =
        if (literals.size == 1) {
            intArrayOf(literals[0])
        } else {
            intArrayOf(literals[0], literals[1])
        }

    /** Blocking literal for each initial watch (#200): the *other* watched literal. A clause
     *  is satisfied by any single true literal, so if the partner watch is true the engine
     *  can skip waking this clause when the watched literal goes false. A unit clause has no
     *  partner, so no blockers. Kept in sync as watches drift via the `blocker` argument to
     *  [PropagationState.moveBoolWatcher]. */
    override val initialBoolWatcherBlockers: IntArray? =
        if (literals.size == 1) null else intArrayOf(literals[1], literals[0])

    /** Pre-computed `boolVar → literal index` lookup. Cheap to materialise once at
     *  construction; turns the per-flip "find my literal" loop into a hash lookup. The
     *  compile path doesn't generate clauses where a var appears multiple times (`v` and
     *  `¬v` together would be a tautology and gets dropped). Sentinel `-1` for absent. */
    private val litIndexByVar: IntIntMap = IntIntMap.build(
        keys = IntArray(literals.size) { Lit.variable(literals[it]) },
        values = IntArray(literals.size) { it },
        absent = -1,
    )

    /** CP-only memo: are all literals plain bool vars (no atom-lits)? Encoded as a primitive
     *  tri-state (−1 unknown / 0 no / 1 yes) rather than a boxed `Boolean?`, since this is read
     *  once per clause fire on the BCP hot path and a boxed read costs a load + null-check +
     *  unbox each time. A pure-bool clause only ever fires when a watched bool literal just went
     *  false at the *current* decision level, so its effective level is exactly the current
     *  decision level — letting the propagation dispatch skip the per-fire level scan. Atom-lit
     *  clauses can fire on an atom that flipped at a sub-decision level, so they still need the
     *  scan. Intrinsic to the clause (numBoolVars is fixed per Problem), so it's valid across
     *  learned-clause forget/remap. Unused by the local-search path. */
    private var pureBoolMemo: Int = -1

    /** True iff every literal is a plain bool var (variable id `< numBoolVars`), memoised. */
    fun allLiteralsBool(numBoolVars: Int): Boolean {
        val m = pureBoolMemo
        if (m >= 0) return m == 1
        var allBool = true
        for (lit in literals) {
            if (Lit.variable(lit) >= numBoolVars) {
                allBool = false
                break
            }
        }
        pureBoolMemo = if (allBool) 1 else 0
        return allBool
    }

    override fun litIndexForVar(v: Int): Int = litIndexByVar[v]
}
