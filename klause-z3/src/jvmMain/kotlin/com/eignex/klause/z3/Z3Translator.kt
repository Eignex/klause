package com.eignex.klause.z3

import com.eignex.klause.ast.PbOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.AllDifferent
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.factor.Product
import com.eignex.klause.solver.factor.PseudoBoolean
import com.eignex.klause.solver.factor.ReifiedCardinality
import com.eignex.klause.solver.factor.ReifiedLinear
import com.eignex.klause.solver.factor.ReifiedPseudoBoolean
import com.eignex.klause.solver.factor.Xor
import com.eignex.klause.solver.decompose.DecompositionContext
import com.eignex.klause.solver.decompose.FactorDecomposer
import com.eignex.klause.solver.IntDomain
import com.microsoft.z3.ArithExpr
import com.microsoft.z3.BoolExpr
import com.microsoft.z3.BoolSort
import com.microsoft.z3.Context
import com.microsoft.z3.IntExpr
import com.microsoft.z3.IntSort
import com.microsoft.z3.RealExpr
import com.microsoft.z3.RealSort

/**
 * Result of translating a klause [Problem] into Z3 expressions. Holds the boolean and
 * integer Z3 variables corresponding to the original problem's vars; [decode] uses them
 * to lift a Z3 model back to klause types.
 */
internal class Z3Encoding(
    val ctx: Context,
    /** Bool consts indexed by var id. Entries `[0, numOriginalBoolVars)` correspond to
     *  the original problem's bool variables; entries beyond are aux vars allocated by
     *  the [FactorDecomposer] (one per fresh bool requested during decomposition). The
     *  sample-decoding path uses [numOriginalBoolVars] to slice off just the user-facing
     *  prefix. */
    val boolExprs: Array<BoolExpr>,
    val intExprs: Array<IntExpr>,
    /** Number of bool consts that correspond to the original problem's variables;
     *  `boolExprs.size - numOriginalBoolVars` are decomposition aux. */
    val numOriginalBoolVars: Int,
    val numOriginalIntVars: Int,
    /** Real-sorted Z3 expressions for each float var in [com.eignex.klause.solver.Problem.floatMetadata].
     *  Empty when the problem has no float metadata. Z3 reasons over LRA directly here —
     *  much faster than the bucketed-int path. */
    val realExprs: Array<RealExpr> = emptyArray(),
)

/**
 * Translation result: variable encoding plus the BoolExprs to assert. Constraints split
 * into [auxiliary] (var domains, real-link bookkeeping — not user constraints, never
 * appear in unsat cores) and [factorExprs] (parallel to [com.eignex.klause.solver.Problem.factors],
 * one expression per factor in id order). The split lets `solve` track only factor
 * constraints when [com.eignex.klause.z3.Z3Solver] is asked for an unsat core.
 */
internal class Z3Translation(
    val encoding: Z3Encoding,
    val auxiliary: List<BoolExpr>,
    val factorExprs: List<BoolExpr>,
) {
    /** All constraints flattened in `auxiliary ++ factorExprs` order. Used by callers
     *  that don't care about the source split (sample / enumerate / minimize). */
    fun allConstraints(): List<BoolExpr> = auxiliary + factorExprs
}

/**
 * Direct (non-bit-blasted) SMT translation of a klause [Problem] to Z3. Each factor type
 * maps to a native Z3 expression — Z3 reasons over integers natively, so this catches
 * bit-blaster bugs the LogicNG path inherits.
 */
internal object Z3Translator {

