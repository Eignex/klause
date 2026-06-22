package com.eignex.klause.solver.factor.bool

import com.eignex.klause.solver.EmptyIntArray
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.FactorKind
import com.eignex.klause.solver.Invariant
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Propagator
import com.eignex.klause.solver.StructuralKey
import com.eignex.klause.solver.factor.litVars
import com.eignex.klause.solver.factor.remapLits

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
class Clause(val literals: IntArray) : Factor {

    init {
        require(literals.isNotEmpty()) { "Clause must have at least one literal" }
    }

    override fun structuralKey(): StructuralKey = StructuralKey.of(FactorKind.CLAUSE) { sortedInts(literals) }

    override fun remap(boolMap: IntArray, intMap: IntArray): Factor = Clause(literals.remapLits(boolMap))

    override val boolVars: IntArray = literals.litVars()
    override val intVars: IntArray = EmptyIntArray

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

    override fun asPropagator(): Propagator = ClausePropagator(boolVars, intVars, literals)

    override fun asInvariant(): Invariant = ClauseInvariant(boolVars, literals)
}
