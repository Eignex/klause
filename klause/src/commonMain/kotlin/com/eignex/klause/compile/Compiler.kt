package com.eignex.klause.compile

import com.eignex.klause.ast.AllDifferent
import com.eignex.klause.ast.AllDifferentOpt
import com.eignex.klause.ast.And
import com.eignex.klause.ast.CircuitExpr
import com.eignex.klause.ast.CountExprOpt
import com.eignex.klause.ast.CumulativeExprOpt
import com.eignex.klause.ast.DisjunctiveExprOpt
import com.eignex.klause.ast.GccExprOpt
import com.eignex.klause.ast.NValueExprOpt
import com.eignex.klause.ast.CumulativeExpr
import com.eignex.klause.ast.DisjunctiveExpr
import com.eignex.klause.ast.SubcircuitExpr
import com.eignex.klause.ast.AtLeast
import com.eignex.klause.ast.AtMost
import com.eignex.klause.ast.PbOp
import com.eignex.klause.ast.PseudoBooleanExpr
import com.eignex.klause.ast.TableConstraint
import com.eignex.klause.ast.XorExpr
import com.eignex.klause.ast.BoolExpr
import com.eignex.klause.ast.BoolRef
import com.eignex.klause.ast.BoolSpec
import com.eignex.klause.ast.CardinalityExpr
import com.eignex.klause.ast.FloatSpec
import com.eignex.klause.ast.Iff
import com.eignex.klause.ast.Implies
import com.eignex.klause.ast.IntCmpOp
import com.eignex.klause.ast.IntCompare
import com.eignex.klause.ast.IntExpr
import com.eignex.klause.ast.IntLit
import com.eignex.klause.ast.IntAbs
import com.eignex.klause.ast.IntDiv
import com.eignex.klause.ast.IntElement
import com.eignex.klause.ast.IntIfThenElse
import com.eignex.klause.ast.IntMax
import com.eignex.klause.ast.IntMin
import com.eignex.klause.ast.IntMod
import com.eignex.klause.ast.IntMul
import com.eignex.klause.ast.IntRef
import com.eignex.klause.ast.IntScale
import com.eignex.klause.ast.IntSpec
import com.eignex.klause.ast.IntSum
import com.eignex.klause.ast.NominalEq
import com.eignex.klause.ast.NominalSpec
import com.eignex.klause.ast.Not
import com.eignex.klause.ast.Or
import com.eignex.klause.ast.NamedConstraint
import com.eignex.klause.ast.SchemaEntry
import com.eignex.klause.schema.PRESENCE_SUFFIX
import com.eignex.klause.schema.VariableSchema
import com.eignex.skema.SchemaDef
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.factor.Product
import com.eignex.klause.solver.factor.PseudoBoolean
import com.eignex.klause.solver.factor.ReifiedCardinality
import com.eignex.klause.solver.factor.ReifiedPseudoBoolean
import com.eignex.klause.solver.factor.Xor
import com.eignex.klause.solver.factor.AllDifferent as AllDifferentFactor
import com.eignex.klause.solver.factor.Circuit as CircuitFactor
import com.eignex.klause.solver.factor.Cumulative as CumulativeFactor
import com.eignex.klause.solver.factor.Disjunctive as DisjunctiveFactor
import com.eignex.klause.solver.factor.ReifiedLinear
import com.eignex.klause.solver.factor.Subcircuit as SubcircuitFactor

class Compiler(private val config: com.eignex.klause.config.KlauseConfig = com.eignex.klause.config.KlauseConfig.current) {

    fun compile(def: SchemaDef<SchemaEntry>): CompiledProblem = Build(config).run(def)

