package com.eignex.klause.compile

import com.eignex.klause.config.DEFAULT_FLOAT_BUCKETS
import com.eignex.klause.config.KlauseConfig
import com.eignex.klause.factor.arithmetic.ReifiedCardinality
import com.eignex.klause.factor.arithmetic.ReifiedLinear
import com.eignex.klause.factor.arithmetic.ReifiedPseudoBoolean
import com.eignex.klause.factor.bool.Cardinality
import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.Xor
import com.eignex.klause.factor.codec.FactorCodecs
import com.eignex.klause.ir.EffectiveSourceSemantics
import com.eignex.klause.ir.FloatSemantics
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.ir.ProblemSettings
import com.eignex.klause.ir.SemanticOverrides
import com.eignex.klause.ir.SourceBoolId
import com.eignex.klause.ir.SourceRealId
import com.eignex.klause.ir.ceilingOrNull
import com.eignex.klause.lowering.CnfLowering
import com.eignex.klause.lowering.ProblemBuilder
import com.eignex.klause.lowering.SetLayout
import com.eignex.klause.lowering.reifyRealLinear
import com.eignex.klause.lowering.tseitinAnd
import com.eignex.klause.lowering.tseitinIff
import com.eignex.klause.lowering.tseitinOr
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
import com.eignex.klause.model.ConstraintRole
import com.eignex.klause.model.ContinuousRealEquality
import com.eignex.klause.model.CostMddExpr
import com.eignex.klause.model.CostRegularExpr
import com.eignex.klause.model.CumulativeExpr
import com.eignex.klause.model.CumulativeExprOpt
import com.eignex.klause.model.DiffnExpr
import com.eignex.klause.model.DisjunctiveExpr
import com.eignex.klause.model.DisjunctiveExprOpt
import com.eignex.klause.model.FactorConstraint
import com.eignex.klause.model.FloatLinearConstraint
import com.eignex.klause.model.FloatSpec
import com.eignex.klause.model.GccExprOpt
import com.eignex.klause.model.Iff
import com.eignex.klause.model.Implies
import com.eignex.klause.model.IncreasingExpr
import com.eignex.klause.model.IntCmpOp
import com.eignex.klause.model.IntCompare
import com.eignex.klause.model.IntDomainSpec
import com.eignex.klause.model.IntExpr
import com.eignex.klause.model.IntLit
import com.eignex.klause.model.IntRef
import com.eignex.klause.model.IntSpec
import com.eignex.klause.model.InverseChannel
import com.eignex.klause.model.LexExpr
import com.eignex.klause.model.MddExpr
import com.eignex.klause.model.MultipleSpec
import com.eignex.klause.model.NValueExpr
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
import com.eignex.klause.model.VariableCumulativeExpr
import com.eignex.klause.model.XorExpr
import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.solver.projection.FloatGridAbsentPin
import com.eignex.klause.solver.projection.FloatGridVariable
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.MutableIntObjectMap
import com.eignex.skema.SchemaDef

internal class Compiler(
    config: KlauseConfig,
    overrides: SemanticOverrides,
    private val factorCodecs: FactorCodecs = FactorCodecs.builtin,
) {
    private val semantics = schemaSemantics(overrides)
    private val settings = config.problemSettings()

    fun compile(def: SchemaDef<SchemaEntry>): CompiledSchema = Lowering(settings, semantics, factorCodecs).run(def)
}

/**
 * Compile this schema definition into a solver-ready [CompiledSchema]. [factorCodecs] decodes
 * [FactorConstraint] nodes and must know every factor key they use.
 */
fun SchemaDef<SchemaEntry>.compile(
    config: KlauseConfig = KlauseConfig.current,
    semanticOverrides: SemanticOverrides = SemanticOverrides(),
    factorCodecs: FactorCodecs = FactorCodecs.builtin,
): CompiledSchema = Compiler(config, semanticOverrides, factorCodecs).compile(this)