    fun translate(problem: Problem, ctx: Context): Z3Translation {
        // First pass: decompose any factor type not in the native translator set into
        // mid-IR factors. The decomposer's [DecompositionContext] hands out fresh
        // aux var ids; we track how many were allocated so the Z3 const arrays can be
        // sized to fit both the original problem and the new aux.
        val decomposedFactors = ArrayList<Factor>(problem.factors.size)
        val factorSpans = ArrayList<IntRange>(problem.factors.size)
        val decomposeCtx = Z3DecomposeContext(
            startBool = problem.numBoolVars,
            startInt = problem.numIntVars,
            originalIntDomains = problem.intDomains,
        )
        for (factor in problem.factors) {
            val start = decomposedFactors.size
            if (isNativeZ3(factor)) {
                decomposedFactors.add(factor)
            } else {
                val pieces = FactorDecomposer.decompose(factor, decomposeCtx)
                    ?: error("Z3Translator: unsupported factor type ${factor::class.simpleName} and no decomposition registered")
                decomposedFactors.addAll(pieces)
            }
            factorSpans.add(start until decomposedFactors.size)
        }
        val totalBoolVars = decomposeCtx.nextBool
        val totalIntVars = decomposeCtx.nextInt

        val boolExprs: Array<BoolExpr> = Array(totalBoolVars) { i ->
            ctx.mkBoolConst(if (i < problem.numBoolVars) "b$i" else "aux_b$i") as BoolExpr
        }
        val intExprs: Array<IntExpr> = Array(totalIntVars) { i ->
            ctx.mkIntConst(if (i < problem.numIntVars) "i$i" else "aux_i$i") as IntExpr
        }
        // Native-real handling for problems with float metadata. Each float var becomes
        // a Z3 Real const; the original real-valued constraints are emitted as LRA
        // arithmetic alongside the bucketed-int versions already in `problem.factors`.
        // Z3 reasons over both — LRA accelerates the real side, the int constraints
        // anchor the bucket index to the user's schema-declared grid.
        val meta = problem.floatMetadata
        val realExprs: Array<RealExpr> =
            if (meta == null) emptyArray()
            else Array(meta.numFloatVars) { i ->
                ctx.mkRealConst("r$i") as RealExpr
            }

        val encoding = Z3Encoding(
            ctx = ctx,
            boolExprs = boolExprs,
            intExprs = intExprs,
            numOriginalBoolVars = problem.numBoolVars,
            numOriginalIntVars = problem.numIntVars,
            realExprs = realExprs,
        )

        val auxiliary = ArrayList<BoolExpr>()
        // Int-domain constraints — we always add these, even for float-backing int vars,
        // so the bucket index Z3 picks lives in `[0, buckets - 1]`.
        for (i in 0 until problem.numIntVars) {
            val d = problem.intDomains[i]
            auxiliary.add(ctx.mkAnd(
                ctx.mkGe(intExprs[i], ctx.mkInt(d.min)),
                ctx.mkLe(intExprs[i], ctx.mkInt(d.max)),
            ))
        }
        // Domain constraints for decomposer-allocated aux int vars.
        for ((id, dom) in decomposeCtx.auxIntDomains) {
            auxiliary.add(ctx.mkAnd(
                ctx.mkGe(intExprs[id], ctx.mkInt(dom.min)),
                ctx.mkLe(intExprs[id], ctx.mkInt(dom.max)),
            ))
        }
        // Native-real domain constraints and the link `real = lo + bucket * step` so
        // the int bucket and the real value stay consistent.
        if (meta != null) {
            for (i in 0 until meta.numFloatVars) {
                val ivl = meta.intervals[i]
                auxiliary.add(ctx.mkAnd(
                    ctx.mkGe(realExprs[i], ctx.mkReal(ivl.lo.toString())),
                    ctx.mkLe(realExprs[i], ctx.mkReal(ivl.hi.toString())),
                ))
                val buckets = meta.bucketCounts[i]
                val step = if (buckets > 1) (ivl.hi - ivl.lo) / (buckets - 1) else 0.0
                val intVar = intExprs[meta.intVarByFloatVar[i]]
                @Suppress("UNCHECKED_CAST")
                val bucketReal = ctx.mkInt2Real(intVar) as ArithExpr<RealSort>
                @Suppress("UNCHECKED_CAST")
                val linked = ctx.mkAdd(
                    ctx.mkReal(ivl.lo.toString()),
                    ctx.mkMul(ctx.mkReal(step.toString()), bucketReal),
                ) as ArithExpr<RealSort>
                auxiliary.add(ctx.mkEq(realExprs[i], linked))
            }
            // Native-real linear constraints — auxiliary because they're derived from
            // factor metadata, not directly from a Problem.factors entry; they exist to
            // accelerate the LRA path alongside the int constraints, so attributing an
            // unsat core to them isn't useful.
            for (c in meta.constraints) {
                auxiliary.add(translateRealLinear(c, encoding, ctx))
            }
        }
        // We keep BOTH the bucketed-int factors and the native-real constraints on the
        // float-backing vars. The real-arithmetic constraints accelerate Z3's LRA path;
        // the int constraints anchor the chosen bucket to one that respects the user's
        // schema-declared precision. (Skipping the bucketed factors would let Z3 pick
        // real values that decode to wrong-side buckets when the grid is coarse.) The
        // perf hit is small because the int Linear factors are short and Z3's mixed
        // int-real Simplex handles them efficiently.
        // Translate the post-decomposition factor list. One Z3 BoolExpr per *original*
        // factor: when a factor decomposed to multiple pieces, AND them so the cores
        // / minimize loop still sees a 1-to-1 mapping with `problem.factors`.
        val piecewise = ArrayList<BoolExpr>(decomposedFactors.size)
        for (factor in decomposedFactors) {
            piecewise.add(translateFactor(factor, encoding, ctx))
        }
        val factorExprs = ArrayList<BoolExpr>(problem.factors.size)
        for (span in factorSpans) {
            factorExprs.add(when (span.last - span.first + 1) {
                0 -> ctx.mkTrue()
                1 -> piecewise[span.first]
                else -> ctx.mkAnd(*piecewise.subList(span.first, span.last + 1).toTypedArray())
            })
        }
        return Z3Translation(encoding, auxiliary, factorExprs)
    }

