package com.eignex.klause.compile

import com.eignex.klause.config.KlauseConfig
import com.eignex.klause.factor.arithmetic.LinearOp
import com.eignex.klause.factor.arithmetic.ReifiedCardinality
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.arithmetic.ReifiedPseudoBoolean
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.Xor
import com.eignex.klause.formats.CnfLowering
import com.eignex.klause.formats.tseitinAnd
import com.eignex.klause.formats.tseitinIff
import com.eignex.klause.formats.tseitinOr
import com.eignex.klause.model.AllDifferent
import com.eignex.klause.model.AllDifferentOpt
import com.eignex.klause.model.And
import com.eignex.klause.model.AtLeast
import com.eignex.klause.model.AtMost
import com.eignex.klause.model.BoolExpr
import com.eignex.klause.model.BoolRef
import com.eignex.klause.model.BoolSpec
import com.eignex.klause.model.CardinalityExpr
import com.eignex.klause.model.CircuitExpr
import com.eignex.klause.model.CostMddExpr
import com.eignex.klause.model.CostRegularExpr
import com.eignex.klause.model.CumulativeExpr
import com.eignex.klause.model.CumulativeExprOpt
import com.eignex.klause.model.DiffnExpr
import com.eignex.klause.model.DisjunctiveExpr
import com.eignex.klause.model.DisjunctiveExprOpt
import com.eignex.klause.model.FloatLinearConstraint
import com.eignex.klause.model.FloatSpec
import com.eignex.klause.model.GccExprOpt
import com.eignex.klause.model.Iff
import com.eignex.klause.model.Implies
import com.eignex.klause.model.IncreasingExpr
import com.eignex.klause.model.IntCmpOp
import com.eignex.klause.model.IntCompare
import com.eignex.klause.model.IntLit
import com.eignex.klause.model.IntRef
import com.eignex.klause.model.IntSpec
import com.eignex.klause.model.InverseChannel
import com.eignex.klause.model.MddExpr
import com.eignex.klause.model.MultipleSpec
import com.eignex.klause.model.NValueExprOpt
import com.eignex.klause.model.NamedConstraint
import com.eignex.klause.model.NominalEq
import com.eignex.klause.model.NominalSpec
import com.eignex.klause.model.Not
import com.eignex.klause.model.Or
import com.eignex.klause.model.PresenceSpec
import com.eignex.klause.model.PseudoBooleanExpr
import com.eignex.klause.model.RegularExpr
import com.eignex.klause.model.SchemaEntry
import com.eignex.klause.model.SetDisjoint
import com.eignex.klause.model.SetEq
import com.eignex.klause.model.SetIn
import com.eignex.klause.model.SetNominalIn
import com.eignex.klause.model.SetSpec
import com.eignex.klause.model.SetSubsetOf
import com.eignex.klause.model.SortExpr
import com.eignex.klause.model.SubcircuitExpr
import com.eignex.klause.model.SymmetricAllDifferent
import com.eignex.klause.model.TableConstraint
import com.eignex.klause.model.XorExpr
import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.util.FloatInterval
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.MutableIntObjectMap
import com.eignex.skema.SchemaDef
import kotlin.math.roundToInt

internal class Compiler(private val config: KlauseConfig = KlauseConfig.current) {
    fun compile(def: SchemaDef<SchemaEntry>): CompiledSchema = Lowering(config).run(def)
}

internal class Lowering(val config: KlauseConfig) : CnfLowering {
    override val factors = mutableListOf<Factor>()
    val boolVarIdByName = mutableMapOf<String, Int>()
    val intVarIdByName = mutableMapOf<String, Int>()

    // Reverse indices (id → name), kept in lock-step with the two forward maps via
    // [bindIntName] / [bindBoolName]. They turn the per-node/per-edge reverse lookups in the
    // global/set lowering from O(n) entry scans into O(1), so that lowering is no longer
    // O(n²) in model size (#97).
    val idToIntName = MutableIntObjectMap<String>()
    val idToBoolName = MutableIntObjectMap<String>()
    val intDomains = mutableListOf<IntDomain>()
    val nominalIndicators = mutableMapOf<String, Map<String, Int>>()

