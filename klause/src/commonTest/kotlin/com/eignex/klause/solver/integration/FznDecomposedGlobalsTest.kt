package com.eignex.klause.solver.integration

import com.eignex.klause.backtrack.BacktrackParams
import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.global.AllDifferent
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.lowering.flatzinc.parseFlatZinc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Brute-force equivalence for globals handled at the FlatZinc emit site. Most are lowered to
 * primitives (all_equal, increasing/decreasing, member, value_precede); alldifferent_except_0 is
 * dispatched to the native AllDifferent factor with an excepted-value set (#433). Each ingests a
 * small model and asserts the enumerated solution set (projected onto the named int vars) equals the
 * brute-force solution set, so the handling is neither over- nor under-constrained.
 */
class FznDecomposedGlobalsTest {

    /** Enumerate all solutions of [src], projecting each onto the values of [vars] in order. */
    private fun enumerate(src: String, vars: List<String>): Set<List<Int>> {
        val program = parseFlatZinc(src)
        val ids = vars.map { program.intVarsByName.getValue(it) }
        return BacktrackSolver(program.problem.bake()).enumerate(BacktrackParams(randomSeed = 0L))
            .map { s -> ids.map { s.ints[it].toInt() } }
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
    fun `all_different over a value span beyond Int range lowers to pairwise not-equal`() {
        // A value span > 2^31 (reachable for wide/unbounded int vars) would truncate AllDifferent's
        // Int-sized value-indexed scratch, so the emit site decomposes to pairwise != instead.
        val src = "var 0..5000000000: x1; var 0..5000000000: x2; var 0..5000000000: x3;\n" +
            "constraint all_different_int([x1, x2, x3]);\nsolve satisfy;"
        val factors = parseFlatZinc(src).problem.factors
        assertTrue(factors.none { it is AllDifferent }, "wide-span alldifferent must not build a value-indexed factor")
        assertEquals(
            3,
            factors.filterIsInstance<Linear>().count { it.op == LinearOp.NE },
            "expected C(3,2) pairwise !=",
        )
    }

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
    fun `all_different with bounds annotation matches brute force`() {
        // The `::bounds` annotation routes to AllDifferent's bounds-consistency path (#561); the
        // enumerated solution set must still be exactly the all-different tuples (consistency level
        // changes pruning speed, never the constraint's semantics).
        val found = enumerate(
            decl(2) + "constraint klause_all_different_int([x1, x2, x3]):: bounds;\nsolve satisfy;",
            names,
        )
        assertEquals(brute(3, 2) { it.toSet().size == 3 }, found)
    }

    @Test
    fun `at_most matches brute force`() {
        // fzn_at_most_int(n, x, v): at most n of x equal v.
        val found = enumerate(decl(2) + "constraint fzn_at_most_int(1, [x1, x2, x3], 1);\nsolve satisfy;", names)
        assertEquals(brute(3, 2) { t -> t.count { it == 1 } <= 1 }, found)
    }

    @Test
    fun `at_least matches brute force`() {
        // fzn_at_least_int(n, x, v): at least n of x equal v.
        val found = enumerate(decl(2) + "constraint fzn_at_least_int(2, [x1, x2, x3], 1);\nsolve satisfy;", names)
        assertEquals(brute(3, 2) { t -> t.count { it == 1 } >= 2 }, found)
    }

    @Test
    fun `exactly matches brute force`() {
        // fzn_exactly_int(n, x, v): exactly n of x equal v.
        val found = enumerate(decl(2) + "constraint fzn_exactly_int(2, [x1, x2, x3], 1);\nsolve satisfy;", names)
        assertEquals(brute(3, 2) { t -> t.count { it == 1 } == 2 }, found)
    }

    @Test
    fun `count_eq with a var count matches brute force`() {
        // klause_count_eq(x, y, c): c = #{i : x(i) = y}, fixed value y, count var c. Project onto
        // the x vars plus c; c is functionally determined, so exactly one c per x assignment.
        val src = decl(2) + "var 0..3: c;\nconstraint klause_count_eq([x1, x2, x3], 1, c);\nsolve satisfy;"
        val found = enumerate(src, names + "c")
        val expected = HashSet<List<Int>>()
        for (a in 0..2) {
            for (b in 0..2) {
                for (d in 0..2) {
                    expected.add(listOf(a, b, d, listOf(a, b, d).count { it == 1 }))
                }
            }
        }
        assertEquals(expected, found)
    }

    @Test
    fun `count_eq with a fixed count matches brute force`() {
        // Constant count form (countLow == countHigh): #{i : x(i) = 1} = 2.
        val found = enumerate(decl(2) + "constraint klause_count_eq([x1, x2, x3], 1, 2);\nsolve satisfy;", names)
        assertEquals(brute(3, 2) { t -> t.count { it == 1 } == 2 }, found)
    }

    @Test
    fun `among matches brute force for every value-set literal form`() {
        // fzn_among(n, x, v): n = #{i : x(i) in v}, constant value set v, count var n. MiniZinc
        // emits a contiguous set either enumerated or as a range literal; both must resolve alike.
        val expected = HashSet<List<Int>>()
        for (a in 0..2) {
            for (b in 0..2) {
                for (d in 0..2) {
                    expected.add(listOf(a, b, d, listOf(a, b, d).count { it == 1 || it == 2 }))
                }
            }
        }
        for (valueSet in listOf("{1, 2}", "1..2")) {
            val src = decl(2) + "var 0..3: n;\nconstraint fzn_among(n, [x1, x2, x3], $valueSet);\nsolve satisfy;"
            assertEquals(expected, enumerate(src, names + "n"), "among over $valueSet")
        }
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

    @Test
    fun `inverse with a restricted first element is not falsely unsat`() {
        // f1 is declared 2..3, so its domain min (2) differs from the 1-based array index base. The
        // channel offset is structurally 1 for FlatZinc index sets; inferring it from
        // `intDomains[f[0]].min` yields 2 here and over-prunes to a root false-UNSAT (the
        // elitserien/handball failure). Enumerate against the inverse predicate directly.
        val src = "var 2..3: f1; var 1..3: f2; var 1..3: f3;\n" +
            "var 1..3: g1; var 1..3: g2; var 1..3: g3;\n" +
            "constraint fzn_inverse([f1, f2, f3], [g1, g2, g3]);\nsolve satisfy;"
        val found = enumerate(src, listOf("f1", "f2", "f3", "g1", "g2", "g3"))
        // f[i] = j  ⇔  g[j] = i, 1-based, with f1 restricted to {2,3}.
        val expected = brute1(arity = 6, hi = 3) { t ->
            val f = listOf(t[0], t[1], t[2])
            val g = listOf(t[3], t[4], t[5])
            t[0] >= 2 && // f1's declared 2..3
                (1..3).all { i -> g[f[i - 1] - 1] == i } && (1..3).all { j -> f[g[j - 1] - 1] == j }
        }
        assertEquals(expected, found)
        assertTrue(found.isNotEmpty(), "inverse must be satisfiable, not falsely UNSAT")
    }

    @Test
    fun `symmetric_all_different with a restricted first element is not falsely unsat`() {
        // Same offset-inference trap as inverse: x1 declared 2..4 so its domain min is not the
        // 1-based index base. symmetric_all_different is the self-inverse permutation x[x[i]] = i.
        val src = "var 2..4: x1; var 1..4: x2; var 1..4: x3; var 1..4: x4;\n" +
            "constraint symmetric_all_different([x1, x2, x3, x4]);\nsolve satisfy;"
        val found = enumerate(src, listOf("x1", "x2", "x3", "x4"))
        val expected = brute1(arity = 4, hi = 4) { t ->
            t[0] >= 2 && (1..4).all { i -> t[t[i - 1] - 1] == i }
        }
        assertEquals(expected, found)
        assertTrue(found.isNotEmpty(), "symmetric_all_different must be satisfiable, not falsely UNSAT")
    }

    /** Brute-force tuples of arity [arity] over the 1-based value range `1..hi` satisfying [pred]. */
    private fun brute1(arity: Int, hi: Int, pred: (List<Int>) -> Boolean): Set<List<Int>> {
        val out = HashSet<List<Int>>()
        val acc = IntArray(arity)
        fun rec(i: Int) {
            if (i == arity) {
                if (pred(acc.toList())) out.add(acc.toList())
                return
            }
            for (v in 1..hi) {
                acc[i] = v
                rec(i + 1)
            }
        }
        rec(0)
        return out
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
