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
 * Factors with intentionally weak proposers (no override, e.g. propagation-only
 * PathTree) skip the move-set oracle. Factors whose default ±1 IntSet proposer
 * is incomplete by design (e.g. Linear with a far-away bound) run the oracle with
 * `requireImprovement = false` so spurious non-improving proposals don't fail the test —
 * the propose-cover assertion still fires.
 */
class AllFactorsOracleTest {

    // ---- Booleans ----------------------------------------------------------------

    @Test fun `clause passes the brute-force propagation and repair oracles`() {
        val f = Clause(intArrayOf(Lit.make(0, true), Lit.make(1, false), Lit.make(2, true)))
        check(f, numBoolVars = 3)
    }

    @Test fun `cardinality passes the brute-force propagation and repair oracles`() {
        val f = Cardinality(
            literals = intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)),
            min = 1,
            max = 2,
        )
        check(f, numBoolVars = 3)
    }

    @Test fun `pseudo-boolean passes the brute-force propagation and repair oracles`() {
        val f = PseudoBoolean(
            weights = intArrayOf(3, -2, 5),
            literals = intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, true)),
            op = PbOp.LE,
            bound = 4,
        )
        check(f, numBoolVars = 3)
    }

    @Test fun `xor passes the brute-force propagation and repair oracles`() {
        val f = Xor(
            literals = intArrayOf(Lit.make(0, true), Lit.make(1, true), Lit.make(2, false)),
            targetParity = 1,
        )
        check(f, numBoolVars = 3)
    }

    // ---- Reified ------------------------------------------------------------------

    @Test fun `reified linear passes the propagation and repair oracles with an exact probe`() {
        val f = ReifiedLinear(
            auxBoolVar = 0,
            coeffs = intArrayOf(1, 1),
            vars = intArrayOf(0, 1),
            op = LinearOp.LE,
            bound = 2,
        )
        check(f, numBoolVars = 1, intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2)), exactProbe = true)
    }

    @Test fun `reified pseudo-boolean passes the brute-force propagation and repair oracles`() {
        val f = ReifiedPseudoBoolean(
            auxBoolVar = 0,
            weights = intArrayOf(1, 1, 1),
            literals = intArrayOf(Lit.make(1, true), Lit.make(2, true), Lit.make(3, true)),
            op = PbOp.LE,
            bound = 1,
        )
        check(f, numBoolVars = 4)
    }

    @Test fun `reified cardinality passes the brute-force propagation and repair oracles`() {
        val f = ReifiedCardinality(
            auxBoolVar = 0,
            literals = intArrayOf(Lit.make(1, true), Lit.make(2, true), Lit.make(3, true)),
            min = 1,
            max = 2,
        )
        check(f, numBoolVars = 4)
    }

    @Test fun `reified int compare passes the brute-force propagation and repair oracles for every operator`() {
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

    @Test fun `linear le passes the propagation and repair oracles with an exact probe`() {
        val f = Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.LE, 2)
        check(f, intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3)), exactProbe = true)
    }

    @Test fun `linear eq passes the propagation and repair oracles with an exact probe`() {
        val f = Linear(intArrayOf(1, 1, 1), intArrayOf(0, 1, 2), LinearOp.EQ, 3)
        check(f, intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)), exactProbe = true)
    }

    @Test fun `linear ge passes the propagation and repair oracles with an exact probe`() {
        val f = Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.GE, 3)
        check(f, intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3)), exactProbe = true)
    }

    @Test fun `linear ne passes the propagation and repair oracles with an exact probe`() {
        val f = Linear(intArrayOf(1, 1), intArrayOf(0, 1), LinearOp.NE, 2)
        check(f, intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2)), exactProbe = true)
    }

    @Test fun `product passes the brute-force propagation and repair oracles`() {
        val f = Product(a = 0, b = 1, result = 2)
        check(f, intDomains = arrayOf(IntDomain(-2, 2), IntDomain(-2, 2), IntDomain(-4, 4)))
    }

    // ---- Counting / occurrence ---------------------------------------------------

    @Test fun `count passes the GAC propagation and repair oracles with an exact probe`() {
        // n (the count var) is a *separate* var (3), not aliased with a counted xs element.
        val f = Count(xs = intArrayOf(0, 1, 2), v = 1, op = Count.Op.Eq, n = 3)
        check(
            f,
            intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 3)),
            exactProbe = true,
            gac = true,
        )
    }

    @Test fun `count eq forces members in when the pinned total demands them and passes the GAC oracle`() {
        // count_eq(xs, v=1) with xs ∈ {0,1}; n pinned to 2 = possible ⟹ both forced to 1.
        val f = Count(xs = intArrayOf(0, 1), v = 1, op = Count.Op.Eq, n = 2)
        check(f, intDomains = arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(2, 2)), gac = true)
    }

    @Test fun `count eq forces members out when the pinned total forbids them and passes the GAC oracle`() {
        // count_eq(xs, v=1) with xs ∈ {0,1}; n pinned to 0 = definite ⟹ both forced to 0.
        val f = Count(xs = intArrayOf(0, 1), v = 1, op = Count.Op.Eq, n = 2)
        check(f, intDomains = arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 0)), gac = true)
    }

    @Test fun `count ge forces the swing vars up to the threshold and passes the GAC oracle`() {
        // count_ge(xs, v=2) with xs ∈ {1,2,3}; n pinned to 2 = possible ⟹ both swing vars ≥ 2.
        val f = Count(xs = intArrayOf(0, 1), v = 2, op = Count.Op.Ge, n = 2)
        check(f, intDomains = arrayOf(IntDomain(1, 3), IntDomain(1, 3), IntDomain(2, 2)), gac = true)
    }

    @Test fun `among passes the GAC propagation and repair oracles with an exact probe`() {
        // n (the count var) is a *separate* var (3), not aliased with a counted xs element.
        val f = Among(n = 3, xs = intArrayOf(0, 1, 2), values = intArrayOf(1, 2))
        check(
            f,
            intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 3)),
            exactProbe = true,
            gac = true,
        )
    }

    @Test fun `among forces the swing vars to match when the pinned total demands them and passes the GAC oracle`() {
        // S = {1}; xs ∈ {0,1}; n pinned to 2 = possible ⟹ both swing vars forced to match (=1).
        val f = Among(n = 2, xs = intArrayOf(0, 1), values = intArrayOf(1))
        check(
            f,
            intDomains = arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(2, 2)),
            gac = true,
        )
    }

    @Test fun `among forbids the swing vars from matching when the pinned total is zero and passes the GAC oracle`() {
        // S = {1}; xs ∈ {0,1}; n pinned to 0 = definite ⟹ no swing var may match (both → 0).
        val f = Among(n = 2, xs = intArrayOf(0, 1), values = intArrayOf(1))
        check(
            f,
            intDomains = arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 0)),
            gac = true,
        )
    }

    @Test fun `nvalue passes the brute-force propagation and repair oracles`() {
        val f = NValue(n = 0, xs = intArrayOf(0, 1, 2), mode = NValue.Mode.Eq)
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

    @Test fun `nvalue at-most lower-bounds the count from domain-disjoint vars and passes the propagation oracle`() {
        // AtMost: n ≥ distinct(xs). x0 ∈ {0,1}, x1 ∈ {2,3} are domain-disjoint, so distinct is
        // always 2 ⟹ n ≥ 2. Exercises the greedy independent-set lower bound (sound-only;
        // nvalue is not GAC).
        val f = NValue(n = 0, xs = intArrayOf(1, 2), mode = NValue.Mode.AtMost)
        check(f, intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 1), IntDomain(2, 3)))
    }

    @Test fun `global cardinality passes the GAC propagation and repair oracles`() {
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
            gac = true,
        )
    }

    // ---- Sequencing / global -----------------------------------------------------

    @Test fun `all different passes the GAC propagation and repair oracles`() {
        val f = AllDifferent(vars = intArrayOf(0, 1, 2), domainMin = 1, domainSize = 3)
        check(f, intDomains = arrayOf(IntDomain(1, 3), IntDomain(1, 3), IntDomain(1, 3)), gac = true)
    }

    @Test fun `all different except passes the brute-force propagation and repair oracles`() {
        val f = AllDifferentExcept(xs = intArrayOf(0, 1, 2), except = intArrayOf(0))
        check(f, intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)))
    }

    @Test fun `symmetric all different passes the brute-force propagation and repair oracles`() {
        val f = SymmetricAllDifferent(xs = intArrayOf(0, 1, 2, 3))
        check(f, intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3)))
    }

    @Test fun `lex less passes the brute-force propagation and repair oracles`() {
        val f = LexLess(xs = intArrayOf(0, 1), ys = intArrayOf(2, 3), strict = false)
        check(f, intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)))
    }

    @Test fun `sequence passes the brute-force propagation and repair oracles`() {
        val f = Sequence(low = 1, high = 2, k = 2, xs = intArrayOf(0, 1, 2), values = intArrayOf(1))
        check(f, intDomains = arrayOf(IntDomain(0, 1), IntDomain(0, 1), IntDomain(0, 1)))
    }

    // ---- Array / extremum --------------------------------------------------------

    @Test fun `array min passes the propagation and repair oracles with an exact probe`() {
        val f = ArrayMinMax(result = 0, xs = intArrayOf(1, 2, 3), max = false)
        check(
            f,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3)),
            exactProbe = true,
        )
    }

    @Test fun `array max passes the propagation and repair oracles with an exact probe`() {
        val f = ArrayMinMax(result = 0, xs = intArrayOf(1, 2, 3), max = true)
        check(
            f,
            intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3), IntDomain(0, 3)),
            exactProbe = true,
        )
    }

    @Test fun `arg min passes the propagation and repair oracles with an exact probe`() {
        val f = ArgMinMax(idx = 0, xs = intArrayOf(1, 2, 3), max = false)
        check(
            f,
            intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)),
            exactProbe = true,
        )
    }

    @Test fun `arg max passes the propagation and repair oracles with an exact probe`() {
        val f = ArgMinMax(idx = 0, xs = intArrayOf(1, 2, 3), max = true)
        check(
            f,
            intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)),
            exactProbe = true,
        )
    }

    @Test fun `element with a constant array passes the propagation and repair oracles with an exact probe`() {
        // result(0) = arr[idx(1)], arr = [5,7,9] constants, idx 1-based ∈ [1,3].
        val f = Element(idx = 1, result = 0, arr = intArrayOf(5, 7, 9), arrIsVars = false, indexOffset = 1)
        check(f, intDomains = arrayOf(IntDomain(4, 10), IntDomain(0, 4)), exactProbe = true)
    }

    @Test fun `element with a variable array passes the propagation and repair oracles with an exact probe`() {
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

    @Test fun `sort passes the brute-force propagation and repair oracles`() {
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

    @Test fun `arg sort passes the brute-force propagation and repair oracles`() {
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

    @Test fun `inverse passes the brute-force propagation and repair oracles`() {
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

    @Test fun `circuit passes the brute-force propagation and repair oracles`() {
        val f = Circuit(succ = intArrayOf(0, 1, 2))
        check(f, intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)))
    }

    @Test fun `subcircuit passes the brute-force propagation and repair oracles`() {
        val f = Subcircuit(succ = intArrayOf(0, 1, 2))
        check(f, intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)))
    }

    // ---- Packing / scheduling ----------------------------------------------------

    @Test fun `bin packing passes the brute-force propagation and repair oracles`() {
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

    @Test fun `knapsack passes the brute-force propagation and repair oracles`() {
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

    @Test fun `cumulative passes the brute-force propagation and repair oracles`() {
        val f = Cumulative(
            starts = intArrayOf(0, 1, 2),
            durations = intArrayOf(2, 1, 1),
            resources = intArrayOf(1, 1, 1),
            capacity = 2,
        )
        check(f, intDomains = arrayOf(IntDomain(0, 2), IntDomain(0, 2), IntDomain(0, 2)))
    }

    @Test fun `disjunctive passes the brute-force propagation and repair oracles`() {
        val f = Disjunctive(starts = intArrayOf(0, 1), durations = intArrayOf(2, 1))
        check(f, intDomains = arrayOf(IntDomain(0, 3), IntDomain(0, 3)))
    }

    // `cumulatives` is no longer a native factor (#209) — it decomposes at the FlatZinc emit
    // site into per-machine `Cumulative` (capacity) or a time-indexed reified ≥ (min-load),
    // covered by FznCumulativesIngestTest rather than the factor oracle here.

    @Test fun `diffn passes the brute-force propagation and repair oracles`() {
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

    @Test fun `diffn with variable rectangle sizes passes the brute-force propagation and repair oracles`() {
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

    // ---- Automata ----------------------------------------------------------------

    @Test fun `regular passes the brute-force propagation and repair oracles`() {
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

    @Test fun `mdd passes the brute-force propagation and repair oracles`() {
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

    // ---- Table ------------------------------------------------------------------

    @Test fun `table passes the propagation and repair oracles with an exact probe`() {
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
     * `gac`: set true for factors that document full GAC (e.g. Régin / max-flow filtering) —
     *   asserts [FactorPropagationOracle.assertGac] (completeness: every unsupported value is
     *   pruned), not just soundness. Leave false for weaker-than-GAC propagators.
     */
    private fun check(
        factor: Factor,
        numBoolVars: Int = 0,
        intDomains: Array<IntDomain> = emptyArray(),
        label: String? = null,
        exactProbe: Boolean = false,
        gac: Boolean = false,
    ) {
        val name = label ?: factor::class.simpleName ?: "factor"
        val problem = Problem(
            numBoolVars = numBoolVars,
            numIntVars = intDomains.size,
            intDomains = intDomains,
            factors = listOf(factor),
        )
        if (gac) {
            FactorPropagationOracle.assertGac(
                problem,
                name,
            )
        } else {
            FactorPropagationOracle.assertSound(problem, name)
        }
        MoveSetOracle.assertRepairsCoverImproving(problem, name, iters = 40, requireImprovement = false)
        DegreeConsistencyOracle.assertConsistent(problem, name, exactProbe = exactProbe)
    }

    // ---- Holey-domain propagation guard ------------------------------------------
    //
    // The matching/Régin/max-flow factors must stay sound — and, where they claim GAC,
    // complete — when per-var domains carry *interior holes* (e.g. {0,2}). The prior
    // false-UNSAT bug was exactly a holey-domain free-value-reachability defect, and every
    // case above uses contiguous IntDomain(a,b), so without these the regression net has a
    // hole-shaped gap. These run the propagation oracle only (the move-set / degree-probe
    // oracles are exercised by the contiguous cases above).

    /** Build a holey domain `[lo..hi]` minus each value in [holes]. */
    private fun holey(lo: Int, hi: Int, vararg holes: Int): IntDomain {
        var d = IntDomain(lo, hi)
        for (h in holes) d = d.excludeValue(h)
        return d
    }

    private fun checkPropagation(factor: Factor, intDomains: Array<IntDomain>, label: String, gac: Boolean) {
        val problem = Problem(
            numBoolVars = 0,
            numIntVars = intDomains.size,
            intDomains = intDomains,
            factors = listOf(factor),
        )
        if (gac) {
            FactorPropagationOracle.assertGac(
                problem,
                label,
            )
        } else {
            FactorPropagationOracle.assertSound(problem, label)
        }
    }

    @Test fun `all different with holey domains passes the GAC propagation oracle`() {
        // x0,x1 ∈ {0,2} between them must take {0,2}, forcing x2 ∈ {0,1,2} to 1 (GAC prune).
        val f = AllDifferent(vars = intArrayOf(0, 1, 2), domainMin = 0, domainSize = 3)
        checkPropagation(
            f,
            arrayOf(holey(0, 2, 1), holey(0, 2, 1), IntDomain(0, 2)),
            "AllDifferent.holey-forced",
            gac = true,
        )
    }

    @Test fun `all different with holey domains detects the Hall violation under the GAC oracle`() {
        // Three distinct vars all confined to the two-value set {0,2} — a Hall violation
        // (false-UNSAT-class probe: a reachability-orientation bug must not over- or under-call it).
        val f = AllDifferent(vars = intArrayOf(0, 1, 2), domainMin = 0, domainSize = 3)
        checkPropagation(
            f,
            arrayOf(holey(0, 2, 1), holey(0, 2, 1), holey(0, 2, 1)),
            "AllDifferent.holey-unsat",
            gac = true,
        )
    }

    @Test fun `global cardinality with holey domains passes the GAC propagation oracle`() {
        // count[0]=#zeros, count[1]=#twos across xs=(2,3,4) ∈ {0,2}; counts ∈ [0,3].
        val f = GlobalCardinality(
            xs = intArrayOf(2, 3, 4),
            cover = intArrayOf(0, 2),
            countVars = intArrayOf(0, 1),
        )
        checkPropagation(
            f,
            arrayOf(IntDomain(0, 3), IntDomain(0, 3), holey(0, 2, 1), holey(0, 2, 1), holey(0, 2, 1)),
            "GlobalCardinality.holey",
            gac = true,
        )
    }

    @Test fun `all different except with holey domains passes the propagation oracle`() {
        // alldifferent_except({0}) with interior holes; weaker-than-GAC, soundness only.
        val f = AllDifferentExcept(xs = intArrayOf(0, 1, 2), except = intArrayOf(0))
        checkPropagation(
            f,
            arrayOf(holey(0, 3, 1), holey(0, 3, 2), IntDomain(0, 3)),
            "AllDifferentExcept.holey",
            gac = false,
        )
    }

    @Test fun `symmetric all different with holey domains passes the propagation oracle`() {
        val f = SymmetricAllDifferent(xs = intArrayOf(0, 1, 2, 3))
        checkPropagation(
            f,
            arrayOf(holey(0, 3, 1), holey(0, 3, 2), IntDomain(0, 3), IntDomain(0, 3)),
            "SymmetricAllDifferent.holey",
            gac = false,
        )
    }

    @Test fun `inverse with holey domains passes the propagation oracle`() {
        val f = Inverse(f = intArrayOf(0, 1, 2), g = intArrayOf(3, 4, 5))
        checkPropagation(
            f,
            arrayOf(
                holey(0, 2, 1),
                IntDomain(0, 2),
                IntDomain(0, 2),
                IntDomain(0, 2),
                holey(0, 2, 1),
                IntDomain(0, 2),
            ),
            "Inverse.holey",
            gac = false,
        )
    }
}