    // Schema-layer float bookkeeping. `floatDecoders` records bucket parameters per float-var
    // name (so the schema can decode `sample.ints[id]` back to a Double). `floatMetaIntervals` /
    // `floatMetaIntVarIds` / `floatMetaBuckets` are the parallel per-float-var arrays the
    // float-linear lowering reads to build the scaled-integer factor.
    val floatDecoders = mutableMapOf<String, FloatSpec>()
    val floatMetaIntervals = mutableListOf<FloatInterval>()
    val floatMetaIntVarIds = IntArrayList()
    val floatMetaBuckets = IntArrayList()
    val floatVarIdByName = mutableMapOf<String, Int>() // float-id (metadata index) by name

    // LP-only continuous columns (issue #1232): when [schemaFloatsLpOnly] holds, every schema float is a
    // real variable (the simplex resolves it) rather than a bucket-index int, and float-linear rows lower
    // to real [com.eignex.klause.factor.arithmetic.Linear]. `realVarIdByName` maps a float name to its real
    // var id; parallel real bounds by real var id.
    var schemaFloatsLpOnly = false
    val realVarIdByName = mutableMapOf<String, Int>()
    val realLo = mutableListOf<Double>()
    val realHi = mutableListOf<Double>()

    /** Indicator-bool layout per declared set variable. Mirrors FlatZinc's
     *  `SetVarLayout`: for set var `S` over universe `[e_0, …, e_{n-1}]`,
     *  `setLayouts["S"].indicatorBoolIds[i]` is the klause bool var that's `true` iff
     *  `e_i ∈ S`. Both [SetSpec] (int universe) and
     *  [MultipleSpec] (nominal universe) populate this — the
     *  difference is the decoder shape (ints vs label strings). */
    val setLayouts = mutableMapOf<String, SetLayout>()

    /** Label → universe index for set vars whose universe is a nominal label list.
     *  Empty for int-universe set vars. */
    val setLabelOrder = mutableMapOf<String, List<String>>()
    var numBoolVars = 0
    var numIntVars = 0
    internal var auxIntCounter = 0

    fun newBoolVar(): Int = numBoolVars++

    // CnfLowering hooks: share the Tseitin gates ([tseitinAnd]/[tseitinOr]/[tseitinIff]) with the format
    // front-ends instead of re-implementing them here. [trueLitCache] is unused — this class keeps its own
    // [trueLit] (a fresh forced literal per call) rather than CnfLowering's cached one.
    override fun newBool(): Int = newBoolVar()
    override var trueLitCache: Int = -1

    fun newIntVar(domain: IntDomain): Int {
        val id = numIntVars++
        intDomains += domain
        return id
    }

    /** Register `name → id` for an int/float var and the reverse `id → name`. Every write to
     *  [intVarIdByName] must go through here so [idToIntName] stays consistent (#97). */
    fun bindIntName(name: String, id: Int): Int {
        intVarIdByName[name] = id
        idToIntName.put(id, name)
        return id
    }

    /** Register `name → id` for a bool var and the reverse `id → name`. Every write to
     *  [boolVarIdByName] must go through here so [idToBoolName] stays consistent (#97). */
    fun bindBoolName(name: String, id: Int): Int {
        boolVarIdByName[name] = id
        idToBoolName.put(id, name)
        return id
    }

    fun lowerAllBool(children: List<BoolExpr>): IntArray {
        val lits = IntArray(children.size)
        for (i in children.indices) lits[i] = lowerToLit(children[i])
        return lits
    }

