package com.eignex.klause.cnf

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.Sample
import com.eignex.klause.solver.factor.AllEqual
import com.eignex.klause.solver.factor.Among
import com.eignex.klause.solver.factor.ArgMinMax
import com.eignex.klause.solver.factor.ArrayMinMax
import com.eignex.klause.solver.factor.BinPacking
import com.eignex.klause.solver.factor.Count
import com.eignex.klause.solver.factor.Cumulative
import com.eignex.klause.solver.factor.Diffn
import com.eignex.klause.solver.factor.Disjunctive
import com.eignex.klause.solver.factor.Element
import com.eignex.klause.solver.factor.GlobalCardinality
import com.eignex.klause.solver.factor.Inverse
import com.eignex.klause.solver.factor.Knapsack
import com.eignex.klause.solver.factor.LexLess
import com.eignex.klause.solver.factor.Member
import com.eignex.klause.solver.factor.Monotone
import com.eignex.klause.solver.factor.NValue
import com.eignex.klause.solver.factor.Regular
import com.eignex.klause.solver.factor.Sequence
import com.eignex.klause.solver.factor.SetBitsetDisjoint
import com.eignex.klause.solver.factor.SetBitsetEq
import com.eignex.klause.solver.factor.SetBitsetSubset
import com.eignex.klause.solver.factor.Sort
import com.eignex.klause.solver.factor.SymmetricAllDifferent
import com.eignex.klause.solver.factor.Table
import com.eignex.klause.solver.factor.ValuePrecede
import com.eignex.klause.solver.localsearch.LocalSearchParams
import com.eignex.klause.solver.localsearch.LocalSearchSolver
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Each previously-unsupported native factor must bit-blast to CNF that (a) accepts every
 * feasible assignment the local-search solver finds, and (b) rejects targeted infeasible
 * pinnings. Validation reuses the [SatCheck] oracle: pin a feasible sample into the CNF and
 * assert SAT; pin an infeasible scenario and assert UNSAT.
 */
class GapFactorBitBlastTest {

    private fun feasibleSampleIsSat(name: String, problem: Problem) {
        val cnf = BitBlaster.compile(problem)
        val solver = LocalSearchSolver(problem)
        val sample = solver.samples(LocalSearchParams(maxFlips = 200_000L, randomSeed = 0L)).take(1).toList()
        assertTrue(sample.isNotEmpty(), "$name: LS found no feasible sample")
        val pins = pinSample(cnf, problem, sample.first())
        assertTrue(SatCheck.isSat(cnf.numVars, cnf.clauses, pins), "$name: feasible sample UNSAT under bit-blast")
    }

    @Test fun disjunctive() = feasibleSampleIsSat(
        "disjunctive",
        Problem(
            0,
            2,
            arrayOf(IntDomain(0, 5), IntDomain(0, 5)),
            arrayOf<Factor>(
                Disjunctive(starts = intArrayOf(0, 1), durations = intArrayOf(3, 2)),
            ),
        ),
    )

    @Test fun cumulative() = feasibleSampleIsSat(
        "cumulative",
        Problem(
            0,
            3,
            arrayOf(IntDomain(0, 4), IntDomain(0, 4), IntDomain(0, 4)),
            arrayOf<Factor>(
                Cumulative(
                    starts = intArrayOf(0, 1, 2),
                    durations = intArrayOf(2, 2, 2),
                    resources = intArrayOf(1, 1, 1),
                    capacity = 2,
                ),
            ),
        ),
    )

