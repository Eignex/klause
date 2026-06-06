package com.eignex.klause.yuck

import com.eignex.klause.ast.PbOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.LinearObjective
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.AllDifferent
import com.eignex.klause.solver.factor.AllDifferentExcept
import com.eignex.klause.solver.factor.BinPacking
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.factor.Circuit
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.solver.factor.Count
import com.eignex.klause.solver.factor.Cumulative
import com.eignex.klause.solver.factor.Diffn
import com.eignex.klause.solver.factor.Element
import com.eignex.klause.solver.factor.GlobalCardinality
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.factor.Monotone
import com.eignex.klause.solver.factor.PseudoBoolean
import com.eignex.klause.solver.factor.Regular
import com.eignex.klause.solver.factor.ReifiedLinear
import com.eignex.klause.solver.factor.Subcircuit
import com.eignex.klause.solver.factor.Table
import com.eignex.klause.solver.factor.Xor
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Emission-only coverage for [FznModel] — string assertions, no Yuck binary required. */
class FznModelTest {

    private fun problem(numBool: Int, doms: Array<IntDomain>, vararg fs: Factor) =
        Problem(numBoolVars = numBool, numIntVars = doms.size, intDomains = doms, factors = arrayOf(*fs))

    private fun dom(n: Int, lo: Int, hi: Int) = Array(n) { IntDomain(lo, hi) }

    @Test
    fun `declares output vars and satisfy directive`() {
        val fzn = FznModel.emit(problem(1, dom(1, -3, 7)))
        assertContains(fzn, "var bool: b0 :: output_var;")
        assertContains(fzn, "var -3..7: i0 :: output_var;")
        assertContains(fzn, "solve satisfy;")
    }

    @Test
    fun `clause maps to bool_clause with split polarities`() {
        val fzn = FznModel.emit(
            problem(2, dom(0, 0, 0), Clause(intArrayOf(Lit.make(0, true), Lit.make(1, false)))),
        )
        assertContains(fzn, "constraint bool_clause([b0], [b1]);")
    }

    @Test
    fun `alldifferent uses the native predicate and declares it`() {
        val fzn = FznModel.emit(
            problem(0, dom(3, 0, 2), AllDifferent(vars = intArrayOf(0, 1, 2), domainMin = 0, domainSize = 3)),
        )
        assertContains(fzn, "predicate fzn_all_different_int(array [int] of var int: x);")
        assertContains(fzn, "constraint fzn_all_different_int([i0, i1, i2]);")
    }

    @Test
    fun `linear GE normalizes to int_lin_le`() {
        val fzn = FznModel.emit(
            problem(0, dom(2, 0, 9), Linear(intArrayOf(2, 3), intArrayOf(0, 1), LinearOp.GE, 5)),
        )
        assertContains(fzn, "constraint int_lin_le([-2, -3], [i0, i1], -5);")
    }

    @Test
    fun `negative pseudo-boolean literals fold into the bound`() {
        // 2·x0 + 3·(¬x1) ≤ 4  ⇒  2·x0 − 3·x1 ≤ 1 over the bool2int channels.
        val fzn = FznModel.emit(
            problem(
                2,
                dom(0, 0, 0),
                PseudoBoolean(intArrayOf(2, 3), intArrayOf(Lit.make(0, true), Lit.make(1, false)), PbOp.LE, 4),
            ),
        )
        assertContains(fzn, "constraint bool2int(b0, t0);")
        assertContains(fzn, "constraint bool2int(b1, t1);")
        assertContains(fzn, "constraint int_lin_le([2, -3], [t0, t1], 1);")
    }

    @Test
    fun `cardinality emits only the binding sides`() {
        val fzn = FznModel.emit(
            problem(3, dom(0, 0, 0), Cardinality(intArrayOf(Lit.make(0, true), Lit.make(1, true)), 0, 1)),
        )
        // min = 0 is vacuous; only the ≤ side appears.
        assertContains(fzn, "constraint int_lin_le([1, 1], [t0, t1], 1);")
        assertTrue("int_lin_le([-1, -1]" !in fzn, "vacuous min side should be omitted:\n$fzn")
    }

    @Test
    fun `xor parity zero appends a constant true`() {
        val fzn = FznModel.emit(
            problem(2, dom(0, 0, 0), Xor(intArrayOf(Lit.make(0, true), Lit.make(1, true)), 0)),
        )
        assertContains(fzn, "constraint array_bool_xor([b0, b1, true]);")
    }

    @Test
    fun `reified linear channels onto the aux bool`() {
        val fzn = FznModel.emit(
            problem(1, dom(1, 0, 9), ReifiedLinear(0, intArrayOf(1), intArrayOf(0), LinearOp.LE, 4)),
        )
        assertContains(fzn, "constraint int_lin_le_reif([1], [i0], 4, b0);")
    }

    @Test
    fun `element shifts a non-unit index offset`() {
        val fzn = FznModel.emit(
            problem(
                0,
                arrayOf(IntDomain(0, 2), IntDomain(0, 9)),
                Element(idx = 0, result = 1, arr = intArrayOf(5, 7, 9), arrIsVars = false, indexOffset = 0),
            ),
        )
        assertContains(fzn, "var 1..3: t0;")
        assertContains(fzn, "constraint int_lin_eq([1, -1], [i0, t0], -1);")
        assertContains(fzn, "constraint array_int_element(t0, [5, 7, 9], i1);")
    }

