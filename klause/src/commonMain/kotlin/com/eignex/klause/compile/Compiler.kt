package com.eignex.klause.compile

import com.eignex.klause.ast.AllDifferent
import com.eignex.klause.ast.AllDifferentOpt
import com.eignex.klause.ast.And
import com.eignex.klause.ast.AtLeast
import com.eignex.klause.ast.AtMost
import com.eignex.klause.ast.BoolExpr
import com.eignex.klause.ast.BoolRef
import com.eignex.klause.ast.BoolSpec
import com.eignex.klause.ast.CardinalityExpr
import com.eignex.klause.ast.CircuitExpr
import com.eignex.klause.ast.CostMddExpr
import com.eignex.klause.ast.CostRegularExpr
import com.eignex.klause.ast.CumulativeExpr
import com.eignex.klause.ast.CumulativeExprOpt
import com.eignex.klause.ast.DiffnExpr
import com.eignex.klause.ast.DisjunctiveExpr
import com.eignex.klause.ast.DisjunctiveExprOpt
import com.eignex.klause.ast.FloatLinearConstraint
import com.eignex.klause.ast.FloatSpec
import com.eignex.klause.ast.GccExprOpt
import com.eignex.klause.ast.Iff
import com.eignex.klause.ast.Implies
import com.eignex.klause.ast.IntCmpOp
import com.eignex.klause.ast.IntCompare
import com.eignex.klause.ast.IntLit
import com.eignex.klause.ast.IntRef
import com.eignex.klause.ast.IntSpec
import com.eignex.klause.ast.InverseChannel
import com.eignex.klause.ast.MddExpr
import com.eignex.klause.ast.MultipleSpec
import com.eignex.klause.ast.NValueExprOpt
import com.eignex.klause.ast.NamedConstraint
import com.eignex.klause.ast.NominalEq
import com.eignex.klause.ast.NominalSpec
import com.eignex.klause.ast.Not
import com.eignex.klause.ast.Or
import com.eignex.klause.ast.PresenceSpec
import com.eignex.klause.ast.PseudoBooleanExpr
import com.eignex.klause.ast.RegularExpr
import com.eignex.klause.ast.SchemaEntry
import com.eignex.klause.ast.SearchAnnotation
import com.eignex.klause.ast.SetDisjoint
import com.eignex.klause.ast.SetEq
import com.eignex.klause.ast.SetIn
import com.eignex.klause.ast.SetNominalIn
import com.eignex.klause.ast.SetSpec
import com.eignex.klause.ast.SetSubsetOf
import com.eignex.klause.ast.SortExpr
import com.eignex.klause.ast.SubcircuitExpr
import com.eignex.klause.ast.SymmetricAllDifferent
import com.eignex.klause.ast.TableConstraint
import com.eignex.klause.ast.XorExpr
import com.eignex.klause.config.KlauseConfig
import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.FloatInterval
import com.eignex.klause.solver.FloatMetadata
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.RealLinearConstraint
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.factor.ReifiedCardinality
import com.eignex.klause.solver.factor.ReifiedLinear
import com.eignex.klause.solver.factor.ReifiedPseudoBoolean
import com.eignex.klause.solver.factor.Xor
import com.eignex.skema.SchemaDef
import kotlin.math.roundToInt

internal class Compiler(private val config: KlauseConfig = KlauseConfig.current) {

    fun compile(def: SchemaDef<SchemaEntry>): CompiledProblem = Build(config).run(def)

    internal class Build(val config: KlauseConfig) {
        val factors = mutableListOf<Factor>()
        val boolVarIdByName = mutableMapOf<String, Int>()
        val intVarIdByName = mutableMapOf<String, Int>()

        // Reverse indices (id → name), kept in lock-step with the two forward maps via
        // [bindIntName] / [bindBoolName]. They turn the per-node/per-edge reverse lookups in the
        // global/set lowering from O(n) entry scans into O(1), so that lowering is no longer
        // O(n²) in model size (#97).
        val idToIntName = mutableMapOf<Int, String>()
        val idToBoolName = mutableMapOf<Int, String>()
        val intDomains = mutableListOf<IntDomain>()
        val nominalIndicators = mutableMapOf<String, Map<String, Int>>()