    @Test fun count() = feasibleSampleIsSat(
        "count",
        // n (var 3) = #{i : xs[i] = 1}, xs = vars 0..2 ∈ [0,2], n ∈ [0,3].
        Problem(
            0,
            4,
            arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 3)),
            arrayOf<Factor>(
                Count(xs = intArrayOf(0, 1, 2), v = 1, op = Count.Op.Eq, n = 3),
            ),
        ),
    )

    @Test fun nvalue() = feasibleSampleIsSat(
        "nvalue",
        Problem(
            0,
            4,
            arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2), IntDomain(1, 3)),
            arrayOf<Factor>(
                NValue(n = 3, xs = intArrayOf(0, 1, 2), mode = NValue.Mode.Eq),
            ),
        ),
    )

    @Test fun gcc() = feasibleSampleIsSat(
        "gcc",
        Problem(
            0,
            3,
            arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)),
            arrayOf<Factor>(
                GlobalCardinality(
                    xs = intArrayOf(0, 1, 2),
                    cover = intArrayOf(0, 1, 2),
                    countLow = intArrayOf(1, 1, 0),
                    countHigh = intArrayOf(2, 2, 1),
                ),
            ),
        ),
    )

    // Circuit / Subcircuit synthesise free position vars that aren't pinned by a problem
    // sample, so the brute-force SatCheck oracle can't validate them — they're round-tripped
    // through a real SAT solver in klause-logicng's GapFactorLogicNgTest instead.

    @Test fun setSubset() = feasibleSampleIsSat(
        "set subset",
        Problem(
            4,
            0,
            emptyArray(),
            arrayOf<Factor>(
                SetBitsetSubset(leftBools = intArrayOf(0, 1), rightBools = intArrayOf(2, 3)),
                // Force left to be non-trivial so the implication has teeth.
                com.eignex.klause.solver.factor.Clause(intArrayOf(Lit.make(0, true))),
            ),
        ),
    )

    @Test fun setDisjoint() = feasibleSampleIsSat(
        "set disjoint",
        Problem(
            4,
            0,
            emptyArray(),
            arrayOf<Factor>(
                SetBitsetDisjoint(leftBools = intArrayOf(0, 1), rightBools = intArrayOf(2, 3)),
            ),
        ),
    )

    @Test fun setEq() = feasibleSampleIsSat(
        "set eq",
        Problem(
            4,
            0,
            emptyArray(),
            arrayOf<Factor>(
                SetBitsetEq(leftBools = intArrayOf(0, 1), rightBools = intArrayOf(2, 3)),
            ),
        ),
    )

    @Test fun allEqual() = feasibleSampleIsSat(
        "all equal",
        Problem(0, 3, Array(3) { IntDomain(0, 3) }, arrayOf<Factor>(AllEqual(intArrayOf(0, 1, 2)))),
    )

    @Test fun member() = feasibleSampleIsSat(
        "member",
        Problem(0, 4, Array(4) { IntDomain(0, 3) }, arrayOf<Factor>(Member(xs = intArrayOf(0, 1, 2), y = 3))),
    )

    @Test fun among() = feasibleSampleIsSat(
        "among",
        Problem(
            0,
            4,
            arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 3)),
            arrayOf<Factor>(
                Among(n = 3, xs = intArrayOf(0, 1, 2), values = intArrayOf(1, 2)),
            ),
        ),
    )

    @Test fun monotone() = feasibleSampleIsSat(
        "monotone",
        Problem(
            0,
            3,
            Array(3) { IntDomain(0, 5) },
            arrayOf<Factor>(
                Monotone(intArrayOf(0, 1, 2), Monotone.Direction.Increasing, strict = true),
            ),
        ),
    )

    @Test fun lexLess() = feasibleSampleIsSat(
        "lex less",
        Problem(
            0,
            4,
            Array(4) { IntDomain(0, 3) },
            arrayOf<Factor>(
                LexLess(xs = intArrayOf(0, 1), ys = intArrayOf(2, 3), strict = true),
            ),
        ),
    )

    @Test fun valuePrecede() = feasibleSampleIsSat(
        "value precede",
        Problem(
            0,
            3,
            Array(3) { IntDomain(0, 2) },
            arrayOf<Factor>(
                ValuePrecede(s = 1, t = 2, xs = intArrayOf(0, 1, 2)),
            ),
        ),
    )

    @Test fun elementConst() = feasibleSampleIsSat(
        "element const",
        // idx (var0) ∈ [1,3], result (var1) ∈ [5,9], arr = [5,7,9], 1-based.
        Problem(
            0,
            2,
            arrayOf(IntDomain(1, 3), IntDomain(5, 9)),
            arrayOf<Factor>(
                Element(idx = 0, result = 1, arr = intArrayOf(5, 7, 9), arrIsVars = false, indexOffset = 1),
            ),
        ),
    )

    @Test fun elementVars() = feasibleSampleIsSat(
        "element vars",
        // result (var0), idx (var1) ∈ [1,3], arr = vars 2,3,4.
        Problem(
            0,
            5,
            arrayOf(IntDomain(0, 5), IntDomain(1, 3), IntDomain(0, 5), IntDomain(0, 5), IntDomain(0, 5)),
            arrayOf<Factor>(
                Element(idx = 1, result = 0, arr = intArrayOf(2, 3, 4), arrIsVars = true, indexOffset = 1),
            ),
        ),
    )

    @Test fun inverse() = feasibleSampleIsSat(
        "inverse",
        Problem(
            0,
            6,
            Array(6) { IntDomain(0, 2) },
            arrayOf<Factor>(
                Inverse(f = intArrayOf(0, 1, 2), g = intArrayOf(3, 4, 5), fOffset = 0, gOffset = 0),
            ),
        ),
    )

    @Test fun symmetricAllDifferent() = feasibleSampleIsSat(
        "symmetric all different",
        Problem(
            0,
            4,
            Array(4) { IntDomain(0, 3) },
            arrayOf<Factor>(
                SymmetricAllDifferent(xs = intArrayOf(0, 1, 2, 3), indexOffset = 0),
            ),
        ),
    )

    @Test fun sort() = feasibleSampleIsSat(
        "sort",
        Problem(
            0,
            6,
            Array(6) { IntDomain(0, 3) },
            arrayOf<Factor>(
                Sort(xs = intArrayOf(0, 1, 2), ys = intArrayOf(3, 4, 5)),
            ),
        ),
    )

    @Test fun arrayMax() = feasibleSampleIsSat(
        "array max",
        Problem(
            0,
            4,
            Array(4) { IntDomain(0, 5) },
            arrayOf<Factor>(
                ArrayMinMax(result = 0, xs = intArrayOf(1, 2, 3), max = true),
            ),
        ),
    )

    @Test fun argMin() = feasibleSampleIsSat(
        "arg min",
        Problem(
            0,
            4,
            arrayOf(IntDomain(0, 2), IntDomain(0, 5), IntDomain(0, 5), IntDomain(0, 5)),
            arrayOf<Factor>(
                ArgMinMax(idx = 0, xs = intArrayOf(1, 2, 3), max = false, indexOffset = 0),
            ),
        ),
    )

    @Test fun diffn() = feasibleSampleIsSat(
        "diffn",
        Problem(
            0,
            4,
            Array(4) { IntDomain(0, 5) },
            arrayOf<Factor>(
                Diffn(
                    xs = intArrayOf(0, 1),
                    ys = intArrayOf(2, 3),
                    widths = intArrayOf(2, 2),
                    heights = intArrayOf(2, 2),
                ),
            ),
        ),
    )

    @Test fun binPacking() = feasibleSampleIsSat(
        "bin packing",
        Problem(
            0,
            3,
            Array(3) { IntDomain(1, 2) },
            arrayOf<Factor>(
                BinPacking(
                    bins = intArrayOf(0, 1, 2),
                    weights = intArrayOf(1, 1, 1),
                    mode = BinPacking.Mode.UniformCapacity,
                    uniformCapacity = 3,
                    numBins = 2,
                    binOffset = 1,
                ),
            ),
        ),
    )

    @Test fun knapsack() = feasibleSampleIsSat(
        "knapsack",
        // xs = vars 0,1 ∈ {0,1}; w (var2) = Σ weights·xs; p (var3) = Σ profits·xs.
        Problem(
            0,
            4,
            arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 5), IntDomain(0, 9)),
            arrayOf<Factor>(
                Knapsack(weights = intArrayOf(2, 3), profits = intArrayOf(5, 4), xs = intArrayOf(0, 1), w = 2, p = 3),
            ),
        ),
    )

    @Test fun table() = feasibleSampleIsSat(
        "table",
        Problem(
            0,
            2,
            Array(2) { IntDomain(0, 2) },
            arrayOf<Factor>(
                Table(xs = intArrayOf(0, 1), tuples = intArrayOf(0, 0, 1, 2, 2, 1)),
            ),
        ),
    )

    @Test fun sequence() = feasibleSampleIsSat(
        "sequence",
        Problem(
            0,
            5,
            Array(5) { IntDomain(0, 1) },
            arrayOf<Factor>(
                Sequence(low = 1, high = 2, k = 3, xs = intArrayOf(0, 1, 2, 3, 4), values = intArrayOf(1)),
            ),
        ),
    )

    @Test fun regular() = feasibleSampleIsSat(
        "regular",
        // 2-state DFA, alphabet {1,2}, q0=1, accept {1}. trans[(q-1)*S+(s-1)]: 1×1→1,1×2→2,2×1→2,2×2→1.
        Problem(
            0,
            3,
            Array(3) { IntDomain(1, 2) },
            arrayOf<Factor>(
                Regular(
                    seq = intArrayOf(0, 1, 2),
                    numStates = 2,
                    alphabetSize = 2,
                    transitions = intArrayOf(1, 2, 2, 1),
                    q0 = 1,
                    accepting = intArrayOf(1),
                ),
            ),
        ),
    )

    /** Pin every problem var (bools + int bits) to a feasible sample. Mirrors the helper in
     *  [SolverVsBitBlasterTest]. */
    private fun pinSample(cnf: CnfProblem, problem: Problem, sample: Sample): IntArray {
        val out = ArrayList<Int>()
        for (b in 0 until problem.numBoolVars) {
            out += cnf.boolVarToCnfVar[b]
            out += if (sample.bools[b]) 1 else 0
        }
        for (i in 0 until problem.numIntVars) {
            val bits = cnf.intVarBits[i]
            val offset = sample.ints[i] - cnf.intVarMin[i]
            for (k in bits.indices) {
                out += bits[k]
                out += (offset ushr k) and 1
            }
        }
        return out.toIntArray()
    }
}