    @Test
    fun `count reifies per element and sums into the count var`() {
        val fzn = FznModel.emit(
            problem(
                0,
                dom(3, 0, 3),
                Count(xs = intArrayOf(0, 1), v = 2, op = Count.Op.Ge, n = 2),
            ),
        )
        assertContains(fzn, "constraint int_le_reif(2, i0, t0);")
        assertContains(fzn, "constraint int_lin_eq([1, 1, -1], [t2, t3, i2], 0);")
    }

    @Test
    fun `gcc closed adds cover membership`() {
        val fzn = FznModel.emit(
            problem(
                0,
                dom(2, 0, 2),
                GlobalCardinality(
                    xs = intArrayOf(0, 1),
                    cover = intArrayOf(1, 2),
                    countLow = intArrayOf(0, 0),
                    countHigh = intArrayOf(2, 2),
                    closed = true,
                ),
            ),
        )
        assertContains(fzn, "constraint fzn_global_cardinality([i0, i1], [1, 2], [t0, t1]);")
        assertContains(fzn, "constraint set_in(i0, {1, 2});")
    }

    @Test
    fun `table uses the flat yuck predicate`() {
        val fzn = FznModel.emit(
            problem(0, dom(2, 0, 2), Table(intArrayOf(0, 1), intArrayOf(0, 1, 2, 2))),
        )
        assertContains(fzn, "constraint yuck_table_int([i0, i1], [0, 1, 2, 2]);")
    }

    @Test
    fun `regular passes the flat transition table`() {
        val fzn = FznModel.emit(
            problem(
                0,
                dom(2, 1, 2),
                Regular(
                    seq = intArrayOf(0, 1),
                    numStates = 2,
                    alphabetSize = 2,
                    transitions = intArrayOf(2, 0, 0, 2),
                    q0 = 1,
                    accepting = intArrayOf(2),
                ),
            ),
        )
        assertContains(fzn, "constraint yuck_regular([i0, i1], 2, 2, [2, 0, 0, 2], 1, {2});")
    }

    @Test
    fun `monotone decreasing reverses into increasing`() {
        val fzn = FznModel.emit(
            problem(0, dom(2, 0, 3), Monotone(intArrayOf(0, 1), Monotone.Direction.Decreasing, strict = false)),
        )
        assertContains(fzn, "constraint yuck_increasing_int([i1, i0], false);")
    }

    @Test
    fun `circuit uses offset zero`() {
        val fzn = FznModel.emit(problem(0, dom(3, 0, 2), Circuit(intArrayOf(0, 1, 2))))
        assertContains(fzn, "constraint yuck_circuit([i0, i1, i2], 0);")
    }

    @Test
    fun `cumulative passes constant arrays inline`() {
        val fzn = FznModel.emit(
            problem(
                0,
                dom(2, 0, 5),
                Cumulative(
                    starts = intArrayOf(0, 1),
                    durations = intArrayOf(2, 2),
                    resources = intArrayOf(1, 1),
                    capacity = 1,
                ),
            ),
        )
        assertContains(fzn, "constraint fzn_cumulative([i0, i1], [2, 2], [1, 1], 1);")
    }

    @Test
    fun `diffn strictness inverts the nonStrict flag`() {
        val fzn = FznModel.emit(
            problem(
                0,
                dom(4, 0, 3),
                Diffn(
                    xs = intArrayOf(0, 1),
                    ys = intArrayOf(2, 3),
                    widths = intArrayOf(1, 1),
                    heights = intArrayOf(1, 1),
                ),
            ),
        )
        assertContains(fzn, "constraint yuck_diffn([i0, i1], [i2, i3], [1, 1], [1, 1], true);")
    }

    @Test
    fun `bin packing load vars channel directly`() {
        val fzn = FznModel.emit(
            problem(
                0,
                arrayOf(IntDomain(1, 2), IntDomain(1, 2), IntDomain(0, 5), IntDomain(0, 5)),
                BinPacking(
                    bins = intArrayOf(0, 1),
                    weights = intArrayOf(3, 4),
                    mode = BinPacking.Mode.LoadVars,
                    loadVars = intArrayOf(2, 3),
                    numBins = 2,
                    binOffset = 1,
                ),
            ),
        )
        assertContains(fzn, "constraint yuck_bin_packing_load([i2, i3], [i0, i1], [3, 4], 1);")
    }

    @Test
    fun `alldifferent except passes the exclusion set`() {
        val fzn = FznModel.emit(
            problem(0, dom(2, 0, 3), AllDifferentExcept(intArrayOf(0, 1), intArrayOf(0, 3))),
        )
        assertContains(fzn, "constraint fzn_alldifferent_except([i0, i1], {0, 3});")
    }

    @Test
    fun `holey domains render as value sets`() {
        val fzn = FznModel.emit(problem(0, arrayOf(IntDomain(0, 4).excludeValue(2))))
        assertContains(fzn, "var {0, 1, 3, 4}: i0 :: output_var;")
    }

    @Test
    fun `minimize channels the objective and annotates output`() {
        val fzn = FznModel.emit(
            problem(0, dom(2, 0, 9)),
            LinearObjective(intCoefficients = doubleArrayOf(2.0, -1.0)),
        )
        assertContains(fzn, "var -9..18: objv :: output_var;")
        assertContains(fzn, "constraint int_lin_eq([2, -1, -1], [i0, i1, objv], 0);")
        assertContains(fzn, "solve minimize objv;")
    }

    @Test
    fun `subcircuit is loudly unsupported`() {
        assertFailsWith<UnsupportedFactorException> {
            FznModel.emit(problem(0, dom(3, 0, 2), Subcircuit(intArrayOf(0, 1, 2))))
        }
    }
}
