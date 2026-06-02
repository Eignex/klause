package com.eignex.klause.solver.count

import com.eignex.klause.cnf.BitBlaster
import com.eignex.klause.cnf.CnfProblem
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.factor.Clause

/**
 * Everything needed to count / sample one family of XOR-hash cells over a fixed projection of a
 * problem: the [problem] the solver actually enumerates, the bit ids the hashes range over
 * ([hashDomain]), and how to project / decode an enumerated model back to the original variables.
 *
 * A purely-Boolean projection runs natively over the original problem — no transformation, full
 * native propagation. When the projection includes integer variables, the problem is bit-blasted
 * once via [BitBlaster] and the hashes range over the chosen variables' CNF bits; counting/sampling
 * is then over distinct integer *values* (the Tseitin/bit encoding is functionally determined, so
 * projecting onto the original variables is exact). Built once per count/sample run and reused
 * across all hash families.
 */
internal class CellContext private constructor(
    val problem: Problem,
    val hashDomain: IntArray,
    private val cnf: CnfProblem?,
    private val boolSet: IntArray,
    private val intSet: IntArray,
    private val baseNumBoolVars: Int,
    private val baseNumIntVars: Int,
) {
    /** Projection key of an enumerated [model]: chosen Boolean values (0/1) then integer values. */
    fun projectionKey(model: Sample): List<Int> {
        val key = ArrayList<Int>(boolSet.size + intSet.size)
        if (cnf == null) {
            for (v in boolSet) key.add(if (model.bools[v]) 1 else 0)
        } else {
            for (v in boolSet) key.add(if (cnf.decodeBool(v, model.bools)) 1 else 0)
            for (v in intSet) key.add(cnf.decodeInt(v, model.bools))
        }
        return key
    }

    /** Decode an enumerated [model] back to an assignment over the original problem's variables. */
    fun decode(model: Sample): Sample {
        if (cnf == null) return model
        return Sample(
            bools = BooleanArray(baseNumBoolVars) { cnf.decodeBool(it, model.bools) },
            ints = IntArray(baseNumIntVars) { cnf.decodeInt(it, model.bools) },
        )
    }

    companion object {
        /**
         * Resolve the projection from a config's [boolSet]/[intSet] (see [ApproxCountConfig]) and build
         * the context. Both `null` projects over every variable; otherwise only the listed ones.
         */
        fun resolve(base: Problem, boolSet: IntArray?, intSet: IntArray?): CellContext {
            val bools: IntArray
            val ints: IntArray
            if (boolSet == null && intSet == null) {
                bools = base.allBoolVars()
                ints = IntArray(base.numIntVars) { it }
            } else {
                bools = boolSet ?: IntArray(0)
                ints = intSet ?: IntArray(0)
            }
            return build(base, bools, ints)
        }

        private fun build(base: Problem, boolSet: IntArray, intSet: IntArray): CellContext {
            if (intSet.isEmpty()) {
                return CellContext(base, boolSet, cnf = null, boolSet, intSet, base.numBoolVars, base.numIntVars)
            }
            val cnf = BitBlaster.compile(base)
            val cnfProblem = Problem(
                numBoolVars = cnf.numVars,
                numIntVars = 0,
                intDomains = emptyArray(),
                factors = cnf.clauses.map { Clause(it) as Factor },
            )
            val domain = ArrayList<Int>(boolSet.size + intSet.size * 4)
            for (v in boolSet) domain.add(cnf.boolVarToCnfVar[v])
            for (v in intSet) for (bit in cnf.intVarBits[v]) domain.add(bit)
            return CellContext(cnfProblem, domain.toIntArray(), cnf, boolSet, intSet, base.numBoolVars, base.numIntVars)
        }
    }
}