    /** True for factor types this translator handles natively (no decomposition). */
    private fun isNativeZ3(f: Factor): Boolean = when (f) {
        is Clause, is Cardinality, is Linear, is PseudoBoolean, is Xor,
        is AllDifferent, is Product,
        is ReifiedLinear, is ReifiedPseudoBoolean, is ReifiedCardinality,
        -> true
        else -> false
    }

    /** [DecompositionContext] that hands out fresh klause var ids for aux variables;
     *  the Z3 translator allocates Z3 const decls for them in a second pass. */
    private class Z3DecomposeContext(
        startBool: Int,
        startInt: Int,
        private val originalIntDomains: Array<IntDomain>,
    ) : DecompositionContext {
        var nextBool: Int = startBool
        var nextInt: Int = startInt
        val auxIntDomains = ArrayList<Pair<Int, IntDomain>>()
        private val auxDomainLookup = HashMap<Int, IntDomain>()
        override fun freshBool(): Int = nextBool++
        override fun freshInt(domain: IntDomain): Int {
            val id = nextInt++
            auxIntDomains.add(id to domain)
            auxDomainLookup[id] = domain
            return id
        }
        override fun intDomainOf(id: Int): IntDomain =
            if (id < originalIntDomains.size) originalIntDomains[id]
            else auxDomainLookup[id]
                ?: error("Z3DecomposeContext: unknown int var id $id")
    }

    /** Translate a [com.eignex.klause.solver.RealLinearConstraint] into native Z3 real arithmetic. */
    private fun translateRealLinear(
        c: com.eignex.klause.solver.RealLinearConstraint,
        e: Z3Encoding,
        ctx: Context,
    ): BoolExpr {
        val terms = Array<ArithExpr<RealSort>>(c.coeffs.size) { i ->
            ctx.mkMul(ctx.mkReal(c.coeffs[i].toString()), e.realExprs[c.floatVarIds[i]])
        }
        @Suppress("UNCHECKED_CAST")
        val sum = ctx.mkAdd(*terms) as ArithExpr<RealSort>
        val bound = ctx.mkReal(c.bound.toString())
        return when (c.op) {
            LinearOp.LE -> ctx.mkLe(sum, bound)
            LinearOp.EQ -> ctx.mkEq(sum, bound)
            LinearOp.GE -> ctx.mkGe(sum, bound)
            LinearOp.NE -> ctx.mkNot(ctx.mkEq(sum, bound))
        }
    }

    private fun translateFactor(factor: Factor, e: Z3Encoding, ctx: Context): BoolExpr = when (factor) {
        is Clause -> orOfLits(factor.literals, e, ctx)
        is Cardinality -> {
            val sum = sumOfLitInts(factor.literals, e, ctx)
            val n = ctx.mkInt(factor.literals.size).let { _ -> sum } // keep ref
            ctx.mkAnd(
                ctx.mkGe(n, ctx.mkInt(factor.min)),
                ctx.mkLe(n, ctx.mkInt(factor.max)),
            )
        }
        is Linear -> {
            val sum = weightedIntSum(factor.coeffs, factor.vars, e, ctx)
            opLinear(sum, factor.op, factor.bound, ctx)
        }
        is PseudoBoolean -> {
            val sum = weightedLitSum(factor.weights, factor.literals, e, ctx)
            opPb(sum, factor.op, factor.bound, ctx)
        }
        is Xor -> {
            // XOR of all literals == targetParity
            // Z3's mkXor is binary, so fold it.
            var acc: BoolExpr = ctx.mkFalse()
            for (lit in factor.literals) acc = ctx.mkXor(acc, litExpr(lit, e, ctx))
            if (factor.targetParity == 1) acc else ctx.mkNot(acc)
        }
        is AllDifferent -> {
            val operands = factor.vars.map { e.intExprs[it] as ArithExpr<IntSort> }
            ctx.mkDistinct(*operands.toTypedArray())
        }
        is Product -> ctx.mkEq(
            e.intExprs[factor.result],
            ctx.mkMul(e.intExprs[factor.a], e.intExprs[factor.b]),
        )
        is ReifiedLinear -> {
            val sum = weightedIntSum(factor.coeffs, factor.vars, e, ctx)
            ctx.mkIff(e.boolExprs[factor.auxBoolVar], opLinear(sum, factor.op, factor.bound, ctx))
        }
        is ReifiedPseudoBoolean -> {
            val sum = weightedLitSum(factor.weights, factor.literals, e, ctx)
            ctx.mkIff(e.boolExprs[factor.auxBoolVar], opPb(sum, factor.op, factor.bound, ctx))
        }
        is ReifiedCardinality -> {
            val sum = sumOfLitInts(factor.literals, e, ctx)
            val pred = ctx.mkAnd(
                ctx.mkGe(sum, ctx.mkInt(factor.min)),
                ctx.mkLe(sum, ctx.mkInt(factor.max)),
            )
            ctx.mkIff(e.boolExprs[factor.auxBoolVar], pred)
        }
        else -> error("Z3Translator: factor type ${factor::class.simpleName} reached translator without decomposition (bug in isNativeZ3 / FactorDecomposer)")
    }