    internal class Build(val config: com.eignex.klause.config.KlauseConfig) {
        val factors = mutableListOf<Factor>()
        val boolVarIdByName = mutableMapOf<String, Int>()
        val intVarIdByName = mutableMapOf<String, Int>()
        val intDomains = mutableListOf<IntDomain>()
        val nominalIndicators = mutableMapOf<String, Map<String, Int>>()
        // Schema-layer float bookkeeping. `floatDecoders` records bucket parameters per
        // float-var name (so the schema can decode `sample.ints[id]` back to a Double).
        // `floatMetaIntervals` / `floatMetaIntVarIds` / `floatMetaBuckets` are the parallel
        // arrays that get packaged into the Problem's optional [FloatMetadata] for backends
        // that solve over reals natively.
        val floatDecoders = mutableMapOf<String, FloatSpec>()
        val floatMetaIntervals = mutableListOf<com.eignex.klause.solver.FloatInterval>()
        val floatMetaIntVarIds = mutableListOf<Int>()
        val floatMetaBuckets = mutableListOf<Int>()
        val floatVarIdByName = mutableMapOf<String, Int>()  // float-id (metadata index) by name
        val floatMetaConstraints = mutableListOf<com.eignex.klause.solver.RealLinearConstraint>()
        /** Indicator-bool layout per declared set variable. Mirrors FlatZinc's
         *  `SetVarLayout`: for set var `S` over universe `[e_0, …, e_{n-1}]`,
         *  `setLayouts["S"].indicatorBoolIds[i]` is the klause bool var that's `true` iff
         *  `e_i ∈ S`. Both [com.eignex.klause.ast.SetSpec] (int universe) and
         *  [com.eignex.klause.ast.MultipleSpec] (nominal universe) populate this — the
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
                    is BoolSpec -> boolVarIdByName[name] = newBoolVar()
                    is NominalSpec -> {
                        val ids = LinkedHashMap<String, Int>()
                        for (label in entry.labels) ids[label] = newBoolVar()
                        nominalIndicators[name] = ids
                        val lits = IntArray(ids.size)
                        var i = 0
                        for (id in ids.values) lits[i++] = Lit.make(id, positive = true)
                        factors += Cardinality.exactlyOne(lits)
                    }
                    is IntSpec -> intVarIdByName[name] = newIntVar(IntDomain(entry.min, entry.max))
                    is com.eignex.klause.ast.SetSpec -> {
                        // Allocate one indicator bool per universe element. Universe is
                        // already deduplicated and sorted by [setVar]'s declarator.
                        val universe = entry.universe.toIntArray()
                        val indicators = IntArray(universe.size) { newBoolVar() }
                        setLayouts[name] = SetLayout(universe, indicators)
                    }
                    is com.eignex.klause.ast.MultipleSpec -> {
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
                        intVarIdByName[name] = intId
                        floatDecoders[name] = entry
                        val fid = floatMetaIntervals.size
                        floatVarIdByName[name] = fid
                        floatMetaIntervals += com.eignex.klause.solver.FloatInterval(entry.min, entry.max)
                        floatMetaIntVarIds += intId
                        floatMetaBuckets += entry.buckets
                    }
                    is NamedConstraint -> {} // handled in a second pass once all vars are registered
                    is com.eignex.klause.ast.SearchAnnotation -> {} // picked up at the end of compile()
                }
            }

            for ((_, entry) in def.entries) {
                if (entry is NamedConstraint) assertExpr(entry.expr)
            }

            // Opt-var pinning: when an optional variable is absent (its `__present` bool is
            // false), fix its value to a canonical in-domain default so absent vars don't
            // contribute dead-value symmetry. Gated by config so it can be turned off.
            if (config.pinAbsentOptVars) emitOptVarPins(def)

            val metadata: com.eignex.klause.solver.FloatMetadata? =
                if (floatMetaIntervals.isEmpty()) null
                else com.eignex.klause.solver.FloatMetadata(
                    intervals = floatMetaIntervals.toTypedArray(),
                    bucketCounts = floatMetaBuckets.toIntArray(),
                    intVarByFloatVar = floatMetaIntVarIds.toIntArray(),
                    constraints = floatMetaConstraints.toList(),
                )

            // Pick up the last `__search*` annotation in declaration order — schemas may
            // re-declare to refine an inherited choice.
            val searchAnnotation = def.entries.entries
                .filter { it.value is com.eignex.klause.ast.SearchAnnotation }
                .lastOrNull()?.value as? com.eignex.klause.ast.SearchAnnotation
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
         * Emit `¬present → value = default` for every optional variable. An opt var named
         * `X` is declared (by [com.eignex.klause.schema.VariableSchema.optIntVar] and friends)
         * alongside a synthetic presence Boolean named `X$PRESENCE_SUFFIX`; we detect the pair
         * by that naming convention. Default per kind:
         *  - int     → `0` coerced into `[min, max]` (always representable, so the pin can never
         *              accidentally force `present` true by being unsatisfiable),
         *  - bool    → `false`,
         *  - nominal → the first declared label.
         */
        private fun emitOptVarPins(def: SchemaDef<SchemaEntry>) {
            for ((name, entry) in def.entries) {
                if (entry !is BoolSpec || !name.endsWith(PRESENCE_SUFFIX)) continue
                val base = name.removeSuffix(PRESENCE_SUFFIX)
                val absent = Not(BoolRef(name))
                when {
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
            is com.eignex.klause.ast.FloatLinearConstraint -> {
                // Reified float-linear: introduce an aux bool, assert one factor per
                // truth side. Today we have FloatLinear but not ReifiedFloatLinear, so
                // the implication is inert in the engine — usable as a top-level constraint
                // but not yet as a sub-expression. Tracked as a follow-up.
                error("FloatLinearConstraint at non-top-level position is not yet supported; " +
                    "ReifiedFloatLinear factor still TODO.")
            }
            is AtMost -> reifyCardinality(expr.children, 0, expr.k)
            is AtLeast -> reifyCardinality(expr.children, expr.k, expr.children.size)
            is CardinalityExpr -> reifyCardinality(expr.children, expr.min, expr.max)
            is AllDifferent -> reifyAllDifferent(expr.terms.map { lift(it) })
            is com.eignex.klause.ast.AllDifferentExceptExpr -> lowerToLit(decomposeAllDifferentExcept(expr))
            is com.eignex.klause.ast.ArgSortExpr -> lowerToLit(decomposeArgSort(expr))
            is com.eignex.klause.ast.NetworkFlowExpr -> lowerToLit(decomposeNetworkFlow(expr))
            is com.eignex.klause.ast.NetworkFlowCostExpr -> lowerToLit(decomposeNetworkFlowCost(expr))
            is com.eignex.klause.ast.GeostExpr -> lowerToLit(decomposeGeost(expr))
            is com.eignex.klause.ast.PathExpr -> error("path: reified context not supported (use at top-level)")
            is com.eignex.klause.ast.TreeExpr -> error("tree: reified context not supported (use at top-level)")
            is com.eignex.klause.ast.MddExpr -> error("mdd: reified context not supported (use at top-level)")
            is com.eignex.klause.ast.CostMddExpr -> error("cost_mdd: reified context not supported (use at top-level)")
            is com.eignex.klause.ast.CostRegularExpr -> error("cost_regular: reified context not supported (use at top-level)")
            is CircuitExpr -> reifyCircuit(expr)
            is SubcircuitExpr -> reifySubcircuit(expr)
            is CumulativeExpr -> reifyCumulative(expr)
            is DisjunctiveExpr -> reifyDisjunctive(expr)
            is AllDifferentOpt -> reifyAllDifferentOpt(expr)
            is CumulativeExprOpt -> reifyCumulativeOpt(expr)
            is DisjunctiveExprOpt -> reifyDisjunctiveOpt(expr)
            is CountExprOpt -> reifyCountOpt(expr)
            is NValueExprOpt -> reifyNValueOpt(expr)
            is GccExprOpt -> reifyGccOpt(expr)
            is com.eignex.klause.ast.SetIn -> reifySetIn(expr)
            is com.eignex.klause.ast.SetNominalIn -> reifySetNominalIn(expr)
            is com.eignex.klause.ast.SetSubsetOf -> reifySetSubsetOf(expr)
            is com.eignex.klause.ast.SetDisjoint -> reifySetDisjoint(expr)
            is com.eignex.klause.ast.SetEq -> reifySetEq(expr)
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
                        IntCmpOp.LE -> IntCmpOp.GE; IntCmpOp.GE -> IntCmpOp.LE
                        IntCmpOp.EQ -> IntCmpOp.EQ; IntCmpOp.NE -> IntCmpOp.NE
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

        fun intVarOf(name: String): Int =
            intVarIdByName[name] ?: error("Unknown int/float variable '$name'")

        internal fun trueLit(): Int {
            val v = newBoolVar()
            factors += Clause(intArrayOf(Lit.make(v, positive = true)))
            return Lit.make(v, positive = true)
        }

        internal fun falseLit(): Int = Lit.negate(trueLit())
    }
}

fun VariableSchema.compile(
    config: com.eignex.klause.config.KlauseConfig = com.eignex.klause.config.KlauseConfig.current,
): CompiledProblem = Compiler(config).compile(this.definition())
