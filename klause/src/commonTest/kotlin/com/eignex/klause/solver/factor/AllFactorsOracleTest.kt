package com.eignex.klause.solver.factor

import com.eignex.klause.ast.IntCmpOp
import com.eignex.klause.ast.PbOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import kotlin.test.Test

/**
 * Brute-force oracle pass over every [com.eignex.klause.solver.Factor]. For each factor we
 * build a small enough instance for [com.eignex.klause.solver.brute.BruteForceSolver] to
 * enumerate the full assignment space, then assert:
 *  - [FactorPropagationOracle.assertSound] — propagate's deductions hold on every satisfying
 *    assignment (no false pin / bound / hole).
 *  - [MoveSetOracle.assertRepairsCoverImproving] — `proposeRepairMoves` on a violating state
 *    covers at least one strictly-improving 1-step move when brute finds one.
 *
 * Factors with intentionally weak proposers (no override, e.g. propagation-only Geost /
 * MinCostFlow / PathTree) skip the move-set oracle. Factors whose default ±1 IntSet proposer
 * is incomplete by design (e.g. Linear with a far-away bound) run the oracle with
 * `requireImprovement = false` so spurious non-improving proposals don't fail the test —
 * the propose-cover assertion still fires.
 */
class AllFactorsOracleTest {

    // ---- Booleans ----------------------------------------------------------------

    @Test fun clause() {
        val f = Clause(intArrayOf(Lit.make(0, true), Lit.make(1, false), Lit.make(2, true)))
        check(f, numBoolVars = 3)
    }

