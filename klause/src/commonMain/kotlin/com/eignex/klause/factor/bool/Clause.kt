package com.eignex.klause.factor.bool

import com.eignex.klause.factor.litVars
import com.eignex.klause.ir.BoolVars
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.FactorKind
import com.eignex.klause.ir.IntegerConstants
import com.eignex.klause.ir.KeySink
import com.eignex.klause.ir.LinearForm
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.LinearRow
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.StructuralKey
import com.eignex.klause.ir.Term
import com.eignex.klause.ir.UnitConsts
import com.eignex.klause.ir.VarList
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.ir.hashRemappedKey
import com.eignex.klause.ir.materializeKey
import com.eignex.klause.util.IntHashSet
import kotlin.concurrent.Volatile

/**
 * Disjunction of Boolean literals.
 *
 * Uses two-watched-literal scheme (Zhang–Stickel 1996). Two indices
 * into [literals] are watched at any time; the clause is satisfied iff at least one watched
 * literal evaluates to true. When an accepted flip turns a watched literal false, we scan the
 * unwatched literals for a true one to rewatch, finishing in O(1) amortized when most flips
 * leave the watches alone. Single-literal clauses have only one watch (`w2 = -1`).
 *
 * Repeated literals are folded at construction. A variable may occur with both polarities, in
 * which case the clause is tautological.
 */
class Clause(literals: IntArray) :
    Factor,
    LinearRow {

    // Factors may outlive a frontend's scratch buffer.
    val literals: IntArray = distinctLiterals(literals)

    internal val tautological: Boolean = hasComplementaryLiterals(this.literals)

    init {
        require(literals.isNotEmpty()) { "Clause must have at least one literal" }
    }

    override fun structuralKey(): StructuralKey = materializeKey(FactorKind.CLAUSE, ::buildKey)

    // Allocation-free per-incidence key hash via the two-mode [KeySink]: symmetry refinement rebuilds
    // this once per incident variable each round, so avoiding the remapped clause + its key matters.
    override fun remapStructuralHash(mapping: VarRemap): Int = hashRemappedKey(FactorKind.CLAUSE, mapping, ::buildKey)

    private fun buildKey(sink: KeySink) {
        sink.sortedBoolLits(literals)
    }

    override fun remap(mapping: VarRemap): Factor = Clause(mapping.lits(literals))

    override val variables: VarList = BoolVars(literals.litVars())

    /**
     * CP-only tri-state cache: −1 unknown, 0 contains atom literals, 1 contains only Boolean variables.
     * A pure-Boolean clause fires at the current decision level, allowing dispatch to skip its level
     * scan. Atom literals may fire below that level and still require the scan. Fixed per Problem
     * and valid across learned-clause forgetting/remapping; unused by local search.
     */
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

    // The clause *is* its own exact linear row `Σ literals ≥ 1`, read by presolve with no allocation.
    override val relation: LinearOp get() = LinearOp.GE
    override val bound: Long get() = 1L
    override val size: Int get() = literals.size
    override fun ref(k: Int): Int = Term.ofLit(literals[k])
    override fun coeff(k: Int): Long = 1L
    override val isIntegerOnly: Boolean get() = false

    @Volatile
    private var cachedConstants: IntegerConstants? = null

    @Volatile
    private var cachedForm: LinearForm? = null

    override val constants: IntegerConstants
        get() = cachedConstants ?: IntegerConstants(UnitConsts(size), 1L).also { cachedConstants = it }
    override val linearForm: LinearForm
        get() = cachedForm ?: LinearForm.Conjunction(listOf(this)).also { cachedForm = it }

    private companion object {
        fun distinctLiterals(literals: IntArray): IntArray {
            val seen = IntHashSet(literals.size)
            var size = 0
            val copy = IntArray(literals.size)
            for (literal in literals) {
                if (seen.add(literal)) copy[size++] = literal
            }
            return copy.copyOf(size)
        }

        fun hasComplementaryLiterals(literals: IntArray): Boolean {
            val seen = IntHashSet(literals.size)
            for (literal in literals) {
                if (Lit.negate(literal) in seen) return true
                seen.add(literal)
            }
            return false
        }
    }
}
