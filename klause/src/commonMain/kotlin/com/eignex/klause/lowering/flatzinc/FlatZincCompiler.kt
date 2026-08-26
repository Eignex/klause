package com.eignex.klause.lowering.flatzinc

import com.eignex.klause.config.DEFAULT_FLOAT_BUCKETS
import com.eignex.klause.config.DEFAULT_FLOAT_SCALE
import com.eignex.klause.config.DEFAULT_UNBOUNDED_INT_HI
import com.eignex.klause.config.DEFAULT_UNBOUNDED_INT_LO
import com.eignex.klause.config.MINIZINC_UNBOUNDED_DEFAULT
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.formats.flatzinc.*
import com.eignex.klause.ir.Lit
import com.eignex.klause.lowering.CnfLowering
import com.eignex.klause.lowering.FloatBucketing
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Problem
import com.eignex.klause.util.CharReader
import com.eignex.klause.util.CharSource
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.IntHashSet
import com.eignex.klause.util.StringCharSource
import com.eignex.klause.util.binarySearchInt
import com.eignex.klause.util.toSortedIntArray

/** Float-constraint names that are non-strict, linear-in-reals and non-reified — a float whose whole
 *  constraint-connected component uses only these is coloured LP-only (see `classifyLpOnlyFloats`) and
 *  lowered to real [com.eignex.klause.factor.arithmetic.Linear] rows. Any other float constraint
 *  (products, abs, element, min/max, strict `<`, `ne`, reified) taints its component back to bucketing. */
private val FLOAT_LP_ONLY_NAMES = setOf("float_lin_le", "float_lin_eq", "int2float", "float_eq", "float_le")

