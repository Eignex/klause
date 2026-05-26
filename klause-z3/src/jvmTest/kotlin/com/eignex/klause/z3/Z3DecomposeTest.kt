package com.eignex.klause.z3

import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.SolveResult
import com.eignex.klause.solver.factor.AllEqual
import com.eignex.klause.solver.factor.AllDifferentExcept
import com.eignex.klause.solver.factor.AllDifferentExceptZero
import com.eignex.klause.solver.factor.Among
import com.eignex.klause.solver.factor.ArgMinMax
import com.eignex.klause.solver.factor.ArrayMinMax
import com.eignex.klause.solver.factor.BinPacking
import com.eignex.klause.solver.factor.Count
import com.eignex.klause.solver.factor.GlobalCardinality
import com.eignex.klause.solver.factor.Inverse
import com.eignex.klause.solver.factor.Knapsack
import com.eignex.klause.solver.factor.LexLess
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.factor.Member
import com.eignex.klause.solver.factor.MinCostFlow
import com.eignex.klause.solver.factor.Monotone
import com.eignex.klause.solver.factor.NValue
import com.eignex.klause.solver.factor.ArgSort
import com.eignex.klause.solver.factor.Circuit
import com.eignex.klause.solver.factor.Cumulative
import com.eignex.klause.solver.factor.Diffn
import com.eignex.klause.solver.factor.Disjunctive
import com.eignex.klause.solver.factor.Geost
import com.eignex.klause.solver.factor.Mdd
import com.eignex.klause.solver.factor.Path
import com.eignex.klause.solver.factor.Regular
import com.eignex.klause.solver.factor.Tree
import com.eignex.klause.solver.factor.Sequence
import com.eignex.klause.solver.factor.Sort
import com.eignex.klause.solver.factor.Subcircuit
import com.eignex.klause.solver.factor.Table
import com.eignex.klause.solver.factor.SymmetricAllDifferent
import com.eignex.klause.solver.factor.ValuePrecede
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Decomposition-routed factor coverage. Each case constructs a [Problem] using a
 * factor that isn't natively translated by the Z3 backend — relying on
 * [com.eignex.klause.solver.decompose.FactorDecomposer] to lower it to mid-IR before
 * translation. SAT/UNSAT verdicts are checked against the factor's semantics.
 */
class Z3DecomposeTest {