internal class Lowering(
    settings: ProblemSettings,
    val semantics: EffectiveSourceSemantics,
    val factorCodecs: FactorCodecs = FactorCodecs.builtin,
) : CnfLowering {
    private val problemBuilder = ProblemBuilder(settings, semantics)

    /** Factors posted by implied or symmetry-breaking constraints, by index. */
    val impliedFactors = HashSet<Int>()
    var hasSymmetryBreaking = false
    val pinAbsentOptVars = checkNotNull(semantics.effective.pinAbsentOptionalValues)
    private val grid = semantics.effective.floats as? FloatSemantics.Grid
    val floatBuckets = grid?.buckets ?: DEFAULT_FLOAT_BUCKETS

    override val factors get() = problemBuilder.factors
    val boolVarIdByName get() = problemBuilder.boolVarIdByName
    val intVarIdByName get() = problemBuilder.intVarIdByName

    // Reverse indices kept consistent by [bindIntName] / [bindBoolName] avoid linear name scans.
    val idToIntName = MutableIntObjectMap<String>()
    val idToBoolName = MutableIntObjectMap<String>()
    val intDomains get() = problemBuilder.intDomains
    internal val divModOperands = HashMap<IntExpr, Int>()
    internal val divModRelations = com.eignex.klause.lowering.DivModRelationIndex()
    val nominalIndicators = mutableMapOf<String, Map<String, Int>>()

    val floatDecoders = mutableMapOf<String, FloatSpec>()
    val auxiliaryReals = HashSet<Int>()
    val floatAbsentPins = HashMap<Int, FloatGridAbsentPin>()
    val truncatedFloatRows = HashSet<Int>()
    val realVarIdByName get() = problemBuilder.realVarIdByName

    /**
     * Indicator layout for each set variable: indicator i is true iff universe element i belongs
     * to the set. [SetSpec] uses integer elements; [MultipleSpec] uses label indices.
     */
    val setLayouts = mutableMapOf<String, SetLayout>()

    /** Label → universe index for set vars whose universe is a nominal label list.
     *  Empty for int-universe set vars. */
    val setLabelOrder = mutableMapOf<String, List<String>>()
    internal var auxIntCounter = 0

    val numBoolVars get() = problemBuilder.numBoolVars
    val numIntVars get() = problemBuilder.numIntVars

    fun newBoolVar(): Int = problemBuilder.newBool()

    override fun newBool(): Int = problemBuilder.newBool()
    override var trueLitCache: Int
        get() = problemBuilder.trueLitCache
        set(value) {
            problemBuilder.trueLitCache = value
        }

    fun newIntVar(domain: IntDomain): Int = problemBuilder.newInt(domain)

    fun markOpenInt(id: Int, lower: Boolean, upper: Boolean) = problemBuilder.markOpen(id, lower, upper)

    fun newRealVar(lower: Double, upper: Double, name: String? = null): Int =
        problemBuilder.newReal(lower, upper, name).also { if (name == null) auxiliaryReals += it }

    fun continuousProduct(a: Int, b: Int, result: Int) = problemBuilder.continuousProduct(a, b, result)

    fun buildProblem() = problemBuilder.build(
        impliedFactorMask = if (impliedFactors.isEmpty()) null else BooleanArray(factors.size) { it in impliedFactors },
        hasSymmetryBreaking = hasSymmetryBreaking,
    )

    /** Register `name → id` for an int/float var and the reverse `id → name`. Every write to
     *  [intVarIdByName] must go through here so [idToIntName] stays consistent. */
    fun bindIntName(name: String, id: Int): Int {
        problemBuilder.bindIntName(name, id)
        idToIntName.put(id, name)
        return id
    }

    /** Register `name → id` for a bool var and the reverse `id → name`. Every write to
     *  [boolVarIdByName] must go through here so [idToBoolName] stays consistent. */
    fun bindBoolName(name: String, id: Int): Int {
        problemBuilder.bindBoolName(name, id)
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

        is ContinuousRealEquality -> error("Continuous equality requires a top-level constraint")

        is FloatLinearConstraint -> reifyFloatLinear(expr)

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

        is NValueExpr -> error("nValue: reified context not supported (use at top-level)")

        is GccExprOpt -> reifyGccOpt(expr)

        is LexExpr -> lowerToLit(lexDecomposition(expr))

        is VariableCumulativeExpr -> error("cumulativeVars: reified context not supported (use at top-level)")

        is FactorConstraint -> error("factor: reified context not supported (use at top-level)")

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

    // Shared [CnfLowering] gates lower Boolean combinations to literals.
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
fun VariableSchema.compile(
    config: KlauseConfig = KlauseConfig.current,
    semanticOverrides: SemanticOverrides = SemanticOverrides(),
    factorCodecs: FactorCodecs = FactorCodecs.builtin,
): CompiledSchema = Compiler(config, semanticOverrides, factorCodecs).compile(this.definition())

// Schema-entry driver: turns a SchemaDef into a CompiledSchema by declaring variables,
// asserting constraints, and packaging the engine state. Kept off the Lowering class so the
// engine itself stays free of any SchemaDef / schema-shape dependency.

private fun Lowering.run(def: SchemaDef<SchemaEntry>): CompiledSchema {
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

            is IntDomainSpec -> bindIntName(name, declareIntDomain(entry))

            is SetSpec -> {
                // Set universes are deduplicated and sorted by [setVar].
                val universe = entry.universe.toIntArray()
                val indicators = IntArray(universe.size) { newBoolVar() }
                setLayouts[name] = SetLayout(universe, indicators)
            }

            is MultipleSpec -> {
                // Label indices let nominal sets share the integer-set lowering.
                val labels = entry.labels
                val universe = IntArray(labels.size) { it }
                val indicators = IntArray(universe.size) { newBoolVar() }
                setLayouts[name] = SetLayout(universe, indicators)
                setLabelOrder[name] = labels
            }

            is FloatSpec -> {
                val buckets = entry.buckets ?: floatBuckets
                floatDecoders[name] = entry.copy(buckets = buckets)
                newRealVar(entry.min, entry.max, name)
            }

            is NamedConstraint -> {}
        }
    }

    for ((_, entry) in def.entries) {
        if (entry !is NamedConstraint) continue
        val first = factors.size
        assertExpr(entry.expr)
        if (entry.role != ConstraintRole.REQUIRED) impliedFactors.addAll(first until factors.size)
        if (entry.role == ConstraintRole.SYMMETRY_BREAKING) hasSymmetryBreaking = true
    }

    // Canonical absent values remove dead-value symmetry.
    if (pinAbsentOptVars) emitOptVarPins(def)

    // The compiler builds a plain base-baked `Problem`; the SAC / failed-literal probing tiers, resolved
    // from the presolve config, run in the presolve lane via [RootBaker] (kernel → presolve would
    // cycle). The presolve pipeline reads the same config through [BakeConfig.from].
    val source = buildProblem()
    val namedReals = realVarIdByName.toMap()
    val specsById = namedReals.entries.associate { (name, id) -> id to floatDecoders.getValue(name) }
    val binding = CompiledSourceBinding(source, intVarIdByName, namedReals,
        (0 until source.numRealVars).map { id ->
            FloatGridVariable(SourceRealId(id), specsById[id]?.buckets, id in auxiliaryReals, floatAbsentPins[id],
                preserveBuckets = id in specsById)
        }, schemaFloatRepresentations(def), truncatedFloatRows)
    return CompiledSchema(
        problem = source,
        boolVarIdByName = boolVarIdByName.toMap(),
        intVarIdByName = intVarIdByName.toMap(),
        nominalIndicators = nominalIndicators.mapValues { it.value.toMap() },
        floatDecoders = floatDecoders.toMap(),
        setLayouts = setLayouts.toMap(),
        setNominalLabels = setLabelOrder.toMap(),
        realVarIdByName = realVarIdByName.toMap(),
        sourceBinding = binding,
    )
}

