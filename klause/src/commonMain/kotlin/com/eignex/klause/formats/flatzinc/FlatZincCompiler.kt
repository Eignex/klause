package com.eignex.klause.formats.flatzinc
import com.eignex.klause.config.DEFAULT_FLOAT_BUCKETS
import com.eignex.klause.config.DEFAULT_FLOAT_SCALE
import com.eignex.klause.config.DEFAULT_UNBOUNDED_INT_HI
import com.eignex.klause.config.DEFAULT_UNBOUNDED_INT_LO
import com.eignex.klause.config.KlauseConfig
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.backtrack.BacktrackParams
import com.eignex.klause.solver.backtrack.SearchTier
import com.eignex.klause.solver.backtrack.TierVarSelect
import com.eignex.klause.solver.backtrack.TieredValueSelector
import com.eignex.klause.solver.backtrack.TieredVariableSelector
import com.eignex.klause.solver.backtrack.selector.DomWdeg
import com.eignex.klause.solver.backtrack.selector.DomainMaxRegret
import com.eignex.klause.solver.backtrack.selector.IndomainMax
import com.eignex.klause.solver.backtrack.selector.IndomainMedian
import com.eignex.klause.solver.backtrack.selector.IndomainMiddle
import com.eignex.klause.solver.backtrack.selector.IndomainMin
import com.eignex.klause.solver.backtrack.selector.IndomainRandom
import com.eignex.klause.solver.backtrack.selector.IndomainSplit
import com.eignex.klause.solver.backtrack.selector.InputOrder
import com.eignex.klause.solver.backtrack.selector.LargestDomain
import com.eignex.klause.solver.backtrack.selector.LargestUpperBound
import com.eignex.klause.solver.backtrack.selector.LastConflict
import com.eignex.klause.solver.backtrack.selector.MaxRegret
import com.eignex.klause.solver.backtrack.selector.RandomVariable
import com.eignex.klause.solver.backtrack.selector.SmallestDomain
import com.eignex.klause.solver.backtrack.selector.SmallestLowerBound
import com.eignex.klause.solver.backtrack.selector.SolutionGuided
import com.eignex.klause.solver.backtrack.selector.ValueSelector
import com.eignex.klause.solver.backtrack.selector.VariableSelector
import com.eignex.klause.solver.backtrack.selector.Vsids
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.solver.factor.GaussianXor
import com.eignex.klause.solver.factor.Xor
import com.eignex.klause.solver.presolve.PresolveContext
import com.eignex.klause.solver.presolve.PresolvePass
import com.eignex.klause.util.binarySearchInt

/**
 * Translates a parsed [FznModel] into a klause [Problem] plus the auxiliary maps needed by
 * the solution writer. Supports the FlatZinc common-subset built-ins; anything else
 * throws [FlatZincParseException] with a clear "unsupported builtin" message.
 *
 * Float variables are discretized: each `var float: x` ∈ `[lo, hi]` becomes an int var with
 * domain `[0, buckets-1]`. Float linear constraints are rescaled to integer coefficients
 * and a rescaled bound.
 */