    fun lowerToLit(expr: BoolExpr): Int = when (expr) {
        is BoolRef -> {
            val id = boolVarIdByName[expr.name] ?: error("Unknown Boolean variable '${expr.name}'")
            Lit.make(id, positive = !expr.negated)
        }

        is NominalEq -> {
            val map = nominalIndicators[expr.name] ?: error("Unknown nominal '${expr.name}'")
            val id = map[expr.label] ?: error("Label '${expr.label}' not in nominal '${expr.name}'")
            Lit.make(id, positive = true)
        }

        is Not -> Lit.negate(lowerToLit(expr.child))

        is And -> tseitinAnd(expr.children)

        is Or -> tseitinOr(expr.children)

        is Implies -> tseitinOr(listOf(negate(expr.left), expr.right))

        is Iff -> {
            val l = lowerToLit(expr.left)
            val r = lowerToLit(expr.right)
            tseitinIff(l, r)
        }

        is IntCompare -> reifyIntCompare(expr)

        is FloatLinearConstraint -> {
            // Reified float-linear: introduce an aux bool, assert one factor per
            // truth side. Today we have FloatLinear but not ReifiedFloatLinear, so
            // the implication is inert in the engine — usable as a top-level constraint
            // but not yet as a sub-expression. Tracked as a follow-up.
            error(
                "FloatLinearConstraint at non-top-level position is not yet supported; " +
                    "ReifiedFloatLinear factor still TODO.",
            )
        }

        is AtMost -> reifyCardinality(expr.children, 0, expr.k)

        is AtLeast -> reifyCardinality(expr.children, expr.k, expr.children.size)

        is CardinalityExpr -> reifyCardinality(expr.children, expr.min, expr.max)

        is AllDifferent -> reifyAllDifferent(expr.terms.map { lift(it) })

        is SymmetricAllDifferent ->
            error("symmetric_all_different is a top-level constraint, not reifiable as a sub-expression")

        is InverseChannel ->
            error("inverse is a top-level constraint, not reifiable as a sub-expression")

        is MddExpr -> error("mdd: reified context not supported (use at top-level)")

        is CostMddExpr -> error("cost_mdd: reified context not supported (use at top-level)")

        is CostRegularExpr -> error(
            "cost_regular: reified context not supported (use at top-level)",
        )

        is CircuitExpr -> reifyCircuit(expr)

        is SubcircuitExpr -> reifySubcircuit(expr)

        is CumulativeExpr -> reifyCumulative(expr)

        is DisjunctiveExpr -> reifyDisjunctive(expr)

        is SortExpr -> error("sort: reified context not supported (use at top-level)")

        is IncreasingExpr -> error("increasing: reified context not supported (use at top-level)")

        is DiffnExpr -> error("diffn: reified context not supported (use at top-level)")

        is RegularExpr -> error("regular: reified context not supported (use at top-level)")

        is AllDifferentOpt -> reifyAllDifferentOpt(expr)

        is CumulativeExprOpt -> reifyCumulativeOpt(expr)

        is DisjunctiveExprOpt -> reifyDisjunctiveOpt(expr)

        is NValueExprOpt -> reifyNValueOpt(expr)

        is GccExprOpt -> reifyGccOpt(expr)

        is SetIn -> reifySetIn(expr)

        is SetNominalIn -> reifySetNominalIn(expr)

        is SetSubsetOf -> reifySetSubsetOf(expr)

        is SetDisjoint -> reifySetDisjoint(expr)

        is SetEq -> reifySetEq(expr)

        is TableConstraint -> lowerToLit(expandTable(expr))

        is PseudoBooleanExpr -> {
            val lits = lowerAllBool(expr.lits)
            val aux = newBoolVar()
            factors += ReifiedPseudoBoolean(
                aux,
                LongArray(expr.weights.size) { expr.weights[it].toLong() },
                lits,
                expr.op,
                expr.bound.toLong(),
            )
            Lit.make(aux, positive = true)
        }

        is XorExpr -> {
            // aux ↔ xor(c1, …, cn)  ⟺  xor(aux, c1, …, cn) has even parity.
            val childLits = lowerAllBool(expr.children)
            val aux = newBoolVar()
            val auxLit = Lit.make(aux, positive = true)
            val all = IntArray(childLits.size + 1)
            all[0] = auxLit
            childLits.copyInto(all, destinationOffset = 1)
            factors += Xor(all, targetParity = 0)
            auxLit
        }
    }

    fun reifyCardinality(children: List<BoolExpr>, min: Int, max: Int): Int {
        val lits = lowerAllBool(children)
        val aux = newBoolVar()
        factors += ReifiedCardinality(aux, lits, min, max)
        return Lit.make(aux, positive = true)
    }

