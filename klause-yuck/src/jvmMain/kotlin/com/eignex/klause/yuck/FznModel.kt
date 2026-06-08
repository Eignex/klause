package com.eignex.klause.yuck

import com.eignex.klause.ast.PbOp
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.LinearObjective
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.AllDifferent
import com.eignex.klause.solver.factor.AllDifferentExcept
import com.eignex.klause.solver.factor.Among
import com.eignex.klause.solver.factor.ArgMinMax
import com.eignex.klause.solver.factor.ArrayMinMax
import com.eignex.klause.solver.factor.BinPacking
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.factor.Circuit
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.solver.factor.Count
import com.eignex.klause.solver.factor.Cumulative
import com.eignex.klause.solver.factor.Diffn
import com.eignex.klause.solver.factor.Element
import com.eignex.klause.solver.factor.Geost
import com.eignex.klause.solver.factor.GlobalCardinality
import com.eignex.klause.solver.factor.Inverse
import com.eignex.klause.solver.factor.Knapsack
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
import com.eignex.klause.solver.factor.Sequence
import com.eignex.klause.solver.factor.SetBitsetDisjoint
import com.eignex.klause.solver.factor.SetBitsetEq
import com.eignex.klause.solver.factor.SetBitsetSubset
import com.eignex.klause.solver.factor.Sort
import com.eignex.klause.solver.factor.SymmetricAllDifferent
import com.eignex.klause.solver.factor.Table
import com.eignex.klause.solver.factor.Xor

/**
 * Translates a klause [Problem] into FlatZinc text for Yuck. As a local-search **reference**,
 * the adapter maps each klause factor to Yuck's native FlatZinc predicate where one exists
 * (the bare-declaration `fzn_*` / `yuck_*` predicates from Yuck's `mzn/lib`), and to a
 * faithful decomposition over standard FlatZinc builtins otherwise. Unsupported factors raise
 * [UnsupportedFactorException] rather than silently dropping a constraint — a reference solver
 * that quietly ignores constraints would make parity meaningless.
 */
class UnsupportedFactorException(
    /** The klause factor that has no FlatZinc translation. */
    val factor: Factor,
) : RuntimeException("klause-yuck: unsupported factor ${factor::class.simpleName}")

/**
 * A klause [Problem] rendered as FlatZinc source, built via [emit]. Variable naming is the
 * read-back contract with [YuckSolver]: klause bool var `v` is `b{v}`, int var `v` is `i{v}`,
 * and the minimization objective (when present) is `objv`; exactly these carry `output_var`
 * annotations, so Yuck's solution lines map back to a klause `Sample` by name.
 */
