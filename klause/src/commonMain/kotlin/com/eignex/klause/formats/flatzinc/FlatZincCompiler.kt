package com.eignex.klause.formats.flatzinc

import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.AllDifferent
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.factor.Circuit
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.solver.factor.Cumulative
import com.eignex.klause.solver.factor.Disjunctive
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.factor.Product
import com.eignex.klause.solver.factor.ReifiedLinear
import com.eignex.klause.solver.factor.Subcircuit
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
    internal val model: FznModel,
    internal val floatBuckets: Int = 1024,
    internal val floatScale: Long = 1_000_000L,
) {
    // State is `internal` (not `private`) so the extension functions in
    // `FlatZincExprEval.kt` / `FlatZincConstraints.kt` / `FlatZincSolveOutput.kt` can
    // access it. The class itself is `internal`, so this is intra-module bookkeeping —
    // no encapsulation leak to the public API.
    internal val params = HashMap<String, ParamValue>()
    internal val boolVars = HashMap<String, Int>()
    internal val intVars = HashMap<String, Int>()
    internal val floatVars = HashMap<String, FloatBucketing>()
    internal val arrays = HashMap<String, FlatZincArray>()
    internal val intDomains = ArrayList<IntDomain>()
    internal val factors = ArrayList<Factor>()
    internal var numBoolVars: Int = 0

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
            outputItems = model.output?.let { compileOutput(it) } ?: synthesizeOutputItems(),
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
    internal fun compileSearchAnnotation(): com.eignex.klause.solver.backtrack.BacktrackParams? {
        val ann = model.solve.annotations.firstOrNull(::isSearchAnnotation) ?: return null
        val (varStr, valStr) = extractStrategies(ann) ?: return null
        val varH = mapVariableStrategy(varStr) ?: return null
        val valH = mapValueStrategy(valStr) ?: return null
        return com.eignex.klause.solver.backtrack.BacktrackParams(
            variableHeuristic = varH,
            valueHeuristic = valH,
        )
    }

    internal fun isSearchAnnotation(a: FznAnnotation): Boolean =
        a.name == "int_search" || a.name == "bool_search" || a.name == "seq_search"

    /** Returns (var_strategy_name, value_strategy_name) for the first int_search/bool_search
     *  block we find, or null. */
    internal fun extractStrategies(a: FznAnnotation): Pair<String, String>? = when (a.name) {
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

    internal fun mapVariableStrategy(name: String): com.eignex.klause.solver.backtrack.VariableHeuristic? = when (name) {
        "input_order" -> com.eignex.klause.solver.backtrack.InputOrder
        "first_fail", "dom_w_deg" -> com.eignex.klause.solver.backtrack.SmallestDomain
        "anti_first_fail", "occurrence" -> com.eignex.klause.solver.backtrack.LargestDomain
        "random_order" -> com.eignex.klause.solver.backtrack.RandomVariable
        else -> null
    }

    internal fun mapValueStrategy(name: String): com.eignex.klause.solver.backtrack.ValueHeuristic? = when (name) {
        "indomain_min", "indomain" -> com.eignex.klause.solver.backtrack.IndomainMin
        "indomain_max" -> com.eignex.klause.solver.backtrack.IndomainMax
        "indomain_middle", "indomain_split" -> com.eignex.klause.solver.backtrack.IndomainMiddle
        "indomain_random" -> com.eignex.klause.solver.backtrack.IndomainRandom
        else -> null
    }

    // ---- declarations -------------------------------------------------------

    internal fun processDecl(d: FznVarDecl) {
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
            is FznType.Array -> processArrayDecl(d.name, t, d.value, d.isVar)
        }
    }

    internal fun processArrayDecl(
        name: String,
        type: FznType.Array,
        value: FznExpr?,
        isVar: Boolean,
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

    internal fun arrayElementKind(t: FznType): FlatZincArray.Vars.ElementKind = when (t) {
        FznType.Bool -> FlatZincArray.Vars.ElementKind.Bool
        is FznType.IntRange, is FznType.IntSet, FznType.IntAny -> FlatZincArray.Vars.ElementKind.Int
        is FznType.FloatRange, FznType.FloatAny -> FlatZincArray.Vars.ElementKind.Float
        is FznType.Array -> failHere("nested arrays not supported")
    }

    internal fun allocBool(name: String): Int {
        val id = numBoolVars++
        boolVars[name] = id
        return id
    }
    internal fun allocInt(name: String, lo: Int, hi: Int): Int {
        val id = intDomains.size
        intDomains.add(IntDomain(lo, hi))
        intVars[name] = id
        return id
    }
    internal fun allocFloat(name: String, lo: Double, hi: Double): Int {
        val id = intDomains.size
        intDomains.add(IntDomain(0, floatBuckets - 1))
        intVars[name] = id
        floatVars[name] = FloatBucketing(id, lo, hi, floatBuckets)
        return id
    }

    // ---- parameter / expression evaluation ----------------------------------

    internal sealed interface ParamValue {
        data class Bool(val value: Boolean) : ParamValue
        data class Int(val value: Long) : ParamValue
        data class Float(val value: Double) : ParamValue
        data class IntSet(val values: LongArray) : ParamValue
        data class Array(val arr: FlatZincArray) : ParamValue
    }

    internal fun evaluateParam(e: FznExpr, declaredType: FznType): ParamValue = when (e) {
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

    internal fun compileParamArray(name: String, elem: FznType, lit: FznExpr.ArrayLit): FlatZincArray = when (elem) {
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

    internal fun arrayToFlatZincArray(name: String, arr: ParamValue.Array): FlatZincArray = arr.arr

    /** Constant-evaluate [e] as an integer. */
    internal fun evalIntConst(e: FznExpr): Long = when (e) {
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

    /** Non-throwing variant of [evalIntConst]. Returns `null` when [e] refers to a
     *  solver variable rather than a compile-time constant. */
    internal fun evalIntConstOrNull(e: FznExpr): Long? = when (e) {
        is FznExpr.IntLit -> e.value
        is FznExpr.BoolLit -> if (e.value) 1L else 0L
        is FznExpr.Ident -> when (val p = params[e.name]) {
            is ParamValue.Int -> p.value
            is ParamValue.Bool -> if (p.value) 1L else 0L
            else -> null
        }
        is FznExpr.ArrayAccess -> (arrays[e.name] as? FlatZincArray.IntParam)?.values?.get(e.index - 1)?.toLong()
        else -> null
    }

    internal fun evalFloatConst(e: FznExpr): Double = when (e) {
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

    internal fun evalBoolConst(e: FznExpr): Boolean = when (e) {
        is FznExpr.BoolLit -> e.value
        is FznExpr.Ident -> when (val p = params[e.name]) {
            is ParamValue.Bool -> p.value
            null -> failHere("`${e.name}` is not a constant bool")
            else -> failHere("`${e.name}` is not a bool")
        }
        else -> failHere("expected bool constant, got ${e::class.simpleName}")
    }

    /** Resolve a constraint argument that's expected to be a list of int constants. */
    internal fun evalIntConstArray(e: FznExpr): IntArray = when (e) {
        is FznExpr.ArrayLit -> IntArray(e.elements.size) { evalIntConst(e.elements[it]).toInt() }
        is FznExpr.Ident -> when (val arr = arrays[e.name]) {
            is FlatZincArray.IntParam -> arr.values
            else -> failHere("`${e.name}` is not an int parameter array")
        }
        else -> failHere("expected int array, got ${e::class.simpleName}")
    }

    /** Resolve a constraint argument that's expected to be a list of float constants. */
    internal fun evalFloatConstArray(e: FznExpr): DoubleArray = when (e) {
        is FznExpr.ArrayLit -> DoubleArray(e.elements.size) { evalFloatConst(e.elements[it]) }
        is FznExpr.Ident -> when (val arr = arrays[e.name]) {
            is FlatZincArray.FloatParam -> arr.values
            is FlatZincArray.IntParam -> DoubleArray(arr.values.size) { arr.values[it].toDouble() }
            else -> failHere("`${e.name}` is not a float parameter array")
        }
        else -> failHere("expected float array, got ${e::class.simpleName}")
    }

    /** Resolve a constraint argument as an array of bool variables/literals. */
    internal fun evalBoolVarArray(e: FznExpr): IntArray = when (e) {
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

    internal fun evalBoolConstArray(e: FznExpr): BooleanArray = when (e) {
        is FznExpr.ArrayLit -> BooleanArray(e.elements.size) {
            (e.elements[it] as? FznExpr.BoolLit)?.value
                ?: failHere("expected bool literal in const array, got ${e.elements[it]::class.simpleName}")
        }
        is FznExpr.Ident -> (arrays[e.name] as? FlatZincArray.BoolParam)?.values
            ?: failHere("`${e.name}` is not a bool parameter array")
        else -> failHere("expected bool const array, got ${e::class.simpleName}")
    }

    /** Resolve a constraint argument as an array of int variables. */
    internal fun evalIntVarArray(e: FznExpr): IntArray = when (e) {
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
    internal fun resolveBoolLit(e: FznExpr): Int = when (e) {
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
    internal fun resolveIntVar(e: FznExpr): Int = when (e) {
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
    internal fun resolveVarRef(e: FznExpr, declaredElement: FznType): Int = when (declaredElement) {
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

    internal fun nameOfBoundVar(e: FznExpr): String = (e as? FznExpr.Ident)?.name
        ?: failHere("expected a var name, got ${e::class.simpleName}")


    // ---- solve / output -----------------------------------------------------

    internal fun compileSolve(): SolveDirective = when (val s = model.solve) {
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

    internal fun resolveObjVar(e: FznExpr): Pair<String, SolveDirective.ObjKind> {
        val name = (e as? FznExpr.Ident)?.name
            ?: failHere("solve objective must be a variable name")
        return when {
            name in boolVars -> name to SolveDirective.ObjKind.Bool
            name in floatVars -> name to SolveDirective.ObjKind.Float
            name in intVars -> name to SolveDirective.ObjKind.Int
            else -> failHere("solve objective `$name` is not a declared variable")
        }
    }

    /**
     * When the FZN file has no explicit `output [...]` section, MiniZinc relies on
     * `:: output_var` / `:: output_array(...)` annotations on individual var declarations
     * to mark what to display. Synthesize an equivalent `OutputItem` list so the writer
     * emits only the user-declared variables and skips internal `X_INTRODUCED_*` vars.
     */
    internal fun synthesizeOutputItems(): List<OutputItem>? {
        val items = ArrayList<OutputItem>()
        for (decl in model.varDecls) {
            val asArray = decl.annotations.firstOrNull { it.name == "output_array" }
            val asVar = decl.annotations.firstOrNull { it.name == "output_var" }
            when {
                asArray != null -> {
                    items += OutputItem.Literal("${decl.name} = ")
                    items += OutputItem.ShowArray(decl.name)
                    items += OutputItem.Literal(";\n")
                }
                asVar != null -> {
                    items += OutputItem.Literal("${decl.name} = ")
                    items += OutputItem.ShowVar(decl.name)
                    items += OutputItem.Literal(";\n")
                }
            }
        }
        // Returning null preserves the writer's "no annotations, print every var" fallback.
        return if (items.isEmpty()) null else items
    }

    internal fun compileOutput(items: List<FznExpr>): List<OutputItem> = items.map { compileOutputItem(it) }

    internal fun compileOutputItem(e: FznExpr): OutputItem = when (e) {
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

    internal fun failHere(msg: String): Nothing = throw FlatZincParseException(msg, 0, 0)
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