    fun reifyIntCompare(expr: IntCompare): Int {
        val (op, normBound) = normalize(expr.op, 0)
        val combined = subtract(affine(lift(expr.left)), affine(lift(expr.right)))
        val coeffs = combined.coeffs
        val bound = normBound - combined.constant
        if (coeffs.isEmpty()) {
            val holds = when (op) {
                IntCmpOp.LE -> 0 <= bound
                IntCmpOp.GE -> 0 >= bound
                IntCmpOp.EQ -> 0 == bound
                IntCmpOp.NE -> 0 != bound
                IntCmpOp.LT, IntCmpOp.GT -> error("normalized away")
            }
            return if (holds) trueLit() else falseLit()
        }
        if (coeffs.size == 1) {
            val (name, c) = coeffs.entries.first()
            return reifySingleVar(name, c, op, bound)
        }
        val (varIds, coeffArr) = coeffsToArrays(coeffs)
        val aux = newBoolVar()
        val linOp = when (op) {
            IntCmpOp.LE -> LinearOp.LE

            IntCmpOp.GE -> LinearOp.GE

            IntCmpOp.EQ -> LinearOp.EQ

            IntCmpOp.NE -> {
                factors += ReifiedLinear(aux, coeffArr, varIds, LinearOp.EQ, bound)
                return Lit.make(aux, positive = false)
            }

            IntCmpOp.LT, IntCmpOp.GT -> error("normalized away")
        }
        factors += ReifiedLinear(aux, coeffArr, varIds, linOp, bound)
        return Lit.make(aux, positive = true)
    }

    internal fun reifySingleVar(name: String, coeff: Int, op: IntCmpOp, bound: Int): Int {
        // Normalize so the var has unit coefficient when possible; else fall back to ReifiedLinear.
        val (effectiveOp, effectiveBound) = when (coeff) {
            1 -> op to bound

            -1 -> {
                val flipped = when (op) {
                    IntCmpOp.LE -> IntCmpOp.GE
                    IntCmpOp.GE -> IntCmpOp.LE
                    IntCmpOp.EQ -> IntCmpOp.EQ
                    IntCmpOp.NE -> IntCmpOp.NE
                    IntCmpOp.LT, IntCmpOp.GT -> error("normalized away")
                }
                flipped to -bound
            }

            else -> {
                val varId = intVarOf(name)
                val aux = newBoolVar()
                val linOp = when (op) {
                    IntCmpOp.LE -> LinearOp.LE

                    IntCmpOp.GE -> LinearOp.GE

                    IntCmpOp.EQ -> LinearOp.EQ

                    IntCmpOp.NE -> {
                        factors += ReifiedLinear(aux, intArrayOf(coeff), intArrayOf(varId), LinearOp.EQ, bound)
                        return Lit.make(aux, positive = false)
                    }

                    IntCmpOp.LT, IntCmpOp.GT -> error("normalized away")
                }
                factors += ReifiedLinear(aux, intArrayOf(coeff), intArrayOf(varId), linOp, bound)
                return Lit.make(aux, positive = true)
            }
        }
        val v = intVarOf(name)
        val aux = newBoolVar()
        val linOp = when (effectiveOp) {
            IntCmpOp.LE -> LinearOp.LE
            IntCmpOp.GE -> LinearOp.GE
            IntCmpOp.EQ -> LinearOp.EQ
            IntCmpOp.NE -> LinearOp.NE
            IntCmpOp.LT, IntCmpOp.GT -> error("normalized away")
        }
        factors += ReifiedLinear(aux, intArrayOf(1), intArrayOf(v), linOp, effectiveBound)
        return Lit.make(aux, positive = true)
    }

    // Lower the children to literals, then emit the shared [CnfLowering] Tseitin gate — the gate itself
    // is defined once in `formats/CnfLowering.kt` and reused by every front-end. `tseitinIff(l, r)` is
    // the shared gate directly (same `(Int, Int)` signature), so it needs no wrapper here.
    fun tseitinAnd(children: List<BoolExpr>): Int = tseitinAnd(lowerAllBool(children).asList())

    fun tseitinOr(children: List<BoolExpr>): Int = tseitinOr(lowerAllBool(children).asList())

    fun negate(expr: BoolExpr): BoolExpr = when (expr) {
        is BoolRef -> expr.copy(negated = !expr.negated)
        is Not -> expr.child
        else -> Not(expr)
    }

    fun normalize(op: IntCmpOp, bound: Int): Pair<IntCmpOp, Int> = when (op) {
        IntCmpOp.LT -> IntCmpOp.LE to (bound - 1)
        IntCmpOp.GT -> IntCmpOp.GE to (bound + 1)
        else -> op to bound
    }

    fun intVarOf(name: String): Int = intVarIdByName[name] ?: error("Unknown int/float variable '$name'")

