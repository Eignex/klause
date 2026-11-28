package com.eignex.klause.formats.smtlib

import com.eignex.klause.factor.table.Element
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.lowering.LinComb
import com.eignex.util.collections.LongHashSet
import com.eignex.util.collections.MutableLongIntMap

private const val MIN_CHAIN_DEPTH = 16

// The propagator's per-value support walk stops above this span.
private const val MAX_CHAIN_SPAN = 4096L

private const val SPAN_PER_ARM = 8L

internal class EqAtom(val variable: Int, val value: Long, val allowsElement: Boolean)

internal class GuardEquality(val variable: Int, val value: Long, val equal: Boolean) {
    fun truthWhen(variable: Int?, value: Long): Boolean? =
        if (this.variable == variable) (this.value == value) == equal else null
}

// `(ite (= i k0) v0 (ite (= i k1) v1 … d))` with distinct constants is a table lookup written as a decision
// list; reading it as `result = arr(i)` is the case-analysis-to-`element` reformulation (Van Hentenryck and
// Carillon, *Generality versus Specificity: an Experience with AI and OR Techniques*, AAAI 1988,
// https://cdn.aaai.org/AAAI/1988/AAAI88-117.pdf), which propagates in one step instead of one per arm.
//
// Both forms define a fresh quantity, so the substitution is valid under any Boolean structure. Distinct keys
// make the arms exclusive. Element also forces `i` into the index range, so the array spans the selector's whole
// domain and positions with no arm hold the default. [result] is shared by every level, since an interior
// level's value is consumed only by the level extending it.
internal class IteChain(val result: Int, val selector: Int, val default: LinComb) {
    val keys = ArrayList<Long>()
    val arms = ArrayList<LinComb>()

    // `conds(i)` holds iff `selector = keys(i)`.
    val conds = ArrayList<Int>()
    private val distinct = LongHashSet()
    var allowsElement = true
        private set

    // A repeated key would make the arms non-exclusive.
    fun addArm(key: Long, arm: LinComb, cond: Int, allowsElement: Boolean): Boolean {
        if (!distinct.add(key)) return false
        keys.add(key)
        arms.add(arm)
        conds.add(cond)
        this.allowsElement = this.allowsElement && allowsElement
        return true
    }
}

// Insertion-ordered so lowering follows source order.
internal class IteChainTable {
    private val atoms = HashMap<Int, EqAtom>()
    private val chains = LinkedHashMap<Int, IteChain>()
    private val fixedVars = MutableLongIntMap()

    fun noteAtom(lit: Int, variable: Int, value: Long, allowsElement: Boolean = true) {
        atoms[lit] = EqAtom(variable, value, allowsElement)
    }

    fun atomOf(lit: Int): EqAtom? = atoms[lit]

    fun guardEquality(lit: Int): GuardEquality? {
        atoms[lit]?.let { return GuardEquality(it.variable, it.value, equal = true) }
        atoms[Lit.negate(lit)]?.let { return GuardEquality(it.variable, it.value, equal = false) }
        return null
    }

    fun open(chain: IteChain) {
        chains[chain.result] = chain
    }

    fun openAt(variable: Int): IteChain? = chains[variable]

    fun branchUnder(variable: Int, knownLiteral: Int): LinComb? {
        val chain = chains[variable] ?: return null
        val guard = chain.conds.singleOrNull() ?: return null
        return when (knownLiteral) {
            guard -> chain.arms.single()
            Lit.negate(guard) -> chain.default
            else -> null
        }
    }

    fun close(variable: Int): IteChain? = chains.remove(variable)

    fun drain(): List<IteChain> {
        val out = chains.values.toList()
        chains.clear()
        return out
    }

    fun fixedVar(value: Long, fresh: (Long) -> Int): Int {
        val existing = fixedVars.getOrDefault(value, -1)
        if (existing >= 0) return existing
        val variable = fresh(value)
        fixedVars.put(value, variable)
        return variable
    }
}

