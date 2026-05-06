package com.eignex.klause.cnf

import com.eignex.klause.solver.Lit

/**
 * Result of bit-blasting a [com.eignex.klause.solver.Problem] to propositional CNF.
 *
 * - [numVars] is the total number of CNF variables (Boolean + bit-encoded integer + Tseitin aux).
 * - [clauses] are MiniSAT-encoded; index into [clauses][i][j] yields a literal where
 *   `lit ushr 1` is the variable id and `lit and 1` is the negation flag.
 * - [boolVarToCnfVar] maps an original Boolean variable id to its CNF variable id.
 * - [intVarBits] gives, for each original integer variable, the LSB-first array of CNF
 *   variable ids that encode its offset from [intVarMin].
 *
 * `decodeBool` and `decodeInt` lift a CNF model (a `BooleanArray` of size [numVars] indexed
 * by CNF variable id) back to original-problem values.
 */
class CnfProblem(
    val numVars: Int,
    val clauses: List<IntArray>,
    val boolVarToCnfVar: IntArray,
    val intVarBits: Array<IntArray>,
    val intVarMin: IntArray,
) {
    fun decodeBool(originalBoolVar: Int, model: BooleanArray): Boolean =
        model[boolVarToCnfVar[originalBoolVar]]

    fun decodeInt(originalIntVar: Int, model: BooleanArray): Int {
        val bits = intVarBits[originalIntVar]
        var v = 0
        for (i in bits.indices) {
            if (model[bits[i]]) v = v or (1 shl i)
        }
        return intVarMin[originalIntVar] + v
    }

    /** DIMACS CNF serialization. Empty clauses (compile-time false) are emitted as `0`. */
    fun toDimacs(): String {
        val sb = StringBuilder()
        sb.append("p cnf ").append(numVars).append(' ').append(clauses.size).append('\n')
        for (clause in clauses) {
            for (lit in clause) {
                val v = Lit.variable(lit) + 1
                sb.append(if (Lit.isPositive(lit)) v else -v).append(' ')
            }
            sb.append("0\n")
        }
        return sb.toString()
    }
}
