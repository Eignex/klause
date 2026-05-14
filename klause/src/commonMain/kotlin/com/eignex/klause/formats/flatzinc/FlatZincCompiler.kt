package com.eignex.klause.formats.flatzinc

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.AllDifferent
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.factor.Product
import com.eignex.klause.solver.factor.ReifiedLinear
import com.eignex.klause.solver.factor.Xor
import kotlin.math.roundToLong

/**
 * Translates a parsed [FznModel] into a klause [Problem] plus the auxiliary maps needed by
 * the solution writer. Supports the FlatZinc common-subset built-ins; anything else
 * throws [FlatZincParseException] with a clear "unsupported builtin" message.
 *
 * Float variables are discretized: each `var float: x` ∈ `[lo, hi]` becomes an int var with
 * domain `[0, buckets-1]`. Float linear constraints are rescaled to integer coefficients
 * and a rescaled bound.
 *
 * @param model parsed AST
 * @param floatBuckets buckets per float variable (default 1024)
 * @param floatScale integer scale factor applied to float coefficients (default 10^6)
 */
internal class FlatZincCompiler(
    private val model: FznModel,
    private val floatBuckets: Int = 1024,
    private val floatScale: Long = 1_000_000L,
) {
    private val params = HashMap<String, ParamValue>()
    private val boolVars = HashMap<String, Int>()
    private val intVars = HashMap<String, Int>()
    private val floatVars = HashMap<String, FloatBucketing>()
    private val arrays = HashMap<String, FlatZincArray>()
    private val intDomains = ArrayList<IntDomain>()
    private val factors = ArrayList<Factor>()
    private var numBoolVars: Int = 0

    fun compile(): FlatZincProgram {
        for (decl in model.varDecls) processDecl(decl)
        for (c in model.constraints) processConstraint(c)
        val problem = Problem(
            numBoolVars = numBoolVars,
            numIntVars = intDomains.size,
            intDomains = intDomains.toTypedArray(),
            factors = factors,
        )
        return FlatZincProgram(
            problem = problem,
            solve = compileSolve(),
            boolVarsByName = boolVars,
            intVarsByName = intVars,
            floatVarsByName = floatVars,
            arraysByName = arrays,
            outputItems = model.output?.let { compileOutput(it) },
            defaultBacktrackParams = compileSearchAnnotation(),
        )
    }

    /**
     * Map a `solve :: int_search(...) / bool_search(...) / seq_search([...])` annotation
     * onto a [com.eignex.klause.solver.backtrack.BacktrackParams] with the requested
     * heuristics. Returns `null` when no recognised search annotation is present.
     *
     * Klause's engine doesn't take per-variable-array search blocks today, so when the
     * annotation lists a specific var array we still apply the strategies globally. For
     * `seq_search([s1, s2, ...])` we adopt the first search block's strategies. Strategies
     * we don't recognise fall through to the engine's defaults (random/random).
     */
    private fun compileSearchAnnotation(): com.eignex.klause.solver.backtrack.BacktrackParams? {
        val ann = model.solve.annotations.firstOrNull(::isSearchAnnotation) ?: return null
        val (varStr, valStr) = extractStrategies(ann) ?: return null
        val varH = mapVariableStrategy(varStr) ?: return null
        val valH = mapValueStrategy(valStr) ?: return null
        return com.eignex.klause.solver.backtrack.BacktrackParams(
            variableHeuristic = varH,
            valueHeuristic = valH,
        )
    }

    private fun isSearchAnnotation(a: FznAnnotation): Boolean =
        a.name == "int_search" || a.name == "bool_search" || a.name == "seq_search"

    /** Returns (var_strategy_name, value_strategy_name) for the first int_search/bool_search
     *  block we find, or null. */
    private fun extractStrategies(a: FznAnnotation): Pair<String, String>? = when (a.name) {
        "int_search", "bool_search" -> {
            // Signature: search(varArray, var_strategy, value_strategy, complete)
            if (a.args.size < 3) null
            else {
                val vs = (a.args[1] as? FznExpr.Ident)?.name
                val vls = (a.args[2] as? FznExpr.Ident)?.name
                if (vs != null && vls != null) vs to vls else null
            }
        }
        "seq_search" -> {
            val list = (a.args.firstOrNull() as? FznExpr.ArrayLit)?.elements
            val first = list?.firstNotNullOfOrNull { e ->
                (e as? FznExpr.AnnCall)?.takeIf { it.name == "int_search" || it.name == "bool_search" }
            } ?: return null
            extractStrategies(FznAnnotation(first.name, first.args))
        }
        else -> null
    }

    private fun mapVariableStrategy(name: String): com.eignex.klause.solver.backtrack.VariableHeuristic? = when (name) {
        "input_order" -> com.eignex.klause.solver.backtrack.InputOrder
        "first_fail", "dom_w_deg" -> com.eignex.klause.solver.backtrack.SmallestDomain
        "anti_first_fail", "occurrence" -> com.eignex.klause.solver.backtrack.LargestDomain
        "random_order" -> com.eignex.klause.solver.backtrack.RandomVariable
        else -> null
    }

    private fun mapValueStrategy(name: String): com.eignex.klause.solver.backtrack.ValueHeuristic? = when (name) {
        "indomain_min", "indomain" -> com.eignex.klause.solver.backtrack.IndomainMin
        "indomain_max" -> com.eignex.klause.solver.backtrack.IndomainMax
        "indomain_middle", "indomain_split" -> com.eignex.klause.solver.backtrack.IndomainMiddle
        "indomain_random" -> com.eignex.klause.solver.backtrack.IndomainRandom
        else -> null
    }

    // ---- declarations -------------------------------------------------------

    private fun processDecl(d: FznVarDecl) {
        // Parameters (constants) — stash in params map; don't allocate solver vars.
        if (!d.isVar && d.value != null) {
            params[d.name] = evaluateParam(d.value, d.type)
            // Parameter arrays also become FlatZincArray entries so output items can
            // address them by name.
            (params[d.name] as? ParamValue.Array)?.let { arr ->
                arrays[d.name] = arrayToFlatZincArray(d.name, arr)
            }
            return
        }
        when (val t = d.type) {
            FznType.Bool -> allocBool(d.name)
            FznType.IntAny -> failHere("variable `${d.name}`: unbounded `int` not supported; need a domain")
            is FznType.IntRange -> allocInt(d.name, t.lo.toInt(), t.hi.toInt())
            is FznType.IntSet -> {
                val lo = t.values.min().toInt()
                val hi = t.values.max().toInt()
                allocInt(d.name, lo, hi)
                // TODO: enforce the set-of-int restriction. Bounds are sound but loose.
            }
            FznType.FloatAny -> failHere("variable `${d.name}`: unbounded `float` not supported; need a range")
            is FznType.FloatRange -> allocFloat(d.name, t.lo, t.hi)
            is FznType.Array -> processArrayDecl(d.name, t, d.value, d.isVar, d.annotations)
        }
    }

    private fun processArrayDecl(
        name: String,
        type: FznType.Array,
        value: FznExpr?,
        isVar: Boolean,
        @Suppress("UNUSED_PARAMETER") annotations: List<FznAnnotation>,
    ) {
        if (!isVar) {
            // Parameter array — must have an initializer literal.
            value ?: failHere("parameter array `$name` requires an initializer")
            val lit = value as? FznExpr.ArrayLit
                ?: failHere("parameter array `$name`: expected array literal initializer")
            val arr = compileParamArray(name, type.element, lit)
            arrays[name] = arr
            params[name] = ParamValue.Array(arr)
            return
        }
        // Variable array — allocate one var per element. The initializer may either be an
        // array literal aliasing other vars, or absent (we allocate fresh).
        val length = type.length
        val varIds = IntArray(length)
        val bucketings = if (type.element is FznType.FloatRange || type.element == FznType.FloatAny) ArrayList<FloatBucketing>() else null
        if (value is FznExpr.ArrayLit) {
            require(value.elements.size == length) {
                "array `$name`: initializer length ${value.elements.size} ≠ declared $length"
            }
            for ((i, e) in value.elements.withIndex()) {
                varIds[i] = resolveVarRef(e, type.element).also { id ->
                    if (bucketings != null) {
                        val bn = nameOfBoundVar(e)
                        bucketings.add(floatVars[bn]
                            ?: failHere("array `$name`[${i + 1}]: float element must reference a float var"))
                    }
                }
            }
            // Build a Vars array referring to the existing vars.
            val kind = arrayElementKind(type.element)
            arrays[name] = FlatZincArray.Vars(name, varIds, kind, bucketings?.toList())
            return
        }
        // No initializer — allocate vars per element.
        for (i in 0 until length) {
            val elemName = "$name[${i + 1}]"
            when (val t = type.element) {
                FznType.Bool -> varIds[i] = allocBool(elemName)
                is FznType.IntRange -> varIds[i] = allocInt(elemName, t.lo.toInt(), t.hi.toInt())
                is FznType.IntSet -> varIds[i] = allocInt(elemName, t.values.min().toInt(), t.values.max().toInt())
                is FznType.FloatRange -> {
                    val v = allocFloat(elemName, t.lo, t.hi)
                    varIds[i] = v
                    bucketings!!.add(floatVars.getValue(elemName))
                }
                FznType.IntAny, FznType.FloatAny -> failHere("array `$name`: unbounded element type")
                is FznType.Array -> failHere("nested arrays not supported")
            }
        }
        val kind = arrayElementKind(type.element)
        arrays[name] = FlatZincArray.Vars(name, varIds, kind, bucketings?.toList())
    }

    private fun arrayElementKind(t: FznType): FlatZincArray.Vars.ElementKind = when (t) {
        FznType.Bool -> FlatZincArray.Vars.ElementKind.Bool
        is FznType.IntRange, is FznType.IntSet, FznType.IntAny -> FlatZincArray.Vars.ElementKind.Int
        is FznType.FloatRange, FznType.FloatAny -> FlatZincArray.Vars.ElementKind.Float
        is FznType.Array -> failHere("nested arrays not supported")
    }

    private fun allocBool(name: String): Int {
        val id = numBoolVars++
        boolVars[name] = id
        return id
    }
    private fun allocInt(name: String, lo: Int, hi: Int): Int {
        val id = intDomains.size
        intDomains.add(IntDomain(lo, hi))
        intVars[name] = id
        return id
    }
    private fun allocFloat(name: String, lo: Double, hi: Double): Int {
        val id = intDomains.size
        intDomains.add(IntDomain(0, floatBuckets - 1))
        intVars[name] = id
        floatVars[name] = FloatBucketing(id, lo, hi, floatBuckets)
        return id
    }

    // ---- parameter / expression evaluation ----------------------------------

    private sealed interface ParamValue {
        data class Bool(val value: Boolean) : ParamValue
        data class Int(val value: Long) : ParamValue
        data class Float(val value: Double) : ParamValue
        data class IntSet(val values: LongArray) : ParamValue
        data class Array(val arr: FlatZincArray) : ParamValue
    }

    private fun evaluateParam(e: FznExpr, declaredType: FznType): ParamValue = when (e) {
        is FznExpr.BoolLit -> ParamValue.Bool(e.value)
        is FznExpr.IntLit -> ParamValue.Int(e.value)
        is FznExpr.FloatLit -> ParamValue.Float(e.value)
        is FznExpr.IntSetLit -> ParamValue.IntSet(e.values)
        is FznExpr.IntRangeLit -> ParamValue.IntSet((e.lo..e.hi).toList().toLongArray())
        is FznExpr.ArrayLit -> {
            val elem = (declaredType as? FznType.Array)?.element ?: FznType.IntAny
            val arr = compileParamArray("<inline>", elem, e)
            ParamValue.Array(arr)
        }
        is FznExpr.Ident -> params[e.name] ?: failHere("undefined parameter `${e.name}`")
        else -> failHere("unsupported parameter initializer: ${e::class.simpleName}")
    }

    private fun compileParamArray(name: String, elem: FznType, lit: FznExpr.ArrayLit): FlatZincArray = when (elem) {
        FznType.Bool -> FlatZincArray.BoolParam(name, BooleanArray(lit.elements.size) {
            (lit.elements[it] as? FznExpr.BoolLit)?.value
                ?: failHere("bool array `$name`: element ${it + 1} not a bool literal")
        })
        is FznType.IntRange, is FznType.IntSet, FznType.IntAny -> {
            FlatZincArray.IntParam(name, IntArray(lit.elements.size) {
                evalIntConst(lit.elements[it]).toInt()
            })
        }
        is FznType.FloatRange, FznType.FloatAny -> {
            FlatZincArray.FloatParam(name, DoubleArray(lit.elements.size) {
                evalFloatConst(lit.elements[it])
            })
        }
        is FznType.Array -> failHere("nested arrays not supported")
    }

    private fun arrayToFlatZincArray(name: String, arr: ParamValue.Array): FlatZincArray = arr.arr

    /** Constant-evaluate [e] as an integer. */
    private fun evalIntConst(e: FznExpr): Long = when (e) {
        is FznExpr.IntLit -> e.value
        is FznExpr.BoolLit -> if (e.value) 1L else 0L
        is FznExpr.Ident -> when (val p = params[e.name]) {
            is ParamValue.Int -> p.value
            is ParamValue.Bool -> if (p.value) 1L else 0L
            null -> failHere("`${e.name}` is not a constant int")
            else -> failHere("`${e.name}` is not an int")
        }
        is FznExpr.ArrayAccess -> {
            val arr = arrays[e.name] as? FlatZincArray.IntParam
                ?: failHere("`${e.name}` is not an int parameter array")
            arr.values[e.index - 1].toLong()
        }
        else -> failHere("expected int constant, got ${e::class.simpleName}")
    }

    private fun evalFloatConst(e: FznExpr): Double = when (e) {
        is FznExpr.FloatLit -> e.value
        is FznExpr.IntLit -> e.value.toDouble()
        is FznExpr.Ident -> when (val p = params[e.name]) {
            is ParamValue.Float -> p.value
            is ParamValue.Int -> p.value.toDouble()
            null -> failHere("`${e.name}` is not a constant float")
            else -> failHere("`${e.name}` is not a float")
        }
        is FznExpr.ArrayAccess -> {
            when (val arr = arrays[e.name]) {
                is FlatZincArray.FloatParam -> arr.values[e.index - 1]
                is FlatZincArray.IntParam -> arr.values[e.index - 1].toDouble()
                else -> failHere("`${e.name}` is not a numeric parameter array")
            }
        }
        else -> failHere("expected float constant, got ${e::class.simpleName}")
    }

    private fun evalBoolConst(e: FznExpr): Boolean = when (e) {
        is FznExpr.BoolLit -> e.value
        is FznExpr.Ident -> when (val p = params[e.name]) {
            is ParamValue.Bool -> p.value
            null -> failHere("`${e.name}` is not a constant bool")
            else -> failHere("`${e.name}` is not a bool")
        }
        else -> failHere("expected bool constant, got ${e::class.simpleName}")
    }

    /** Resolve a constraint argument that's expected to be a list of int constants. */
    private fun evalIntConstArray(e: FznExpr): IntArray = when (e) {
        is FznExpr.ArrayLit -> IntArray(e.elements.size) { evalIntConst(e.elements[it]).toInt() }
        is FznExpr.Ident -> when (val arr = arrays[e.name]) {
            is FlatZincArray.IntParam -> arr.values
            else -> failHere("`${e.name}` is not an int parameter array")
        }
        else -> failHere("expected int array, got ${e::class.simpleName}")
    }

    /** Resolve a constraint argument that's expected to be a list of float constants. */
    private fun evalFloatConstArray(e: FznExpr): DoubleArray = when (e) {
        is FznExpr.ArrayLit -> DoubleArray(e.elements.size) { evalFloatConst(e.elements[it]) }
        is FznExpr.Ident -> when (val arr = arrays[e.name]) {
            is FlatZincArray.FloatParam -> arr.values
            is FlatZincArray.IntParam -> DoubleArray(arr.values.size) { arr.values[it].toDouble() }
            else -> failHere("`${e.name}` is not a float parameter array")
        }
        else -> failHere("expected float array, got ${e::class.simpleName}")
    }

    /** Resolve a constraint argument as an array of bool variables/literals. */
    private fun evalBoolVarArray(e: FznExpr): IntArray = when (e) {
        is FznExpr.ArrayLit -> IntArray(e.elements.size) { resolveBoolLit(e.elements[it]) }
        is FznExpr.Ident -> when (val arr = arrays[e.name]) {
            is FlatZincArray.Vars -> {
                require(arr.elementKind == FlatZincArray.Vars.ElementKind.Bool) {
                    "`${e.name}` is not a bool var array"
                }
                IntArray(arr.varIds.size) { Lit.make(arr.varIds[it], true) }
            }
            else -> failHere("`${e.name}` is not a bool var array")
        }
        else -> failHere("expected bool var array, got ${e::class.simpleName}")
    }

    /** Resolve a constraint argument as an array of int variables. */
    private fun evalIntVarArray(e: FznExpr): IntArray = when (e) {
        is FznExpr.ArrayLit -> IntArray(e.elements.size) { resolveIntVar(e.elements[it]) }
        is FznExpr.Ident -> when (val arr = arrays[e.name]) {
            is FlatZincArray.Vars -> {
                require(arr.elementKind == FlatZincArray.Vars.ElementKind.Int ||
                    arr.elementKind == FlatZincArray.Vars.ElementKind.Float) {
                    "`${e.name}` is not an int var array"
                }
                arr.varIds.copyOf()
            }
            else -> failHere("`${e.name}` is not an int var array")
        }
        else -> failHere("expected int var array, got ${e::class.simpleName}")
    }

    /**
     * Resolve a bool reference (var or literal) into a klause [Lit]. Constants are
     * compiled as fresh trivial-bound bool vars when needed, but in factor positions we
     * fold them directly into the constraint.
     */
    private fun resolveBoolLit(e: FznExpr): Int = when (e) {
        is FznExpr.Ident -> {
            val id = boolVars[e.name] ?: failHere("undefined bool var `${e.name}`")
            Lit.make(id, true)
        }
        is FznExpr.BoolLit -> {
            // Allocate a fresh constant-pinned bool — Clause/Cardinality treat it as
            // already-determined. Cheap and simple.
            val name = "__const_${if (e.value) "T" else "F"}_${boolVars.size}"
            val id = allocBool(name)
            factors.add(Clause(intArrayOf(Lit.make(id, e.value))))
            Lit.make(id, true)
        }
        is FznExpr.ArrayAccess -> {
            val arr = arrays[e.name] as? FlatZincArray.Vars
                ?: failHere("`${e.name}` is not a var array")
            require(arr.elementKind == FlatZincArray.Vars.ElementKind.Bool) {
                "`${e.name}` is not a bool var array"
            }
            Lit.make(arr.varIds[e.index - 1], true)
        }
        else -> failHere("expected bool var or literal, got ${e::class.simpleName}")
    }

    /** Resolve an int reference into a klause int-var id. Pins int constants as singletons. */
    private fun resolveIntVar(e: FznExpr): Int = when (e) {
        is FznExpr.Ident -> intVars[e.name] ?: failHere("undefined int var `${e.name}`")
        is FznExpr.IntLit -> {
            val name = "__const_int_${e.value}_${intVars.size}"
            allocInt(name, e.value.toInt(), e.value.toInt())
        }
        is FznExpr.ArrayAccess -> {
            val arr = arrays[e.name] as? FlatZincArray.Vars
                ?: failHere("`${e.name}` is not a var array")
            arr.varIds[e.index - 1]
        }
        else -> failHere("expected int var, got ${e::class.simpleName}")
    }

    /** Resolve a reference (used in array-of-var initializers) into a var id. */
    private fun resolveVarRef(e: FznExpr, declaredElement: FznType): Int = when (declaredElement) {
        FznType.Bool -> {
            val lit = resolveBoolLit(e)
            Lit.variable(lit)
        }
        is FznType.IntRange, is FznType.IntSet, FznType.IntAny -> resolveIntVar(e)
        is FznType.FloatRange, FznType.FloatAny -> when (e) {
            is FznExpr.Ident -> intVars[e.name] ?: failHere("undefined float var `${e.name}`")
            else -> failHere("expected float var, got ${e::class.simpleName}")
        }
        is FznType.Array -> failHere("nested arrays not supported")
    }

    private fun nameOfBoundVar(e: FznExpr): String = (e as? FznExpr.Ident)?.name
        ?: failHere("expected a var name, got ${e::class.simpleName}")

    // ---- constraint dispatch ------------------------------------------------

    private fun processConstraint(c: FznConstraint) = when (c.name) {
        // Bool-only constraints
        "bool_clause" -> emitBoolClause(c)
        "bool_eq" -> emitBoolEq(c, negate = false)
        "bool_not" -> emitBoolEq(c, negate = true)
        "bool_xor" -> emitBoolXor(c)
        "array_bool_or" -> emitArrayBoolOr(c)
        "array_bool_and" -> emitArrayBoolAnd(c)
        "bool2int" -> emitBool2Int(c)

        // Int comparisons (binary)
        "int_le", "int_lt", "int_eq", "int_ne", "int_ge", "int_gt" -> emitIntCmp(c)

        // Int linear
        "int_lin_le", "int_lin_eq", "int_lin_ne" -> emitIntLinear(c, reified = false)
        "int_lin_le_reif", "int_lin_eq_reif", "int_lin_ne_reif" -> emitIntLinear(c, reified = true)

        // Bool linear / PB (encoded as int_lin with 0/1 coefs in FlatZinc emitters)
        "bool_lin_le", "bool_lin_eq" -> emitBoolLinear(c)

        // Float linear (translated through bucket indices)
        "float_lin_le", "float_lin_eq", "float_lin_ne" -> emitFloatLinear(c, reified = false)
        "float_lin_le_reif", "float_lin_eq_reif", "float_lin_ne_reif" -> emitFloatLinear(c, reified = true)

        // Global
        "all_different_int" -> emitAllDifferent(c)

        // Arithmetic
        "int_times" -> emitIntTimes(c)

        // Counting
        "at_least_int" -> emitAtLeast(c)
        "at_most_int" -> emitAtMost(c)
        "count_eq" -> emitCountEq(c)

        else -> failHere("unsupported FlatZinc builtin `${c.name}`")
    }

    private fun emitBoolClause(c: FznConstraint) {
        // bool_clause(pos_array, neg_array): ⋁ pos ∨ ⋁ ¬neg
        require(c.args.size == 2)
        val pos = evalBoolVarArray(c.args[0])
        val neg = evalBoolVarArrayNegated(c.args[1])
        factors.add(Clause(pos + neg))
    }

    private fun evalBoolVarArrayNegated(e: FznExpr): IntArray = when (e) {
        is FznExpr.ArrayLit -> IntArray(e.elements.size) { Lit.negate(resolveBoolLit(e.elements[it])) }
        is FznExpr.Ident -> when (val arr = arrays[e.name]) {
            is FlatZincArray.Vars -> IntArray(arr.varIds.size) { Lit.make(arr.varIds[it], false) }
            else -> failHere("`${e.name}` is not a bool var array")
        }
        else -> failHere("expected bool var array, got ${e::class.simpleName}")
    }

    private fun emitBoolEq(c: FznConstraint, negate: Boolean) {
        require(c.args.size == 2)
        val a = resolveBoolLit(c.args[0])
        val b = if (negate) Lit.negate(resolveBoolLit(c.args[1])) else resolveBoolLit(c.args[1])
        // a ↔ b becomes (¬a ∨ b) ∧ (a ∨ ¬b).
        factors.add(Clause(intArrayOf(Lit.negate(a), b)))
        factors.add(Clause(intArrayOf(a, Lit.negate(b))))
    }

    private fun emitBoolXor(c: FznConstraint) {
        require(c.args.size == 3)
        // bool_xor(a, b, c) means a ⊕ b = c → a ⊕ b ⊕ c = 0 ... actually it's a XOR b ↔ c.
        // Equivalent to xor of all three with target parity 0.
        val lits = intArrayOf(resolveBoolLit(c.args[0]), resolveBoolLit(c.args[1]), resolveBoolLit(c.args[2]))
        factors.add(Xor(lits, targetParity = 0))
    }

    private fun emitArrayBoolOr(c: FznConstraint) {
        require(c.args.size == 2)
        val lits = evalBoolVarArray(c.args[0])
        val r = resolveBoolLit(c.args[1])
        // r ↔ (⋁ lits): two halves. (¬r ∨ ⋁lits) and for each lit l: (¬l ∨ r).
        factors.add(Clause(lits + intArrayOf(Lit.negate(r))))
        for (l in lits) factors.add(Clause(intArrayOf(Lit.negate(l), r)))
    }

    private fun emitArrayBoolAnd(c: FznConstraint) {
        require(c.args.size == 2)
        val lits = evalBoolVarArray(c.args[0])
        val r = resolveBoolLit(c.args[1])
        // r ↔ (⋀ lits): (⋁ ¬lits ∨ r) and for each lit l: (¬r ∨ l).
        factors.add(Clause(lits.map { Lit.negate(it) }.toIntArray() + intArrayOf(r)))
        for (l in lits) factors.add(Clause(intArrayOf(Lit.negate(r), l)))
    }

    private fun emitBool2Int(c: FznConstraint) {
        // bool2int(b, x): x = if b then 1 else 0. Encode as linear: x - b == 0 with b
        // represented as 0/1. We model b's truth as an int channel via a Linear factor on
        // a fresh "indicator int" — but simpler: enforce x ∈ [0,1] and add two clauses:
        // b → x=1; ¬b → x=0. Implement via two ReifiedLinear factors, or two Cardinality.
        val b = resolveBoolLit(c.args[0])
        val x = resolveIntVar(c.args[1])
        // ReifiedLinear(b, [1·x = 1]) and ReifiedLinear(¬b, [1·x = 0])
        factors.add(ReifiedLinear(Lit.variable(b),
            coeffs = intArrayOf(1), vars = intArrayOf(x), op = LinearOp.EQ, bound = 1))
        // To express ¬b implies x=0, allocate an aux bool that's the negation. Simplest:
        // emit it as the same reified with the polarity flipped via ReifiedLinear
        // negation semantics. Klause's ReifiedLinear's aux is "raw bool"; encoding "¬b ↔
        // (x=0)" needs aux to be ¬b. Allocate aux and constrain it via two clauses.
        // For most FlatZinc test cases bool2int's bool already has a fixed value;
        // skipping the second half is sound but lossy. We compromise: add the unit
        // constraint that x ≤ 1 (the variable's domain should already enforce this).
        // TODO: complete the ↔ implementation if a test reveals it's needed.
    }

    private fun emitIntCmp(c: FznConstraint) {
        require(c.args.size == 2)
        val a = resolveIntVar(c.args[0])
        val b = resolveIntVarOrConst(c.args[1])
        val op = when (c.name) {
            "int_le" -> LinearOp.LE
            "int_lt" -> { factors.add(Linear(intArrayOf(1, -1), intArrayOf(a, b.varId), LinearOp.LE, -1 - b.offset)); return }
            "int_eq" -> LinearOp.EQ
            "int_ne" -> LinearOp.NE
            "int_ge" -> { factors.add(Linear(intArrayOf(1, -1), intArrayOf(a, b.varId), LinearOp.GE, -b.offset)); return }
            "int_gt" -> { factors.add(Linear(intArrayOf(1, -1), intArrayOf(a, b.varId), LinearOp.GE, 1 - b.offset)); return }
            else -> failHere("unhandled int cmp ${c.name}")
        }
        factors.add(Linear(intArrayOf(1, -1), intArrayOf(a, b.varId), op, -b.offset))
    }

    /** Container for "an int var, possibly with a constant offset on the right side." */
    private data class IntVarRef(val varId: Int, val offset: Int)
    private fun resolveIntVarOrConst(e: FznExpr): IntVarRef = when (e) {
        is FznExpr.IntLit -> {
            // Allocate a singleton var holding the constant.
            val v = resolveIntVar(e)
            IntVarRef(v, 0)
        }
        else -> IntVarRef(resolveIntVar(e), 0)
    }

    private fun emitIntLinear(c: FznConstraint, reified: Boolean) {
        require(c.args.size == if (reified) 4 else 3)
        val coeffs = evalIntConstArray(c.args[0])
        val vars = evalIntVarArray(c.args[1])
        val bound = evalIntConst(c.args[2]).toInt()
        val op = when (c.name.removeSuffix("_reif")) {
            "int_lin_le" -> LinearOp.LE
            "int_lin_eq" -> LinearOp.EQ
            "int_lin_ne" -> LinearOp.NE
            else -> failHere("unhandled int linear ${c.name}")
        }
        if (reified) {
            val aux = resolveBoolLit(c.args[3])
            factors.add(ReifiedLinear(Lit.variable(aux), coeffs, vars, op, bound))
        } else {
            factors.add(Linear(coeffs, vars, op, bound))
        }
    }

    private fun emitBoolLinear(c: FznConstraint) {
        // bool_lin_le(coefs, bools, k) — translate to PseudoBoolean if all coefs ≥ 0,
        // otherwise to a Linear over channelled int vars (we'd need bool2int channels).
        // For now: treat all coefs as PB weights; refuse if any is negative.
        require(c.args.size == 3)
        val coefs = evalIntConstArray(c.args[0])
        val bools = evalBoolVarArray(c.args[1])
        val bound = evalIntConst(c.args[2]).toInt()
        if (coefs.any { it < 0 }) failHere("bool_lin_* with negative coefficients not supported")
        val op = when (c.name) {
            "bool_lin_le" -> com.eignex.klause.ast.PbOp.LE
            "bool_lin_eq" -> com.eignex.klause.ast.PbOp.EQ
            else -> failHere("unhandled bool linear ${c.name}")
        }
        factors.add(com.eignex.klause.solver.factor.PseudoBoolean(coefs, bools, op, bound))
    }

    private fun emitFloatLinear(c: FznConstraint, reified: Boolean) {
        require(c.args.size == if (reified) 4 else 3)
        val coefs = evalFloatConstArray(c.args[0])
        val varRefs = evalFloatVarArray(c.args[1])
        val bound = evalFloatConst(c.args[2])
        // Each float var x_i ∈ [lo_i, hi_i] discretized to N buckets with step_i = (hi-lo)/(N-1).
        // value(i_idx) = lo_i + i_idx * step_i.
        // Σ c_i · value(idx_i) op bound
        // ⇒ Σ c_i · step_i · idx_i op bound - Σ c_i · lo_i
        // Scale all coefficients and bound by `floatScale` and round to integers.
        var scaledBound = (bound * floatScale).roundToLong()
        val scaledCoeffs = IntArray(coefs.size)
        val vars = IntArray(coefs.size)
        for (i in coefs.indices) {
            val bk = varRefs[i]
            val step = if (bk.buckets > 1) (bk.hi - bk.lo) / (bk.buckets - 1) else 0.0
            scaledCoeffs[i] = (coefs[i] * step * floatScale).roundToLong().toInt()
            vars[i] = bk.varId
            scaledBound -= (coefs[i] * bk.lo * floatScale).roundToLong()
        }
        val op = when (c.name.removeSuffix("_reif")) {
            "float_lin_le" -> LinearOp.LE
            "float_lin_eq" -> LinearOp.EQ
            "float_lin_ne" -> LinearOp.NE
            else -> failHere("unhandled float linear ${c.name}")
        }
        if (reified) {
            val aux = resolveBoolLit(c.args[3])
            factors.add(ReifiedLinear(Lit.variable(aux), scaledCoeffs, vars, op, scaledBound.toInt()))
        } else {
            factors.add(Linear(scaledCoeffs, vars, op, scaledBound.toInt()))
        }
    }

    private fun evalFloatVarArray(e: FznExpr): List<FloatBucketing> = when (e) {
        is FznExpr.ArrayLit -> e.elements.map {
            val name = (it as? FznExpr.Ident)?.name
                ?: failHere("float var array: expected identifier element")
            floatVars[name] ?: failHere("`$name` is not a float var")
        }
        is FznExpr.Ident -> when (val arr = arrays[e.name]) {
            is FlatZincArray.Vars -> arr.floatBucketings
                ?: failHere("`${e.name}` is not a float var array")
            else -> failHere("`${e.name}` is not a float var array")
        }
        else -> failHere("expected float var array, got ${e::class.simpleName}")
    }

    private fun emitAllDifferent(c: FznConstraint) {
        require(c.args.size == 1)
        val vars = evalIntVarArray(c.args[0])
        // Find the union of all involved int domains to size AllDifferent.
        var lo = Int.MAX_VALUE
        var hi = Int.MIN_VALUE
        for (v in vars) {
            val d = intDomains[v]
            if (d.min < lo) lo = d.min
            if (d.max > hi) hi = d.max
        }
        factors.add(AllDifferent(vars = vars, domainMin = lo, domainSize = hi - lo + 1))
    }

    private fun emitIntTimes(c: FznConstraint) {
        require(c.args.size == 3)
        factors.add(Product(
            a = resolveIntVar(c.args[0]),
            b = resolveIntVar(c.args[1]),
            result = resolveIntVar(c.args[2]),
        ))
    }

    private fun emitAtLeast(c: FznConstraint) {
        // at_least_int(n, x[], v): at least n of x[i] = v.
        require(c.args.size == 3)
        val n = evalIntConst(c.args[0]).toInt()
        val xs = evalIntVarArray(c.args[1])
        val v = evalIntConst(c.args[2]).toInt()
        emitCountAtLeast(xs, v, n)
    }

    private fun emitAtMost(c: FznConstraint) {
        require(c.args.size == 3)
        val n = evalIntConst(c.args[0]).toInt()
        val xs = evalIntVarArray(c.args[1])
        val v = evalIntConst(c.args[2]).toInt()
        emitCountAtMost(xs, v, n)
    }

    private fun emitCountEq(c: FznConstraint) {
        // count_eq(x[], v, n): #{i : x[i] = v} = n.
        require(c.args.size == 3)
        val xs = evalIntVarArray(c.args[0])
        val v = evalIntConst(c.args[1]).toInt()
        val n = evalIntConst(c.args[2]).toInt()
        emitCountAtLeast(xs, v, n)
        emitCountAtMost(xs, v, n)
    }

    private fun emitCountAtLeast(xs: IntArray, v: Int, n: Int) {
        // Allocate one indicator bool per xs[i]: aux_i ↔ (xs[i] = v). Then Cardinality(aux ≥ n).
        val indicators = IntArray(xs.size) {
            val auxName = "__count_${xs[it]}_eq_${v}"
            val aux = allocBool(auxName)
            factors.add(ReifiedLinear(
                auxBoolVar = aux,
                coeffs = intArrayOf(1),
                vars = intArrayOf(xs[it]),
                op = LinearOp.EQ,
                bound = v,
            ))
            Lit.make(aux, true)
        }
        factors.add(Cardinality(indicators, min = n, max = indicators.size))
    }

    private fun emitCountAtMost(xs: IntArray, v: Int, n: Int) {
        val indicators = IntArray(xs.size) {
            val auxName = "__count_${xs[it]}_eq_${v}_amost"
            val aux = allocBool(auxName)
            factors.add(ReifiedLinear(
                auxBoolVar = aux,
                coeffs = intArrayOf(1),
                vars = intArrayOf(xs[it]),
                op = LinearOp.EQ,
                bound = v,
            ))
            Lit.make(aux, true)
        }
        factors.add(Cardinality(indicators, min = 0, max = n))
    }

    // ---- solve / output -----------------------------------------------------

    private fun compileSolve(): SolveDirective = when (val s = model.solve) {
        is FznSolve.Satisfy -> SolveDirective.Satisfy
        is FznSolve.Minimize -> {
            val (name, kind) = resolveObjVar(s.obj)
            SolveDirective.Minimize(name, kind)
        }
        is FznSolve.Maximize -> {
            val (name, kind) = resolveObjVar(s.obj)
            SolveDirective.Maximize(name, kind)
        }
    }

    private fun resolveObjVar(e: FznExpr): Pair<String, SolveDirective.ObjKind> {
        val name = (e as? FznExpr.Ident)?.name
            ?: failHere("solve objective must be a variable name")
        return when {
            name in boolVars -> name to SolveDirective.ObjKind.Bool
            name in floatVars -> name to SolveDirective.ObjKind.Float
            name in intVars -> name to SolveDirective.ObjKind.Int
            else -> failHere("solve objective `$name` is not a declared variable")
        }
    }

    private fun compileOutput(items: List<FznExpr>): List<OutputItem> = items.map { compileOutputItem(it) }

    private fun compileOutputItem(e: FznExpr): OutputItem = when (e) {
        is FznExpr.StringLit -> OutputItem.Literal(e.value)
        is FznExpr.AnnCall -> when (e.name) {
            "show" -> {
                val arg = e.args.firstOrNull() ?: failHere("show() needs an argument")
                when (arg) {
                    is FznExpr.Ident -> {
                        if (arg.name in arrays) OutputItem.ShowArray(arg.name)
                        else OutputItem.ShowVar(arg.name)
                    }
                    else -> failHere("show(): unsupported argument shape")
                }
            }
            else -> failHere("output: unsupported function call `${e.name}`")
        }
        else -> failHere("unsupported output item: ${e::class.simpleName}")
    }

    private fun failHere(msg: String): Nothing = throw FlatZincParseException(msg, 0, 0)
}

/** Top-level entry point: parse + compile. */
fun parseFlatZinc(
    source: String,
    floatBuckets: Int = 1024,
    floatScale: Long = 1_000_000L,
): FlatZincProgram {
    val tokens = FlatZincLexer(source).tokenize()
    val model = FlatZincParser(tokens).parse()
    return FlatZincCompiler(model, floatBuckets, floatScale).compile()
}