        // Schema-layer float bookkeeping. `floatDecoders` records bucket parameters per
        // float-var name (so the schema can decode `sample.ints[id]` back to a Double).
        // `floatMetaIntervals` / `floatMetaIntVarIds` / `floatMetaBuckets` are the parallel
        // arrays that get packaged into the Problem's optional [FloatMetadata] for backends
        // that solve over reals natively.
        val floatDecoders = mutableMapOf<String, FloatSpec>()
        val floatMetaIntervals = mutableListOf<FloatInterval>()
        val floatMetaIntVarIds = mutableListOf<Int>()
        val floatMetaBuckets = mutableListOf<Int>()
        val floatVarIdByName = mutableMapOf<String, Int>() // float-id (metadata index) by name
        val floatMetaConstraints = mutableListOf<RealLinearConstraint>()

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

        fun run(def: SchemaDef<SchemaEntry>): CompiledProblem {
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

                    is IntSpec -> bindIntName(name, newIntVar(IntDomain(entry.min, entry.max)))

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
                        // Floats are bucketed inline so [Problem.factors] stays pure int+bool.
                        // The original real-valued view (interval, bucket count, int-var
                        // backing) lands in [Problem.floatMetadata] so native-real backends
                        // (Z3) can use it.
                        val intId = newIntVar(IntDomain(0, entry.buckets - 1))
                        bindIntName(name, intId)
                        floatDecoders[name] = entry
                        val fid = floatMetaIntervals.size
                        floatVarIdByName[name] = fid
                        floatMetaIntervals += FloatInterval(entry.min, entry.max)
                        floatMetaIntVarIds += intId
                        floatMetaBuckets += entry.buckets
                    }

                    is NamedConstraint -> {}