    /** Boolean expression for a klause literal — `boolExprs[v]` or its negation. */
    private fun litExpr(lit: Int, e: Z3Encoding, ctx: Context): BoolExpr {
        val v = e.boolExprs[Lit.variable(lit)]
        return if (Lit.isPositive(lit)) v else ctx.mkNot(v)
    }

    /** OR of klause literals as Z3 expression. */
    private fun orOfLits(literals: IntArray, e: Z3Encoding, ctx: Context): BoolExpr {
        val operands = Array(literals.size) { litExpr(literals[it], e, ctx) }
        return ctx.mkOr(*operands)
    }

    /** Σ of (literal as 0/1) over the given literals. */
    private fun sumOfLitInts(literals: IntArray, e: Z3Encoding, ctx: Context): IntExpr {
        if (literals.isEmpty()) return ctx.mkInt(0) as IntExpr
        val terms = Array(literals.size) { litToInt(literals[it], e, ctx) }
        @Suppress("UNCHECKED_CAST")
        return ctx.mkAdd(*terms) as IntExpr
    }

    /** Σ coeffs[i] * intExprs[vars[i]] */
    private fun weightedIntSum(coeffs: IntArray, vars: IntArray, e: Z3Encoding, ctx: Context): IntExpr {
        val terms = Array<ArithExpr<IntSort>>(coeffs.size) { i ->
            ctx.mkMul(ctx.mkInt(coeffs[i]), e.intExprs[vars[i]])
        }
        @Suppress("UNCHECKED_CAST")
        return ctx.mkAdd(*terms) as IntExpr
    }

    /** Σ weights[i] * (literal[i] as 0/1) */
    private fun weightedLitSum(weights: IntArray, literals: IntArray, e: Z3Encoding, ctx: Context): IntExpr {
        val terms = Array<ArithExpr<IntSort>>(weights.size) { i ->
            ctx.mkMul(ctx.mkInt(weights[i]), litToInt(literals[i], e, ctx))
        }
        @Suppress("UNCHECKED_CAST")
        return ctx.mkAdd(*terms) as IntExpr
    }

    /** A klause literal as a Z3 0/1 IntExpr (1 when the literal is true). */
    private fun litToInt(lit: Int, e: Z3Encoding, ctx: Context): IntExpr {
        val v = e.boolExprs[Lit.variable(lit)]
        val bool: BoolExpr = if (Lit.isPositive(lit)) v else ctx.mkNot(v)
        @Suppress("UNCHECKED_CAST")
        return ctx.mkITE(bool, ctx.mkInt(1), ctx.mkInt(0)) as IntExpr
    }

    private fun opLinear(sum: IntExpr, op: LinearOp, bound: Int, ctx: Context): BoolExpr {
        val b = ctx.mkInt(bound)
        return when (op) {
            LinearOp.LE -> ctx.mkLe(sum, b)
            LinearOp.EQ -> ctx.mkEq(sum, b)
            LinearOp.GE -> ctx.mkGe(sum, b)
            LinearOp.NE -> ctx.mkNot(ctx.mkEq(sum, b))
        }
    }

    private fun opPb(sum: IntExpr, op: PbOp, bound: Int, ctx: Context): BoolExpr {
        val b = ctx.mkInt(bound)
        return when (op) {
            PbOp.LE -> ctx.mkLe(sum, b)
            PbOp.GE -> ctx.mkGe(sum, b)
            PbOp.EQ -> ctx.mkEq(sum, b)
        }
    }
}