/** Compile parsed FlatZinc AST into solver data structures. */
internal class FlatZincCompiler(
    internal val model: FznModel,
    internal val floatBuckets: Int = DEFAULT_FLOAT_BUCKETS,
    internal val floatScale: Long = DEFAULT_FLOAT_SCALE,
    /** When true, skip redundant/symmetry annotations for LS-track behavior. */
    internal val forLocalSearch: Boolean = false,
    /** Default domain for unbounded `var int` declarations. */
    internal val unboundedIntLo: Long = DEFAULT_UNBOUNDED_INT_LO,
    internal val unboundedIntHi: Long = DEFAULT_UNBOUNDED_INT_HI,
) : CnfLowering {
    internal val params = HashMap<String, ParamValue>()
    internal val boolVars = HashMap<String, Int>()
    internal val intVars = HashMap<String, Int>()
    internal val floatVars = HashMap<String, FloatBucketing>()
    internal val arrays = HashMap<String, FlatZincArray>()
    internal val intDomains = ArrayList<IntDomain>()
    override val factors: MutableList<Factor> = ArrayList()
    internal var numBoolVars: Int = 0

    override fun newBool(): Int = numBoolVars++
    override var trueLitCache: Int = -1

    // LP-only continuous columns: the scalar float var names a prepass ([classifyLpOnlyFloats])
    // colours LP-only — each is lowered as a real variable rather than a bucket-index int, and the linear
    // float handlers emit real [Linear] rows the simplex resolves. Parallel real bounds by real var id.
    internal var lpOnlyFloats: Set<String> = emptySet()
    internal val realLo = ArrayList<Double>()
    internal val realHi = ArrayList<Double>()

    // Float var name -> the integer argument of the `int2float` that defines it (the float is that int's
    // continuous image). Lets a `float_times` with one such operand lower as an exact int·real product
    // ([com.eignex.klause.factor.arithmetic.RealProduct]) rather than a bucket table.
    internal val int2floatSource = HashMap<String, FznExpr>()

    internal val enumLabelsByVar = HashMap<String, List<String>>()

    internal val setVarsByName = LinkedHashMap<String, SetVarLayout>()

    fun compile(): FlatZincProgram {
        for (c in model.constraints) {
            if (c.name == "int2float" && c.args.size == 2) {
                (c.args[1] as? FznExpr.Ident)?.let { int2floatSource[it.name] = c.args[0] }
            }
        }
        lpOnlyFloats = classifyLpOnlyFloats()
        for (decl in model.varDecls) {
            currentLine = decl.line
            currentCol = decl.col
            processDecl(decl)
        }
        val impliedFactorIds = IntArrayList()
        var hasSymmetryBreaking = false
        for (c in model.constraints) {
            currentLine = c.line
            currentCol = c.col
            val before = factors.size
            processConstraint(c)
            val redundant = c.annotations.any { it.name == "klause_redundant" }
            val symmetry = c.annotations.any { it.name == "klause_symmetry" }
            if (symmetry) hasSymmetryBreaking = true
            if (redundant || symmetry) for (i in before until factors.size) impliedFactorIds.add(i)
        }
        val solveDirective = compileSolve()
        val impliedFactorMask = if (impliedFactorIds.isEmpty()) {
            null
        } else {
            BooleanArray(factors.size).also { mask -> impliedFactorIds.forEach { i -> mask[i] = true } }
        }
        // The LS functional objective and definitional sweep resolve constraint args, which allocates a
        // singleton int var per integer-literal argument ([resolveIntVar] on an `IntLit` appends to
        // [requireFiniteIntDomains]). Build them BEFORE snapshotting the [Problem] so those vars are counted in
        // numIntVars/intDomains — otherwise the sweep references a var id the Problem lacks and the LS
        // invariant network indexes out of bounds.
        val lsObjective = when (solveDirective) {
            is SolveDirective.Minimize -> buildFunctionalObjective(solveDirective.objVar, minimize = true)
            is SolveDirective.Maximize -> buildFunctionalObjective(solveDirective.objVar, minimize = false)
            else -> null
        }
        val definitionalSweep = buildDefinitionalSweep()
        // A plain base-baked [Problem]; the SAC / failed-literal probing resolved from the presolve
        // config runs later in the presolve lane via [RootBaker] (the kernel never probes itself).
        val problem = Problem(
            numBoolVars = numBoolVars,
            numIntVars = intDomains.size,
            intDomains = intDomains.toTypedArray(),
            factors = factors.toTypedArray(),
            impliedFactorMask = impliedFactorMask,
            hasSymmetryBreaking = hasSymmetryBreaking,
            numRealVars = realLo.size,
            realLower = realLo.toDoubleArray(),
            realUpper = realHi.toDoubleArray(),
        )
        return FlatZincProgram(
            problem = problem,
            solve = solveDirective,
            boolVarsByName = boolVars,
            intVarsByName = intVars,
            floatVarsByName = floatVars,
            arraysByName = arrays,
            outputItems = model.output?.let { compileOutput(it) } ?: synthesizeOutputItems(),
            defaultBacktrackParams = compileSearchAnnotation(),
            enumLabelsByVar = enumLabelsByVar.toMap(),
            setVarsByName = setVarsByName.toMap(),
            lsObjective = lsObjective,
            definitionalSweep = definitionalSweep,
        )
    }

    internal fun processDecl(d: FznVarDecl) {
        ensureFreshName(d.name)
        if (!d.isVar) {
            val value = d.value ?: failHere("parameter `${d.name}` requires an initializer")
            params[d.name] = evaluateParam(value, d.type)
            (params[d.name] as? ParamValue.Array)?.let { arr ->
                arrays[d.name] = arr.arr
            }
            return
        }
        // Keep scalar aliases attached to the source var id.
        if (d.isVar && d.value != null && d.type !is FznType.Array && d.type !is FznType.SetOfInt) {
            aliasScalarVar(d.name, d.type, d.value)
            recordEnumLabels(d)
            return
        }
        when (val t = d.type) {
            FznType.Bool -> allocBool(d.name)

            FznType.IntAny -> allocInt(
                d.name,
                unboundedIntLo.coerceIn(-MINIZINC_UNBOUNDED_DEFAULT, MINIZINC_UNBOUNDED_DEFAULT),
                unboundedIntHi.coerceIn(-MINIZINC_UNBOUNDED_DEFAULT, MINIZINC_UNBOUNDED_DEFAULT),
            )

            is FznType.IntRange -> allocInt(d.name, t.lo, t.hi)

            is FznType.IntSet -> allocIntSet(d.name, t)

            FznType.FloatAny -> failHere("variable `${d.name}`: unbounded `float` not supported; need a range")

            is FznType.FloatRange -> allocFloat(d.name, t.lo, t.hi)

            is FznType.SetOfInt -> allocSetVar(d.name, t, d.value)

            is FznType.Array -> processArrayDecl(d.name, t, d.value, d.isVar)
        }
        recordEnumLabels(d)
    }

    /** Bind scalar aliases and constant pins to an existing solver variable id. */
    internal fun aliasScalarVar(name: String, type: FznType, rhs: FznExpr) {
        when (type) {
            FznType.Bool -> boolVars[name] = Lit.variable(resolveBoolLit(rhs))

            FznType.IntAny, is FznType.IntSet -> intVars[name] = resolveIntVar(rhs)

            is FznType.IntRange -> {
                val id = resolveIntVar(rhs)
                intDomains[id] = intDomains[id].withMinAtLeast(type.lo).withMaxAtMost(type.hi)
                intVars[name] = id
            }

            is FznType.FloatRange, FznType.FloatAny -> {
                val src = (rhs as? FznExpr.Ident)?.name
                    ?: failHere("float var `$name`: alias initializer must be a variable reference")
                val fb = floatVars[src] ?: failHere("float var `$name`: undefined float alias target `$src`")
                intVars[name] = fb.varId
                floatVars[name] = fb
            }

            is FznType.SetOfInt, is FznType.Array -> failHere(
                "`$name`: unexpected aliased type ${type::class.simpleName}",
            )
        }
    }

    /** Preserve enum labels emitted as `klause_enum_labels([...])`. */
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

    internal fun processArrayDecl(name: String, type: FznType.Array, value: FznExpr?, isVar: Boolean) {
        if (!isVar) {
            value ?: failHere("parameter array `$name` requires an initializer")
            val lit = value as? FznExpr.ArrayLit
                ?: failHere("parameter array `$name`: expected array literal initializer")
            val arr = compileParamArray(name, type.element, lit)
            arrays[name] = arr
            params[name] = ParamValue.Array(arr)
            return
        }
        if (type.element is FznType.SetOfInt) {
            val layouts = ArrayList<SetVarLayout>(type.length)
            if (value is FznExpr.ArrayLit) {
                if (value.elements.size != type.length) {
                    failHere("array `$name`: initializer length ${value.elements.size} != declared ${type.length}")
                }
                val sharedUniverse: IntArray? = run {
                    val acc = IntHashSet()
                    for (e in value.elements) {
                        if (e is FznExpr.Ident) {
                            val l = setVarsByName[e.name] ?: continue
                            for (u in l.elements) acc.add(u)
                        }
                    }
                    if (acc.isEmpty()) null else acc.toSortedIntArray()
                }
                for ((i, e) in value.elements.withIndex()) {
                    if (e is FznExpr.Ident) {
                        val layout = setVarsByName[e.name]
                            ?: failHere("array `$name`: set-var `${e.name}` referenced before its declaration")
                        layouts.add(layout)
                    } else {
                        val elemName = "$name[${i + 1}]"
                        val members = resolveSetLiteral(e)
                        val universeSet = IntHashSet()
                        for (m in members) universeSet.add(m)
                        if (sharedUniverse != null) for (u in sharedUniverse) universeSet.add(u)
                        val universe = if (universeSet.isEmpty()) {
                            intArrayOf(0)
                        } else {
                            universeSet.toSortedIntArray()
                        }
                        val indicatorIds = IntArray(universe.size) { k ->
                            allocBool("__set_${elemName}_${universe[k]}")
                        }
                        val layout = SetVarLayout(elemName, universe, indicatorIds)
                        setVarsByName[elemName] = layout
                        for (k in universe.indices) {
                            val inSet = members.binarySearchInt(universe[k]) >= 0
                            factors.add(Clause(intArrayOf(Lit.make(indicatorIds[k], inSet))))
                        }
                        layouts.add(layout)
                    }
                }
            } else {
                for (i in 0 until type.length) {
                    val elemName = "$name[${i + 1}]"
                    allocSetVar(elemName, type.element)
                    layouts.add(setVarsByName.getValue(elemName))
                }
            }
            arrays[name] = FlatZincArray.SetVars(name, layouts)
            return
        }
        val length = type.length
        val varIds = IntArray(length)
        val bucketings = if (type.element is FznType.FloatRange ||
            type.element == FznType.FloatAny
        ) {
            ArrayList<FloatBucketing>()
        } else {
            null
        }
        if (value is FznExpr.ArrayLit) {
            if (value.elements.size != length) {
                failHere("array `$name`: initializer length ${value.elements.size} != declared $length")
            }
            for ((i, e) in value.elements.withIndex()) {
                varIds[i] = resolveVarRef(e, type.element).also { _ ->
                    if (bucketings != null) {
                        val bn = nameOfBoundVar(e)
                        bucketings.add(
                            floatVars[bn]
                                ?: failHere("array `$name`[${i + 1}]: float element must reference a float var"),
                        )
                    }
                }
            }
            val kind = arrayElementKind(type.element)
            arrays[name] = FlatZincArray.Vars(name, varIds, kind, bucketings?.toList())
            return
        }
        for (i in 0 until length) {
            val elemName = "$name[${i + 1}]"
            when (val t = type.element) {
                FznType.Bool -> varIds[i] = allocBool(elemName)

                is FznType.IntRange -> varIds[i] = allocInt(elemName, t.lo, t.hi)

                is FznType.IntSet -> varIds[i] = allocIntSet(elemName, t)

                is FznType.FloatRange -> {
                    val v = allocFloat(elemName, t.lo, t.hi)
                    varIds[i] = v
                    requireNotNull(bucketings).add(floatVars.getValue(elemName))
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

    /** FlatZinc has one identifier namespace. Rejecting duplicates prevents later lookup from
     *  silently selecting a different solver variable than already-emitted factors reference. */
    private fun ensureFreshName(name: String) {
        if (name in params || name in boolVars || name in intVars || name in floatVars ||
            name in arrays || name in setVarsByName
        ) {
            failHere("duplicate declaration of `$name`")
        }
    }

    /** Post a trivially unsatisfiable constraint. A [Clause] cannot be empty (an empty clause would
     *  encode `false`, but the factor rejects zero literals), so an exact contradiction is a fresh
     *  Boolean pinned both ways. Use only for exact infeasibility (e.g. two constants that violate
     *  their relation), never to paper over a value an approximate encoding cannot represent — that
     *  must reject via [failHere] so the instance is not silently reported unsatisfiable. */
    internal fun postFalseFactor() {
        val f = allocBool("__false_$numBoolVars")
        factors.add(Clause(intArrayOf(Lit.make(f, true))))
        factors.add(Clause(intArrayOf(Lit.make(f, false))))
    }
    internal fun allocInt(name: String, lo: Long, hi: Long): Int {
        val id = intDomains.size
        intDomains.add(IntDomain(lo, hi))
        intVars[name] = id
        return id
    }

    /** Allocate int var with an explicit sparse domain. */
    internal fun allocIntSet(name: String, t: FznType.IntSet): Int {
        val sorted = t.values.distinct().sorted()
        if (sorted.isEmpty()) failHere("int variable `$name` has an empty domain")
        val id = allocInt(name, sorted.first(), sorted.last())
        var dom = intDomains[id]
        var prev = sorted.first()
        for (v in sorted) {
            for (gap in prev + 1 until v) dom = dom.excludeValue(gap)
            prev = v
        }
        intDomains[id] = dom
        return id
    }

    /** Materialize a set var as parallel indicator bools. */
    internal fun allocSetVar(name: String, type: FznType.SetOfInt, initializer: FznExpr? = null) {
        val elements = universeElements(type.element, name)
        val indicatorIds = IntArray(elements.size) { i ->
            allocBool("__set_${name}_${elements[i]}")
        }
        setVarsByName[name] = SetVarLayout(name, elements, indicatorIds)
        if (initializer != null) {
            val members = resolveSetLiteral(initializer)
            for (i in elements.indices) {
                val inSet = members.binarySearchInt(elements[i]) >= 0
                factors.add(Clause(intArrayOf(Lit.make(indicatorIds[i], inSet))))
            }
            for (m in members) {
                if (elements.binarySearchInt(m) < 0) {
                    failHere("set var `$name` initializer element $m outside declared universe")
                }
            }
        }
    }

    internal fun universeElements(elem: FznType, ownerName: String): IntArray = when (elem) {
        is FznType.IntRange -> {
            if (elem.lo > elem.hi) failHere("set `$ownerName` has an empty universe ${elem.lo}..${elem.hi}")
            if (elem.lo !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() ||
                elem.hi !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()
            ) {
                failHere("set `$ownerName` universe must fit in 32-bit integers")
            }
            val size = elem.hi - elem.lo + 1
            if (size > Int.MAX_VALUE) failHere("set `$ownerName` universe is too large: $size elements")
            IntArray((elem.hi - elem.lo + 1).toInt()) { (elem.lo + it).toInt() }
        }

        is FznType.IntSet -> {
            if (elem.values.any { it !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }) {
                failHere("set `$ownerName` universe must fit in 32-bit integers")
            }
            elem.values.distinct().sorted().map { it.toInt() }.toIntArray()
        }

        else -> failHere("set `$ownerName`: universe must be an int range or int set, got ${elem::class.simpleName}")
    }

    internal fun allocFloat(name: String, lo: Double, hi: Double): Int {
        if (name in lpOnlyFloats) {
            // LP-only continuous column: a real variable, absent from CP search. The linear
            // float handlers emit real rows over it; the returned id is a real var id (not an int var).
            val rid = realLo.size
            realLo.add(lo)
            realHi.add(hi)
            floatVars[name] = FloatBucketing(rid, lo, hi, floatBuckets, lpOnly = true)
            return rid
        }
        val id = intDomains.size
        intDomains.add(IntDomain(0L, (floatBuckets - 1).toLong()))
        intVars[name] = id
        floatVars[name] = FloatBucketing(id, lo, hi, floatBuckets)
        return id
    }

    // Whether [c] is a `float_times(a, b, result)` with exactly one operand an `int2float` image (an
    // integer's continuous shadow) and the other operand and result plain float vars — an int·real
    // product that lowers exactly rather than by bucketing (see [int2floatSource] / emitFloatTimes).
    private fun isIntFloatProduct(c: FznConstraint, scalarFloats: Set<String>): Boolean {
        if (c.name != "float_times" || c.args.size != 3) return false
        val a = (c.args[0] as? FznExpr.Ident)?.name ?: return false
        val b = (c.args[1] as? FznExpr.Ident)?.name ?: return false
        val r = (c.args[2] as? FznExpr.Ident)?.name ?: return false
        if (r !in scalarFloats) return false
        val aInt = a in int2floatSource
        val bInt = b in int2floatSource
        if (aInt == bInt) return false // need exactly one integer-backed operand
        return (if (aInt) b else a) in scalarFloats
    }

    // Colour each scalar float variable name LP-only or bucketed. A scalar float is LP-only
    // iff its constraint-connected component is purely linear-in-reals: floats that share a constraint are
    // unioned (a single row is emitted over all of them, so they must share a representation), and a
    // component is tainted — kept bucketed — if any of its constraints is non-linear / strict / `ne` /
    // reified, touches a var float array (whose elements need an integer-var-id array), or is the objective
    // (a float objective needs a real objective through the Long-typed machinery, not yet built). This
    // per-variable colouring lets a linear-float component be LP-only even beside an unrelated non-linear one.
    private fun classifyLpOnlyFloats(): Set<String> {
        val scalarFloats = HashSet<String>()
        val floatArrays = HashSet<String>()
        for (d in model.varDecls) {
            if (!d.isVar) continue
            val t = d.type
            if (t is FznType.FloatRange || t is FznType.FloatAny) scalarFloats.add(d.name)
            if (t is FznType.Array && (t.element is FznType.FloatRange || t.element is FznType.FloatAny)) {
                floatArrays.add(d.name)
            }
        }
        if (scalarFloats.isEmpty()) return emptySet()
        val parent = HashMap<String, String>().apply { scalarFloats.forEach { put(it, it) } }
        fun find(x: String): String {
            var r = x
            while (parent[r] != r) r = parent.getValue(r)
            var c = x
            while (parent[c] != r) {
                val next = parent.getValue(c)
                parent[c] = r
                c = next
            }
            return r
        }
        fun union(a: String, b: String) {
            parent[find(a)] = find(b)
        }
        val tainted = HashSet<String>()
        val objName = when (val s = model.solve) {
            is FznSolve.Minimize -> (s.obj as? FznExpr.Ident)?.name
            is FznSolve.Maximize -> (s.obj as? FznExpr.Ident)?.name
            else -> null
        }
        if (objName != null && objName in scalarFloats) tainted.add(objName)
        for (c in model.constraints) {
            val here = ArrayList<String>()
            var touchesFloatArray = false
            fun walk(e: FznExpr) {
                when (e) {
                    is FznExpr.Ident -> if (e.name in scalarFloats) {
                        here.add(
                            e.name,
                        )
                    } else if (e.name in floatArrays) {
                        touchesFloatArray = true
                    }

                    is FznExpr.ArrayAccess -> if (e.name in floatArrays) touchesFloatArray = true

                    is FznExpr.ArrayLit -> e.elements.forEach(::walk)

                    else -> Unit
                }
            }
            c.args.forEach(::walk)
            if (here.isEmpty() && !touchesFloatArray) continue
            for (k in 1 until here.size) union(here[0], here[k])
            val eligible = c.name in FLOAT_LP_ONLY_NAMES || isIntFloatProduct(c, scalarFloats)
            if (!eligible || touchesFloatArray) here.forEach { tainted.add(it) }
        }
        val taintedRoots = tainted.map { find(it) }.toHashSet()
        return scalarFloats.filterTo(HashSet()) { find(it) !in taintedRoots }
    }

    internal sealed interface ParamValue {
        data class Bool(val value: Boolean) : ParamValue
        data class Int(val value: Long) : ParamValue
        data class Float(val value: Double) : ParamValue
        data class IntSet(val values: LongArray) : ParamValue
        data class Array(val arr: FlatZincArray) : ParamValue
    }

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
        when (e) {
            is FznExpr.IntLit -> {
                val name = "__obj_const_${e.value}"
                val v = e.value
                if (name !in intVars) {
                    allocInt(name, v, v)
                }
                return name to SolveDirective.ObjKind.Int
            }

            is FznExpr.BoolLit -> {
                val name = "__obj_const_${e.value}"
                if (name !in boolVars) {
                    allocBool(name) /* pin via Clause below */
                    factors.add(
                        Clause(
                            intArrayOf(Lit.make(boolVars.getValue(name), e.value)),
                        ),
                    )
                }
                return name to SolveDirective.ObjKind.Bool
            }

            else -> {}
        }
        val name = (e as? FznExpr.Ident)?.name
            ?: failHere("solve objective must be a variable name")
        (params[name] as? ParamValue.Int)?.let { p ->
            val pinName = "__obj_const_$name"
            val v = p.value
            if (pinName !in intVars) {
                allocInt(pinName, v, v)
            }
            return pinName to SolveDirective.ObjKind.Int
        }
        (params[name] as? ParamValue.Bool)?.let { p ->
            val pinName = "__obj_const_$name"
            if (pinName !in boolVars) {
                allocBool(pinName)
                factors.add(
                    Clause(
                        intArrayOf(Lit.make(boolVars.getValue(pinName), p.value)),
                    ),
                )
            }
            return pinName to SolveDirective.ObjKind.Bool
        }
        return when (name) {
            in boolVars -> name to SolveDirective.ObjKind.Bool
            in floatVars -> name to SolveDirective.ObjKind.Float
            in intVars -> name to SolveDirective.ObjKind.Int
            else -> failHere("solve objective `$name` is not a declared variable")
        }
    }

    /** Build output items from `output_var` / `output_array` annotations. */
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
                        if (arg.name in arrays) {
                            OutputItem.ShowArray(arg.name)
                        } else {
                            OutputItem.ShowVar(arg.name)
                        }
                    }

                    else -> failHere("show(): unsupported argument shape")
                }
            }

            else -> failHere("output: unsupported function call `${e.name}`")
        }

        else -> failHere("unsupported output item: ${e::class.simpleName}")
    }

    // Source position of the declaration/constraint currently being compiled, so a semantic error
    // (unsupported builtin, arity, domain) reports where it came from rather than `(at 0:0)`.
    private var currentLine = 0
    private var currentCol = 0

    internal fun failHere(msg: String): Nothing = throw FlatZincParseException(msg, currentLine, currentCol)

    /** Require constraint [c] to carry exactly [n] arguments, failing with a [FlatZincParseException]
     *  (not a bare `require`/index crash) when a malformed instance supplies the wrong arity. */
    internal fun expectArity(c: FznConstraint, n: Int) {
        if (c.args.size != n) failHere("`${c.name}` expects $n arguments, got ${c.args.size}")
    }
}

