package com.eignex.klause.cnf

import com.eignex.klause.model.PbOp
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.ArrayMinMax
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.factor.Circuit
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.solver.factor.Cumulative
import com.eignex.klause.solver.factor.Diffn
import com.eignex.klause.solver.factor.Disjunctive
import com.eignex.klause.solver.factor.Element
import com.eignex.klause.solver.factor.GlobalCardinality
import com.eignex.klause.solver.factor.Inverse
import com.eignex.klause.solver.factor.LexLess
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.factor.Mdd
import com.eignex.klause.solver.factor.NValue
import com.eignex.klause.solver.factor.Product
import com.eignex.klause.solver.factor.PseudoBoolean
import com.eignex.klause.solver.factor.Regular
import com.eignex.klause.solver.factor.ReifiedCardinality
import com.eignex.klause.solver.factor.ReifiedLinear
import com.eignex.klause.solver.factor.ReifiedPseudoBoolean
import com.eignex.klause.solver.factor.Sort
import com.eignex.klause.solver.factor.Subcircuit
import com.eignex.klause.solver.factor.SymmetricAllDifferent
import com.eignex.klause.solver.factor.Table
import com.eignex.klause.solver.factor.Xor
import kotlin.math.abs
import com.eignex.klause.solver.factor.AllDifferent as AllDifferentFactor

/**
 * Compiles a [Problem] (mixed Boolean/integer factors) to propositional CNF using canonical
 * binary encoding for integer variables. Each integer variable in domain `[min, max]` gets
 * `ceil(log2(max - min + 1))` bits representing the offset from `min`; an explicit
 * `constantLeq` domain constraint is emitted when the domain size is not a power of two.
 *
 * Covers every factor type that can appear in a [Problem]. Core primitives: [Clause],
 * [Cardinality], [Linear] (all four ops including NE), [PseudoBoolean], [Xor], [Product], and
 * the reified forms ([ReifiedLinear], [ReifiedCardinality], [ReifiedPseudoBoolean]). Globals
 * are lowered directly to CNF: `AllDifferent`
 * / [SymmetricAllDifferent], [Circuit] / [Subcircuit] (MTZ position vectors), [Cumulative] /
 * [Disjunctive] (time-tabling / pairwise no-overlap), [Diffn] (2D), [NValue],
 * [GlobalCardinality], [Element], [Table], [Regular] (DFA layers), [Inverse],
 * [Sort], [LexLess], [ArrayMinMax].
 *
 * Propagation-only natives ([Mdd]) are skipped — the compile lowering already pairs them
 * with primitive decompositions BitBlaster handles directly. Out-of-domain `Linear` constants
 * are short-circuited at compile time to a true/false unit clause via [emitLinear].
 *
 * Variable-dimension forms of a few scheduling/packing globals (variable durations/resources
 * for [Cumulative], variable sizes for [Diffn]) are not yet supported and raise if encountered.
 */
object BitBlaster {