    internal fun trueLit(): Int {
        val v = newBoolVar()
        factors += Clause(intArrayOf(Lit.make(v, positive = true)))
        return Lit.make(v, positive = true)
    }

    internal fun falseLit(): Int = Lit.negate(trueLit())
}

/** Compile this schema's [VariableSchema.definition] into a solver-ready [CompiledSchema],
 *  using [config] (defaults to the ambient [KlauseConfig.current]). */
fun VariableSchema.compile(config: KlauseConfig = KlauseConfig.current): CompiledSchema =
    Compiler(config).compile(this.definition())

// Schema-entry driver: turns a SchemaDef into a CompiledSchema by declaring variables,
// asserting constraints, and packaging the engine state. Kept off the Lowering class so the
// engine itself stays free of any SchemaDef / schema-shape dependency.

/**
 * Whether every schema float can be an LP-only continuous variable (issue #1232): the model declares a
 * float, and every constraint is either a top-level non-strict, non-`ne` [FloatLinearConstraint] or a
 * constraint that could not hide a float-linear. Conservatively declines (keeps bucketing) on any
 * compound Boolean top-level constraint (`And`/`Or`/`Not`/`Implies`/`Iff`), which could reify or nest a
 * float-linear, and on any strict/`ne` float-linear (not exactly LP-representable). A whole-surface gate:
 * either all floats are LP-only or all bucketed, avoiding a mixed representation in one problem.
 */
private fun schemaFloatsAreLpOnly(def: SchemaDef<SchemaEntry>): Boolean {
    if (def.entries.values.none { it is FloatSpec }) return false
    for ((_, entry) in def.entries) {
        if (entry !is NamedConstraint) continue
        when (val e = entry.expr) {
            is FloatLinearConstraint -> if (e.op != IntCmpOp.LE && e.op != IntCmpOp.GE && e.op != IntCmpOp.EQ) {
                return false
            }

            is And, is Or, is Not, is Implies, is Iff -> return false

            else -> Unit
        }
    }
    return true
}

private fun Lowering.run(def: SchemaDef<SchemaEntry>): CompiledSchema {
    schemaFloatsLpOnly = schemaFloatsAreLpOnly(def)
    for ((name, entry) in def.entries) {
        when (entry) {
            is BoolSpec -> bindBoolName(name, newBoolVar())

            is PresenceSpec -> bindBoolName(name, newBoolVar())

            is NominalSpec -> {
                val ids = LinkedHashMap<String, Int>()
                for (label in entry.labels) ids[label] = newBoolVar()
                nominalIndicators[name] = ids
                val lits = IntArray(ids.size)
                var i = 0
                for (id in ids.values) lits[i++] = Lit.make(id, positive = true)
                factors += Cardinality.exactlyOne(lits)
            }

            is IntSpec -> bindIntName(name, newIntVar(IntDomain(entry.min.toLong(), entry.max.toLong())))

            is SetSpec -> {
                // Allocate one indicator bool per universe element. Universe is
                // already deduplicated and sorted by [setVar]'s declarator.
                val universe = entry.universe.toIntArray()
                val indicators = IntArray(universe.size) { newBoolVar() }
                setLayouts[name] = SetLayout(universe, indicators)
            }

            is MultipleSpec -> {
                // Nominal universe: indicators are typed against labels, but the
                // encoding is the same — one bool per label. We synthesise a
                // synthetic int "id" (the label's index) for the universe so the
                // shared lowering machinery can treat both kinds uniformly.
                val labels = entry.labels
                val universe = IntArray(labels.size) { it }
                val indicators = IntArray(universe.size) { newBoolVar() }
                setLayouts[name] = SetLayout(universe, indicators)
                setLabelOrder[name] = labels
            }

            is FloatSpec -> {
                floatDecoders[name] = entry
                if (schemaFloatsLpOnly) {
                    // LP-only continuous column: a real variable the simplex resolves (issue #1232), no
                    // bucketing. `decode` reads it from `Sample.reals`; float-linear lowers to a real row.
                    val rid = realLo.size
                    realVarIdByName[name] = rid
                    realLo += entry.min
                    realHi += entry.max
                } else {
                    // Bucketed inline so [Problem.factors] stays pure int+bool; the parallel floatMeta*
                    // arrays record the bucket params the float-linear lowering reads.
                    val intId = newIntVar(IntDomain(0L, (entry.buckets - 1).toLong()))
                    bindIntName(name, intId)
                    val fid = floatMetaIntervals.size
                    floatVarIdByName[name] = fid
                    floatMetaIntervals += FloatInterval(entry.min, entry.max)
                    floatMetaIntVarIds.add(intId)
                    floatMetaBuckets.add(entry.buckets)
                }
            }

            is NamedConstraint -> {}
        }
    }

    for ((_, entry) in def.entries) {
        if (entry is NamedConstraint) assertExpr(entry.expr)
    }

    // Opt-var pinning: when an optional variable is absent (its `__present` bool is
    // false), fix its value to a canonical in-domain default so absent vars don't
    // contribute dead-value symmetry. Gated by config so it can be turned off.
    if (config.pinAbsentOptVars) emitOptVarPins(def)

    // The compiler builds a plain base-baked [Problem]; the SAC / failed-literal probing tiers, resolved
    // from the presolve config, now run in the presolve lane via [RootBaker] (kernel → presolve would
    // cycle). The presolve pipeline reads the same config through [BakeConfig.from].
    return CompiledSchema(
        problem = Problem(
            numBoolVars = numBoolVars,
            numIntVars = numIntVars,
            intDomains = intDomains.toTypedArray(),
            factors = factors.toTypedArray(),
            numRealVars = realLo.size,
            realLower = realLo.toDoubleArray(),
            realUpper = realHi.toDoubleArray(),
        ),
        boolVarIdByName = boolVarIdByName.toMap(),
        intVarIdByName = intVarIdByName.toMap(),
        nominalIndicators = nominalIndicators.mapValues { it.value.toMap() },
        floatDecoders = floatDecoders.toMap(),
        setLayouts = setLayouts.toMap(),
        setNominalLabels = setLabelOrder.toMap(),
        realVarIdByName = realVarIdByName.toMap(),
    )
}

