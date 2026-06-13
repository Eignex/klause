package com.eignex.klause.formats.flatzinc

import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.BacktrackSolver
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Brute-force equivalence for the constraints that are no longer native factors and are
 * lowered to primitives at the FlatZinc emit site (all_equal, increasing/decreasing, member,
 * alldifferent_except_0, value_precede). Each ingests a small model and asserts the enumerated
 * solution set (projected onto the named int vars) equals the brute-force solution set, so the
 * decomposition is neither over- nor under-constrained.
 */
class FznDecomposedGlobalsTest {

    /** Enumerate all solutions of [src], projecting each onto the values of [vars] in order. */
    private fun enumerate(src: String, vars: List<String>): Set<List<Int>> {
        val program = parseFlatZinc(src)
        val ids = vars.map { program.intVarsByName.getValue(it) }
        return BacktrackSolver(program.problem).enumerate(BacktrackParams(randomSeed = 0L))
            .map { s -> ids.map { s.ints[it] } }
            .toHashSet()
    }

    /** Brute-force tuples over `0..hi` of arity [n] satisfying [pred]. */
    private fun brute(n: Int, hi: Int, pred: (List<Int>) -> Boolean): Set<List<Int>> {
        val out = HashSet<List<Int>>()
        val acc = IntArray(n)
        fun rec(i: Int) {
            if (i == n) {
                if (pred(acc.toList())) {
                    out.add(acc.toList())
                }
                return
            }
            for (v in 0..hi) {
                acc[i] = v
                rec(i + 1)
            }
        }
        rec(0)
        return out
    }

    private val names = listOf("x1", "x2", "x3")
    private fun decl(hi: Int) = "var 0..$hi: x1; var 0..$hi: x2; var 0..$hi: x3;\n"

    @Test
    fun `all_equal matches brute force`() {
        val found = enumerate(decl(2) + "constraint all_equal_int([x1, x2, x3]);\nsolve satisfy;", names)
        assertEquals(brute(3, 2) { it[0] == it[1] && it[1] == it[2] }, found)
    }

    @Test
    fun `increasing matches brute force`() {
        val found = enumerate(decl(2) + "constraint increasing_int([x1, x2, x3]);\nsolve satisfy;", names)
        assertEquals(brute(3, 2) { it[0] <= it[1] && it[1] <= it[2] }, found)
    }

    @Test
    fun `strictly increasing matches brute force`() {
        val found = enumerate(decl(2) + "constraint strictly_increasing_int([x1, x2, x3]);\nsolve satisfy;", names)
        assertEquals(brute(3, 2) { it[0] < it[1] && it[1] < it[2] }, found)
    }

    @Test
    fun `member matches brute force`() {
        // y = x3 must equal one of x1, x2.
        val found = enumerate(decl(2) + "constraint member_int([x1, x2], x3);\nsolve satisfy;", names)
        assertEquals(brute(3, 2) { it[2] == it[0] || it[2] == it[1] }, found)
    }

    @Test
    fun `alldifferent_except_0 matches brute force`() {
        val found = enumerate(decl(2) + "constraint alldifferent_except_0([x1, x2, x3]);\nsolve satisfy;", names)
        assertEquals(
            brute(3, 2) { t ->
                val nz = t.filter { it != 0 }
                nz.size == nz.toSet().size
            },
            found,
        )
    }

    @Test
    fun `value_precede matches brute force`() {
        // value 1 may appear only after value 2 has appeared earlier (s=2 precedes t=1).
        val found = enumerate(decl(2) + "constraint value_precede_int(2, 1, [x1, x2, x3]);\nsolve satisfy;", names)
        assertEquals(brute(3, 2) { t -> precedes(t, s = 2, target = 1) }, found)
    }

    @Test
    fun `value_precede_chain matches brute force`() {
        // Chain [0,1,2]: first occurrences must appear in the order 0, then 1, then 2.
        val found = enumerate(
            decl(2) + "constraint value_precede_chain_int([0, 1, 2], [x1, x2, x3]);\nsolve satisfy;",
            names,
        )
        val chain = intArrayOf(0, 1, 2)
        assertEquals(
            brute(3, 2) { t -> (0 until chain.size - 1).all { precedes(t, s = chain[it], target = chain[it + 1]) } },
            found,
        )
    }

    /** True iff in [t] the first occurrence of [target] (if any) comes after some earlier [s]. */
    private fun precedes(t: List<Int>, s: Int, target: Int): Boolean {
        var seenS = false
        for (v in t) {
            if (v == target && !seenS) return false
            if (v == s) seenS = true
        }
        return true
    }
}