    /** Bit-blast [problem] into an equisatisfiable [CnfProblem]. */
    fun compile(problem: Problem): CnfProblem {
        val b = CnfBuilder()
        val boolMap = IntArray(problem.numBoolVars) { b.newVar() }
        val intBits = Array(problem.numIntVars) { i ->
            val d = problem.intDomains[i]
            // Width covers the full [min..max] span: values are encoded as `value - min`
            // offsets, so a domain with interior holes still needs span-many codes (the
            // holes are excluded by explicit clauses below, not by shrinking the width).
            val span = (d.max.toLong() - d.min.toLong()).coerceAtMost(Int.MAX_VALUE.toLong())
            val width = bitWidth(span.toInt()).coerceAtLeast(1)
            IntArray(width) { b.newVar() }
        }
        val intMin = IntArray(problem.numIntVars) { problem.intDomains[it].min }

        for (i in 0 until problem.numIntVars) {
            val bits = intBits[i]
            val d = problem.intDomains[i]
            val maxOffset = (d.max.toLong() - d.min.toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val capacity = if (bits.size >= 31) Int.MAX_VALUE else (1 shl bits.size) - 1
            if (maxOffset < capacity) {
                val ok = b.constantLeq(bitsToLits(bits), maxOffset)
                b.addClause(intArrayOf(ok))
            }
            // Forbid each interior hole: at least one bit must differ from the hole's offset.
            d.forEachHole { value ->
                val offset = value - d.min
                b.addClause(
                    IntArray(bits.size) { bit ->
                        Lit.make(bits[bit], positive = (offset ushr bit) and 1 == 0)
                    },
                )
            }
        }

        for (factor in problem.factors) {
            when (factor) {
                is Clause -> emitClause(b, factor.literals, boolMap)

                is Cardinality -> emitCardinality(b, factor, boolMap)

                is Linear -> emitLinear(b, factor, intBits, intMin, problem)

                is Product -> emitProduct(b, factor, intBits, intMin, problem)

                is PseudoBoolean -> emitPseudoBoolean(b, factor, boolMap)

                is ReifiedLinear -> emitReifiedLinear(b, factor, boolMap, intBits, intMin)

                is ReifiedCardinality -> emitReifiedCardinality(b, factor, boolMap)

                is ReifiedPseudoBoolean -> emitReifiedPseudoBoolean(b, factor, boolMap)

                is Xor -> emitXor(b, factor, boolMap)

                is AllDifferentFactor -> emitAllDifferent(b, factor, intBits, intMin, boolMap)

                is Disjunctive -> emitDisjunctive(b, factor, intBits, intMin, boolMap)

                is Cumulative -> emitCumulative(b, factor, intBits, intMin, boolMap, problem)

                is NValue -> emitNValue(b, factor, intBits, intMin, boolMap, problem)

                is GlobalCardinality -> emitGlobalCardinality(b, factor, intBits, intMin, boolMap)

                is Circuit -> emitCircuit(b, factor.succ, intBits, intMin, problem, sub = false)

                is Subcircuit -> emitCircuit(b, factor.succ, intBits, intMin, problem, sub = true)

                is LexLess -> emitLexLess(b, factor, intBits, intMin)

                is Element -> emitElement(b, factor, intBits, intMin)

                is Inverse -> emitInverse(
                    b,
                    factor.f,
                    factor.g,
                    factor.fOffset,
                    factor.gOffset,
                    intBits,
                    intMin,
                    problem,
                )

                is SymmetricAllDifferent -> emitInverse(
                    b,
                    factor.xs,
                    factor.xs,
                    factor.indexOffset,
                    factor.indexOffset,
                    intBits,
                    intMin,
                    problem,
                )

                is Sort -> emitSort(b, factor, intBits, intMin, problem)

                is ArrayMinMax -> emitArrayMinMax(b, factor, intBits, intMin)

                is Diffn -> emitDiffn(b, factor, intBits, intMin)

                is Table -> emitTable(b, factor, intBits, intMin)

                is Regular -> emitRegular(b, factor, intBits, intMin)

                is Mdd -> {
                    // Propagation-only native factors. The compile lowering pairs them with
                    // primitive constraints (Linear / Clause / Table / AllDifferent / Iff)
                    // that BitBlaster handles directly, so it's safe to skip the factor
                    // itself. See CompilerGlobalsLowering — each `assertX` emits the
                    // decomposition factors alongside the native one.
                }

                else -> throw UnsupportedOperationException(
                    "BitBlaster cannot lower factor type ${factor::class.simpleName}",
                )
            }
        }

        return CnfProblem(
            numVars = b.numVars,
            clauses = b.clauses.toList(),
            boolVarToCnfVar = boolMap,
            intVarBits = intBits,
            intVarMin = intMin,
        )
    }

    private fun emitClause(b: CnfBuilder, literals: IntArray, boolMap: IntArray) {
        val out = IntArray(literals.size)
        for (i in literals.indices) {
            val lit = literals[i]
            val cnfVar = boolMap[Lit.variable(lit)]
            out[i] = Lit.make(cnfVar, Lit.isPositive(lit))
        }
        b.addClause(out)
    }

    private fun emitCardinality(b: CnfBuilder, c: Cardinality, boolMap: IntArray) {
        val remapped = IntArray(c.literals.size) {
            val lit = c.literals[it]
            Lit.make(boolMap[Lit.variable(lit)], Lit.isPositive(lit))
        }
        if (c.max < c.literals.size) emitAtMostK(b, remapped, c.max)
        if (c.min > 0) {
            // at-least-min over lits ⟺ at-most-(n-min) over negated lits.
            val negated = IntArray(remapped.size) { Lit.negate(remapped[it]) }
            emitAtMostK(b, negated, remapped.size - c.min)
        }
    }

    /** Sinz 2005 sequential-counter encoding of `at-most-k`. Falls back to specialised forms
     *  for the corner cases (k=0, k=n-1, small pairwise). */
    private fun emitAtMostK(b: CnfBuilder, lits: IntArray, k: Int) {
        val n = lits.size
        if (k >= n) return
        if (k == 0) {
            for (l in lits) b.addClause(intArrayOf(Lit.negate(l)))
            return
        }
        if (k == n - 1) {
            val cl = IntArray(n) { Lit.negate(lits[it]) }
            b.addClause(cl)
            return
        }
        if (k == 1 && n <= 4) {
            for (i in 0 until n) {
                for (j in i + 1 until n) {
                    b.addClause(intArrayOf(Lit.negate(lits[i]), Lit.negate(lits[j])))
                }
            }
            return
        }
        val s = Array(n - 1) { IntArray(k) { Lit.make(b.newVar(), positive = true) } }
        b.addClause(intArrayOf(Lit.negate(lits[0]), s[0][0]))
        for (j in 1 until k) b.addClause(intArrayOf(Lit.negate(s[0][j])))
        for (i in 1 until n - 1) {
            b.addClause(intArrayOf(Lit.negate(lits[i]), s[i][0]))
            b.addClause(intArrayOf(Lit.negate(s[i - 1][0]), s[i][0]))
            for (j in 1 until k) {
                b.addClause(intArrayOf(Lit.negate(lits[i]), Lit.negate(s[i - 1][j - 1]), s[i][j]))
                b.addClause(intArrayOf(Lit.negate(s[i - 1][j]), s[i][j]))
            }
            b.addClause(intArrayOf(Lit.negate(lits[i]), Lit.negate(s[i - 1][k - 1])))
        }
        b.addClause(intArrayOf(Lit.negate(lits[n - 1]), Lit.negate(s[n - 2][k - 1])))
    }

    private fun emitAllDifferent(
        b: CnfBuilder,
        f: AllDifferentFactor,
        intBits: Array<IntArray>,
        intMin: IntArray,
        boolMap: IntArray,
    ) {
        // Pairwise NE via offset-shifted bit-vector equality. For two vars i, j with possibly
        // different domains, compare their actual values: i_actual = iMin + decode(iBits) and
        // similarly for j. NE on actuals ⟺ NE on (iBits + (iMin - jMin)) and jBits when both
        // shifted to a common reference. Simpler: rebuild both as canonical bit-vectors of equal
        // width via a small offset add, then assert unsignedEq is false.
        //
        // Opt-aware: when [presents] is non-empty, gate each pairwise NE on the conjunction
        // of both presence bits — `(present_i ∧ present_j) → (i ≠ j)`. Encoded as
        //   `¬present_i ∨ ¬present_j ∨ ¬eq(i, j)`.
        // The non-opt case (empty presents) still emits the bare unit clause.
        val hasPresents = f.presents.isNotEmpty()
        for (i in f.vars.indices) {
            for (j in i + 1 until f.vars.size) {
                val a = f.vars[i]
                val c = f.vars[j]
                val aMin = intMin[a]
                val cMin = intMin[c]
                val aLits = bitsToLits(intBits[a])
                val cLits = bitsToLits(intBits[c])
                val base = minOf(aMin, cMin)
                val aShifted = if (aMin == base) aLits else b.rippleAdd(aLits, constantLits(b, (aMin - base).toLong()))
                val cShifted = if (cMin == base) cLits else b.rippleAdd(cLits, constantLits(b, (cMin - base).toLong()))
                val notEq = Lit.negate(b.unsignedEq(aShifted, cShifted))
                if (!hasPresents) {
                    b.addClause(intArrayOf(notEq))
                } else {
                    val pi = f.presents[i]
                    val pj = f.presents[j]
                    val cnfPi = Lit.make(boolMap[Lit.variable(pi)], Lit.isPositive(pi))
                    val cnfPj = Lit.make(boolMap[Lit.variable(pj)], Lit.isPositive(pj))
                    b.addClause(intArrayOf(Lit.negate(cnfPi), Lit.negate(cnfPj), notEq))
                }
            }
        }
    }

    private fun emitXor(b: CnfBuilder, f: Xor, boolMap: IntArray) {
        val remapped = IntArray(f.literals.size) {
            val lit = f.literals[it]
            Lit.make(boolMap[Lit.variable(lit)], Lit.isPositive(lit))
        }
        // Chain pairwise XOR to combine all literals into a single parity literal.
        var acc = remapped[0]
        for (i in 1 until remapped.size) acc = b.tseitinXor(acc, remapped[i])
        // Assert parity matches the target.
        b.addClause(intArrayOf(if (f.targetParity == 1) acc else Lit.negate(acc)))
    }

    private fun emitPseudoBoolean(b: CnfBuilder, f: PseudoBoolean, boolMap: IntArray) {
        b.addClause(intArrayOf(buildPbComparator(b, f.weights, f.literals, f.op, f.bound, boolMap)))
    }

    private fun emitReifiedPseudoBoolean(b: CnfBuilder, f: ReifiedPseudoBoolean, boolMap: IntArray) {
        val cnfAux = Lit.make(boolMap[f.auxBoolVar], positive = true)
        val cmp = buildPbComparator(b, f.weights, f.literals, f.op, f.bound, boolMap)
        b.addClause(intArrayOf(Lit.negate(cnfAux), cmp))
        b.addClause(intArrayOf(cnfAux, Lit.negate(cmp)))
    }

    private fun buildPbComparator(
        b: CnfBuilder,
        weights: IntArray,
        literals: IntArray,
        op: PbOp,
        bound: Int,
        boolMap: IntArray,
    ): Int {
        // Split positive- and negative-weight terms; bound shifts to absorb negative contributions.
        // Σ wᵢ * lᵢ = posSum − negSum, where posSum / negSum are non-negative.
        val posTerms = mutableListOf<IntArray>()
        val negTerms = mutableListOf<IntArray>()
        for (i in literals.indices) {
            val w = weights[i]
            if (w == 0) continue
            val lit = literals[i]
            val cnfLit = Lit.make(boolMap[Lit.variable(lit)], Lit.isPositive(lit))
            val term = b.multiplyByConstant(intArrayOf(cnfLit), abs(w))
            if (w > 0) posTerms += term else negTerms += term
        }
        val pSum = sumAll(b, posTerms)
        val nSum = sumAll(b, negTerms)
        val lhs: IntArray
        val rhs: IntArray
        if (bound >= 0) {
            lhs = pSum
            rhs = if (bound > 0) b.rippleAdd(nSum, constantLits(b, bound.toLong())) else nSum
        } else {
            lhs = b.rippleAdd(pSum, constantLits(b, (-bound).toLong()))
            rhs = nSum
        }
        return when (op) {
            PbOp.LE -> b.unsignedLeq(lhs, rhs)
            PbOp.GE -> b.unsignedLeq(rhs, lhs)
            PbOp.EQ -> b.unsignedEq(lhs, rhs)
        }
    }

    private fun emitProduct(b: CnfBuilder, f: Product, intBits: Array<IntArray>, intMin: IntArray, problem: Problem) {
        val aMin = intMin[f.a]
        val bMin = intMin[f.b]
        val rMin = intMin[f.result]
        val aMax = problem.intDomains[f.a].max
        val bMax = problem.intDomains[f.b].max
        val rMax = problem.intDomains[f.result].max

        if (aMin >= 0 && bMin >= 0 && rMin >= 0) {
            // Unsigned fast path.
            val aActual = if (aMin == 0) {
                bitsToLits(intBits[f.a])
            } else {
                b.rippleAdd(bitsToLits(intBits[f.a]), constantLits(b, aMin.toLong()))
            }
            val bActual = if (bMin == 0) {
                bitsToLits(intBits[f.b])
            } else {
                b.rippleAdd(bitsToLits(intBits[f.b]), constantLits(b, bMin.toLong()))
            }
            val rActual = if (rMin == 0) {
                bitsToLits(intBits[f.result])
            } else {
                b.rippleAdd(bitsToLits(intBits[f.result]), constantLits(b, rMin.toLong()))
            }
            val product = b.multiply(aActual, bActual)
            b.addClause(intArrayOf(b.unsignedEq(product, rActual)))
            return
        }

        // Signed path: build two's-complement bit-vectors wide enough to hold each operand's
        // signed range, then use signedMultiply.
        val aWidth = signedWidth(aMin, aMax)
        val bWidth = signedWidth(bMin, bMax)
        val rWidth = signedWidth(rMin, rMax)
        val aSigned = toSignedBits(b, intBits[f.a], aMin, aWidth)
        val bSigned = toSignedBits(b, intBits[f.b], bMin, bWidth)
        val rSigned = toSignedBits(b, intBits[f.result], rMin, rWidth)
        val product = b.signedMultiply(aSigned, bSigned)
        // Sign-extend whichever side is shorter so unsignedEq can compare bit-for-bit.
        val targetWidth = maxOf(product.size, rSigned.size)
        val productExt = signExtend(product, targetWidth)
        val rExt = signExtend(rSigned, targetWidth)
        b.addClause(intArrayOf(b.unsignedEq(productExt, rExt)))
    }

    /** Bits needed for two's-complement representation of a value range `[min, max]`. */
    private fun signedWidth(min: Int, max: Int): Int {
        val maxAbs = maxOf(if (min < 0) -min - 1 else 0, if (max > 0) max else 0)
        // 1 sign bit + bits to represent maxAbs.
        val payload = if (maxAbs == 0) 1 else (32 - maxAbs.countLeadingZeroBits())
        return payload + 1
    }

    /**
     * Build a [width]-bit two's-complement representation of `min + decode(bits)`. The integer
     * variable is encoded as offset bits with offset `min`; this lifts that to a signed
     * bit-vector by zero-extending the offset bits and adding `min` modulo `2^width`.
     */
    private fun toSignedBits(b: CnfBuilder, bits: IntArray, min: Int, width: Int): IntArray {
        val lits = bitsToLits(bits)
        val zeroExtended = b.zeroExtend(lits, width)
        if (min == 0) return zeroExtended
        val constMod = if (min >= 0) min.toLong() else (1L shl width) + min.toLong()
        val sum = b.rippleAdd(zeroExtended, constantLits(b, constMod))
        // Truncate to `width`; overflow bits are discarded (intended in two's complement).
        return sum.copyOfRange(0, width)
    }

    /** Sign-extend a two's-complement bit-vector to [width]. */
    private fun signExtend(bits: IntArray, width: Int): IntArray {
        if (bits.size >= width) return bits.copyOfRange(0, width)
        val sign = bits.last()
        val out = IntArray(width)
        bits.copyInto(out, 0, 0, bits.size)
        for (i in bits.size until width) out[i] = sign
        return out
    }

    /** Comparator literal for `x ⟨op⟩ bound` on a single int var. */
    private fun cmp1(
        b: CnfBuilder,
        v: Int,
        op: LinearOp,
        bound: Int,
        intBits: Array<IntArray>,
        intMin: IntArray,
    ): Int = buildLinearComparator(b, intArrayOf(1), intArrayOf(v), op, bound, intBits, intMin)

    /** Comparator literal for `a ⟨op⟩ c` between two int vars. */
    private fun cmp2(b: CnfBuilder, a: Int, c: Int, op: LinearOp, intBits: Array<IntArray>, intMin: IntArray): Int =
        buildLinearComparator(b, intArrayOf(1, -1), intArrayOf(a, c), op, 0, intBits, intMin)

    /** Remap a [Problem]-space Boolean literal into a CNF literal. */
    private fun presLit(boolMap: IntArray, lit: Int): Int = Lit.make(boolMap[Lit.variable(lit)], Lit.isPositive(lit))

    /** Unsigned bit-vector for the *actual* value of int var `v` (`min + offset`). Requires a
     *  non-negative domain min — used only for count-style vars (counts, nvalue n). */
    private fun intActualUnsigned(b: CnfBuilder, v: Int, intBits: Array<IntArray>, intMin: IntArray): IntArray {
        val min = intMin[v]
        require(min >= 0) { "intActualUnsigned: var $v has negative domain min $min" }
        val bits = bitsToLits(intBits[v])
        return if (min == 0) bits else b.rippleAdd(bits, constantLits(b, min.toLong()))
    }

    /** Sum a list of 0/1 literals into an unsigned count bit-vector. */
    private fun sumIndicators(b: CnfBuilder, lits: List<Int>): IntArray {
        if (lits.isEmpty()) return intArrayOf(b.falseLit())
        var acc = intArrayOf(lits[0])
        for (i in 1 until lits.size) acc = b.rippleAdd(acc, intArrayOf(lits[i]))
        return acc
    }

    /** Pairwise non-overlap, the unary special case of cumulative. Mirrors the AST-level
     *  [com.eignex.klause.compile.reifyDisjunctive] lowering. Presence-gated when opt. */
    private fun emitDisjunctive(
        b: CnfBuilder,
        f: Disjunctive,
        intBits: Array<IntArray>,
        intMin: IntArray,
        boolMap: IntArray,
    ) {
        require(f.durationVars.isEmpty()) { "BitBlaster: variable-duration Disjunctive unsupported" }
        val hasPresents = f.presents.isNotEmpty()
        for (i in f.starts.indices) {
            for (j in i + 1 until f.starts.size) {
                val di = f.durations[i]
                val dj = f.durations[j]
                if (di == 0 || dj == 0) continue
                // start_i + d_i ≤ start_j  ∨  start_j + d_j ≤ start_i
                val le1 = buildLinearComparator(
                    b,
                    intArrayOf(1, -1),
                    intArrayOf(f.starts[i], f.starts[j]),
                    LinearOp.LE,
                    -di,
                    intBits,
                    intMin,
                )
                val le2 = buildLinearComparator(
                    b,
                    intArrayOf(1, -1),
                    intArrayOf(f.starts[j], f.starts[i]),
                    LinearOp.LE,
                    -dj,
                    intBits,
                    intMin,
                )
                val noOverlap = b.tseitinOr(intArrayOf(le1, le2))
                if (!hasPresents) {
                    b.addClause(intArrayOf(noOverlap))
                } else {
                    b.addClause(
                        intArrayOf(
                            Lit.negate(presLit(boolMap, f.presents[i])),
                            Lit.negate(presLit(boolMap, f.presents[j])),
                            noOverlap,
                        ),
                    )
                }
            }
        }
    }

    /** Time-tabling: at every integer time point in the static horizon, the summed resource
     *  use of running tasks stays ≤ capacity. Mirrors [com.eignex.klause.compile] cumulative
     *  time-tabling. Constant durations / resources / capacity only (the compiler's bit-blast
     *  path never emits the variable forms). */
    private fun emitCumulative(
        b: CnfBuilder,
        f: Cumulative,
        intBits: Array<IntArray>,
        intMin: IntArray,
        boolMap: IntArray,
        problem: Problem,
    ) {
        require(f.durationVars.isEmpty() && f.resourceVars.isEmpty() && f.capacityVar < 0) {
            "BitBlaster: variable duration/resource/capacity Cumulative unsupported"
        }
        val hasPresents = f.presents.isNotEmpty()
        var horizonLo = Int.MAX_VALUE
        var horizonHi = Int.MIN_VALUE
        for (i in f.starts.indices) {
            val d = problem.intDomains[f.starts[i]]
            if (d.min < horizonLo) horizonLo = d.min
            val hi = d.max + f.durations[i]
            if (hi > horizonHi) horizonHi = hi
        }
        if (horizonLo >= horizonHi) return
        for (t in horizonLo until horizonHi) {
            val terms = mutableListOf<IntArray>()
            for (i in f.starts.indices) {
                val d = f.durations[i]
                val r = f.resources[i]
                if (d == 0 || r == 0) continue
                val le = cmp1(b, f.starts[i], LinearOp.LE, t, intBits, intMin)
                val ge = cmp1(b, f.starts[i], LinearOp.GE, t - d + 1, intBits, intMin)
                val runs = if (hasPresents) {
                    b.tseitinAnd(intArrayOf(presLit(boolMap, f.presents[i]), le, ge))
                } else {
                    b.tseitinAnd(intArrayOf(le, ge))
                }
                terms += b.multiplyByConstant(intArrayOf(runs), r)
            }
            if (terms.isEmpty()) continue
            b.addClause(intArrayOf(b.constantLeq(sumAll(b, terms), f.capacity)))
        }
    }

    /** NValue: `n ⟨mode⟩ |distinct(present xs)|`. Enumerates the union of static domains. */
    private fun emitNValue(
        b: CnfBuilder,
        f: NValue,
        intBits: Array<IntArray>,
        intMin: IntArray,
        boolMap: IntArray,
        problem: Problem,
    ) {
        val hasPresents = f.presents.isNotEmpty()
        val union = sortedSetOfValues(f.xs, problem)
        val perValue = union.map { vv ->
            val holds = f.xs.indices.map { i ->
                val eq = cmp1(b, f.xs[i], LinearOp.EQ, vv, intBits, intMin)
                if (hasPresents) b.tseitinAnd(intArrayOf(presLit(boolMap, f.presents[i]), eq)) else eq
            }
            if (holds.size == 1) holds[0] else b.tseitinOr(holds.toIntArray())
        }
        val distinct = sumIndicators(b, perValue)
        val nActual = intActualUnsigned(b, f.n, intBits, intMin)
        val lit = when (f.mode) {
            NValue.Mode.Eq -> b.unsignedEq(distinct, nActual)

            NValue.Mode.AtLeast -> b.unsignedLeq(nActual, distinct)

            // n ≤ distinct
            NValue.Mode.AtMost -> b.unsignedLeq(distinct, nActual) // n ≥ distinct
        }
        b.addClause(intArrayOf(lit))
    }

    private fun sortedSetOfValues(xs: IntArray, problem: Problem): List<Int> {
        val set = HashSet<Int>()
        for (v in xs) {
            val d = problem.intDomains[v]
            for (k in d.min..d.max) set.add(k)
        }
        return set.sorted()
    }

    /** Global cardinality: per cover value `k`, `count_k = #{i : present_i ∧ xs[i]=cover[k]}`
     *  bounded by `countVars[k]` (channel) or `[countLow[k], countHigh[k]]`. Optional closed
     *  check: every present `xs[i]` lies in the cover set. */
    private fun emitGlobalCardinality(
        b: CnfBuilder,
        f: GlobalCardinality,
        intBits: Array<IntArray>,
        intMin: IntArray,
        boolMap: IntArray,
    ) {
        val hasPresents = f.presents.isNotEmpty()
        for (k in f.cover.indices) {
            val coverVal = f.cover[k]
            val indicators = f.xs.indices.map { i ->
                val eq = cmp1(b, f.xs[i], LinearOp.EQ, coverVal, intBits, intMin)
                if (hasPresents) b.tseitinAnd(intArrayOf(presLit(boolMap, f.presents[i]), eq)) else eq
            }
            val count = sumIndicators(b, indicators)
            val cv = f.countVars
            if (cv != null) {
                b.addClause(intArrayOf(b.unsignedEq(count, intActualUnsigned(b, cv[k], intBits, intMin))))
            } else {
                b.addClause(intArrayOf(b.constantGeq(count, requireNotNull(f.countLow)[k])))
                b.addClause(intArrayOf(b.constantLeq(count, requireNotNull(f.countHigh)[k])))
            }
        }
        if (f.closed) {
            for (i in f.xs.indices) {
                val inCover = f.cover.map { cmp1(b, f.xs[i], LinearOp.EQ, it, intBits, intMin) }
                val inCoverLit = if (inCover.size == 1) inCover[0] else b.tseitinOr(inCover.toIntArray())
                if (hasPresents) {
                    b.addClause(intArrayOf(Lit.negate(presLit(boolMap, f.presents[i])), inCoverLit))
                } else {
                    b.addClause(intArrayOf(inCoverLit))
                }
            }
        }
    }

    /**
     * Circuit / subcircuit via MTZ position vectors — the same decomposition as
     * [com.eignex.klause.compile.reifyCircuit] / `reifySubcircuit`, lowered directly to CNF.
     * Position vars are synthesised as fresh CNF bit-vectors (they're encoding-internal — not
     * decoded). `succ` values are 0-indexed in `[0, n)`; the compiler channels any value
     * offset away before the factor is built.
     */
    @Suppress("UnusedParameter") // `problem` kept for signature symmetry with the emit dispatch
    private fun emitCircuit(
        b: CnfBuilder,
        succ: IntArray,
        intBits: Array<IntArray>,
        intMin: IntArray,
        problem: Problem,
        sub: Boolean,
    ) {
        val n = succ.size
        // Clamp every successor to [0, n) — circuit semantics, independent of declared domain.
        for (i in 0 until n) {
            b.addClause(intArrayOf(cmp1(b, succ[i], LinearOp.LE, n - 1, intBits, intMin)))
            b.addClause(intArrayOf(cmp1(b, succ[i], LinearOp.GE, 0, intBits, intMin)))
        }
        // AllDifferent over succ — the assignment is a permutation.
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                b.addClause(intArrayOf(cmp2(b, succ[i], succ[j], LinearOp.NE, intBits, intMin)))
            }
        }
        if (!sub && n >= 2) {
            for (i in 0 until n) b.addClause(intArrayOf(cmp1(b, succ[i], LinearOp.NE, i, intBits, intMin)))
        }
        if (n <= 1) return
        val w = bitWidth(n - 1).coerceAtLeast(1)
        // Position vectors. Circuit: pos[0] fixed to 0, pos[1..] ∈ [1, n-1] pairwise-distinct.
        // Subcircuit: all pos ∈ [0, n-1], no distinctness (excluded nodes share freely).
        val pos: Array<IntArray> = Array(n) { k ->
            if (!sub && k == 0) {
                IntArray(w) { b.falseLit() }
            } else {
                val bits = IntArray(w) { Lit.make(b.newVar(), positive = true) }
                b.addClause(intArrayOf(b.constantLeq(bits, n - 1)))
                if (!sub) b.addClause(intArrayOf(b.constantGeq(bits, 1)))
                bits
            }
        }
        if (!sub) {
            for (k in 1 until n) {
                for (l in k + 1 until n) {
                    b.addClause(intArrayOf(Lit.negate(b.unsignedEq(pos[k], pos[l]))))
                }
            }
        }
        for (i in 0 until n) {
            val pAtSucc = elementByVar(b, succ[i], pos, intBits, intMin)
            val advance = b.unsignedEq(pAtSucc, b.rippleAdd(pos[i], constantLits(b, 1L)))
            if (!sub) {
                val closing = cmp1(b, succ[i], LinearOp.EQ, 0, intBits, intMin)
                b.addClause(intArrayOf(closing, advance))
            } else {
                val isExcluded = cmp1(b, succ[i], LinearOp.EQ, i, intBits, intMin)
                val closing = b.unsignedEq(pAtSucc, constantLits(b, 0L))
                b.addClause(intArrayOf(isExcluded, advance, closing))
            }
        }
    }

    /** Element by an int var: fresh bit-vector `P` such that `P = table[idx]`. Encoded as
     *  `(idx == c) → (P == table[c])` for every candidate `c`; the caller guarantees `idx`
     *  is pinned to a single value in `[0, table.size)`, so exactly one antecedent fires. */
    private fun elementByVar(
        b: CnfBuilder,
        idx: Int,
        table: Array<IntArray>,
        intBits: Array<IntArray>,
        intMin: IntArray,
    ): IntArray {
        var w = 0
        for (t in table) if (t.size > w) w = t.size
        val p = IntArray(w) { Lit.make(b.newVar(), positive = true) }
        for (c in table.indices) {
            val eqc = cmp1(b, idx, LinearOp.EQ, c, intBits, intMin)
            b.addClause(intArrayOf(Lit.negate(eqc), b.unsignedEq(p, table[c])))
        }
        return p
    }

    /** Comparator literal for `a < c` between two int vars (a − c ≤ −1). */
    private fun ltLit(b: CnfBuilder, a: Int, c: Int, intBits: Array<IntArray>, intMin: IntArray): Int =
        buildLinearComparator(b, intArrayOf(1, -1), intArrayOf(a, c), LinearOp.LE, -1, intBits, intMin)

    /** Comparator literal for `a ≤ c` between two int vars. */
    private fun leLit(b: CnfBuilder, a: Int, c: Int, intBits: Array<IntArray>, intMin: IntArray): Int =
        buildLinearComparator(b, intArrayOf(1, -1), intArrayOf(a, c), LinearOp.LE, 0, intBits, intMin)

    /** Assert the biconditional `a ↔ c`. */
    private fun assertIff(b: CnfBuilder, a: Int, c: Int) {
        b.addClause(intArrayOf(Lit.negate(a), c))
        b.addClause(intArrayOf(a, Lit.negate(c)))
    }

    /** `lex_less` / `lex_lesseq` over (possibly unequal-length) int vectors. */
    private fun emitLexLess(b: CnfBuilder, f: LexLess, intBits: Array<IntArray>, intMin: IntArray) {
        val nx = f.xs.size
        val ny = f.ys.size
        val m = minOf(nx, ny)
        val eqs = IntArray(m) { cmp2(b, f.xs[it], f.ys[it], LinearOp.EQ, intBits, intMin) }
        val disj = mutableListOf<Int>()
        for (k in 0 until m) {
            val less = ltLit(b, f.xs[k], f.ys[k], intBits, intMin)
            val prefix = if (k == 0) less else b.tseitinAnd((eqs.copyOfRange(0, k) + less))
            disj += prefix
        }
        // Prefix-equal term: xs == ys on the first m positions.
        val allEqM = if (m == 0) b.trueLit() else b.tseitinAnd(eqs)
        val includeAllEq = when {
            nx < ny -> true

            // xs is a proper prefix ⇒ strictly less
            nx == ny -> !f.strict

            // equal vectors satisfy ≤ only
            else -> false // ys is a proper prefix ⇒ xs > ys
        }
        if (includeAllEq) disj += allEqM
        b.addClause(disj.toIntArray())
    }

    /** `result = arr[idx]`, `idx` `indexOffset`-based. */
    private fun emitElement(b: CnfBuilder, f: Element, intBits: Array<IntArray>, intMin: IntArray) {
        val len = f.arr.size
        b.addClause(intArrayOf(cmp1(b, f.idx, LinearOp.GE, f.indexOffset, intBits, intMin)))
        b.addClause(intArrayOf(cmp1(b, f.idx, LinearOp.LE, f.indexOffset + len - 1, intBits, intMin)))
        for (p in 0 until len) {
            val idxEq = cmp1(b, f.idx, LinearOp.EQ, f.indexOffset + p, intBits, intMin)
            val resEq = if (f.arrIsVars) {
                cmp2(b, f.result, f.arr[p], LinearOp.EQ, intBits, intMin)
            } else {
                cmp1(b, f.result, LinearOp.EQ, f.arr[p], intBits, intMin)
            }
            b.addClause(intArrayOf(Lit.negate(idxEq), resEq))
        }
    }

    /** `inverse(f, g)`: `f[i] = j ⇔ g[j − gOffset + fOffset] = i`. Reused by
     *  [SymmetricAllDifferent] with `f = g`. Channels biconditionally over the logical index
     *  ranges, with range clamps making each side a permutation. */
    @Suppress("UnusedParameter") // `problem` kept for signature symmetry with the emit dispatch
    private fun emitInverse(
        b: CnfBuilder,
        fArr: IntArray,
        gArr: IntArray,
        fOffset: Int,
        gOffset: Int,
        intBits: Array<IntArray>,
        intMin: IntArray,
        problem: Problem,
    ) {
        val nf = fArr.size
        val ng = gArr.size
        // f[p] is a logical index into g: value ∈ [gOffset, gOffset+ng-1]. Symmetric for g.
        for (p in 0 until nf) {
            b.addClause(intArrayOf(cmp1(b, fArr[p], LinearOp.GE, gOffset, intBits, intMin)))
            b.addClause(intArrayOf(cmp1(b, fArr[p], LinearOp.LE, gOffset + ng - 1, intBits, intMin)))
        }
        for (q in 0 until ng) {
            b.addClause(intArrayOf(cmp1(b, gArr[q], LinearOp.GE, fOffset, intBits, intMin)))
            b.addClause(intArrayOf(cmp1(b, gArr[q], LinearOp.LE, fOffset + nf - 1, intBits, intMin)))
        }
        for (p in 0 until nf) {
            for (q in 0 until ng) {
                val v = q + gOffset // logical g-index that f[p] would hold
                val a = cmp1(b, fArr[p], LinearOp.EQ, v, intBits, intMin)
                val c = cmp1(b, gArr[q], LinearOp.EQ, p + fOffset, intBits, intMin)
                assertIff(b, a, c)
            }
        }
    }

    /** `sort(xs, ys)` — `ys` non-decreasing with the same multiset as `xs`. Multiset equality
     *  via per-value count equality over the union of static domains. */
    private fun emitSort(b: CnfBuilder, f: Sort, intBits: Array<IntArray>, intMin: IntArray, problem: Problem) {
        for (i in 0 until f.ys.size - 1) {
            b.addClause(intArrayOf(leLit(b, f.ys[i], f.ys[i + 1], intBits, intMin)))
        }
        val union = sortedSetOfValues(f.xs + f.ys, problem)
        for (vv in union) {
            val cntX = sumIndicators(b, f.xs.map { cmp1(b, it, LinearOp.EQ, vv, intBits, intMin) })
            val cntY = sumIndicators(b, f.ys.map { cmp1(b, it, LinearOp.EQ, vv, intBits, intMin) })
            b.addClause(intArrayOf(b.unsignedEq(cntX, cntY)))
        }
    }

    /** `result = max(xs)` / `min(xs)`. */
    private fun emitArrayMinMax(b: CnfBuilder, f: ArrayMinMax, intBits: Array<IntArray>, intMin: IntArray) {
        for (x in f.xs) {
            val bound = if (f.max) {
                buildLinearComparator(b, intArrayOf(1, -1), intArrayOf(f.result, x), LinearOp.GE, 0, intBits, intMin)
            } else {
                buildLinearComparator(b, intArrayOf(1, -1), intArrayOf(f.result, x), LinearOp.LE, 0, intBits, intMin)
            }
            b.addClause(intArrayOf(bound))
        }
        val eq = IntArray(f.xs.size) { cmp2(b, f.result, f.xs[it], LinearOp.EQ, intBits, intMin) }
        b.addClause(eq)
    }

    /** `diffn(xs, ys, widths, heights)` — pairwise 2D non-overlap (constant dimensions). */
    private fun emitDiffn(b: CnfBuilder, f: Diffn, intBits: Array<IntArray>, intMin: IntArray) {
        require(f.widthVars == null && f.heightVars == null) { "BitBlaster: variable-size Diffn unsupported" }
        val n = f.xs.size
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val wi = f.widths[i]
                val wj = f.widths[j]
                val hi = f.heights[i]
                val hj = f.heights[j]
                if (f.nonStrict && (wi == 0 || hi == 0 || wj == 0 || hj == 0)) continue
                val clause = intArrayOf(
                    buildLinearComparator(
                        b,
                        intArrayOf(1, -1),
                        intArrayOf(f.xs[i], f.xs[j]),
                        LinearOp.LE,
                        -wi,
                        intBits,
                        intMin,
                    ),
                    buildLinearComparator(
                        b,
                        intArrayOf(1, -1),
                        intArrayOf(f.xs[j], f.xs[i]),
                        LinearOp.LE,
                        -wj,
                        intBits,
                        intMin,
                    ),
                    buildLinearComparator(
                        b,
                        intArrayOf(1, -1),
                        intArrayOf(f.ys[i], f.ys[j]),
                        LinearOp.LE,
                        -hi,
                        intBits,
                        intMin,
                    ),
                    buildLinearComparator(
                        b,
                        intArrayOf(1, -1),
                        intArrayOf(f.ys[j], f.ys[i]),
                        LinearOp.LE,
                        -hj,
                        intBits,
                        intMin,
                    ),
                )
                b.addClause(clause)
            }
        }
    }

    /** Positive `table(xs, tuples)` — disjunction of row-equalities. */
    private fun emitTable(b: CnfBuilder, f: Table, intBits: Array<IntArray>, intMin: IntArray) {
        val n = f.xs.size
        if (n == 0) return
        val rows = f.tuples.size / n
        val rowLits = IntArray(rows) { r ->
            val conj = IntArray(n) { k -> cmp1(b, f.xs[k], LinearOp.EQ, f.tuples[r * n + k], intBits, intMin) }
            b.tseitinAnd(conj)
        }
        b.addClause(rowLits)
    }

    /** `regular(seq, Q, S, d, q0, F)` — DFA acceptance via per-(position, state) activity
     *  bools. States and symbols are 1-based; `d[(q-1)*S+(s-1)] = 0` is the dead state. */
    private fun emitRegular(b: CnfBuilder, f: Regular, intBits: Array<IntArray>, intMin: IntArray) {
        val q = f.numStates
        val s = f.alphabetSize
        val len = f.seq.size
        // active[t][state] for t = 0..len, state = 1..q (index 0 unused).
        val active = Array(len + 1) { IntArray(q + 1) }
        for (st in 1..q) active[0][st] = if (st == f.q0) b.trueLit() else b.falseLit()
        for (t in 0 until len) {
            b.addClause(intArrayOf(cmp1(b, f.seq[t], LinearOp.GE, 1, intBits, intMin)))
            b.addClause(intArrayOf(cmp1(b, f.seq[t], LinearOp.LE, s, intBits, intMin)))
            for (qp in 1..q) {
                val disj = mutableListOf<Int>()
                for (qq in 1..q) {
                    for (sym in 1..s) {
                        if (f.transitions[(qq - 1) * s + (sym - 1)] == qp) {
                            disj += b.tseitinAnd(
                                intArrayOf(active[t][qq], cmp1(b, f.seq[t], LinearOp.EQ, sym, intBits, intMin)),
                            )
                        }
                    }
                }
                active[t + 1][qp] = if (disj.isEmpty()) b.falseLit() else b.tseitinOr(disj.toIntArray())
            }
        }
        b.addClause(IntArray(f.accepting.size) { active[len][f.accepting[it]] })
    }

    private fun emitLinear(b: CnfBuilder, f: Linear, intBits: Array<IntArray>, intMin: IntArray, problem: Problem) {
        // Short-circuit single-var equality / disequality against an out-of-domain constant,
        // matching the old IntEq / IntNeq behavior.
        if (f.coeffs.size == 1 && f.coeffs[0] == 1) {
            val v = f.vars[0]
            val d = problem.intDomains[v]
            val inDomain = f.bound in d
            when (f.op) {
                LinearOp.EQ -> if (!inDomain) {
                    b.addClause(IntArray(0))
                    return
                }

                LinearOp.NE -> if (!inDomain) return

                // trivially true
                else -> Unit
            }
        }
        b.addClause(intArrayOf(buildLinearComparator(b, f.coeffs, f.vars, f.op, f.bound, intBits, intMin)))
    }

    private fun buildLinearComparator(
        b: CnfBuilder,
        coeffs: IntArray,
        vars: IntArray,
        op: LinearOp,
        bound: Int,
        intBits: Array<IntArray>,
        intMin: IntArray,
    ): Int {
        // Substitute x_i = x_i' + min_i; new bound b' = bound - Σ c_i min_i.
        var bPrime: Long = bound.toLong()
        for (i in vars.indices) bPrime -= coeffs[i].toLong() * intMin[vars[i]].toLong()

        // Split positive and negative contributions, both as non-negative coefficient × x_i'.
        val posTerms = mutableListOf<IntArray>()
        val negTerms = mutableListOf<IntArray>()
        for (i in vars.indices) {
            val c = coeffs[i]
            if (c == 0) continue
            val term = b.multiplyByConstant(bitsToLits(intBits[vars[i]]), abs(c))
            if (c > 0) posTerms += term else negTerms += term
        }
        val pSum = sumAll(b, posTerms)
        val nSum = sumAll(b, negTerms)

        // Rearrange so both sides are non-negative: lhs op rhs.
        val lhs: IntArray
        val rhs: IntArray
        if (bPrime >= 0) {
            lhs = pSum
            rhs = if (bPrime > 0) b.rippleAdd(nSum, constantLits(b, bPrime)) else nSum
        } else {
            lhs = b.rippleAdd(pSum, constantLits(b, -bPrime))
            rhs = nSum
        }

        return when (op) {
            LinearOp.LE -> b.unsignedLeq(lhs, rhs)
            LinearOp.GE -> b.unsignedLeq(rhs, lhs)
            LinearOp.EQ -> b.unsignedEq(lhs, rhs)
            LinearOp.NE -> Lit.negate(b.unsignedEq(lhs, rhs))
        }
    }

    private fun emitReifiedCardinality(b: CnfBuilder, f: ReifiedCardinality, boolMap: IntArray) {
        val cnfAux = Lit.make(boolMap[f.auxBoolVar], positive = true)
        val remapped = IntArray(f.literals.size) {
            val lit = f.literals[it]
            Lit.make(boolMap[Lit.variable(lit)], Lit.isPositive(lit))
        }
        // Sum each 0/1-valued literal into a count bitvector.
        var sum = if (remapped.isEmpty()) intArrayOf(b.falseLit()) else intArrayOf(remapped[0])
        for (i in 1 until remapped.size) sum = b.rippleAdd(sum, intArrayOf(remapped[i]))
        val parts = mutableListOf<Int>()
        if (f.min > 0) parts += b.constantGeq(sum, f.min)
        if (f.max < f.literals.size) parts += b.constantLeq(sum, f.max)
        val inRangeLit = if (parts.isEmpty()) {
            b.trueLit()
        } else if (parts.size == 1) {
            parts[0]
        } else {
            b.tseitinAnd(parts.toIntArray())
        }
        b.addClause(intArrayOf(Lit.negate(cnfAux), inRangeLit))
        b.addClause(intArrayOf(cnfAux, Lit.negate(inRangeLit)))
    }

    private fun emitReifiedLinear(
        b: CnfBuilder,
        f: ReifiedLinear,
        boolMap: IntArray,
        intBits: Array<IntArray>,
        intMin: IntArray,
    ) {
        val cnfAux = Lit.make(boolMap[f.auxBoolVar], positive = true)
        val cmp = buildLinearComparator(b, f.coeffs, f.vars, f.op, f.bound, intBits, intMin)
        b.addClause(intArrayOf(Lit.negate(cnfAux), cmp))
        b.addClause(intArrayOf(cnfAux, Lit.negate(cmp)))
    }

    private fun sumAll(b: CnfBuilder, terms: List<IntArray>): IntArray {
        if (terms.isEmpty()) return intArrayOf(b.falseLit())
        var acc = terms[0]
        for (i in 1 until terms.size) acc = b.rippleAdd(acc, terms[i])
        return acc
    }

    private fun constantLits(b: CnfBuilder, value: Long): IntArray {
        require(value >= 0) { "constantLits expects non-negative, got $value" }
        if (value == 0L) return intArrayOf(b.falseLit())
        val width = 64 - value.countLeadingZeroBits()
        val out = IntArray(width)
        val tL = b.trueLit()
        val fL = b.falseLit()
        for (i in 0 until width) out[i] = if ((value ushr i) and 1L == 1L) tL else fL
        return out
    }

    private fun bitsToLits(bits: IntArray): IntArray {
        val out = IntArray(bits.size)
        for (i in bits.indices) out[i] = Lit.make(bits[i], positive = true)
        return out
    }

    private fun bitWidth(value: Int): Int = if (value <= 0) 1 else 32 - value.countLeadingZeroBits()
}
