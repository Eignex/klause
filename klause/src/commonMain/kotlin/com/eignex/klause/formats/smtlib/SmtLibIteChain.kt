package com.eignex.klause.formats.smtlib

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.table.Element
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.lowering.LinComb

/** Fewest arms worth reading as a table; below it the chain is a decision tree (see [IteChain]). */
private const val MIN_CHAIN_DEPTH = 16

/** Widest array the collapse will build — the propagator's per-value support walk stops above this. */
private const val MAX_CHAIN_SPAN = 4096L

/** Array positions allowed per arm; beyond this the constants are too sparse to be a table. */
private const val SPAN_PER_ARM = 8L

/** A reified `variable = value` atom, recorded so a chain condition can be read back off its literal. */
internal class EqAtom(val variable: Int, val value: Long)

/**
 * One `ite`-on-equality chain being accumulated, defining `result` as
 * `if selector = keys(0) then arms(0) elif … else default`.
 *
 * A term `(ite (= i k0) v0 (ite (= i k1) v1 … d))` over a single selector `i` with distinct constants
 * `k` is a table lookup written as a decision list. Reading it back as the extensional relation
 * `result = arr(i)` is the case-analysis-to-`element` reformulation (Van Hentenryck and Carillon,
 * *Generality versus Specificity: an Experience with AI and OR Techniques*, AAAI 1988): the chain
 * infers `result` from `i` only by traversing one reified equality per arm, where the extensional form
 * does it in a single propagation step.
 *
 * Both forms are functional *definitions* of a fresh quantity — neither is a reified constraint — so
 * the substitution is valid inside any surrounding boolean structure, and posting it is independent of
 * where the term occurred.
 *
 * Two properties make the collapse sound:
 *  - the arm constants are distinct, so the arms are mutually exclusive and their decision-list order
 *    carries no information;
 *  - [Element] additionally forces `i` into the array's index range, which the chain does not — so the
 *    array must span the selector's **whole** domain, not just the arm constants' range. Index values
 *    with no arm take the chain's default, written into those positions: a constant default directly,
 *    an identity default (`d` is the selector) as the position's own index value, which is exact
 *    because `idx = i` selects position `i`.
 *
 * Below [MIN_CHAIN_DEPTH] arms the extensional form is the *worse* encoding, not merely a costlier one:
 * short chains in practice are sparse decision trees whose default is a further test on a different
 * selector, so their constants spread far beyond the arm count and the dense array is mostly fill. The
 * span conditions express the same applicability judgement — an array wider than [MAX_CHAIN_SPAN] also
 * loses the propagator's value walk, and with it the domain consistency the collapse is for.
 *
 * [result] is shared by every level of the chain: an interior level's value is consumed only by the
 * level that extends it, so re-using the variable (widening its domain per arm) leaves no undefined
 * interior variables behind, whichever lowering the chain ends up with.
 */
internal class IteChain(val result: Int, val selector: Int, val default: LinComb) {
    val keys = ArrayList<Long>()
    val arms = ArrayList<LinComb>()

    /** Positive-form condition literals, parallel to [keys]; `conds(i)` holds iff `selector = keys(i)`. */
    val conds = ArrayList<Int>()
    private val distinct = HashSet<Long>()

    /** Append an arm, or decline when [key] repeats — a repeat would make the arms non-exclusive. */
    fun addArm(key: Long, arm: LinComb, cond: Int): Boolean {
        if (!distinct.add(key)) return false
        keys.add(key)
        arms.add(arm)
        conds.add(cond)
        return true
    }
}

/**
 * The chains still open for extension, keyed by their result variable, plus the reified equality atoms
 * their conditions are read from. Insertion-ordered so lowering follows source order.
 */
internal class IteChainTable {
    private val atoms = HashMap<Int, EqAtom>()
    private val chains = LinkedHashMap<Int, IteChain>()
    private val fixedVars = HashMap<Long, Int>()

    fun noteAtom(lit: Int, variable: Int, value: Long) {
        atoms[lit] = EqAtom(variable, value)
    }

    fun atomOf(lit: Int): EqAtom? = atoms[lit]

    fun open(chain: IteChain) {
        chains[chain.result] = chain
    }

    fun openAt(variable: Int): IteChain? = chains[variable]

    /** Remove and return the chain defining [variable], if any — it can no longer be extended. */
    fun close(variable: Int): IteChain? = chains.remove(variable)

    /** Remove and return every chain still open. */
    fun drain(): List<IteChain> {
        val out = chains.values.toList()
        chains.clear()
        return out
    }

    /** A variable pinned to [value], shared across every array that needs that constant as an entry. */
    fun fixedVar(value: Long, fresh: (Long) -> Int): Int = fixedVars.getOrPut(value) { fresh(value) }
}

/**
 * Fold `(ite cond thenTerm elseTerm)` into a chain, returning the chain's result term, or null when the
 * condition is not an equality on a finite-domain variable (the caller then lowers the `ite` directly).
 */
internal fun SmtLib.Builder.chainIte(cond: Int, thenTerm: LinComb, elseTerm: LinComb): LinComb? {
    // `(ite (not (= i k)) A B)` is the positive chain step `(ite (= i k) B A)`.
    val negated = iteChains.atomOf(cond) == null
    val atomLit = if (negated) Lit.negate(cond) else cond
    val armTerm = if (negated) elseTerm else thenTerm
    val restTerm = if (negated) thenTerm else elseTerm
    val atom = iteChains.atomOf(atomLit) ?: return null
    val extended = restTerm.asSimpleVar()?.let { iteChains.openAt(it) }
    if (extended != null && extended.selector == atom.variable && extended.addArm(atom.value, armTerm, atomLit)) {
        widenToInclude(extended.result, armTerm)
        return LinComb(mapOf(extended.result to 1), 0)
    }
    val (armLo, armHi) = linCombRange(armTerm)
    val (restLo, restHi) = linCombRange(restTerm)
    val chain = IteChain(
        newInt(nullableMin(armLo, restLo), nullableMax(armHi, restHi)),
        atom.variable,
        restTerm,
    )
    chain.addArm(atom.value, armTerm, atomLit)
    iteChains.open(chain)
    return LinComb(mapOf(chain.result to 1), 0)
}