    @Test
    fun `AllEqual - sat when all xs can equal`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 5), IntDomain(0, 5), IntDomain(0, 5)),
            factors = arrayOf(AllEqual(intArrayOf(0, 1, 2))),
        )
        val r = Z3Solver(problem).solve(Z3Params())
        val sat = assertIs<SolveResult.Sat>(r)
        assertEquals(sat.assignment.ints[0], sat.assignment.ints[1])
        assertEquals(sat.assignment.ints[0], sat.assignment.ints[2])
    }

    @Test
    fun `AllEqual - unsat when domains disjoint`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 2), IntDomain(5, 7)),
            factors = arrayOf(AllEqual(intArrayOf(0, 1))),
        )
        val r = Z3Solver(problem).solve(Z3Params())
        assertIs<SolveResult.Unsat>(r)
    }

    @Test
    fun `AllDifferentExcept - empty except acts like AllDifferent`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)),
            factors = arrayOf(AllDifferentExcept(intArrayOf(0, 1, 2), intArrayOf())),
        )
        val r = Z3Solver(problem).solve(Z3Params())
        val sat = assertIs<SolveResult.Sat>(r)
        val vals = sat.assignment.ints.toSet()
        assertEquals(3, vals.size, "expected pairwise-distinct values")
    }

    @Test
    fun `Monotone - strictly increasing chain`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(0, 9), IntDomain(0, 9), IntDomain(0, 9)),
            factors = arrayOf(Monotone(intArrayOf(0, 1, 2), Monotone.Direction.Increasing, strict = true)),
        )
        val r = Z3Solver(problem).solve(Z3Params())
        val sat = assertIs<SolveResult.Sat>(r)
        val xs = sat.assignment.ints
        assertEquals(true, xs[0] < xs[1])
        assertEquals(true, xs[1] < xs[2])
    }

    @Test
    fun `Member - y must equal some xs entry`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            // xs domains restricted to {1, 3, 5}; y domain {2, 3, 4} → only y=3 works.
            intDomains = arrayOf(IntDomain(1, 1), IntDomain(3, 3), IntDomain(2, 4)),
            factors = arrayOf(Member(intArrayOf(0, 1), 2)),
        )
        val r = Z3Solver(problem).solve(Z3Params())
        val sat = assertIs<SolveResult.Sat>(r)
        assertEquals(3, sat.assignment.ints[2])
    }

    // -------- Tier 2 (counting / PB) --------

    @Test
    fun `Among - exactly two of xs in S`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = Array(4) { com.eignex.klause.solver.IntDomain(0, 3) },
            factors = arrayOf(Among(n = 2, xs = intArrayOf(0, 1, 2, 3), values = intArrayOf(1, 3))),
        )
        val r = Z3Solver(problem).solve(Z3Params())
        val sat = assertIs<SolveResult.Sat>(r)
        val cnt = sat.assignment.ints.count { it == 1 || it == 3 }
        assertEquals(2, cnt)
    }

    @Test
    fun `Count - Eq with target value`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = Array(3) { com.eignex.klause.solver.IntDomain(0, 5) },
            factors = arrayOf(Count(xs = intArrayOf(0, 1, 2), v = 4, op = Count.Op.Eq, n = 2)),
        )
        val r = Z3Solver(problem).solve(Z3Params())
        val sat = assertIs<SolveResult.Sat>(r)
        assertEquals(2, sat.assignment.ints.count { it == 4 })
    }

    @Test
    fun `Count - Ne with target value`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = Array(3) { com.eignex.klause.solver.IntDomain(0, 1) },
            factors = arrayOf(Count(xs = intArrayOf(0, 1, 2), v = 1, op = Count.Op.Ne, n = 2)),
        )
        val r = Z3Solver(problem).solve(Z3Params())
        val sat = assertIs<SolveResult.Sat>(r)
        val cnt = sat.assignment.ints.count { it == 1 }
        assertEquals(true, cnt != 2)
    }

    @Test
    fun `NValue - exactly distinct count`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = Array(3) { com.eignex.klause.solver.IntDomain(0, 4) },
            factors = arrayOf(NValue(n = 2, xs = intArrayOf(0, 1, 2))),
        )
        val r = Z3Solver(problem).solve(Z3Params())
        val sat = assertIs<SolveResult.Sat>(r)
        assertEquals(2, sat.assignment.ints.toSet().size)
    }

    @Test
    fun `AllDifferentExceptZero - zeros may repeat`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            // Force two zeros to share — would be unsat under pure AllDifferent.
            intDomains = arrayOf(
                com.eignex.klause.solver.IntDomain(0, 0),
                com.eignex.klause.solver.IntDomain(0, 0),
                com.eignex.klause.solver.IntDomain(1, 3),
            ),
            factors = arrayOf(AllDifferentExceptZero(intArrayOf(0, 1, 2))),
        )
        val r = Z3Solver(problem).solve(Z3Params())
        assertIs<SolveResult.Sat>(r)
    }

    @Test
    fun `AllDifferentExcept with non-empty except`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            // 7 is the exception; the other two must differ from each other (and from 7
            // only if neither is 7).
            intDomains = arrayOf(
                com.eignex.klause.solver.IntDomain(7, 7),
                com.eignex.klause.solver.IntDomain(7, 7),
                com.eignex.klause.solver.IntDomain(0, 5),
            ),
            factors = arrayOf(AllDifferentExcept(intArrayOf(0, 1, 2), intArrayOf(7))),
        )
        val r = Z3Solver(problem).solve(Z3Params())
        assertIs<SolveResult.Sat>(r)
    }

    @Test
    fun `SymmetricAllDifferent - involution permutation`() {
        // 4-element permutation that's its own inverse, indexOffset = 0.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = Array(4) { com.eignex.klause.solver.IntDomain(0, 3) },
            factors = arrayOf(SymmetricAllDifferent(intArrayOf(0, 1, 2, 3), indexOffset = 0)),
        )
        val r = Z3Solver(problem).solve(Z3Params())
        val sat = assertIs<SolveResult.Sat>(r)
        val xs = sat.assignment.ints
        for (i in 0..3) {
            assertEquals(i, xs[xs[i]], "xs[xs[$i]] should be $i; got ${xs.toList()}")
        }
    }

    @Test
    fun `GlobalCardinality - count bounds per cover value`() {
        // 4 xs in {0..2}; cover {0, 1}; demand at least one 0 and at least one 1.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = Array(4) { com.eignex.klause.solver.IntDomain(0, 2) },
            factors = arrayOf(GlobalCardinality(
                xs = intArrayOf(0, 1, 2, 3),
                cover = intArrayOf(0, 1),
                countLow = intArrayOf(1, 1),
                countHigh = intArrayOf(4, 4),
            )),
        )
        val r = Z3Solver(problem).solve(Z3Params())
        val sat = assertIs<SolveResult.Sat>(r)
        val cnt0 = sat.assignment.ints.count { it == 0 }
        val cnt1 = sat.assignment.ints.count { it == 1 }
        assertEquals(true, cnt0 >= 1)
        assertEquals(true, cnt1 >= 1)
    }

    @Test
    fun `ValuePrecede - t cannot appear before s`() {
        // ValuePrecede(s=1, t=2, xs). Forbid xs[0]=2.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(1, 2), IntDomain(1, 2), IntDomain(1, 2)),
            factors = arrayOf(
                ValuePrecede(s = 1, t = 2, xs = intArrayOf(0, 1, 2)),
                // Force a 2 to appear so the constraint is non-trivial.
                Linear(intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.GE, 5),
            ),
        )
        val r = Z3Solver(problem).solve(Z3Params())
        val sat = assertIs<SolveResult.Sat>(r)
        val xs = sat.assignment.ints
        // Find first 2; verify a 1 appeared earlier.
        val firstT = xs.indexOfFirst { it == 2 }
        if (firstT >= 0) {
            val firstS = xs.indexOfFirst { it == 1 }
            assertEquals(true, firstS in 0 until firstT, "first s ($firstS) must precede first t ($firstT) in $xs")
        }
    }

    // -------- Tier 3 (arithmetic) --------

    @Test
    fun `Knapsack - simultaneous weight and profit equations`() {
        // 3 items, all xs ∈ {0,1}, weights 2/3/4, profits 3/4/5. Want w=5 (1+0+1? no:
        // pick first and third → weight 6; or pick first and second → weight 5).
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 5,
            intDomains = arrayOf(
                IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 1),  // xs
                IntDomain(0, 9), IntDomain(0, 12),                  // w, p
            ),
            factors = arrayOf(
                Knapsack(
                    weights = intArrayOf(2, 3, 4),
                    profits = intArrayOf(3, 4, 5),
                    xs = intArrayOf(0, 1, 2),
                    w = 3,
                    p = 4,
                ),
                Linear(intArrayOf(1), intArrayOf(3), LinearOp.EQ, 5),  // pin w to 5
            ),
        )
        val r = Z3Solver(problem).solve(Z3Params())
        val sat = assertIs<SolveResult.Sat>(r)
        val xs = sat.assignment.ints
        val w = xs[0] * 2 + xs[1] * 3 + xs[2] * 4
        val p = xs[0] * 3 + xs[1] * 4 + xs[2] * 5
        assertEquals(5, w)
        assertEquals(p, xs[4])
    }

    @Test
    fun `ArrayMinMax - max picks the largest`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = arrayOf(IntDomain(1, 5), IntDomain(3, 7), IntDomain(2, 4), IntDomain(0, 10)),
            factors = arrayOf(ArrayMinMax(result = 3, xs = intArrayOf(0, 1, 2), max = true)),
        )
        val r = Z3Solver(problem).solve(Z3Params())
        val sat = assertIs<SolveResult.Sat>(r)
        val xs = sat.assignment.ints
        assertEquals(maxOf(xs[0], xs[1], xs[2]), xs[3])
    }

    @Test
    fun `ArgMinMax - argmax index points at a max element`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            // Force xs[1] to be strictly the largest so the chosen idx is unique.
            intDomains = arrayOf(IntDomain(1, 1), IntDomain(9, 9), IntDomain(3, 3), IntDomain(0, 2)),
            factors = arrayOf(ArgMinMax(idx = 3, xs = intArrayOf(0, 1, 2), max = true, indexOffset = 0)),
        )
        val r = Z3Solver(problem).solve(Z3Params())
        val sat = assertIs<SolveResult.Sat>(r)
        assertEquals(1, sat.assignment.ints[3])
    }

    @Test
    fun `BinPacking - uniform capacity packs items`() {
        // 4 items of sizes 2/3/4/1 into 2 bins of capacity 5 each. Feasible: {1,4}+{2,3}.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = Array(4) { IntDomain(1, 2) },
            factors = arrayOf(BinPacking(
                bins = intArrayOf(0, 1, 2, 3),
                weights = intArrayOf(2, 3, 4, 1),
                mode = BinPacking.Mode.UniformCapacity,
                uniformCapacity = 5,
                numBins = 2,
                binOffset = 1,
            )),
        )
        val r = Z3Solver(problem).solve(Z3Params())
        val sat = assertIs<SolveResult.Sat>(r)
        val bins = sat.assignment.ints
        val loads = IntArray(2)
        val sizes = intArrayOf(2, 3, 4, 1)
        for (i in 0..3) loads[bins[i] - 1] += sizes[i]
        for (load in loads) assertEquals(true, load <= 5)
    }

    @Test
    fun `MinCostFlow - source-sink balance`() {
        // 2-node, 1-arc graph: node 0 supplies 1, node 1 demands 1. Flow var ∈ [0, 5].
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 5)),
            factors = arrayOf(MinCostFlow(
                numNodes = 2,
                arcFrom = intArrayOf(0),
                arcTo = intArrayOf(1),
                balance = intArrayOf(-1, 1),
                flow = intArrayOf(0),
                weight = null,
                cost = -1,
            )),
        )
        val r = Z3Solver(problem).solve(Z3Params())
        val sat = assertIs<SolveResult.Sat>(r)
        assertEquals(1, sat.assignment.ints[0])
    }

    // -------- Tier 4 (comparison / connectivity / misc) --------

    @Test
    fun `Inverse - pairwise inverse permutation`() {
        // f and g are inverses; both length 3, offsets 0.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 6,
            intDomains = Array(6) { IntDomain(0, 2) },
            factors = arrayOf(Inverse(
                f = intArrayOf(0, 1, 2),
                g = intArrayOf(3, 4, 5),
                fOffset = 0,
                gOffset = 0,
            )),
        )
        val r = Z3Solver(problem).solve(Z3Params())
        val sat = assertIs<SolveResult.Sat>(r)
        val ints = sat.assignment.ints
        for (i in 0..2) {
            val j = ints[i]
            assertEquals(i, ints[3 + j], "g[f[$i]] should be $i; got f=${ints.toList().subList(0,3)} g=${ints.toList().subList(3,6)}")
        }
    }

    @Test
    fun `LexLess - non-strict requires xs lex-leq ys`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            // xs = [0, 1] (forced); ys = [a, b] free.
            intDomains = arrayOf(IntDomain(0, 0), IntDomain(1, 1), IntDomain(0, 1), IntDomain(0, 1)),
            factors = arrayOf(LexLess(xs = intArrayOf(0, 1), ys = intArrayOf(2, 3), strict = false)),
        )
        val r = Z3Solver(problem).solve(Z3Params())
        val sat = assertIs<SolveResult.Sat>(r)
        val xs = listOf(sat.assignment.ints[0], sat.assignment.ints[1])
        val ys = listOf(sat.assignment.ints[2], sat.assignment.ints[3])
        // Verify xs ≤_lex ys.
        val leq = (xs[0] < ys[0]) || (xs[0] == ys[0] && xs[1] <= ys[1])
        assertEquals(true, leq, "$xs ≤_lex $ys should hold")
    }

    @Test
    fun `Sequence - sliding window cardinality`() {
        // xs of length 5 in {0,1}. values={1}. Each 3-window must have between 1 and 2 ones.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 5,
            intDomains = Array(5) { IntDomain(0, 1) },
            factors = arrayOf(Sequence(
                low = 1,
                high = 2,
                k = 3,
                xs = intArrayOf(0, 1, 2, 3, 4),
                values = intArrayOf(1),
            )),
        )
        val r = Z3Solver(problem).solve(Z3Params())
        val sat = assertIs<SolveResult.Sat>(r)
        val xs = sat.assignment.ints
        for (start in 0..2) {
            val ones = (start..start + 2).count { xs[it] == 1 }
            assertEquals(true, ones in 1..2, "window starting at $start has $ones ones; xs=${xs.toList()}")
        }
    }

    @Test
    fun `Table - only listed tuples accepted`() {
        // 2 vars, allowed tuples (1,2) and (3,4); each var in {0..4}.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 4), IntDomain(0, 4)),
            factors = arrayOf(Table(xs = intArrayOf(0, 1), tuples = intArrayOf(1, 2, 3, 4))),
        )
        val r = Z3Solver(problem).solve(Z3Params())
        val sat = assertIs<SolveResult.Sat>(r)
        val xs = sat.assignment.ints
        val matches = (xs[0] == 1 && xs[1] == 2) || (xs[0] == 3 && xs[1] == 4)
        assertEquals(true, matches, "tuple ${xs.toList()} not in {(1,2), (3,4)}")
    }

    @Test
    fun `Regular - accept DFA`() {
        // 2-state DFA accepting strings over {1,2} with at least one 2. q0=1, accepting={2}.
        // transitions[(q-1)*2 + (s-1)]: from q=1 on s=1 → 1; q=1 on s=2 → 2;
        // q=2 on s=1 → 2; q=2 on s=2 → 2.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = arrayOf(IntDomain(1, 2), IntDomain(1, 2), IntDomain(1, 2)),
            factors = arrayOf(Regular(
                seq = intArrayOf(0, 1, 2),
                numStates = 2,
                alphabetSize = 2,
                transitions = intArrayOf(1, 2, 2, 2),
                q0 = 1,
                accepting = intArrayOf(2),
            )),
        )
        val r = Z3Solver(problem).solve(Z3Params())
        val sat = assertIs<SolveResult.Sat>(r)
        // At least one 2 in the sequence.
        assertEquals(true, sat.assignment.ints.any { it == 2 })
    }

    @Test
    fun `Mdd - layered acceptance`() {
        // Same shape as the existing CP-engine smoke test for Mdd:
        //  Layer 0: state 0 → seq[0]=1 → state 0, or → seq[0]=2 → state 1.
        //  Layer 1: state 0 → seq[1]=1 → state 0; state 1 → seq[1]=2 → state 0.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(1, 2), IntDomain(1, 2)),
            factors = arrayOf(Mdd(
                seq = intArrayOf(0, 1),
                numStatesPerLayer = intArrayOf(1, 2, 1),
                layerStarts = intArrayOf(0, 6, 12),
                transitions = intArrayOf(
                    0, 1, 0,  0, 2, 1,  // layer 0
                    0, 1, 0,  1, 2, 0,  // layer 1
                ),
                initial = 0,
                accepting = intArrayOf(0),
                recordStride = 3,
            )),
        )
        val r = Z3Solver(problem).solve(Z3Params())
        val sat = assertIs<SolveResult.Sat>(r)
        val s = sat.assignment.ints
        val pathOk = (s[0] == 1 && s[1] == 1) || (s[0] == 2 && s[1] == 2)
        assertEquals(true, pathOk, "MDD-accepted seq must be (1,1) or (2,2); got ${s.toList()}")
    }

    @Test
    fun `Diffn - 2D rectangle non-overlap`() {
        // 2 rectangles, both 2x2, in a 4x4 grid. Must be placed without overlap.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = Array(4) { IntDomain(0, 2) },
            factors = arrayOf(Diffn(
                xs = intArrayOf(0, 2),
                ys = intArrayOf(1, 3),
                widths = intArrayOf(2, 2),
                heights = intArrayOf(2, 2),
                nonStrict = true,
            )),
        )
        val r = Z3Solver(problem).solve(Z3Params())
        val sat = assertIs<SolveResult.Sat>(r)
        val xs = sat.assignment.ints
        // Check separation: in x OR in y axis.
        val x0 = xs[0]; val y0 = xs[1]; val x1 = xs[2]; val y1 = xs[3]
        val sep = (x0 + 2 <= x1) || (x1 + 2 <= x0) || (y0 + 2 <= y1) || (y1 + 2 <= y0)
        assertEquals(true, sep, "rects at ($x0,$y0) and ($x1,$y1) must not overlap")
    }

    @Test
    fun `Geost - 2D pairwise non-overlap`() {
        // 2 objects, both 2-d size (1, 1). Origins in [0..3]; must not overlap.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = Array(4) { IntDomain(0, 3) },
            factors = arrayOf(Geost(
                numDims = 2,
                numObjects = 2,
                origin = intArrayOf(0, 1, 2, 3),
                length = intArrayOf(1, 1, 1, 1),
            )),
        )
        val r = Z3Solver(problem).solve(Z3Params())
        val sat = assertIs<SolveResult.Sat>(r)
        val o0x = sat.assignment.ints[0]
        val o0y = sat.assignment.ints[1]
        val o1x = sat.assignment.ints[2]
        val o1y = sat.assignment.ints[3]
        val sep = (o0x + 1 <= o1x) || (o1x + 1 <= o0x) || (o0y + 1 <= o1y) || (o1y + 1 <= o0y)
        assertEquals(true, sep)
    }

    @Test
    fun `Cumulative - resource capacity respected`() {
        // 3 tasks of duration 2, resource 1 each, capacity 2.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 3,
            intDomains = Array(3) { IntDomain(0, 4) },
            factors = arrayOf(Cumulative(
                starts = intArrayOf(0, 1, 2),
                durations = intArrayOf(2, 2, 2),
                resources = intArrayOf(1, 1, 1),
                capacity = 2,
            )),
        )
        val r = Z3Solver(problem).solve(Z3Params())
        val sat = assertIs<SolveResult.Sat>(r)
        val s = sat.assignment.ints
        // Verify at each integer time, ≤ 2 tasks are active.
        for (t in 0..5) {
            val active = (0..2).count { t in s[it] until s[it] + 2 }
            assertEquals(true, active <= 2)
        }
    }

    @Test
    fun `Circuit - Hamiltonian cycle`() {
        // 4 nodes; succ ∈ [0, 3]. Standard Hamiltonian cycle (no self-loops).
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 4,
            intDomains = Array(4) { IntDomain(0, 3) },
            factors = arrayOf(Circuit(intArrayOf(0, 1, 2, 3))),
        )
        val r = Z3Solver(problem).solve(Z3Params())
        val sat = assertIs<SolveResult.Sat>(r)
        val s = sat.assignment.ints
        // Walk from 0; should visit all 4 nodes and return.
        val visited = mutableSetOf<Int>()
        var cur = 0
        for (step in 0..4) {
            visited.add(cur)
            cur = s[cur]
            if (step >= 3 && cur == 0) break
        }
        assertEquals(setOf(0, 1, 2, 3), visited)
    }

    @Test
    fun `Sort - ys is sorted permutation of xs`() {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 6,
            intDomains = arrayOf(
                IntDomain(3, 3), IntDomain(1, 1), IntDomain(2, 2),  // xs forced to [3,1,2]
                IntDomain(0, 5), IntDomain(0, 5), IntDomain(0, 5),  // ys free
            ),
            factors = arrayOf(Sort(xs = intArrayOf(0, 1, 2), ys = intArrayOf(3, 4, 5))),
        )
        val r = Z3Solver(problem).solve(Z3Params())
        val sat = assertIs<SolveResult.Sat>(r)
        val ys = listOf(sat.assignment.ints[3], sat.assignment.ints[4], sat.assignment.ints[5])
        assertEquals(listOf(1, 2, 3), ys)
    }

    @Test
    fun `ArgSort - perm sorts values`() {
        // Values [3, 1, 2] → sort indices = [1, 2, 0] (perm[0]=1, perm[1]=2, perm[2]=0).
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 6,
            intDomains = arrayOf(
                IntDomain(3, 3), IntDomain(1, 1), IntDomain(2, 2),
                IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2),
            ),
            factors = arrayOf(ArgSort(
                values = intArrayOf(0, 1, 2),
                perm = intArrayOf(3, 4, 5),
                permOffset = 0,
            )),
        )
        val r = Z3Solver(problem).solve(Z3Params())
        val sat = assertIs<SolveResult.Sat>(r)
        val perm = listOf(sat.assignment.ints[3], sat.assignment.ints[4], sat.assignment.ints[5])
        assertEquals(listOf(1, 2, 0), perm)
    }

    @Test
    fun `Path - 3-node line graph degree decomposition`() {
        // 3 nodes 0→1→2, two edges. source/sink ints, presence bools. The degree
        // decomposition makes the linear path satisfiable.
        val problem = Problem(
            numBoolVars = 5,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2)),
            factors = arrayOf(
                Path(
                    numNodes = 3,
                    from = intArrayOf(0, 1),
                    to = intArrayOf(1, 2),
                    source = 0,
                    sink = 1,
                    nodePresent = intArrayOf(0, 1, 2),
                    edgePresent = intArrayOf(3, 4),
                ),
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 0),  // pin source = 0
                Linear(intArrayOf(1), intArrayOf(1), LinearOp.EQ, 2),  // pin sink = 2
            ),
        )
        val r = Z3Solver(problem).solve(Z3Params())
        assertIs<SolveResult.Sat>(r)
    }

    @Test
    fun `Tree - 3-node in-tree degree decomposition`() {
        val problem = Problem(
            numBoolVars = 5,
            numIntVars = 1,
            intDomains = arrayOf(IntDomain(0, 2)),
            factors = arrayOf(
                Tree(
                    numNodes = 3,
                    from = intArrayOf(0, 0),
                    to = intArrayOf(1, 2),
                    root = 0,
                    nodePresent = intArrayOf(0, 1, 2),
                    edgePresent = intArrayOf(3, 4),
                ),
                Linear(intArrayOf(1), intArrayOf(0), LinearOp.EQ, 0),  // pin root = 0
            ),
        )
        val r = Z3Solver(problem).solve(Z3Params())
        assertIs<SolveResult.Sat>(r)
    }

    @Test
    fun `Disjunctive - non-overlapping tasks`() {
        // 2 tasks, durations 3 and 2; starts in [0, 5]. They must not overlap.
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = 2,
            intDomains = arrayOf(IntDomain(0, 5), IntDomain(0, 5)),
            factors = arrayOf(Disjunctive(starts = intArrayOf(0, 1), durations = intArrayOf(3, 2))),
        )
        val r = Z3Solver(problem).solve(Z3Params())
        val sat = assertIs<SolveResult.Sat>(r)
        val s0 = sat.assignment.ints[0]
        val s1 = sat.assignment.ints[1]
        val ok = (s0 + 3 <= s1) || (s1 + 2 <= s0)
        assertEquals(true, ok, "tasks at ($s0,3) and ($s1,2) should not overlap")
    }
}