internal class FlatZincCompiler(
    internal val model: FznModel,
    internal val floatBuckets: Int = DEFAULT_FLOAT_BUCKETS,
    internal val floatScale: Long = DEFAULT_FLOAT_SCALE,
    /**
     * Per the MiniZinc Challenge LS-track rules, `symmetry_breaking_constraint(...)` and
     * `redundant_constraint(...)` may be dropped by local-search solvers. Set this to
     * `true` from LS-engine entry points to skip those constraints entirely. The CP
     * default enforces them as `bool == true`.
     */
    internal val forLocalSearch: Boolean = false,
    /**
     * Default domain `[lo, hi]` for `var int` declarations that arrive without an explicit
     * range. MiniZinc emits these for auxiliary intermediates; the surrounding linear /
     * element constraints normally pin them via propagation. Wide enough by default to
     * absorb typical CP arithmetic without int overflow during factor coefficient × value
     * products; tune at the CLI boundary (env / flag) when a model needs different limits.
     */
    internal val unboundedIntLo: Int = DEFAULT_UNBOUNDED_INT_LO,
    internal val unboundedIntHi: Int = DEFAULT_UNBOUNDED_INT_HI,
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
        // Joint GF(2) reasoning for multi-xor models: one GaussianXor system on top of the
        // individual Xor factors (which keep their sharper unit-propagation reasons), plus
        // a search recipe that branches the system's rare variables first. On
        // learning-parity-with-noise shaped models the rare variables are the per-sample
        // error indicators; fixing them first leaves a pure linear system the elimination
        // solves instantly, which decomposes the search by error pattern (proves
        // parity-learning at the reference optimum where every other config finds nothing).
        val xors = factors.filterIsInstance<Xor>()
        val xorParams = if (xors.size < 2) {
            null
        } else {
            factors.add(GaussianXor(xors))
            xorSearchParams(xors)
        }
        // compileSolve may pin a synthetic int/bool var (for `solve minimize <par-int>`),
        // so resolve it before snapshotting var counts into Problem.
        val solveDirective = compileSolve()
        // Construction-time SAC probes from the ambient presolve config (solution-preserving, so
        // resolved under EMPTY); holes imply bounds.
        val presolve = KlauseConfig.current.presolveConfig()
        val holes = presolve.resolved(PresolvePass.PROBE_INT_HOLES, PresolveContext.EMPTY)
        val problem = Problem(
            numBoolVars = numBoolVars,
            numIntVars = intDomains.size,
            intDomains = intDomains.toTypedArray(),
            factors = factors.toTypedArray(),
            probeFailedLiterals = presolve.resolved(PresolvePass.PROBE_FAILED_LITERALS, PresolveContext.EMPTY),
            probeIntBounds = holes || presolve.resolved(PresolvePass.PROBE_INT_BOUNDS, PresolveContext.EMPTY),
            probeIntHoles = holes,
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
            xorSearchParams = xorParams,
            enumLabelsByVar = enumLabelsByVar.toMap(),
            setVarsByName = setVarsByName.toMap(),
            lsObjective = when (solveDirective) {
                is SolveDirective.Minimize -> buildFunctionalObjective(solveDirective.objVar, minimize = true)
                is SolveDirective.Maximize -> buildFunctionalObjective(solveDirective.objVar, minimize = false)
                else -> null
            },
            definitionalSweep = buildDefinitionalSweep(),
        )
    }

    /**
     * Map the `solve :: int_search/bool_search/set_search/seq_search(...)` annotation onto
     * [BacktrackParams]. Each search block becomes a [SearchTier] over the variables its
     * array actually lists, in order; `seq_search` contributes its blocks as consecutive
     * tiers. A [TieredVariableSelector] explores the tiers first and falls back to the
     * first block's strategy applied globally for the remaining (introduced) variables,
     * with a matching [TieredValueSelector] on the value side. Returns `null` when no
     * recognised search annotation is present or no block lists any resolvable variable.
     */
    internal fun compileSearchAnnotation(): BacktrackParams? {
        val blocks = model.solve.annotations.filter(::isSearchAnnotation).flatMap(::searchBlocksOf)
        val tiers = blocks.mapNotNull(::compileSearchBlock)
        if (tiers.isEmpty()) return null
        val firstBlockVarName = blocks.firstNotNullOfOrNull { (it.args.getOrNull(1) as? FznExpr.Ident)?.name }
        val firstBlockValName = blocks.firstNotNullOfOrNull { (it.args.getOrNull(2) as? FznExpr.Ident)?.name }
        val fallbackVar = firstBlockVarName?.let(::mapVariableStrategy)
            ?: SmallestDomain
        val fallbackVal = firstBlockValName?.let(::mapValueStrategy)
            ?: IndomainMin
        val tieredVal = TieredValueSelector(tiers, fallbackVal, numBoolVars, intDomains.size)
        // For minimize / maximize, wrap the value side in SolutionGuided so each new
        // incumbent biases the next descent toward "near the last good solution" — the
        // standard SOTA phase-saving-for-BnB pattern.
        val wrappedValH = when (model.solve) {
            is FznSolve.Minimize, is FznSolve.Maximize -> SolutionGuided(tieredVal)
            is FznSolve.Satisfy -> tieredVal
        }
        return BacktrackParams(
            variableSelector = TieredVariableSelector(tiers, fallbackVar),
            valueSelector = wrappedValH,
        )
    }

    /** Search recipe for a model carrying a multi-xor system: branch the system's bool vars
     *  in ascending xor-occurrence order (rare vars — typically per-row error/slack
     *  indicators — first, smallest value first), with conflict-driven free search
     *  completing the rest. */
    private fun xorSearchParams(xors: List<Xor>): BacktrackParams {
        val occ = LinkedHashMap<Int, Int>()
        for (x in xors) {
            for (lit in x.literals) {
                val v = Lit.variable(lit)
                occ[v] = (occ[v] ?: 0) + 1
            }
        }
        val ordered = occ.entries.sortedBy { it.value }.map { it.key }.toIntArray()
        val tier = SearchTier(ordered, IntArray(0), TierVarSelect.InputOrder, IndomainMin)
        return BacktrackParams(
            variableSelector = TieredVariableSelector(listOf(tier), LastConflict(Vsids())),
            valueSelector = TieredValueSelector(
                listOf(tier),
                SolutionGuided(IndomainMin),
                numBoolVars,
                intDomains.size,
            ),
            phaseSaving = true,
        )
    }

    internal fun isSearchAnnotation(a: FznAnnotation): Boolean =
        a.name == "int_search" || a.name == "bool_search" || a.name == "set_search" || a.name == "seq_search"

    /** Flatten an annotation into its concrete search blocks: a plain block is itself;
     *  `seq_search` lists its blocks in order (recursing through nested seq_search). */
    internal fun searchBlocksOf(a: FznAnnotation): List<FznAnnotation> = when (a.name) {
        "int_search", "bool_search", "set_search" -> listOf(a)

        "seq_search" -> {
            val list = (a.args.firstOrNull() as? FznExpr.ArrayLit)?.elements.orEmpty()
            list.mapNotNull { it as? FznExpr.AnnCall }
                .flatMap { searchBlocksOf(FznAnnotation(it.name, it.args)) }
        }

        else -> emptyList()
    }

    /** One search block → one [SearchTier], or null when the block lists no resolvable
     *  variable. Signature: `search(varArray, var_strategy, value_strategy, complete)`. */
    private fun compileSearchBlock(a: FznAnnotation): SearchTier? {
        if (a.args.size < 3) return null
        val bools = ArrayList<Int>()
        val ints = ArrayList<Int>()
        collectSearchVars(a.args[0], bools, ints)
        if (bools.isEmpty() && ints.isEmpty()) return null
        val varName = (a.args[1] as? FznExpr.Ident)?.name
        val valName = (a.args[2] as? FznExpr.Ident)?.name
        return SearchTier(
            boolVars = bools.toIntArray(),
            intVars = ints.toIntArray(),
            // An unrecognised variable strategy keeps the tier (the var list is the
            // valuable part) and labels it in listed order.
            varSelect = varName?.let(::mapTierVarSelect) ?: TierVarSelect.InputOrder,
            valueSelector = valName?.let(::mapValueStrategy)
                ?: IndomainMin,
        )
    }

    /** Resolve a search block's variable-array expression into engine var ids, in listed
     *  order. Set vars contribute their indicator bools; float vars their bucket int var;
     *  constants are skipped (models do list literals in search arrays). */
    private fun collectSearchVars(e: FznExpr, bools: ArrayList<Int>, ints: ArrayList<Int>) {
        when (e) {
            is FznExpr.Ident -> {
                val name = e.name
                boolVars[name]?.let {
                    bools.add(it)
                    return
                }
                intVars[name]?.let {
                    ints.add(it)
                    return
                }
                floatVars[name]?.let {
                    ints.add(it.varId)
                    return
                }
                setVarsByName[name]?.let { layout ->
                    for (b in layout.indicatorBoolIds) bools.add(b)
                    return
                }
                when (val arr = arrays[name]) {
                    is FlatZincArray.Vars -> when (arr.elementKind) {
                        FlatZincArray.Vars.ElementKind.Bool -> for (v in arr.varIds) bools.add(v)

                        FlatZincArray.Vars.ElementKind.Int,
                        FlatZincArray.Vars.ElementKind.Float,
                        -> for (v in arr.varIds) ints.add(v)
                    }

                    else -> {}
                }
            }

            is FznExpr.ArrayLit -> for (el in e.elements) collectSearchVars(el, bools, ints)

            is FznExpr.ArrayAccess -> {
                val arr = arrays[e.name] as? FlatZincArray.Vars ?: return
                val idx = e.index - 1
                if (idx !in arr.varIds.indices) return
                when (arr.elementKind) {
                    FlatZincArray.Vars.ElementKind.Bool -> bools.add(arr.varIds[idx])

                    FlatZincArray.Vars.ElementKind.Int,
                    FlatZincArray.Vars.ElementKind.Float,
                    -> ints.add(arr.varIds[idx])
                }
            }

            // Constants and anything else contribute no search variables.
            else -> {}
        }
    }

    internal fun mapTierVarSelect(name: String): TierVarSelect? = when (name) {
        "input_order" -> TierVarSelect.InputOrder
        "first_fail", "most_constrained", "dom_w_deg", "occurrence" -> TierVarSelect.SmallestDomain
        "anti_first_fail" -> TierVarSelect.LargestDomain
        "smallest" -> TierVarSelect.SmallestLowerBound
        "largest" -> TierVarSelect.LargestUpperBound
        "max_regret" -> TierVarSelect.MaxRegret
        "random_order" -> TierVarSelect.RandomOrder
        else -> null
    }

    internal fun mapVariableStrategy(name: String): VariableSelector? = when (name) {
        "input_order" -> InputOrder
        "first_fail", "most_constrained" -> SmallestDomain
        "dom_w_deg" -> DomWdeg()
        "anti_first_fail", "occurrence" -> LargestDomain
        "smallest" -> SmallestLowerBound
        "largest" -> LargestUpperBound
        "max_regret" -> DomainMaxRegret
        "random_order" -> RandomVariable
        else -> null
    }

    internal fun mapValueStrategy(name: String): ValueSelector? = when (name) {
        "indomain_min", "indomain" -> IndomainMin
        "indomain_max" -> IndomainMax
        "indomain_middle" -> IndomainMiddle
        "indomain_median" -> IndomainMedian
        "indomain_split" -> IndomainSplit
        "indomain_random" -> IndomainRandom
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
                arrays[d.name] = arrayToFlatZincArray(arr)
            }
            return
        }
        when (val t = d.type) {
            FznType.Bool -> allocBool(d.name)
            FznType.IntAny -> allocInt(d.name, unboundedIntLo, unboundedIntHi)
            is FznType.IntRange -> allocInt(d.name, t.lo.toInt(), t.hi.toInt())
            is FznType.IntSet -> allocIntSet(d.name, t)
            FznType.FloatAny -> failHere("variable `${d.name}`: unbounded `float` not supported; need a range")
            is FznType.FloatRange -> allocFloat(d.name, t.lo, t.hi)
            is FznType.SetOfInt -> allocSetVar(d.name, t, d.value)
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

    internal fun processArrayDecl(name: String, type: FznType.Array, value: FznExpr?, isVar: Boolean) {
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
        // `<arr>[<i>]`. The FZN flattener routinely emits `array of var set of int: x = [...]`
        // where elements are a mix of set-var name references (`X_INTRODUCED_*`) and set
        // literals (`1..0` for empty, `{1,3}`, `1..3`). For Ident refs, alias the existing
        // layout; for literals, allocate a fresh pinned layout sized to the literal's
        // elements (unioned with sibling Ident universes so downstream uses see a uniform
        // universe across the array).
        if (type.element is FznType.SetOfInt) {
            val layouts = ArrayList<SetVarLayout>(type.length)
            if (value is FznExpr.ArrayLit) {
                require(value.elements.size == type.length) {
                    "array `$name`: initializer length ${value.elements.size} ≠ declared ${type.length}"
                }
                val sharedUniverse: IntArray? = run {
                    val acc = HashSet<Int>()
                    for (e in value.elements) {
                        if (e is FznExpr.Ident) {
                            val l = setVarsByName[e.name] ?: continue
                            for (u in l.elements) acc.add(u)
                        }
                    }
                    if (acc.isEmpty()) null else acc.toIntArray().also { it.sort() }
                }
                for ((i, e) in value.elements.withIndex()) {
                    if (e is FznExpr.Ident) {
                        val layout = setVarsByName[e.name]
                            ?: failHere("array `$name`: set-var `${e.name}` referenced before its declaration")
                        layouts.add(layout)
                    } else {
                        val elemName = "$name[${i + 1}]"
                        val members = resolveSetLiteral(e)
                        val universeSet = HashSet<Int>()
                        for (m in members) universeSet += m
                        if (sharedUniverse != null) for (u in sharedUniverse) universeSet += u
                        val universe = if (universeSet.isEmpty()) {
                            intArrayOf(0)
                        } else {
                            universeSet.toIntArray().also { it.sort() }
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
        // Variable array — allocate one var per element. The initializer may either be an
        // array literal aliasing other vars, or absent (we allocate fresh).
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
            require(value.elements.size == length) {
                "array `$name`: initializer length ${value.elements.size} ≠ declared $length"
            }
            for ((i, e) in value.elements.withIndex()) {
                varIds[i] = resolveVarRef(e, type.element).also { id ->
                    if (bucketings != null) {
                        val bn = nameOfBoundVar(e)
                        bucketings.add(
                            floatVars[bn]
                                ?: failHere("array `$name`[${i + 1}]: float element must reference a float var"),
                        )
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
    internal fun allocInt(name: String, lo: Int, hi: Int): Int {
        val id = intDomains.size
        intDomains.add(IntDomain(lo, hi))
        intVars[name] = id
        return id
    }

    /** Allocate an int var whose initial domain is exactly the values of [t] — interior
     *  values not in the set are excised from the contiguous `[min..max]` envelope. */
    internal fun allocIntSet(name: String, t: FznType.IntSet): Int {
        val sorted = t.values.distinct().sorted().map { it.toInt() }
        require(sorted.isNotEmpty()) { "IntSet domain for `$name` is empty" }
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

    /**
     * Materialise a `var set of E: name` declaration as one indicator bool per universe
     * element. Resolves the universe to a sorted ascending int array; allocates one bool
     * per element; records the layout in [setVarsByName] for downstream constraint dispatch
     * and FZN output reconstruction. If [initializer] is present (e.g. `= { 1, 3, 5 }` or
     * `= 1..3`), pins each indicator bool to its constant value via a unit clause.
     */
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
        FznType.Bool -> FlatZincArray.BoolParam(
            name,
            BooleanArray(lit.elements.size) {
                (lit.elements[it] as? FznExpr.BoolLit)?.value
                    ?: failHere("bool array `$name`: element ${it + 1} not a bool literal")
            },
        )

        is FznType.IntRange, is FznType.IntSet, FznType.IntAny -> {
            FlatZincArray.IntParam(
                name,
                IntArray(lit.elements.size) {
                    evalIntConst(lit.elements[it]).toInt()
                },
            )
        }

        is FznType.FloatRange, FznType.FloatAny -> {
            FlatZincArray.FloatParam(
                name,
                DoubleArray(lit.elements.size) {
                    evalFloatConst(lit.elements[it])
                },
            )
        }

        is FznType.SetOfInt -> FlatZincArray.IntSetParam(
            name,
            lit.elements.map { e ->
                val arr: IntArray = when (e) {
                    is FznExpr.IntSetLit -> IntArray(e.values.size) { e.values[it].toInt() }

                    is FznExpr.IntRangeLit -> IntArray((e.hi - e.lo + 1).toInt()) { (e.lo + it).toInt() }

                    is FznExpr.Ident -> (params[e.name] as? ParamValue.IntSet)?.let { p ->
                        IntArray(p.values.size) { p.values[it].toInt() }
                    } ?: failHere("`${e.name}` is not an int-set parameter")

                    else -> failHere("set-of-int array `$name`: unexpected element ${e::class.simpleName}")
                }
                arr.also { it.sort() }
            },
        )

        is FznType.Array -> failHere("nested arrays not supported")
    }

    internal fun arrayToFlatZincArray(arr: ParamValue.Array): FlatZincArray = arr.arr

    /** Constant-evaluate `e` as an integer. */
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

    /** Non-throwing variant of [evalIntConst]. Returns `null` when `e` refers to a
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

    /** Non-throwing variant of [evalIntConstArray]: returns `null` if `e` isn't a known
     *  int-const array (because it's an int-var array, a non-array expression, or a
     *  different param kind). Used at call sites that accept either constant or var
     *  arrays and need to dispatch on the actual form (e.g. GCC's `counts` argument). */
    internal fun tryEvalIntConstArray(e: FznExpr): IntArray? = when (e) {
        is FznExpr.ArrayLit -> {
            val out = IntArray(e.elements.size)
            var ok = true
            for (i in e.elements.indices) {
                val v = evalIntConstOrNull(e.elements[i]) ?: run {
                    ok = false
                    0L
                }
                out[i] = v.toInt()
                if (!ok) break
            }
            if (ok) out else null
        }

        is FznExpr.Ident -> (arrays[e.name] as? FlatZincArray.IntParam)?.values

        else -> null
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
                require(
                    arr.elementKind == FlatZincArray.Vars.ElementKind.Int ||
                        arr.elementKind == FlatZincArray.Vars.ElementKind.Float,
                ) {
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
        // Inline int/float/bool literals: MiniZinc occasionally emits `solve minimize 4;`
        // when the objective folds to a constant. Treat as a satisfy-equivalent by pinning
        // a synthetic var to the literal value.
        when (e) {
            is FznExpr.IntLit -> {
                val name = "__obj_const_${e.value}"
                val v = e.value.toInt()
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
        // Par int / bool objective (e.g. `solve minimize X_INTRODUCED_27_` where the ident
        // is a par constant produced by MiniZinc's flattener). Pin a synthetic var to the
        // constant value so downstream search has something to track.
        (params[name] as? ParamValue.Int)?.let { p ->
            val pinName = "__obj_const_$name"
            val v = p.value.toInt()
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

    internal fun failHere(msg: String): Nothing = throw FlatZincParseException(msg, 0, 0)
}

/** Top-level entry point: parse + compile. */
fun parseFlatZinc(
    source: String,
    floatBuckets: Int = DEFAULT_FLOAT_BUCKETS,
    floatScale: Long = DEFAULT_FLOAT_SCALE,
    forLocalSearch: Boolean = false,
    unboundedIntLo: Int = DEFAULT_UNBOUNDED_INT_LO,
    unboundedIntHi: Int = DEFAULT_UNBOUNDED_INT_HI,
): FlatZincProgram {
    val tokens = FlatZincLexer(source).tokenize()
    val model = FlatZincParser(tokens).parse()
    return FlatZincCompiler(
        model,
        floatBuckets = floatBuckets,
        floatScale = floatScale,
        forLocalSearch = forLocalSearch,
        unboundedIntLo = unboundedIntLo,
        unboundedIntHi = unboundedIntHi,
    ).compile()
}
