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
    /**
     * Per the MiniZinc Challenge LS-track rules, `symmetry_breaking_constraint(...)` and
     * `redundant_constraint(...)` may be dropped by local-search solvers. Set this to
     * `true` from LS-engine entry points to skip those constraints entirely. The CP
     * default enforces them as `bool == true`.
     */
    internal val forLocalSearch: Boolean = false,
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

    /**
     * Float-var-index assigned in allocation order — keyed by the float var's backing int
     * id. Populated by [allocFloat]. Builds [com.eignex.klause.solver.FloatMetadata] at
     * compile time so backends with native real support (Z3) can recover the real-valued
     * view instead of solving over the bucketed ints.
     */
    internal val floatVarIndex = HashMap<Int, Int>()
    internal val floatIntervals = ArrayList<com.eignex.klause.solver.FloatInterval>()
    internal val floatBucketCounts = ArrayList<Int>()
    internal val floatIntVarIds = ArrayList<Int>()
    internal val realConstraints = ArrayList<com.eignex.klause.solver.RealLinearConstraint>()

    /** Enum-typed int vars: declared label list per var name. Populated from
     *  `klause_enum_labels([...])` annotations on the var decl. */
    internal val enumLabelsByVar = HashMap<String, List<String>>()

    /** Per `var set of E: S` declaration, the bool-indicator decomposition. Populated by
     *  [processDecl] when it sees a [FznType.SetOfInt]; consumed by the FZN writer to
     *  reconstruct `{a, b, c}` MiniZinc output. Set predicates (`set_in`, `set_subset`,
     *  `set_card`, ...) dispatch through these indicator bools at constraint-emit time. */
    internal val setVarsByName = LinkedHashMap<String, SetVarLayout>()

    fun compile(): FlatZincProgram {
        for (decl in model.varDecls) processDecl(decl)
        for (c in model.constraints) processConstraint(c)
        val floatMetadata = if (floatIntervals.isEmpty()) null else
            com.eignex.klause.solver.FloatMetadata(
                intervals = floatIntervals.toTypedArray(),
                bucketCounts = floatBucketCounts.toIntArray(),
                intVarByFloatVar = floatIntVarIds.toIntArray(),
                constraints = realConstraints.toList(),
            )
        val problem = Problem(
            numBoolVars = numBoolVars,
            numIntVars = intDomains.size,
            intDomains = intDomains.toTypedArray(),
            factors = factors,
            floatMetadata = floatMetadata,
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
            enumLabelsByVar = enumLabelsByVar.toMap(),
            setVarsByName = setVarsByName.toMap(),
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
            is FznType.SetOfInt -> allocSetVar(d.name, t)
            is FznType.Array -> processArrayDecl(d.name, t, d.value, d.isVar)
        }
        recordEnumLabels(d)
    }

    /**
     * Recognise `klause_enum_labels(["Red","Green","Blue"])` on a var decl. The klause MZN
     * library emits this on enum-typed ints so the tag names survive into klause; without
     * it MiniZinc lowers enums to bare `1..n` ints with the tag table only in `.ozn`.
     */
    internal fun recordEnumLabels(d: FznVarDecl) {
        val ann = d.annotations.firstOrNull { it.name == "klause_enum_labels" } ?: return
        if (ann.args.size != 1) failHere("klause_enum_labels: expected 1 array arg")
        val arr = ann.args[0] as? FznExpr.ArrayLit
            ?: failHere("klause_enum_labels: expected array literal")
        val labels = arr.elements.map {
            (it as? FznExpr.StringLit)?.value
                ?: failHere("klause_enum_labels: elements must be string literals")
        }
        enumLabelsByVar[d.name] = labels
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
        // Array-of-set-of-int: materialise each element as its own SetVarLayout under name
        // `<arr>[<i>]`, and register the array as FlatZincArray.SetVars.
        if (type.element is FznType.SetOfInt) {
            val layouts = ArrayList<SetVarLayout>(type.length)
            for (i in 0 until type.length) {
                val elemName = "$name[${i + 1}]"
                allocSetVar(elemName, type.element)
                layouts.add(setVarsByName.getValue(elemName))
            }
            arrays[name] = FlatZincArray.SetVars(name, layouts)
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
                is FznType.SetOfInt -> failHere("array `$name`: array of set-of-int not supported")
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
        is FznType.SetOfInt -> failHere("set-of-int element kind not supported")
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
    /**
     * Materialise a `var set of E: name` declaration as one indicator bool per universe
     * element. Resolves the universe to a sorted ascending int array; allocates one bool
     * per element; records the layout in [setVarsByName] for downstream constraint dispatch
     * and FZN output reconstruction.
     */
    internal fun allocSetVar(name: String, type: FznType.SetOfInt) {
        val elements = universeElements(type.element, name)
        val indicatorIds = IntArray(elements.size) { i ->
            allocBool("__set_${name}_${elements[i]}")
        }
        setVarsByName[name] = SetVarLayout(name, elements, indicatorIds)
    }

    /** Resolve the universe of a `var set of E` declaration to a sorted ascending int array. */
    internal fun universeElements(elem: FznType, ownerName: String): IntArray = when (elem) {
        is FznType.IntRange -> {
            require(elem.lo <= elem.hi) { "set `$ownerName`: empty universe ${elem.lo}..${elem.hi}" }
            IntArray((elem.hi - elem.lo + 1).toInt()) { (elem.lo + it).toInt() }
        }
        is FznType.IntSet -> elem.values.map { it.toInt() }.toIntArray().also { it.sort() }
        else -> failHere("set `$ownerName`: universe must be an int range or int set, got ${elem::class.simpleName}")
    }

    internal fun allocFloat(name: String, lo: Double, hi: Double): Int {
        val id = intDomains.size
        intDomains.add(IntDomain(0, floatBuckets - 1))
        intVars[name] = id
        floatVars[name] = FloatBucketing(id, lo, hi, floatBuckets)
        // Assign a float-var-index for FloatMetadata in allocation order.
        floatVarIndex[id] = floatIntervals.size
        floatIntervals.add(com.eignex.klause.solver.FloatInterval(lo, hi))
        floatBucketCounts.add(floatBuckets)
        floatIntVarIds.add(id)
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
        is FznType.SetOfInt -> failHere("parameter array `$name`: set-of-int not supported")
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
        is FznType.SetOfInt -> failHere("set-of-int element refs not supported")
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
    forLocalSearch: Boolean = false,
): FlatZincProgram {
    val tokens = FlatZincLexer(source).tokenize()
    val model = FlatZincParser(tokens).parse()
    return FlatZincCompiler(model, floatBuckets, floatScale, forLocalSearch).compile()
}