                    // handled in a second pass once all vars are registered
                    is SearchAnnotation -> {} // picked up at the end of compile()
                }
            }

            for ((_, entry) in def.entries) {
                if (entry is NamedConstraint) assertExpr(entry.expr)
            }

            // Opt-var pinning: when an optional variable is absent (its `__present` bool is
            // false), fix its value to a canonical in-domain default so absent vars don't
            // contribute dead-value symmetry. Gated by config so it can be turned off.
            if (config.pinAbsentOptVars) emitOptVarPins(def)

            val metadata: FloatMetadata? =
                if (floatMetaIntervals.isEmpty()) {
                    null
                } else {
                    FloatMetadata(
                        intervals = floatMetaIntervals.toTypedArray(),
                        bucketCounts = floatMetaBuckets.toIntArray(),
                        intVarByFloatVar = floatMetaIntVarIds.toIntArray(),
                        constraints = floatMetaConstraints.toList(),
                    )
                }

            // Pick up the last `__search*` annotation in declaration order — schemas may
            // re-declare to refine an inherited choice.
            val searchAnnotation = def.entries.entries
                .lastOrNull { it.value is SearchAnnotation }?.value as? SearchAnnotation
            return CompiledProblem(
                problem = Problem(
                    numBoolVars = numBoolVars,
                    numIntVars = numIntVars,
                    intDomains = intDomains.toTypedArray(),
                    factors = factors.toTypedArray(),
                    floatMetadata = metadata,
                ),
                boolVarIdByName = boolVarIdByName.toMap(),
                intVarIdByName = intVarIdByName.toMap(),
                nominalIndicators = nominalIndicators.mapValues { it.value.toMap() },
                floatDecoders = floatDecoders.toMap(),
                setLayouts = setLayouts.toMap(),
                setNominalLabels = setLabelOrder.toMap(),
                defaultBacktrackParams = searchAnnotation?.let { searchAnnotationToParams(it) },
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
        private fun emitOptVarPins(def: SchemaDef<SchemaEntry>) {
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
                        val default = 0.coerceIn(d.min, d.max)
                        assertExpr(Implies(absent, IntCompare(IntRef(base), IntCmpOp.EQ, IntLit(default))))
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

        fun newBoolVar(): Int = numBoolVars++

        fun newIntVar(domain: IntDomain): Int {
            val id = numIntVars++
            intDomains += domain
            return id
        }

        /** Register `name → id` for an int/float var and the reverse `id → name`. Every write to
         *  [intVarIdByName] must go through here so [idToIntName] stays consistent (#97). */
        fun bindIntName(name: String, id: Int): Int {
            intVarIdByName[name] = id
            idToIntName[id] = name
            return id
        }

        /** Register `name → id` for a bool var and the reverse `id → name`. Every write to
         *  [boolVarIdByName] must go through here so [idToBoolName] stays consistent (#97). */
        fun bindBoolName(name: String, id: Int): Int {
            boolVarIdByName[name] = id
            idToBoolName[id] = name
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
                factors += ReifiedPseudoBoolean(aux, expr.weights.toIntArray(), lits, expr.op, expr.bound)
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
            val finalOp = op
            val finalBound = bound
            if (coeffs.isEmpty()) {
                val holds = when (finalOp) {
                    IntCmpOp.LE -> 0 <= finalBound
                    IntCmpOp.GE -> 0 >= finalBound
                    IntCmpOp.EQ -> 0 == finalBound
                    IntCmpOp.NE -> 0 != finalBound
                    IntCmpOp.LT, IntCmpOp.GT -> error("normalized away")
                }
                return if (holds) trueLit() else falseLit()
            }
            if (coeffs.size == 1) {
                val (name, c) = coeffs.entries.first()
                return reifySingleVar(name, c, finalOp, finalBound)
            }
            val (varIds, coeffArr) = coeffsToArrays(coeffs)
            val aux = newBoolVar()
            val linOp = when (finalOp) {
                IntCmpOp.LE -> LinearOp.LE

                IntCmpOp.GE -> LinearOp.GE

                IntCmpOp.EQ -> LinearOp.EQ

                IntCmpOp.NE -> {
                    factors += ReifiedLinear(aux, coeffArr, varIds, LinearOp.EQ, finalBound)
                    return Lit.make(aux, positive = false)
                }

                IntCmpOp.LT, IntCmpOp.GT -> error("normalized away")
            }
            factors += ReifiedLinear(aux, coeffArr, varIds, linOp, finalBound)
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

        fun tseitinAnd(children: List<BoolExpr>): Int {
            val aux = newBoolVar()
            val auxLit = Lit.make(aux, true)
            val childLits = lowerAllBool(children)
            for (cl in childLits) factors += Clause(intArrayOf(Lit.negate(auxLit), cl))
            val big = IntArray(childLits.size + 1)
            big[0] = auxLit
            for (i in childLits.indices) big[i + 1] = Lit.negate(childLits[i])
            factors += Clause(big)
            return auxLit
        }

        fun tseitinOr(children: List<BoolExpr>): Int {
            val aux = newBoolVar()
            val auxLit = Lit.make(aux, true)
            val childLits = lowerAllBool(children)
            for (cl in childLits) factors += Clause(intArrayOf(Lit.negate(cl), auxLit))
            val big = IntArray(childLits.size + 1)
            big[0] = Lit.negate(auxLit)
            for (i in childLits.indices) big[i + 1] = childLits[i]
            factors += Clause(big)
            return auxLit
        }

        fun tseitinIff(l: Int, r: Int): Int {
            val aux = newBoolVar()
            val auxLit = Lit.make(aux, true)
            factors += Clause(intArrayOf(Lit.negate(auxLit), Lit.negate(l), r))
            factors += Clause(intArrayOf(Lit.negate(auxLit), Lit.negate(r), l))
            factors += Clause(intArrayOf(auxLit, l, r))
            factors += Clause(intArrayOf(auxLit, Lit.negate(l), Lit.negate(r)))
            return auxLit
        }

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
}

/** Compile this schema's [VariableSchema.definition] into a solver-ready [CompiledProblem],
 *  using [config] (defaults to the ambient [KlauseConfig.current]). */
fun VariableSchema.compile(config: KlauseConfig = KlauseConfig.current): CompiledProblem =
    Compiler(config).compile(this.definition())