/** Parse and compile FlatZinc from an in-memory [String] — the path for tests and the DSL. */
fun parseFlatZinc(
    source: String,
    floatBuckets: Int = DEFAULT_FLOAT_BUCKETS,
    floatScale: Long = DEFAULT_FLOAT_SCALE,
    forLocalSearch: Boolean = false,
    unboundedIntLo: Long = DEFAULT_UNBOUNDED_INT_LO,
    unboundedIntHi: Long = DEFAULT_UNBOUNDED_INT_HI,
): FlatZincProgram = parseFlatZinc(
    StringCharSource(source),
    floatBuckets = floatBuckets,
    floatScale = floatScale,
    forLocalSearch = forLocalSearch,
    unboundedIntLo = unboundedIntLo,
    unboundedIntHi = unboundedIntHi,
)

/** Parse and compile FlatZinc from a streamed [source], pulling one token at a time so the whole file
 *  and its token list are never held. The compiler stays whole-model (its float classification and
 *  `int2float` forward references need every constraint at once); only lexing/parsing are streamed. */
fun parseFlatZinc(
    source: CharSource,
    floatBuckets: Int = DEFAULT_FLOAT_BUCKETS,
    floatScale: Long = DEFAULT_FLOAT_SCALE,
    forLocalSearch: Boolean = false,
    unboundedIntLo: Long = DEFAULT_UNBOUNDED_INT_LO,
    unboundedIntHi: Long = DEFAULT_UNBOUNDED_INT_HI,
): FlatZincProgram {
    val model = FlatZincParser(FlatZincLexer(CharReader(source))).parse()
    return FlatZincCompiler(
        model,
        floatBuckets = floatBuckets,
        floatScale = floatScale,
        forLocalSearch = forLocalSearch,
        unboundedIntLo = unboundedIntLo,
        unboundedIntHi = unboundedIntHi,
    ).compile()
}