class FznModel private constructor(
    /** The source klause problem this model was translated from. */
    val problem: Problem,
    private val objective: LinearObjective?,
) {
    private val predicates = LinkedHashSet<String>()
    private val decls = StringBuilder()
    private val cons = StringBuilder()
    private var auxCount = 0

    /** Cached `bool2int` channel per bool var id. */
    private val boolToInt = HashMap<Int, String>()

    /** Cached `bool_not` view per bool var id. */
    private val boolNegation = HashMap<Int, String>()

    // ---- naming / item helpers -------------------------------------------------------------

    private fun boolName(v: Int) = "b$v"
    private fun intName(v: Int) = "i$v"

    private fun newBoolAux(): String {
        val n = "t${auxCount++}"
        decls.append("var bool: $n;\n")
        return n
    }

    private fun newIntAux(lo: Int, hi: Int): String {
        val n = "t${auxCount++}"
        decls.append("var $lo..$hi: $n;\n")
        return n
    }

    private fun constraint(s: String) {
        cons.append("constraint ").append(s).append(";\n")
    }

    private fun intVarArray(ids: IntArray) = ids.joinToString(", ", "[", "]") { intName(it) }
    private fun intArray(values: IntArray) = values.joinToString(", ", "[", "]")
    private fun intSet(values: IntArray) = values.joinToString(", ", "{", "}")
    private fun names(ns: List<String>) = ns.joinToString(", ", "[", "]")

    /** 0/1 integer channel of a bool var, shared across factors. */
    private fun boolAsInt(v: Int): String = boolToInt.getOrPut(v) {
        newIntAux(0, 1).also { constraint("bool2int(${boolName(v)}, $it)") }
    }

    /** Bool-typed view of a literal: the var itself, or its cached `bool_not` negation. */
    private fun litBool(lit: Int): String {
        val v = Lit.variable(lit)
        if (Lit.isPositive(lit)) return boolName(v)
        return boolNegation.getOrPut(v) {
            newBoolAux().also { constraint("bool_not(${boolName(v)}, $it)") }
        }
    }

    // ---- linear-form helpers ---------------------------------------------------------------

    /** A weighted sum over var names with a constant folded onto the right-hand side. */
    private class LinTerms {
        val coeffs = ArrayList<Int>()
        val vars = ArrayList<String>()

        /** Constant accumulated on the left side; the effective bound is `bound - shift`. */
        var shift = 0L
    }

    /** Weighted literal sum as integer terms: `w·lit` becomes `w·x` for a positive literal and
     *  `w − w·x` (coefficient `−w`, shift `w`) for a negative one, via the bool2int channels. */
    private fun litTerms(literals: IntArray, weights: IntArray?): LinTerms {
        val t = LinTerms()
        for (k in literals.indices) {
            val w = weights?.get(k) ?: 1
            val x = boolAsInt(Lit.variable(literals[k]))
            if (Lit.isPositive(literals[k])) {
                t.coeffs.add(w)
                t.vars.add(x)
            } else {
                t.coeffs.add(-w)
                t.vars.add(x)
                t.shift += w.toLong()
            }
        }
        return t
    }

    /** The factor's effective integer bound after folding [LinTerms.shift], or throw if the
     *  shifted bound leaves the Int range (the model would be unfaithful). */
    private fun shiftedBound(f: Factor, bound: Int, terms: LinTerms): Int {
        val b = bound.toLong() - terms.shift
        if (b < Int.MIN_VALUE || b > Int.MAX_VALUE) throw UnsupportedFactorException(f)
        return b.toInt()
    }

    /** Emit `Σ coeffs·vars ⟨op⟩ bound`, optionally reified onto [reif]. GE is normalized to LE
     *  by negating both sides (FlatZinc has no `int_lin_ge`). */
    private fun linear(coeffs: List<Int>, vars: List<String>, op: LinearOp, bound: Int, reif: String? = null) {
        val (cs, b, builtin) = when (op) {
            LinearOp.LE -> Triple(coeffs, bound, "int_lin_le")
            LinearOp.GE -> Triple(coeffs.map { -it }, negatedExact(bound), "int_lin_le")
            LinearOp.EQ -> Triple(coeffs, bound, "int_lin_eq")
            LinearOp.NE -> Triple(coeffs, bound, "int_lin_ne")
        }
        val name = if (reif != null) "${builtin}_reif" else builtin
        val tail = if (reif != null) ", $reif" else ""
        constraint("$name(${cs.joinToString(", ", "[", "]")}, ${names(vars)}, $b$tail)")
    }

    private fun negatedExact(bound: Int): Int {
        check(bound != Int.MIN_VALUE) { "cannot negate Int.MIN_VALUE bound" }
        return -bound
    }

    private fun pbOpToLinear(op: PbOp): LinearOp = when (op) {
        PbOp.LE -> LinearOp.LE
        PbOp.EQ -> LinearOp.EQ
        PbOp.GE -> LinearOp.GE
    }

    /** Emit `Σ lits = into` (a count/cardinality channel onto an int var name). */
    private fun sumOfBools(boolNames: List<String>, into: String) {
        val coeffs = MutableList(boolNames.size) { 1 }
        coeffs.add(-1)
        constraint("int_lin_eq(${coeffs.joinToString(", ", "[", "]")}, ${names(boolNames + into)}, 0)")
    }

    /** Declare a native (bare-declaration) Yuck predicate once and return its name. */
    private fun native(name: String, signature: String): String {
        predicates.add("predicate $name($signature);")
        return name
    }

    // ---- factor translation ----------------------------------------------------------------

    @Suppress("CyclomaticComplexMethod", "LongMethod") // one dispatch arm per klause factor kind
    private fun postFactor(f: Factor) {
        when (f) {
            is Clause -> postClause(f.literals)

            is Cardinality -> {
                val t = litTerms(f.literals, null)
                if (f.min > 0) linear(t.coeffs, t.vars, LinearOp.GE, shiftedBound(f, f.min, t))
                if (f.max < f.literals.size) linear(t.coeffs, t.vars, LinearOp.LE, shiftedBound(f, f.max, t))
            }

            is Linear -> linear(f.coeffs.toList(), f.vars.map { intName(it) }, f.op, f.bound)

            is PseudoBoolean -> {
                val t = litTerms(f.literals, f.weights)
                linear(t.coeffs, t.vars, pbOpToLinear(f.op), shiftedBound(f, f.bound, t))
            }

            is Xor -> postXor(f)

            is AllDifferent -> {
                if (f.presents.isNotEmpty()) throw UnsupportedFactorException(f)
                val p = native("fzn_all_different_int", "array [int] of var int: x")
                constraint("$p(${intVarArray(f.vars)})")
            }

            is Product -> constraint("int_times(${intName(f.a)}, ${intName(f.b)}, ${intName(f.result)})")

            is ReifiedLinear ->
                linear(f.coeffs.toList(), f.vars.map { intName(it) }, f.op, f.bound, boolName(f.auxBoolVar))

            is ReifiedPseudoBoolean -> {
                val t = litTerms(f.literals, f.weights)
                linear(t.coeffs, t.vars, pbOpToLinear(f.op), shiftedBound(f, f.bound, t), boolName(f.auxBoolVar))
            }

            is ReifiedCardinality -> postReifiedCardinality(f)

            is AllDifferentExcept -> {
                val p = native("fzn_alldifferent_except", "array [int] of var int: x, set of int: S")
                constraint("$p(${intVarArray(f.xs)}, ${intSet(f.except)})")
            }

            is Among -> {
                val matches = f.xs.map { x ->
                    newBoolAux().also { constraint("set_in_reif(${intName(x)}, ${intSet(f.values)}, $it)") }
                }
                sumOfBools(matches.map { boolAsIntByName(it) }, intName(f.n))
            }

            is Count -> postCountFactor(f)

            is Element -> postElement(f)

            is Inverse -> {
                val p = nativeInverse()
                constraint("$p(${intVarArray(f.f)}, ${f.fOffset}, ${intVarArray(f.g)}, ${f.gOffset})")
            }

            is SymmetricAllDifferent -> {
                val p = nativeInverse()
                constraint("$p(${intVarArray(f.xs)}, ${f.indexOffset}, ${intVarArray(f.xs)}, ${f.indexOffset})")
            }

            is LexLess -> {
                val p = if (f.strict) {
                    native("fzn_lex_less_int", "array [int] of var int: x, array [int] of var int: y")
                } else {
                    native("fzn_lex_lesseq_int", "array [int] of var int: x, array [int] of var int: y")
                }
                constraint("$p(${intVarArray(f.xs)}, ${intVarArray(f.ys)})")
            }

            is NValue -> postNValue(f)

            is GlobalCardinality -> postGcc(f)

            is Table -> {
                val p = native("yuck_table_int", "array [int] of var int: x, array [int] of int: t")
                constraint("$p(${intVarArray(f.xs)}, ${intArray(f.tuples)})")
            }

            is ArgMinMax -> postArgMinMax(f)

            is ArrayMinMax -> {
                val builtin = if (f.max) "array_int_maximum" else "array_int_minimum"
                constraint("$builtin(${intName(f.result)}, ${intVarArray(f.xs)})")
            }

            is Knapsack -> {
                linear(f.weights.toList() + (-1), f.xs.map { intName(it) } + intName(f.w), LinearOp.EQ, 0)
                linear(f.profits.toList() + (-1), f.xs.map { intName(it) } + intName(f.p), LinearOp.EQ, 0)
            }

            is Cumulative -> postCumulative(f)

            is Diffn -> postDiffn(f)

            is BinPacking -> postBinPacking(f)

            is Sort -> postSort(f)

            is Sequence -> postSequence(f)

            is Regular -> {
                val p = native(
                    "yuck_regular",
                    "array [int] of var int: x, int: Q, int: S, array [int] of int: d, int: q0, set of int: F",
                )
                constraint(
                    "$p(${intVarArray(f.seq)}, ${f.numStates}, ${f.alphabetSize}, " +
                        "${intArray(f.transitions)}, ${f.q0}, ${intSet(f.accepting)})",
                )
            }

            is Circuit -> {
                val p = native("yuck_circuit", "array [int] of var int: succ, int: offset")
                constraint("$p(${intVarArray(f.succ)}, 0)")
            }

            is Geost -> postGeost(f)

            is Mdd -> postMdd(f)

            is SetBitsetSubset -> postSetSubset(f.leftBools, f.rightBools)

            is SetBitsetDisjoint -> postSetDisjoint(f.leftBools, f.rightBools)

            is SetBitsetEq -> postSetEq(f.leftBools, f.rightBools)

            else -> throw UnsupportedFactorException(f)
        }
    }

    /** `bool_clause` over the factor's literals (positives vs negated vars). */
    private fun postClause(literals: IntArray) {
        val pos = ArrayList<String>()
        val neg = ArrayList<String>()
        for (lit in literals) {
            (if (Lit.isPositive(lit)) pos else neg).add(boolName(Lit.variable(lit)))
        }
        constraint("bool_clause(${names(pos)}, ${names(neg)})")
    }

    /** `bool_clause` over already-bool-typed aux names (all positive). */
    private fun postClause(positives: List<String>) {
        constraint("bool_clause(${names(positives)}, [])")
    }

    /** bool2int channel for an aux bool created by this factor (not a problem var). */
    private fun boolAsIntByName(boolAux: String): String =
        newIntAux(0, 1).also { constraint("bool2int($boolAux, $it)") }

    private fun postXor(f: Xor) {
        val terms = f.literals.map { litBool(it) }.toMutableList()
        // array_bool_xor holds iff an odd number of terms are true; a constant `true` flips
        // the required parity of the variable terms to even.
        if ((f.targetParity and 1) == 0) terms.add("true")
        constraint("array_bool_xor(${names(terms)})")
    }

    private fun postReifiedCardinality(f: ReifiedCardinality) {
        val t = litTerms(f.literals, null)
        val r = boolName(f.auxBoolVar)
        val needMin = f.min > 0
        val needMax = f.max < f.literals.size
        when {
            needMin && needMax -> {
                val rMin = newBoolAux()
                val rMax = newBoolAux()
                linear(t.coeffs, t.vars, LinearOp.GE, shiftedBound(f, f.min, t), rMin)
                linear(t.coeffs, t.vars, LinearOp.LE, shiftedBound(f, f.max, t), rMax)
                constraint("array_bool_and(${names(listOf(rMin, rMax))}, $r)")
            }

            needMin -> linear(t.coeffs, t.vars, LinearOp.GE, shiftedBound(f, f.min, t), r)

            needMax -> linear(t.coeffs, t.vars, LinearOp.LE, shiftedBound(f, f.max, t), r)

            // 0 ≤ count ≤ n is vacuously true, so the reification literal is forced.
            else -> constraint("bool_eq($r, true)")
        }
    }

    private fun postCountFactor(f: Count) {
        if (f.presents.isNotEmpty()) throw UnsupportedFactorException(f)
        // `n = #{i : xs[i] ⟨op⟩ v}` with `n` the count *variable*. Reify each element's match
        // against the constant and sum the indicators — uniform across all ops. GE/GT have no
        // FlatZinc reified builtin, so flip the operands of LE/LT.
        val matches = f.xs.map { x ->
            val b = newBoolAux()
            val c = when (f.op) {
                Count.Op.Eq -> "int_eq_reif(${intName(x)}, ${f.v}, $b)"
                Count.Op.Ne -> "int_ne_reif(${intName(x)}, ${f.v}, $b)"
                Count.Op.Le -> "int_le_reif(${intName(x)}, ${f.v}, $b)"
                Count.Op.Lt -> "int_lt_reif(${intName(x)}, ${f.v}, $b)"
                Count.Op.Ge -> "int_le_reif(${f.v}, ${intName(x)}, $b)"
                Count.Op.Gt -> "int_lt_reif(${f.v}, ${intName(x)}, $b)"
            }
            constraint(c)
            b
        }
        sumOfBools(matches.map { boolAsIntByName(it) }, intName(f.n))
    }

    private fun postElement(f: Element) {
        // FlatZinc element is 1-based: result = arr[k], k ∈ 1..n. klause reads
        // result = arr[idx - indexOffset], so k = idx - indexOffset + 1.
        val idxName = if (f.indexOffset == 1) {
            intName(f.idx)
        } else {
            val k = newIntAux(1, f.arr.size)
            // idx - k = indexOffset - 1
            linear(listOf(1, -1), listOf(intName(f.idx), k), LinearOp.EQ, f.indexOffset - 1)
            k
        }
        if (f.arrIsVars) {
            constraint("array_var_int_element($idxName, ${intVarArray(f.arr)}, ${intName(f.result)})")
        } else {
            constraint("array_int_element($idxName, ${intArray(f.arr)}, ${intName(f.result)})")
        }
    }

    private fun nativeInverse(): String = native(
        "yuck_inverse",
        "array [int] of var int: f, int: fOffset, array [int] of var int: g, int: gOffset",
    )

    private fun postNValue(f: NValue) {
        if (f.presents.isNotEmpty()) throw UnsupportedFactorException(f)
        val p = native("fzn_nvalue", "var int: n, array [int] of var int: x")
        when (f.mode) {
            NValue.Mode.Eq -> constraint("$p(${intName(f.n)}, ${intVarArray(f.xs)})")

            NValue.Mode.AtLeast -> {
                // n ≤ nvalue(xs)
                val nv = newIntAux(1, f.xs.size)
                constraint("$p($nv, ${intVarArray(f.xs)})")
                constraint("int_le(${intName(f.n)}, $nv)")
            }

            NValue.Mode.AtMost -> {
                // nvalue(xs) ≤ n
                val nv = newIntAux(1, f.xs.size)
                constraint("$p($nv, ${intVarArray(f.xs)})")
                constraint("int_le($nv, ${intName(f.n)})")
            }
        }
    }

    private fun postGcc(f: GlobalCardinality) {
        if (f.presents.isNotEmpty()) throw UnsupportedFactorException(f)
        val countVars = f.countVars
        val countLow = f.countLow
        val countHigh = f.countHigh
        val counts: List<String> = when {
            countVars != null -> countVars.map { intName(it) }
            countLow != null && countHigh != null -> List(f.cover.size) { newIntAux(countLow[it], countHigh[it]) }
            else -> List(f.cover.size) { newIntAux(0, f.xs.size) }
        }
        val p = native(
            "fzn_global_cardinality",
            "array [int] of var int: x, array [int] of int: cover, array [int] of var int: count",
        )
        constraint("$p(${intVarArray(f.xs)}, ${intArray(f.cover)}, ${names(counts)})")
        if (f.closed) {
            for (x in f.xs) constraint("set_in(${intName(x)}, ${intSet(f.cover)})")
        }
    }

    private fun postArgMinMax(f: ArgMinMax) {
        // m = extremum(xs); idx names the first position attaining it (klause/MiniZinc
        // tie-breaking): positions before idx are strictly off the extremum, idx itself is on it.
        var lo = Int.MAX_VALUE
        var hi = Int.MIN_VALUE
        for (x in f.xs) {
            val d = problem.intDomains[x]
            if (d.min < lo) lo = d.min
            if (d.max > hi) hi = d.max
        }
        val m = newIntAux(lo, hi)
        val builtin = if (f.max) "array_int_maximum" else "array_int_minimum"
        constraint("$builtin($m, ${intVarArray(f.xs)})")
        constraint("set_in(${intName(f.idx)}, ${f.indexOffset}..${f.indexOffset + f.xs.size - 1})")
        for (j in f.xs.indices) {
            val idxVal = f.indexOffset + j
            val here = newBoolAux()
            constraint("int_eq_reif(${intName(f.idx)}, $idxVal, $here)")
            val hit = newBoolAux()
            constraint("int_eq_reif(${intName(f.xs[j])}, $m, $hit)")
            // idx = idxVal → xs[j] = m
            constraint("bool_clause(${names(listOf(hit))}, ${names(listOf(here))})")
            val after = newBoolAux()
            constraint("int_lt_reif($idxVal, ${intName(f.idx)}, $after)")
            // idx > idxVal → xs[j] ≠ m (first-occurrence tie-break)
            constraint("bool_clause([], ${names(listOf(after, hit))})")
        }
    }

    private fun postCumulative(f: Cumulative) {
        if (f.presents.isNotEmpty()) throw UnsupportedFactorException(f)
        // durations/resources/capacity are constants; the var forms live in the *Vars arrays
        // (empty = use the constant). Constants coerce inside FlatZinc var-array literals.
        val durVars = f.durationVars.takeIf { it.isNotEmpty() }
        val resVars = f.resourceVars.takeIf { it.isNotEmpty() }
        val d = if (durVars != null) intVarArray(durVars) else intArray(f.durations)
        val r = if (resVars != null) intVarArray(resVars) else intArray(f.resources)
        val b = if (f.capacityVar >= 0) intName(f.capacityVar) else f.capacity.toString()
        val p = native(
            "fzn_cumulative",
            "array [int] of var int: s, array [int] of var int: d, array [int] of var int: r, var int: b",
        )
        constraint("$p(${intVarArray(f.starts)}, $d, $r, $b)")
    }

    private fun postDiffn(f: Diffn) {
        val widthVars = f.widthVars
        val heightVars = f.heightVars
        val w = if (widthVars != null) intVarArray(widthVars) else intArray(f.widths)
        val h = if (heightVars != null) intVarArray(heightVars) else intArray(f.heights)
        val p = native(
            "yuck_diffn",
            "array [int] of var int: x, array [int] of var int: y, " +
                "array [int] of var int: w, array [int] of var int: h, bool: strict",
        )
        constraint("$p(${intVarArray(f.xs)}, ${intVarArray(f.ys)}, $w, $h, ${!f.nonStrict})")
    }

    private fun postBinPacking(f: BinPacking) {
        val loads: List<String> = when (f.mode) {
            BinPacking.Mode.LoadVars -> requireNotNull(f.loadVars).map { intName(it) }
            BinPacking.Mode.UniformCapacity -> List(f.numBins) { newIntAux(0, f.uniformCapacity) }
            BinPacking.Mode.PerBinCapacity -> List(f.numBins) { newIntAux(0, requireNotNull(f.capacities)[it]) }
        }
        val p = native(
            "yuck_bin_packing_load",
            "array [int] of var int: load, array [int] of var int: bin, " +
                "array [int] of int: weight, int: minLoadIndex",
        )
        constraint("$p(${names(loads)}, ${intVarArray(f.bins)}, ${intArray(f.weights)}, ${f.binOffset})")
    }

    private fun postSort(f: Sort) {
        // ys is xs sorted ascending: ys[j] = xs[perm[j]] with perm a permutation, ys increasing.
        val n = f.xs.size
        val perm = List(n) { newIntAux(1, n) }
        val alldiff = native("fzn_all_different_int", "array [int] of var int: x")
        constraint("$alldiff(${names(perm)})")
        for (j in 0 until n) {
            constraint("array_var_int_element(${perm[j]}, ${intVarArray(f.xs)}, ${intName(f.ys[j])})")
        }
        val inc = native("yuck_increasing_int", "array [int] of var int: x, bool: strict")
        constraint("$inc(${intVarArray(f.ys)}, false)")
    }

    private fun postSequence(f: Sequence) {
        // Every length-k window has between low and high elements inside the value set. The
        // membership indicators are shared across overlapping windows.
        val member = f.xs.map { x ->
            val b = newBoolAux()
            constraint("set_in_reif(${intName(x)}, ${intSet(f.values)}, $b)")
            boolAsIntByName(b)
        }
        for (start in 0..f.xs.size - f.k) {
            val window = member.subList(start, start + f.k)
            if (f.low > 0) linear(List(f.k) { 1 }, window, LinearOp.GE, f.low)
            if (f.high < f.k) linear(List(f.k) { 1 }, window, LinearOp.LE, f.high)
        }
    }

    private fun postGeost(f: Geost) {
        // Pairwise separation in at least one dimension: oi+si ≤ oj  ∨  oj+sj ≤ oi (per dim).
        for (i in 0 until f.numObjects) {
            for (j in i + 1 until f.numObjects) {
                val options = ArrayList<String>(2 * f.numDims)
                for (d in 0 until f.numDims) {
                    val oi = intName(f.origin[i * f.numDims + d])
                    val oj = intName(f.origin[j * f.numDims + d])
                    val si = f.length[i * f.numDims + d]
                    val sj = f.length[j * f.numDims + d]
                    val before = newBoolAux()
                    constraint("int_lin_le_reif([1, -1], [$oi, $oj], ${-si}, $before)") // oi + si ≤ oj
                    options.add(before)
                    val after = newBoolAux()
                    constraint("int_lin_le_reif([1, -1], [$oj, $oi], ${-sj}, $after)") // oj + sj ≤ oi
                    options.add(after)
                }
                postClause(options)
            }
        }
    }

    private fun postMdd(f: Mdd) {
        // Each layer is a transition table over (q[i], seq[i], q[i+1][, weight]); chain the
        // state trace through them, fix q[0] = initial and require q[n] accepting.
        val n = f.seq.size
        var lo = f.initial
        var hi = f.initial
        for (a in f.accepting) {
            if (a < lo) lo = a
            if (a > hi) hi = a
        }
        var p = 0
        while (p < f.transitions.size) {
            lo = minOf(lo, f.transitions[p], f.transitions[p + 2])
            hi = maxOf(hi, f.transitions[p], f.transitions[p + 2])
            p += f.recordStride
        }
        val q = List(n + 1) { newIntAux(lo, hi) }
        constraint("int_eq(${q[0]}, ${f.initial})")
        val cost4 = f.recordStride == 4
        var wLo = 0
        var wHi = 0
        if (cost4) {
            var r = 0
            while (r < f.transitions.size) {
                val w = f.transitions[r + 3]
                if (w < wLo) wLo = w
                if (w > wHi) wHi = w
                r += f.recordStride
            }
        }
        val table = native("yuck_table_int", "array [int] of var int: x, array [int] of int: t")
        val wVars = if (cost4) ArrayList<String>(n) else null
        for (i in 0 until n) {
            val rows = (f.layerStarts[i + 1] - f.layerStarts[i]) / f.recordStride
            val flat = IntArray(rows * f.recordStride)
            f.transitions.copyInto(flat, 0, f.layerStarts[i], f.layerStarts[i + 1])
            if (cost4) {
                val w = newIntAux(wLo, wHi)
                requireNotNull(wVars).add(w)
                constraint("$table(${names(listOf(q[i], intName(f.seq[i]), q[i + 1], w))}, ${intArray(flat)})")
            } else {
                constraint("$table(${names(listOf(q[i], intName(f.seq[i]), q[i + 1]))}, ${intArray(flat)})")
            }
        }
        constraint("set_in(${q[n]}, ${intSet(f.accepting)})")
        if (f.cost >= 0 && wVars != null) {
            linear(List(wVars.size) { 1 } + (-1), wVars + intName(f.cost), LinearOp.EQ, 0)
        }
    }

    private fun postSetSubset(left: IntArray, right: IntArray) {
        for (i in left.indices) {
            val l = left[i]
            val r = right[i]
            if (l < 0) continue
            if (r < 0) {
                constraint("bool_eq(${boolName(l)}, false)")
            } else {
                constraint("bool_le(${boolName(l)}, ${boolName(r)})")
            }
        }
    }

    private fun postSetDisjoint(left: IntArray, right: IntArray) {
        for (i in left.indices) {
            val l = left[i]
            val r = right[i]
            if (l >= 0 && r >= 0) constraint("bool_clause([], ${names(listOf(boolName(l), boolName(r)))})")
        }
    }

    private fun postSetEq(left: IntArray, right: IntArray) {
        for (i in left.indices) {
            val l = left[i]
            val r = right[i]
            when {
                l >= 0 && r >= 0 -> constraint("bool_eq(${boolName(l)}, ${boolName(r)})")
                l >= 0 -> constraint("bool_eq(${boolName(l)}, false)")
                r >= 0 -> constraint("bool_eq(${boolName(r)}, false)")
            }
        }
    }

    // ---- objective ---------------------------------------------------------------------------

    /** Channel the linear objective into the `objv` var (constant offset excluded — it is added
     *  back when reporting and doesn't affect the argmin). Returns null for a constant objective. */
    private fun buildObjectiveVar(obj: LinearObjective): String? {
        val coeffs = ArrayList<Int>()
        val vars = ArrayList<String>()
        var lo = 0L
        var hi = 0L
        for (b in 0 until problem.numBoolVars) {
            val w = obj.boolWeights.getOrElse(b) { 0L }
            if (w != 0L) {
                val c = w.toInt()
                coeffs.add(c)
                vars.add(boolAsInt(b))
                if (c >= 0) hi += c.toLong() else lo += c.toLong()
            }
        }
        for (i in 0 until problem.numIntVars) {
            val cd = obj.intCoefficients.getOrElse(i) { 0L }
            if (cd != 0L) {
                val c = cd.toInt()
                coeffs.add(c)
                vars.add(intName(i))
                val d = problem.intDomains[i]
                val a = c.toLong() * d.min
                val b2 = c.toLong() * d.max
                lo += minOf(a, b2)
                hi += maxOf(a, b2)
            }
        }
        if (vars.isEmpty()) return null
        check(lo > Int.MIN_VALUE && hi < Int.MAX_VALUE) { "objective range $lo..$hi exceeds Int" }
        decls.append("var $lo..$hi: $OBJECTIVE_VAR :: output_var;\n")
        coeffs.add(-1)
        vars.add(OBJECTIVE_VAR)
        linear(coeffs, vars, LinearOp.EQ, 0)
        return OBJECTIVE_VAR
    }

    private fun render(): String {
        val out = StringBuilder()
        for (v in 0 until problem.numBoolVars) out.append("var bool: ${boolName(v)} :: output_var;\n")
        for (v in 0 until problem.numIntVars) {
            val d = problem.intDomains[v]
            val domain = if (d.size.toLong() == d.max.toLong() - d.min.toLong() + 1) {
                "${d.min}..${d.max}"
            } else {
                val values = StringBuilder("{")
                var first = true
                d.forEach {
                    if (!first) values.append(", ")
                    values.append(it)
                    first = false
                }
                values.append("}").toString()
            }
            out.append("var $domain: ${intName(v)} :: output_var;\n")
        }
        val problemVarDecls = out.toString()
        val solve = if (objective != null) {
            val objVar = buildObjectiveVar(objective)
            if (objVar != null) "solve minimize $objVar;\n" else "solve satisfy;\n"
        } else {
            "solve satisfy;\n"
        }
        return buildString {
            for (pred in predicates) append(pred).append('\n')
            append(problemVarDecls)
            append(decls)
            append(cons)
            append(solve)
        }
    }

    /** Factory for rendering a klause [Problem] as FlatZinc source. */
    companion object {
        /** Name of the synthesized minimization variable in the emitted FlatZinc. */
        const val OBJECTIVE_VAR = "objv"

        /** Render [problem] (and an optional linear [objective]) as FlatZinc source for Yuck. */
        fun emit(problem: Problem, objective: LinearObjective? = null): String {
            val m = FznModel(problem, objective)
            for (f in problem.factors) m.postFactor(f)
            return m.render()
        }
    }
}