// Null when the condition is not a recorded equality atom; the caller then lowers the `ite` directly.
internal fun Compiler.Builder.chainIte(cond: Int, thenTerm: LinComb, elseTerm: LinComb): LinComb? {
    // `(ite (not (= i k)) A B)` is the positive chain step `(ite (= i k) B A)`.
    val negated = iteChains.atomOf(cond) == null
    val atomLit = if (negated) Lit.negate(cond) else cond
    val armTerm = if (negated) elseTerm else thenTerm
    val restTerm = if (negated) thenTerm else elseTerm
    val atom = iteChains.atomOf(atomLit) ?: return null
    val extended = restTerm.asSimpleVar()?.let { iteChains.openAt(it) }
    if (extended != null && extended.selector == atom.variable &&
        extended.addArm(atom.value, armTerm, atomLit, atom.allowsElement)
    ) {
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
    chain.addArm(atom.value, armTerm, atomLit, atom.allowsElement)
    iteChains.open(chain)
    return LinComb(mapOf(chain.result to 1), 0)
}

internal fun Compiler.Builder.closeIteChain(variable: Int) {
    iteChains.close(variable)?.let { lowerIteChain(it) }
}

internal fun Compiler.Builder.lowerOpenIteChains() {
    for (chain in iteChains.drain()) lowerIteChain(chain)
}

private fun Compiler.Builder.lowerIteChain(chain: IteChain) {
    val start = factors.size
    val parts = if (collapseToElement(chain)) factors.subList(start, factors.size).toList()
    else exclusiveIntegerSelection(chain.result, chain.conds, chain.arms, chain.default)
    conditionalEqualities.define(
        chain.result, chain.conds, chain.arms, chain.default, parts,
        chain.keys.map { GuardEquality(chain.selector, it, equal = true) },
    )
}

private fun Compiler.Builder.collapseToElement(chain: IteChain): Boolean {
    if (!chain.allowsElement) return false
    if (chain.keys.size < MIN_CHAIN_DEPTH) return false
    val domain = (intDomains[chain.selector] as? PresolveDomain.Finite)?.domain ?: return false
    val lo = domain.min
    val hi = domain.max
    if (lo < Int.MIN_VALUE || hi > Int.MAX_VALUE) return false
    val span = hi - lo + 1
    if (span > MAX_CHAIN_SPAN || span > SPAN_PER_ARM * chain.keys.size) return false
    val fill = chain.default.takeIf { it.coeffs.isEmpty() }?.constant
    if (fill == null && chain.default.asSimpleVar() != chain.selector) return false
    // CP holds the whole global, so every column it names must be finite; otherwise the chain stays as rows a
    // theory can decide.
    if (intDomains[chain.result] is PresolveDomain.Open) return false
    if (chain.arms.any { arm -> arm.asSimpleVar()?.let { intDomains[it] is PresolveDomain.Open } == true }) {
        return false
    }
    // A key outside the selector's domain is unreachable, so its arm is dropped.
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

private fun Compiler.Builder.entryVar(value: Long): Int = iteChains.fixedVar(value) { newInt(it, it) }

private fun Compiler.Builder.armVar(arm: LinComb): Int {
    arm.asSimpleVar()?.let { return it }
    if (arm.coeffs.isEmpty()) return entryVar(arm.constant)
    val (lo, hi) = linCombRange(arm)
    val v = newInt(lo, hi)
    postLinearRel(LinComb(mapOf(v to 1), 0), arm, LinearOp.EQ)
    return v
}

private fun Compiler.Builder.widenToInclude(variable: Int, term: LinComb) {
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

// Null is infinity, so an open side stays open.
private fun nullableMin(a: Long?, b: Long?): Long? = if (a == null || b == null) null else minOf(a, b)

private fun nullableMax(a: Long?, b: Long?): Long? = if (a == null || b == null) null else maxOf(a, b)