/** Lower the chain defining [variable], if one is open — called once the value escapes into a binding,
 *  where a further arm can no longer reach it. */
internal fun SmtLib.Builder.closeIteChain(variable: Int) {
    iteChains.close(variable)?.let { lowerIteChain(it) }
}

/** Lower every chain that never escaped into a binding. */
internal fun SmtLib.Builder.lowerOpenIteChains() {
    for (chain in iteChains.drain()) lowerIteChain(chain)
}

private fun SmtLib.Builder.lowerIteChain(chain: IteChain) {
    if (!collapseToElement(chain)) lowerAsDecisionList(chain)
}

/**
 * The uncollapsed lowering: one implication per arm plus the default's, over the shared result
 * variable. The arms are mutually exclusive (distinct constants on one selector), so the decision
 * list's priority order carries no information and each arm can be posted independently.
 */
private fun SmtLib.Builder.lowerAsDecisionList(chain: IteChain) {
    val self = LinComb(mapOf(chain.result to 1), 0)
    for (i in chain.keys.indices) {
        factors.add(Clause(intArrayOf(Lit.negate(chain.conds[i]), reifyEq(self, chain.arms[i]))))
    }
    val noArm = chain.conds.toIntArray() + reifyEq(self, chain.default)
    factors.add(Clause(noArm))
}

/** Post the chain as one [Element] over the selector's whole domain, or decline. */
private fun SmtLib.Builder.collapseToElement(chain: IteChain): Boolean {
    if (chain.keys.size < MIN_CHAIN_DEPTH) return false
    val domain = (intDomains[chain.selector] as? PresolveDomain.Finite)?.domain ?: return false
    val lo = domain.min
    val hi = domain.max
    if (lo < Int.MIN_VALUE || hi > Int.MAX_VALUE) return false
    val span = hi - lo + 1
    if (span > MAX_CHAIN_SPAN || span > SPAN_PER_ARM * chain.keys.size) return false
    val fill = chain.default.takeIf { it.coeffs.isEmpty() }?.constant
    if (fill == null && chain.default.asSimpleVar() != chain.selector) return false
    // CP holds the whole global, not just the column it indexes, so every column the [Element] names has
    // to be finite. With an open result or arm the chain stays as rows a theory can decide.
    if (intDomains[chain.result] is PresolveDomain.Open) return false
    if (chain.arms.any { arm -> arm.asSimpleVar()?.let { intDomains[it] is PresolveDomain.Open } == true }) {
        return false
    }
    // A key outside the selector's domain names an unreachable position, so its arm is dropped rather
    // than widening the array to hold a case that cannot occur.
    val reachable = chain.keys.indices.filter { chain.keys[it] in lo..hi }
    val arrIsVars = reachable.any { chain.arms[it].coeffs.isNotEmpty() }
    val size = span.toInt()
    val arr = if (arrIsVars) {
        LongArray(size) { entryVar(fill ?: (lo + it)).toLong() }
    } else {
        LongArray(size) { fill ?: (lo + it) }
    }
    for (i in reachable) {
        arr[(chain.keys[i] - lo).toInt()] = if (arrIsVars) armVar(chain.arms[i]).toLong() else chain.arms[i].constant
    }
    factors.add(Element(chain.selector, chain.result, arr, arrIsVars, lo.toInt()))
    return true
}

/** The variable holding a constant array entry — one per distinct value, shared across every chain. */
private fun SmtLib.Builder.entryVar(value: Long): Int = iteChains.fixedVar(value) { newInt(it, it) }

/** The variable holding an arm's value: the term itself when it is a bare variable, a shared pinned
 *  variable when it is constant, else a fresh one equated to the term. */
private fun SmtLib.Builder.armVar(arm: LinComb): Int {
    arm.asSimpleVar()?.let { return it }
    if (arm.coeffs.isEmpty()) return entryVar(arm.constant)
    val (lo, hi) = linCombRange(arm)
    val v = newInt(lo, hi)
    postLinearRel(LinComb(mapOf(v to 1), 0), arm, LinearOp.EQ)
    return v
}

/** Widen [variable]'s domain to also cover [term]'s range — the chain result takes every arm's value. */
private fun SmtLib.Builder.widenToInclude(variable: Int, term: LinComb) {
    val (termLo, termHi) = linCombRange(term)
    val curLo: Long?
    val curHi: Long?
    when (val current = intDomains[variable]) {
        is PresolveDomain.Finite -> {
            curLo = current.domain.min
            curHi = current.domain.max
        }

        is PresolveDomain.Open -> {
            curLo = current.lo
            curHi = current.hi
        }
    }
    intDomains[variable] = openOrFinite(nullableMin(curLo, termLo), nullableMax(curHi, termHi))
}

/** Minimum of two bounds where null is infinity, so an open side stays open. */
private fun nullableMin(a: Long?, b: Long?): Long? = if (a == null || b == null) null else minOf(a, b)

/** Maximum of two bounds where null is infinity, so an open side stays open. */
private fun nullableMax(a: Long?, b: Long?): Long? = if (a == null || b == null) null else maxOf(a, b)
