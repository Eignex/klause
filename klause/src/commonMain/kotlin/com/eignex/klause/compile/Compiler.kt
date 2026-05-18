package com.eignex.klause.compile

import com.eignex.klause.ast.AllDifferent
import com.eignex.klause.ast.And
import com.eignex.klause.ast.CircuitExpr
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

class Compiler {

    fun compile(def: SchemaDef<SchemaEntry>): CompiledProblem = Build().run(def)

    private class Build {
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
        var numBoolVars = 0
        var numIntVars = 0
        private var auxIntCounter = 0

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
                }
            }

            for ((_, entry) in def.entries) {
                if (entry is NamedConstraint) assertExpr(entry.expr)
            }

            val metadata: com.eignex.klause.solver.FloatMetadata? =
                if (floatMetaIntervals.isEmpty()) null
                else com.eignex.klause.solver.FloatMetadata(
                    intervals = floatMetaIntervals.toTypedArray(),
                    bucketCounts = floatMetaBuckets.toIntArray(),
                    intVarByFloatVar = floatMetaIntVarIds.toIntArray(),
                    constraints = floatMetaConstraints.toList(),
                )

            return CompiledProblem(
                problem = Problem(
                    numBoolVars = numBoolVars,
                    numIntVars = numIntVars,
                    intDomains = intDomains.toTypedArray(),
                    factors = factors.toList(),
                    floatMetadata = metadata,
                ),
                boolVarIdByName = boolVarIdByName.toMap(),
                intVarIdByName = intVarIdByName.toMap(),
                nominalIndicators = nominalIndicators.mapValues { it.value.toMap() },
                floatDecoders = floatDecoders.toMap(),
            )
        }

        fun newBoolVar(): Int = numBoolVars++

        fun newIntVar(domain: IntDomain): Int {
            val id = numIntVars++
            intDomains += domain
            return id
        }

        fun assertExpr(expr: BoolExpr) {
            when (expr) {
                is And -> for (c in expr.children) assertExpr(c)
                is Implies -> assertExpr(Or(listOf(negate(expr.left), expr.right)))
                is Iff -> {
                    assertExpr(Implies(expr.left, expr.right))
                    assertExpr(Implies(expr.right, expr.left))
                }
                is Or -> {
                    val lits = IntArray(expr.children.size)
                    for (i in expr.children.indices) lits[i] = lowerToLit(expr.children[i])
                    factors += Clause(lits)
                }
                is AtMost -> {
                    val lits = lowerAllBool(expr.children)
                    factors += Cardinality(lits, 0, expr.k)
                }
                is AtLeast -> {
                    val lits = lowerAllBool(expr.children)
                    factors += Cardinality(lits, expr.k, lits.size)
                }
                is CardinalityExpr -> {
                    val lits = lowerAllBool(expr.children)
                    factors += Cardinality(lits, expr.min, expr.max)
                }
                is Not, is BoolRef, is NominalEq -> {
                    factors += Clause(intArrayOf(lowerToLit(expr)))
                }
                is IntCompare -> assertIntCompare(expr)
                is com.eignex.klause.ast.FloatLinearConstraint -> assertFloatLinear(expr)
                is AllDifferent -> assertAllDifferent(expr.terms)
                is CircuitExpr -> assertCircuit(expr.succ, expr.valueOffset, sub = false)
                is SubcircuitExpr -> assertCircuit(expr.succ, expr.valueOffset, sub = true)
                is CumulativeExpr -> assertCumulative(expr)
                is DisjunctiveExpr -> assertDisjunctive(expr)
                is TableConstraint -> assertExpr(expandTable(expr))
                is PseudoBooleanExpr -> {
                    val lits = lowerAllBool(expr.lits)
                    factors += PseudoBoolean(
                        weights = expr.weights.toIntArray(),
                        literals = lits,
                        op = expr.op,
                        bound = expr.bound,
)
                }
                is XorExpr -> {
                    val lits = lowerAllBool(expr.children)
                    factors += Xor(lits, targetParity = 1)
                }
            }
        }

        private fun expandTable(t: TableConstraint): BoolExpr {
            val lifted = t.terms.map { lift(it) }
            val tuples = t.tuples.map { tup ->
                And(lifted.indices.map { i ->
                    IntCompare(lifted[i], IntCmpOp.EQ, IntLit(tup[i]))
                })
            }
            return if (t.negative) {
                And(tuples.map { Not(it) })
            } else {
                if (tuples.size == 1) tuples[0] else Or(tuples)
            }
        }

        /**
         * Lower a [com.eignex.klause.ast.FloatLinearConstraint] in two parallel ways:
         *
         *  1. Bucket each referenced float variable using its declared [FloatSpec.buckets]
         *     and emit a scaled-integer [Linear] factor — this is what every existing
         *     backend solves over.
         *  2. Append a [com.eignex.klause.solver.RealLinearConstraint] (over float-var
         *     ids) to the metadata buffer so a native-real backend (Z3) can solve it
         *     directly in real arithmetic.
         *
         * Scaling math (per float var `v` with interval `[lo, hi]` and `N` buckets, step
         * `step = (hi - lo) / (N - 1)`): substitute `v = lo + b · step` and rearrange to
         * `Σ (c_v · step_v) · b_v ⟨op⟩ bound − Σ c_v · lo_v`, then multiply by `SCALE` and
         * round to integer coefficients. Discretisation error is ~1/SCALE per term.
         */
        private fun assertFloatLinear(c: com.eignex.klause.ast.FloatLinearConstraint) {
            val n = c.varNames.size
            val realIds = IntArray(n) { i ->
                floatVarIdByName[c.varNames[i]]
                    ?: error("Float variable '${c.varNames[i]}' not declared")
            }
            val realOp = when (c.op) {
                com.eignex.klause.ast.IntCmpOp.LE,
                com.eignex.klause.ast.IntCmpOp.LT -> com.eignex.klause.solver.factor.LinearOp.LE
                com.eignex.klause.ast.IntCmpOp.GE,
                com.eignex.klause.ast.IntCmpOp.GT -> com.eignex.klause.solver.factor.LinearOp.GE
                com.eignex.klause.ast.IntCmpOp.EQ -> com.eignex.klause.solver.factor.LinearOp.EQ
                com.eignex.klause.ast.IntCmpOp.NE -> com.eignex.klause.solver.factor.LinearOp.NE
            }
            floatMetaConstraints += com.eignex.klause.solver.RealLinearConstraint(
                coeffs = c.coeffs.copyOf(),
                floatVarIds = realIds,
                op = realOp,
                bound = c.bound,
            )

            // Bucketed-int rewrite for the factor list. Reuses the same SCALE
            // historically used by [com.eignex.klause.schema.FloatExpr.multiHandleBucketCompare].
            val scale = 1_000_000.0
            var scaledBound = c.bound
            val scaledCoeffs = IntArray(n)
            val intVarIds = IntArray(n)
            for (i in 0 until n) {
                val name = c.varNames[i]
                val fid = floatVarIdByName.getValue(name)
                val interval = floatMetaIntervals[fid]
                val buckets = floatMetaBuckets[fid]
                val step = if (buckets > 1) (interval.hi - interval.lo) / (buckets - 1) else 0.0
                scaledCoeffs[i] = (c.coeffs[i] * step * scale).toLong().toInt()
                intVarIds[i] = floatMetaIntVarIds[fid]
                scaledBound -= c.coeffs[i] * interval.lo
            }
            val scaledBoundInt = (scaledBound * scale).toLong().toInt()
            factors += com.eignex.klause.solver.factor.Linear(scaledCoeffs, intVarIds, realOp, scaledBoundInt)
        }

        private fun assertAllDifferent(terms: List<IntExpr>) {
            val lifted = terms.map { lift(it) }
            // Specialisation: when every operand is a bare IntRef (no arithmetic residual), emit
            // the global factor. Otherwise fall back to pairwise NE through the existing
            // reification path.
            if (lifted.all { it is IntRef }) {
                val ids = IntArray(lifted.size) { intVarOf((lifted[it] as IntRef).name) }
                if (ids.toSet().size == ids.size) {
                    var dMin = intDomains[ids[0]].min
                    var dMax = intDomains[ids[0]].max
                    for (id in ids) {
                        val d = intDomains[id]
                        if (d.min < dMin) dMin = d.min
                        if (d.max > dMax) dMax = d.max
                    }
                    factors += AllDifferentFactor(ids, dMin, dMax - dMin + 1)
                    return
                }
            }
            for (i in lifted.indices) for (j in i + 1 until lifted.size) {
                assertExpr(IntCompare(lifted[i], IntCmpOp.NE, lifted[j]))
            }
        }

        /**
         * Lower [CircuitExpr] / [SubcircuitExpr] to its native factor. Each `succ` term must
         * lift to a bare [IntRef]. When [valueOffset] is nonzero (e.g. 1 for FlatZinc-style
         * 1-indexed inputs), aux 0-indexed int vars are allocated and channeled to the original
         * vars via a Linear factor — the factor itself stays 0-indexed.
         */
        private fun assertCircuit(succ: List<IntExpr>, valueOffset: Int, sub: Boolean) {
            val n = succ.size
            val lifted = succ.map { lift(it) }
            require(lifted.all { it is IntRef }) {
                "${if (sub) "subcircuit" else "circuit"}: every successor term must be a bare " +
                    "variable reference (no arithmetic). Got ${lifted.map { it::class.simpleName }}."
            }
            val srcIds = IntArray(n) { intVarOf((lifted[it] as IntRef).name) }
            val ids = if (valueOffset == 0) srcIds else IntArray(n) { i ->
                // Channel: aux = src - valueOffset, with aux ∈ [0, n − 1].
                val auxId = newIntVar(IntDomain(0, n - 1))
                factors += Linear(
                    coeffs = intArrayOf(1, -1),
                    vars = intArrayOf(srcIds[i], auxId),
                    op = LinearOp.EQ,
                    bound = valueOffset,
                )
                auxId
            }
            factors += if (sub) SubcircuitFactor(succ = ids) else CircuitFactor(succ = ids)
        }

        private fun assertCumulative(expr: CumulativeExpr) {
            val lifted = expr.starts.map { lift(it) }
            require(lifted.all { it is IntRef }) {
                "cumulative: every start term must be a bare variable reference (no arithmetic)."
            }
            val ids = IntArray(lifted.size) { intVarOf((lifted[it] as IntRef).name) }
            factors += CumulativeFactor(
                starts = ids,
                durations = expr.durations.toIntArray(),
                resources = expr.resources.toIntArray(),
                capacity = expr.capacity,
            )
        }

        private fun assertDisjunctive(expr: DisjunctiveExpr) {
            val lifted = expr.starts.map { lift(it) }
            require(lifted.all { it is IntRef }) {
                "disjunctive: every start term must be a bare variable reference (no arithmetic)."
            }
            val ids = IntArray(lifted.size) { intVarOf((lifted[it] as IntRef).name) }
            factors += DisjunctiveFactor(
                starts = ids,
                durations = expr.durations.toIntArray(),
            )
        }

        fun assertIntCompare(expr: IntCompare) {
            val (op, normBound) = normalize(expr.op, 0)
            val combined = subtract(affine(lift(expr.left)), affine(lift(expr.right)))
            val coeffs = combined.coeffs
            val bound = normBound - combined.constant
            emitTopLevelCmp(coeffs, op, bound)
        }

        private fun emitTopLevelCmp(
            coeffs: Map<String, Int>,
            op: IntCmpOp,
            bound: Int,
        ) {
            if (coeffs.isEmpty()) {
                // 0 op bound: trivially true or false at compile time.
                val holds = when (op) {
                    IntCmpOp.LE -> 0 <= bound
                    IntCmpOp.GE -> 0 >= bound
                    IntCmpOp.EQ -> 0 == bound
                    IntCmpOp.NE -> 0 != bound
                    IntCmpOp.LT, IntCmpOp.GT -> error("LT/GT should have been normalized away")
                }
                if (!holds) {
                    throw IllegalStateException(
                        "Constraint reduces to a constant-false comparison ($op against $bound) " +
                            "and is unsatisfiable as written."
                    )
                }
                return
            }
            if (coeffs.size == 1) {
                val (name, c) = coeffs.entries.first()
                emitSingleVar(name, c, op, bound)
                return
            }
            val (varIds, coeffArr) = coeffsToArrays(coeffs)
            when (op) {
                IntCmpOp.LE -> factors += Linear(coeffArr, varIds, LinearOp.LE, bound)
                IntCmpOp.GE -> factors += Linear(coeffArr, varIds, LinearOp.GE, bound)
                IntCmpOp.EQ -> factors += Linear(coeffArr, varIds, LinearOp.EQ, bound)
                IntCmpOp.NE -> {
                    // Reify equality and negate: aux ↔ Σ = bound; assert ¬aux.
                    val aux = newBoolVar()
                    factors += ReifiedLinear(aux, coeffArr, varIds, LinearOp.EQ, bound)
                    factors += Clause(intArrayOf(Lit.make(aux, positive = false)))
                }
                IntCmpOp.LT, IntCmpOp.GT -> error("LT/GT should have been normalized away")
            }
        }

        private fun emitSingleVar(
            name: String,
            coeff: Int,
            op: IntCmpOp,
            bound: Int,
        ) {
            // Σ c x ⟨op⟩ b reduces to x ⟨op'⟩ b/c (assuming exact division). Avoid the division
            // by lowering through the Linear factor when c isn't ±1.
            if (coeff == 1) {
                emitSingleVarCanonical(name, op, bound)
                return
            }
            if (coeff == -1) {
                // -x op b ⟺ x op' -b with op flipped (LE↔GE etc).
                val flipped = when (op) {
                    IntCmpOp.LE -> IntCmpOp.GE
                    IntCmpOp.GE -> IntCmpOp.LE
                    IntCmpOp.EQ -> IntCmpOp.EQ
                    IntCmpOp.NE -> IntCmpOp.NE
                    IntCmpOp.LT, IntCmpOp.GT -> error("normalized away")
                }
                emitSingleVarCanonical(name, flipped, -bound)
                return
            }
            // All coeffs: emit as a single-term Linear.
            val varId = intVarOf(name)
            factors += Linear(intArrayOf(coeff), intArrayOf(varId), op.toLinearOp(), bound)
        }

        private fun emitSingleVarCanonical(
            name: String, op: IntCmpOp, bound: Int,
        ) {
            val v = intVarOf(name)
            factors += Linear(intArrayOf(1), intArrayOf(v), op.toLinearOp(), bound)
        }

        private fun IntCmpOp.toLinearOp(): LinearOp = when (this) {
            IntCmpOp.LE -> LinearOp.LE
            IntCmpOp.GE -> LinearOp.GE
            IntCmpOp.EQ -> LinearOp.EQ
            IntCmpOp.NE -> LinearOp.NE
            IntCmpOp.LT, IntCmpOp.GT -> error("normalized away")
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
            is AllDifferent -> {
                val lifted = expr.terms.map { lift(it) }
                val pairs = mutableListOf<BoolExpr>()
                for (i in lifted.indices) for (j in i + 1 until lifted.size) {
                    pairs += IntCompare(lifted[i], IntCmpOp.NE, lifted[j])
                }
                tseitinAnd(pairs)
            }
            is CircuitExpr, is SubcircuitExpr, is CumulativeExpr, is DisjunctiveExpr ->
                error("${expr::class.simpleName} at non-top-level position is not yet supported; " +
                    "circuit / cumulative / disjunctive can only appear as top-level constraints today.")
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

        private fun reifySingleVar(name: String, coeff: Int, op: IntCmpOp, bound: Int): Int {
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

        /**
         * Rewrite [expr] so that the residual is affine: every non-affine subexpression is
         * replaced by a fresh aux [IntRef], with auxiliary constraints emitted that pin the
         * aux to the correct value. The affine fragment ([IntRef], [IntLit], [IntScale],
         * [IntSum]) passes through unchanged.
         */
        fun lift(expr: IntExpr): IntExpr = when (expr) {
            is IntRef, is IntLit -> expr
            is IntScale -> IntScale(expr.coeff, lift(expr.child))
            is IntSum -> IntSum(expr.children.map { lift(it) })
            is IntMin -> liftMinMax(expr.children, isMin = true)
            is IntMax -> liftMinMax(expr.children, isMin = false)
            is IntAbs -> liftAbs(expr.child)
            is IntIfThenElse -> liftIfThenElse(expr.cond, expr.thenE, expr.elseE)
            is IntElement -> liftElement(expr.index, expr.items)
            is IntMul -> liftMul(expr.left, expr.right)
            is IntDiv -> liftDivMod(expr.num, expr.den, returnRemainder = false)
            is IntMod -> liftDivMod(expr.num, expr.den, returnRemainder = true)
        }

        /**
         * Lower `n div d` and `n mod d` together with Euclidean semantics (matching
         * SMT-LIB QF_LIA):
         *
         *   q * d + r = n,    0 ≤ r < |d|,    d ≠ 0.
         *
         * The remainder is always non-negative regardless of the signs of `n` and `d`.
         * For example `(-7) mod 3 = 2` (with `q = -3`) and `(-7) mod (-3) = 2` (with
         * `q = 3`). This disagrees with `kotlin.Int.rem` and Java's `%` operator, which
         * keep the sign of the dividend; callers porting from Java-style semantics need
         * to adjust.
         */
        private fun liftDivMod(num: IntExpr, den: IntExpr, returnRemainder: Boolean): IntExpr {
            val nLifted = lift(num)
            val dLifted = lift(den)
            val nDom = domainOf(nLifted)
            val dDom = domainOf(dLifted)
            require(0 !in dDom) { "div/mod requires denominator domain to exclude 0; got $dDom" }
            val nRef = materializeIntVar(nLifted)
            val dRef = materializeIntVar(dLifted)

            val nAbsMax = maxOf(if (nDom.min < 0) -nDom.min else nDom.min, nDom.max)
            val dAbsMax = maxOf(if (dDom.min < 0) -dDom.min else dDom.min, dDom.max)
            val qDomain = IntDomain(-nAbsMax, nAbsMax)
            val rDomain = IntDomain(0, dAbsMax - 1)
            val qName = newAuxIntVar(qDomain)
            val rName = newAuxIntVar(rDomain)
            val dqAbsMaxLong = nAbsMax.toLong() * dAbsMax + dAbsMax
            require(dqAbsMaxLong <= Int.MAX_VALUE) {
                "div/mod intermediate domain overflows Int: |q*d| up to $dqAbsMaxLong " +
                    "(numerator domain $nDom, denominator domain $dDom)"
            }
            val dqAbsMax = dqAbsMaxLong.toInt()
            val dqDomain = IntDomain(-dqAbsMax, dqAbsMax)
            val dqName = newAuxIntVar(dqDomain)
            factors += Product(intVarOf(dRef.name), intVarOf(qName), intVarOf(dqName))

            // dq + r = n.
            assertExpr(
                IntCompare(IntSum(listOf(IntRef(dqName), IntRef(rName))), IntCmpOp.EQ, nRef),
            )
            // r < |d|. The rDomain pin already enforces r ≥ 0.
            assertExpr(
                IntCompare(IntRef(rName), IntCmpOp.LT, IntAbs(dRef)),
            )
            // d ≠ 0 is required regardless of domain; handled by the require above (0 ∉ dDom).

            return if (returnRemainder) IntRef(rName) else IntRef(qName)
        }

        private fun liftMul(left: IntExpr, right: IntExpr): IntExpr {
            val l = lift(left); val r = lift(right)
            // Constant folding: const * x or x * const → IntScale.
            if (l is IntLit) return IntScale(l.value, r)
            if (r is IntLit) return IntScale(r.value, l)
            val aRef = materializeIntVar(l)
            val bRef = materializeIntVar(r)
            val aDom = intDomains[intVarOf(aRef.name)]
            val bDom = intDomains[intVarOf(bRef.name)]
            val cornersLong = longArrayOf(
                aDom.min.toLong() * bDom.min, aDom.min.toLong() * bDom.max,
                aDom.max.toLong() * bDom.min, aDom.max.toLong() * bDom.max,
            )
            val pMin = cornersLong.min()
            val pMax = cornersLong.max()
            require(pMin >= Int.MIN_VALUE && pMax <= Int.MAX_VALUE) {
                "IntMul product domain overflows Int: $aDom * $bDom = [$pMin, $pMax]"
            }
            val productDomain = IntDomain(pMin.toInt(), pMax.toInt())
            val resultName = newAuxIntVar(productDomain)
            factors += Product(intVarOf(aRef.name), intVarOf(bRef.name), intVarOf(resultName))
            return IntRef(resultName)
        }

        /** Force [expr] into a single [IntRef] so a factor that takes raw int var ids (like
         *  [Product]) can reference it. Affine `IntScale`/`IntSum` get pinned to a fresh aux. */
        private fun materializeIntVar(expr: IntExpr): IntRef = when (expr) {
            is IntRef -> expr
            else -> {
                val d = domainOf(expr)
                val name = newAuxIntVar(d)
                val ref = IntRef(name)
                assertExpr(IntCompare(ref, IntCmpOp.EQ, expr))
                ref
            }
        }

        private fun liftElement(index: IntExpr, items: List<IntExpr>): IntExpr {
            val idxLifted = lift(index)
            val itemsLifted = items.map { lift(it) }
            val itemDoms = itemsLifted.map { domainOf(it) }
            val auxDomain = IntDomain(itemDoms.minOf { it.min }, itemDoms.maxOf { it.max })
            val auxName = newAuxIntVar(auxDomain)
            val auxRef = IntRef(auxName)
            val idxDom = domainOf(idxLifted)
            // For each j the index could take, link the aux to items[j] when index = j; for
            // out-of-bounds j, force index ≠ j.
            for (j in idxDom.min..idxDom.max) {
                if (j in items.indices) {
                    assertExpr(
                        Implies(
                            IntCompare(idxLifted, IntCmpOp.EQ, IntLit(j)),
                            IntCompare(auxRef, IntCmpOp.EQ, itemsLifted[j]),
                        ),
                    )
                } else {
                    assertExpr(IntCompare(idxLifted, IntCmpOp.NE, IntLit(j)))
                }
            }
            return auxRef
        }

        private fun liftIfThenElse(cond: BoolExpr, thenE: IntExpr, elseE: IntExpr): IntExpr {
            val tLifted = lift(thenE)
            val eLifted = lift(elseE)
            val tDom = domainOf(tLifted)
            val eDom = domainOf(eLifted)
            val auxName = newAuxIntVar(IntDomain(minOf(tDom.min, eDom.min), maxOf(tDom.max, eDom.max)))
            val auxRef = IntRef(auxName)
            // cond ⇒ aux = thenE; ¬cond ⇒ aux = elseE.
            assertExpr(Implies(cond, IntCompare(auxRef, IntCmpOp.EQ, tLifted)))
            assertExpr(Implies(Not(cond), IntCompare(auxRef, IntCmpOp.EQ, eLifted)))
            return auxRef
        }

        private fun liftMinMax(children: List<IntExpr>, isMin: Boolean): IntExpr {
            val lifted = children.map { lift(it) }
            val doms = lifted.map { domainOf(it) }
            val auxDomain = if (isMin) {
                IntDomain(doms.minOf { it.min }, doms.minOf { it.max })
            } else {
                IntDomain(doms.maxOf { it.min }, doms.maxOf { it.max })
            }
            val auxName = newAuxIntVar(auxDomain)
            val auxRef = IntRef(auxName)
            val op = if (isMin) IntCmpOp.LE else IntCmpOp.GE
            for (c in lifted) assertExpr(IntCompare(auxRef, op, c))
            val orChildren = lifted.map { IntCompare(auxRef, IntCmpOp.EQ, it) as BoolExpr }
            assertExpr(if (orChildren.size == 1) orChildren[0] else Or(orChildren))
            return auxRef
        }

        private fun liftAbs(child: IntExpr): IntExpr {
            val lifted = lift(child)
            val d = domainOf(lifted)
            val absMax = maxOf(if (d.min < 0) -d.min else d.min, if (d.max < 0) -d.max else d.max)
            val auxName = newAuxIntVar(IntDomain(0, absMax))
            val auxRef = IntRef(auxName)
            // z >= 0; z >= x; z >= -x; (z = x) ∨ (z = -x).
            assertExpr(IntCompare(auxRef, IntCmpOp.GE, IntLit(0)))
            assertExpr(IntCompare(auxRef, IntCmpOp.GE, lifted))
            assertExpr(IntCompare(auxRef, IntCmpOp.GE, IntScale(-1, lifted)))
            assertExpr(
                Or(listOf(
                    IntCompare(auxRef, IntCmpOp.EQ, lifted),
                    IntCompare(auxRef, IntCmpOp.EQ, IntScale(-1, lifted)),
                )),
            )
            return auxRef
        }

        private fun newAuxIntVar(domain: IntDomain): String {
            val name = "__aux_int_${auxIntCounter++}"
            intVarIdByName[name] = newIntVar(domain)
            return name
        }

        /** Domain of any [IntExpr] post-lift. The expression must reside in the affine
         *  fragment (caller is responsible for lifting non-affine subexpressions first). */
        private fun domainOf(expr: IntExpr): IntDomain = when (expr) {
            is IntRef -> intDomains[intVarOf(expr.name)]
            is IntLit -> IntDomain(expr.value, expr.value)
            is IntScale -> {
                val c = expr.coeff
                val d = domainOf(expr.child)
                if (c >= 0) IntDomain(c * d.min, c * d.max) else IntDomain(c * d.max, c * d.min)
            }
            is IntSum -> {
                var lo = 0; var hi = 0
                for (ch in expr.children) {
                    val d = domainOf(ch)
                    lo += d.min; hi += d.max
                }
                IntDomain(lo, hi)
            }
            else -> error("domainOf called on non-affine expression: $expr")
        }

        // Affine canonical form: Σ coeffs[name] * name + constant.
        private data class Affine(val coeffs: Map<String, Int>, val constant: Int)

        private fun affine(expr: IntExpr): Affine = when (expr) {
            is IntRef -> Affine(mapOf(expr.name to 1), 0)
            is IntLit -> Affine(emptyMap(), expr.value)
            is IntScale -> {
                val a = affine(expr.child)
                val coeffs = HashMap<String, Int>(a.coeffs.size)
                for ((k, v) in a.coeffs) coeffs[k] = v * expr.coeff
                Affine(coeffs, a.constant * expr.coeff)
            }
            is IntSum -> {
                val coeffs = HashMap<String, Int>()
                var constant = 0
                for (c in expr.children) {
                    val a = affine(c)
                    constant += a.constant
                    for ((k, v) in a.coeffs) coeffs[k] = (coeffs[k] ?: 0) + v
                }
                coeffs.entries.removeAll { it.value == 0 }
                Affine(coeffs, constant)
            }
            else -> error("affine() called on non-affine expression — caller must lift first: $expr")
        }

        private fun subtract(left: Affine, right: Affine): Affine {
            val coeffs = HashMap(left.coeffs)
            for ((k, v) in right.coeffs) coeffs[k] = (coeffs[k] ?: 0) - v
            coeffs.entries.removeAll { it.value == 0 }
            return Affine(coeffs, left.constant - right.constant)
        }

        private fun coeffsToArrays(coeffs: Map<String, Int>): Pair<IntArray, IntArray> {
            val varIds = IntArray(coeffs.size)
            val coeffArr = IntArray(coeffs.size)
            var i = 0
            for ((name, c) in coeffs) {
                varIds[i] = intVarOf(name)
                coeffArr[i] = c
                i++
            }
            return varIds to coeffArr
        }

        private fun trueLit(): Int {
            val v = newBoolVar()
            factors += Clause(intArrayOf(Lit.make(v, positive = true)))
            return Lit.make(v, positive = true)
        }

        private fun falseLit(): Int = Lit.negate(trueLit())
    }
}

fun VariableSchema.compile(): CompiledProblem = Compiler().compile(this.definition())