/**
 * Emit `¬present → value = default` for every optional variable. An opt var is declared
 * (by [com.eignex.klause.schema.VariableSchema.optIntVar] and friends) alongside a
 * presence Boolean carrying a [PresenceSpec] that names the value
 * variable it gates; we detect the pair by that marker — explicit and type-driven, so an
 * unrelated bool can never be misread as a presence flag. Default per kind:
 *  - int     → `0` coerced into `[min, max]` (always representable, so the pin can never
 *              accidentally force `present` true by being unsatisfiable),
 *  - float   → the bucket index nearest the canonical real default `0.0` coerced into
 *              `[min, max]` (the pin lands on the backing `[0, buckets-1]` int var),
 *  - bool    → `false`,
 *  - nominal → the first declared label.
 */
private fun Lowering.emitOptVarPins(def: SchemaDef<SchemaEntry>) {
    for ((name, entry) in def.entries) {
        if (entry !is PresenceSpec) continue
        val base = entry.valueName
        val absent = Not(BoolRef(name))
        when {
            // Float branch must precede the int branch: float base vars also live in
            // intVarIdByName (backed by a [0, buckets-1] int var), but their canonical
            // default is a *bucket index*, not `0.coerceIn(min, max)` in bucket space.
            floatDecoders.containsKey(base) -> {
                val spec = floatDecoders.getValue(base)
                val default = 0.0.coerceIn(spec.min, spec.max)
                val bucket = ((default - spec.min) / spec.scale).roundToInt().coerceIn(0, spec.buckets - 1)
                assertExpr(Implies(absent, IntCompare(IntRef(base), IntCmpOp.EQ, IntLit(bucket))))
            }

            intVarIdByName.containsKey(base) -> {
                val d = intDomains[intVarIdByName.getValue(base)]
                val default = 0L.coerceIn(d.min, d.max)
                assertExpr(Implies(absent, IntCompare(IntRef(base), IntCmpOp.EQ, IntLit(default.toInt()))))
            }

            nominalIndicators.containsKey(base) -> {
                val firstLabel = nominalIndicators.getValue(base).keys.first()
                assertExpr(Implies(absent, NominalEq(base, firstLabel)))
            }

            boolVarIdByName.containsKey(base) -> {
                assertExpr(Implies(absent, Not(BoolRef(base))))
            }
        }
    }
}