    @Test fun cardinality() {
        val f = Cardinality(
            literals = intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)),
            min = 1,
            max = 2,
        )
        check(f, numBoolVars = 3)
    }

    @Test fun pseudoBoolean() {
        val f = PseudoBoolean(
            weights = intArrayOf(3, -2, 5),
            literals = intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)),
            op = PbOp.LE,
            bound = 4,
        )
        check(f, numBoolVars = 3)
    }

    @Test fun xor() {
        val f = Xor(
            literals = intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, false)),
            targetParity = 1,
        )
        check(f, numBoolVars = 3)
    }

    // ---- Reified ------------------------------------------------------------------

    @Test fun reifiedLinear() {
        val f = ReifiedLinear(
            auxBoolVar = 0,
            coeffs = intArrayOf(1, 1),
            vars = intArrayOf(0, 1),
            op = LinearOp.LE,
            bound = 2,
        )
        check(f, numBoolVars = 1, intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2)), exactProbe = true)
    }

    @Test fun reifiedPseudoBoolean() {
        val f = ReifiedPseudoBoolean(
            auxBoolVar = 0,
            weights = intArrayOf(1, 1, 1),
            literals = intArrayOf(Lit.make(1, true), Lit.make(2, true), Lit.make(3, true)),
            op = PbOp.LE,
            bound = 1,
        )
        check(f, numBoolVars = 4)
    }

    @Test fun reifiedCardinality() {
        val f = ReifiedCardinality(
            auxBoolVar = 0,
            literals = intArrayOf(Lit.make(1, true), Lit.make(2, true), Lit.make(3, true)),
            min = 1,
            max = 2,
        )
        check(f, numBoolVars = 4)
    }

    @Test fun reifiedIntCompare() {
        for (op in IntCmpOp.entries) {
            val f = reifiedIntCompare(auxBoolVar = 0, intVar = 0, op = op, bound = 1)
            check(
                f,
                numBoolVars = 1,
                intDomains = arrayOf(IntDomain(-1, 2)),
                label = "reifiedIntCompare.$op",
            )
        }
    }

    // ---- Linear / arithmetic -----------------------------------------------------

    @Test fun linearLe() {
        val f = Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 2)
        check(f, intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3)), exactProbe = true)
    }

    @Test fun linearEq() {
        val f = Linear(intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.EQ, 3)
        check(f, intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)), exactProbe = true)
    }

    @Test fun linearGe() {
        val f = Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 3)
        check(f, intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3)), exactProbe = true)
    }

    @Test fun linearNe() {
        val f = Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.NE, 2)
        check(f, intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2)), exactProbe = true)
    }

    @Test fun product() {
        val f = Product(a = 0, b = 1, result = 2)
        check(f, intDomains = arrayOf(IntDomain(-2, 2), IntDomain(-2, 2), IntDomain(-4, 4)))
    }

    // ---- Counting / occurrence ---------------------------------------------------

    @Test fun count() {
        // n (the count var) is a *separate* var (3), not aliased with a counted xs element.
        val f = Count(xs = intArrayOf(0, 1, 2), v = 1, op = Count.Op.Eq, n = 3)
        check(
            f,
            intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 3)),
            exactProbe = true,
        )
    }

    @Test fun among() {
        // n (the count var) is a *separate* var (3), not aliased with a counted xs element.
        val f = Among(n = 3, xs = intArrayOf(0, 1, 2), values = intArrayOf(1, 2))
        check(
            f,
            intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 3)),
            exactProbe = true,
        )
    }

    @Test fun nValue() {
        val f = NValue(n = 0, xs = intArrayOf(0, 1, 2), mode = NValue.Mode.Eq)
        // n is an int var (index 0 in problem ordering after xs? actually xs is intVars[0..2], n is intVars[3])
        // Need to know NValue's intVars layout. Build per its constructor — xs are indices into int var space.
        check(
            f,
            intDomains = arrayOf(
                IntDomain(1, 3), // n
                IntDomain(0, 2),
                IntDomain(0, 2),
                IntDomain(0, 2),
            ),
        )
    }

    @Test fun globalCardinality() {
        // count[0]=#zeros, count[1]=#ones across xs=(2,3,4), all ∈ {0,1}; counts ∈ [0,3].
        val f = GlobalCardinality(
            xs = intArrayOf(2, 3, 4),
            cover = intArrayOf(0, 1),
            countVars = intArrayOf(0, 1),
        )
        check(
            f,
            intDomains = arrayOf(
                IntDomain(0, 3),
                IntDomain(0, 3),
                IntDomain(0, 1),
                IntDomain(0, 1),
                IntDomain(0, 1),
            ),
        )
    }

    // ---- Sequencing / global -----------------------------------------------------

    @Test fun allDifferent() {
        val f = AllDifferent(vars = intArrayOf(0, 1, 2), domainMin = 1, domainSize = 3)
        check(f, intDomains = arrayOf(IntDomain(1, 3), IntDomain(1, 3), IntDomain(1, 3)))
    }

    @Test fun allDifferentExcept() {
        val f = AllDifferentExcept(xs = intArrayOf(0, 1, 2), except = intArrayOf(0))
        check(f, intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)))
    }

    @Test fun allDifferentExceptZero() {
        val f = AllDifferentExceptZero(xs = intArrayOf(0, 1, 2))
        check(f, intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)))
    }

    @Test fun allEqual() {
        val f = AllEqual(xs = intArrayOf(0, 1, 2))
        check(f, intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)))
    }

    @Test fun symmetricAllDifferent() {
        val f = SymmetricAllDifferent(xs = intArrayOf(0, 1, 2, 3))
        check(f, intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3)))
    }

    @Test fun lexLess() {
        val f = LexLess(xs = intArrayOf(0, 1), ys = intArrayOf(2, 3), strict = false)
        check(f, intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)))
    }

    @Test fun monotoneIncreasing() {
        val f = Monotone(xs = intArrayOf(0, 1, 2), direction = Monotone.Direction.Increasing, strict = false)
        check(f, intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)))
    }

    @Test fun valuePrecede() {
        val f = ValuePrecede(s = 0, t = 1, xs = intArrayOf(0, 1, 2))
        check(f, intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)))
    }

    @Test fun sequence() {
        val f = Sequence(low = 1, high = 2, k = 2, xs = intArrayOf(0, 1, 2), values = intArrayOf(1))
        check(f, intDomains = arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 1)))
    }

    // ---- Array / extremum --------------------------------------------------------

    @Test fun arrayMin() {
        val f = ArrayMinMax(result = 0, xs = intArrayOf(1, 2, 3), max = false)
        check(
            f,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3)),
            exactProbe = true,
        )
    }

    @Test fun arrayMax() {
        val f = ArrayMinMax(result = 0, xs = intArrayOf(1, 2, 3), max = true)
        check(
            f,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3)),
            exactProbe = true,
        )
    }

    @Test fun argMin() {
        val f = ArgMinMax(idx = 0, xs = intArrayOf(1, 2, 3), max = false)
        check(
            f,
            intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)),
            exactProbe = true,
        )
    }

    @Test fun argMax() {
        val f = ArgMinMax(idx = 0, xs = intArrayOf(1, 2, 3), max = true)
        check(
            f,
            intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)),
            exactProbe = true,
        )
    }

    @Test fun member() {
        val f = Member(xs = intArrayOf(1, 2, 3), y = 0)
        check(f, intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)))
    }

    @Test fun elementConst() {
        // result(0) = arr[idx(1)], arr = [5,7,9] constants, idx 1-based ∈ [1,3].
        val f = Element(idx = 1, result = 0, arr = intArrayOf(5, 7, 9), arrIsVars = false, indexOffset = 1)
        check(f, intDomains = arrayOf(IntDomain(4, 10), IntDomain(0, 4)), exactProbe = true)
    }

    @Test fun elementVar() {
        // result(0) = arr[idx(1)], arr = vars 2,3,4; idx 1-based ∈ [1,3].
        val f = Element(idx = 1, result = 0, arr = intArrayOf(2, 3, 4), arrIsVars = true, indexOffset = 1)
        check(
            f,
            intDomains = arrayOf(
                IntDomain(0, 3), // result
                IntDomain(0, 4), // idx
                IntDomain(0, 3),
                IntDomain(0, 3),
                IntDomain(0, 3), // arr vars
            ),
            exactProbe = true,
        )
    }

    @Test fun sort() {
        val f = Sort(xs = intArrayOf(0, 1, 2), ys = intArrayOf(3, 4, 5))
        check(
            f,
            intDomains = arrayOf(
                IntDomain(0, 2),
                IntDomain(0, 2),
                IntDomain(0, 2),
                IntDomain(0, 2),
                IntDomain(0, 2),
                IntDomain(0, 2),
            ),
        )
    }

    @Test fun argSort() {
        val f = ArgSort(values = intArrayOf(0, 1, 2), perm = intArrayOf(3, 4, 5))
        check(
            f,
            intDomains = arrayOf(
                IntDomain(0, 2),
                IntDomain(0, 2),
                IntDomain(0, 2),
                IntDomain(0, 2),
                IntDomain(0, 2),
                IntDomain(0, 2),
            ),
        )
    }

    @Test fun inverse() {
        val f = Inverse(f = intArrayOf(0, 1, 2), g = intArrayOf(3, 4, 5))
        check(
            f,
            intDomains = arrayOf(
                IntDomain(0, 2),
                IntDomain(0, 2),
                IntDomain(0, 2),
                IntDomain(0, 2),
                IntDomain(0, 2),
                IntDomain(0, 2),
            ),
        )
    }

    // ---- Circuit / path ----------------------------------------------------------

    @Test fun circuit() {
        val f = Circuit(succ = intArrayOf(0, 1, 2))
        check(f, intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)))
    }

    @Test fun subcircuit() {
        val f = Subcircuit(succ = intArrayOf(0, 1, 2))
        check(f, intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)))
    }

    // ---- Packing / scheduling ----------------------------------------------------

    @Test fun binPacking() {
        val f = BinPacking(
            bins = intArrayOf(0, 1, 2),
            weights = intArrayOf(2, 1, 1),
            mode = BinPacking.Mode.UniformCapacity,
            uniformCapacity = 3,
            numBins = 2,
            binOffset = 0,
        )
        check(f, intDomains = arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 1)))
    }

    @Test fun knapsack() {
        val f = Knapsack(
            weights = intArrayOf(2, 1, 3),
            profits = intArrayOf(3, 1, 4),
            xs = intArrayOf(0, 1, 2),
            w = 3,
            p = 4,
        )
        check(
            f,
            intDomains = arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 3), IntDomain(0, 5)),
        )
    }

    @Test fun cumulative() {
        val f = Cumulative(
            starts = intArrayOf(0, 1, 2),
            durations = intArrayOf(2, 1, 1),
            resources = intArrayOf(1, 1, 1),
            capacity = 2,
        )
        check(f, intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)))
    }

    @Test fun disjunctive() {
        val f = Disjunctive(starts = intArrayOf(0, 1), durations = intArrayOf(2, 1))
        check(f, intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3)))
    }

    @Test fun slidingSum() {
        // Every window of 2 consecutive elements sums into [1, 3]; 4 vars ∈ [0,2].
        val f = SlidingSum(low = 1, up = 3, seq = 2, vs = intArrayOf(0, 1, 2, 3))
        check(
            f,
            intDomains = arrayOf(
                IntDomain(0, 2),
                IntDomain(0, 2),
                IntDomain(0, 2),
                IntDomain(0, 2),
            ),
            exactProbe = true,
        )
    }

    @Test fun cumulativesUpper() {
        // 3 tasks (starts 0,1,2 ; machines 3,4,5), 2 machines (values 0,1), cap 2 each.
        val f = Cumulatives(
            starts = intArrayOf(0, 1, 2),
            durations = intArrayOf(2, 1, 1),
            resources = intArrayOf(1, 1, 1),
            machines = intArrayOf(3, 4, 5),
            bounds = intArrayOf(2, 2),
            upper = true,
            minMachine = 0,
        )
        check(
            f,
            intDomains = arrayOf(
                IntDomain(0, 2),
                IntDomain(0, 2),
                IntDomain(0, 2), // starts
                IntDomain(0, 1),
                IntDomain(0, 1),
                IntDomain(0, 1), // machines (∈ {0,1})
            ),
            exactProbe = true,
        )
    }

    @Test fun cumulativesLower() {
        // Minimum-load (upper=false): where a machine is in use it must carry ≥ bound.
        val f = Cumulatives(
            starts = intArrayOf(0, 1),
            durations = intArrayOf(2, 2),
            resources = intArrayOf(1, 1),
            machines = intArrayOf(2, 3),
            bounds = intArrayOf(1, 1),
            upper = false,
            minMachine = 0,
        )
        check(
            f,
            intDomains = arrayOf(
                IntDomain(0, 2),
                IntDomain(0, 2), // starts
                IntDomain(0, 1),
                IntDomain(0, 1), // machines
            ),
            exactProbe = true,
        )
    }

    @Test fun diffn() {
        val f = Diffn(
            xs = intArrayOf(0, 1),
            ys = intArrayOf(2, 3),
            widths = intArrayOf(2, 1),
            heights = intArrayOf(1, 2),
        )
        check(
            f,
            intDomains = arrayOf(
                IntDomain(0, 2),
                IntDomain(0, 2),
                IntDomain(0, 2),
                IntDomain(0, 2),
            ),
        )
    }

    @Test fun diffnVarSize() {
        // Two rectangles whose widths (vars 4,5) and heights (vars 6,7) are themselves
        // variables — exercises the variable-size path (incl. propagateVarSizeSoundOnly).
        val f = Diffn(
            xs = intArrayOf(0, 1),
            ys = intArrayOf(2, 3),
            widths = IntArray(0),
            heights = IntArray(0),
            widthVars = intArrayOf(4, 5),
            heightVars = intArrayOf(6, 7),
        )
        check(
            f,
            intDomains = arrayOf(
                IntDomain(0, 2),
                IntDomain(0, 2), // xs
                IntDomain(0, 2),
                IntDomain(0, 2), // ys
                IntDomain(1, 2),
                IntDomain(1, 2), // width vars
                IntDomain(1, 2),
                IntDomain(1, 2), // height vars
            ),
        )
    }

    @Test fun geost() {
        // 2 boxes in 2D, each 2×2, origins ∈ [0,2]. Row-major origin = [o0x,o0y, o1x,o1y].
        val f = Geost(
            numDims = 2,
            numObjects = 2,
            origin = intArrayOf(0, 1, 2, 3),
            length = intArrayOf(2, 2, 2, 2),
        )
        check(
            f,
            intDomains = arrayOf(
                IntDomain(0, 2),
                IntDomain(0, 2),
                IntDomain(0, 2),
                IntDomain(0, 2),
            ),
            exactProbe = true,
        )
    }

    // ---- Automata ----------------------------------------------------------------

    @Test fun regular() {
        // Even count of '0' over alphabet {0,1}. States 1-based: 1 = even (accept), 2 = odd.
        // Alphabet symbols are also 1-based; xs values map to symbol = value (1 or 2).
        val transitions = intArrayOf(
            2,
            1, // from state 1: '1' → 2, '2' → 1
            1,
            2, // from state 2: '1' → 1, '2' → 2
        )
        val f = Regular(
            seq = intArrayOf(0, 1, 2),
            numStates = 2,
            alphabetSize = 2,
            transitions = transitions,
            q0 = 1,
            accepting = intArrayOf(1),
        )
        check(f, intDomains = arrayOf(IntDomain(1, 2), IntDomain(1, 2), IntDomain(1, 2)))
    }

    @Test fun mdd() {
        // 2-var MDD over {1,2} accepting exactly (1,2) and (2,1) — the layered form klause's
        // FZN front-end builds from `mdd(...)`. Layer0 state0: 1→s0, 2→s1 (layer1); layer1
        // s0: 2→terminal, s1: 1→terminal. Terminal (layer2 state0) is accepting.
        val f = Mdd(
            seq = intArrayOf(0, 1),
            numStatesPerLayer = intArrayOf(1, 2, 1),
            layerStarts = intArrayOf(0, 6, 12),
            transitions = intArrayOf(
                0, 1, 0, 0, 2, 1, // layer 0
                0, 2, 0, 1, 1, 0, // layer 1
            ),
            initial = 0,
            accepting = intArrayOf(0),
            recordStride = 3,
        )
        check(f, intDomains = arrayOf(IntDomain(1, 2), IntDomain(1, 2)))
    }

    // ---- Set algebra (bitset) ----------------------------------------------------

    @Test fun setBitsetSubset() {
        val f = SetBitsetSubset(leftBools = intArrayOf(0, 1, 2), rightBools = intArrayOf(3, 4, 5))
        check(f, numBoolVars = 6)
    }

    @Test fun setBitsetDisjoint() {
        val f = SetBitsetDisjoint(leftBools = intArrayOf(0, 1, 2), rightBools = intArrayOf(3, 4, 5))
        check(f, numBoolVars = 6)
    }

    @Test fun setBitsetEq() {
        val f = SetBitsetEq(leftBools = intArrayOf(0, 1, 2), rightBools = intArrayOf(3, 4, 5))
        check(f, numBoolVars = 6)
    }

    // ---- Table ------------------------------------------------------------------

    @Test fun table() {
        // x0 ∈ {0,1}, x1 ∈ {0,1}, x2 ∈ {0,1}; allowed tuples: (0,0,0), (1,1,0), (1,0,1).
        val f = Table(
            xs = intArrayOf(0, 1, 2),
            tuples = intArrayOf(0, 0, 0, 1, 1, 0, 1, 0, 1),
        )
        check(f, intDomains = arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 1)), exactProbe = true)
    }

    // ---- Helpers ----------------------------------------------------------------

    /**
     * `exactProbe`: set true for factors migrated to graded violation — asserts their
     *   `deltaIf*` probe exactly predicts the cost change (accurate CBLS gradient). Left false
     *   for factors with approximate-by-design probes (cost-tracking is still verified).
     */
    private fun check(
        factor: Factor,
        numBoolVars: Int = 0,
        intDomains: Array<IntDomain> = emptyArray(),
        label: String? = null,
        exactProbe: Boolean = false,
    ) {
        val name = label ?: factor::class.simpleName ?: "factor"
        val problem = Problem(
            numBoolVars = numBoolVars,
            numIntVars = intDomains.size,
            intDomains = intDomains,
            factors = listOf(factor),
        )
        FactorPropagationOracle.assertSound(problem, name)
        MoveSetOracle.assertRepairsCoverImproving(problem, name, iters = 40, requireImprovement = false)
        DegreeConsistencyOracle.assertConsistent(problem, name, exactProbe = exactProbe)
    }
}