/**
 * Emit `¬present → value = default` for each optional variable identified by [PresenceSpec].
 * Defaults are zero clamped to the declared integer or real domain,
 * false for Booleans, and the first nominal label. Each default lies in its value domain, so
 * the pin cannot force presence.
 */
private fun Lowering.emitOptVarPins(def: SchemaDef<SchemaEntry>) {
    for ((name, entry) in def.entries) {
        if (entry !is PresenceSpec) continue
        val base = entry.valueName
        val absent = Not(BoolRef(name))
        when {
            floatDecoders.containsKey(base) -> {
                val spec = floatDecoders.getValue(base)
                val default = 0.0.coerceIn(spec.min, spec.max)
                val id = realVarIdByName.getValue(base)
                val first = factors.size
                val equality = reifyRealLinear(doubleArrayOf(1.0), intArrayOf(id), LinearOp.EQ, default)
                factors += Clause(intArrayOf(Lit.make(boolVarIdByName.getValue(name), true), equality))
                val rows = (first until factors.size).filter {
                    factors[it] is com.eignex.klause.factor.arithmetic.ReifiedRealLinear
                }
                floatAbsentPins[id] = FloatGridAbsentPin(SourceBoolId(boolVarIdByName.getValue(name)),
                    checkNotNull(BigFraction.ofDouble(default)), rows)
            }

            intVarIdByName.containsKey(base) -> {
                val id = intVarIdByName.getValue(base)
                val d = intDomains[id]
                val default = d.ceilingOrNull(0L) ?: d.max
                val equality = newBoolVar()
                factors += ReifiedLinear(equality, longArrayOf(1L), intArrayOf(id), LinearOp.EQ, default)
                factors += Clause(intArrayOf(Lit.make(boolVarIdByName.getValue(name), true), Lit.make(equality, true)))
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
