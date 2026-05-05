package com.eignex.klause.compile

import com.eignex.klause.ast.AllDifferent
import com.eignex.klause.ast.And
import com.eignex.klause.ast.AtLeast
import com.eignex.klause.ast.AtMost
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
import com.eignex.klause.ast.IntElement
import com.eignex.klause.ast.IntIfThenElse
import com.eignex.klause.ast.IntMax
import com.eignex.klause.ast.IntMin
import com.eignex.klause.ast.IntRef
import com.eignex.klause.ast.IntScale
import com.eignex.klause.ast.IntSpec
import com.eignex.klause.ast.IntSum
import com.eignex.klause.ast.NominalEq
import com.eignex.klause.ast.NominalSpec
import com.eignex.klause.ast.Not
import com.eignex.klause.ast.Or
import com.eignex.klause.ast.SchemaDef
import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.factor.Clause
import com.eignex.klause.solver.factor.IntEq
import com.eignex.klause.solver.factor.IntGeq
import com.eignex.klause.solver.factor.IntLeq
import com.eignex.klause.solver.factor.IntNeq
import com.eignex.klause.solver.factor.Linear
import com.eignex.klause.solver.factor.LinearOp
import com.eignex.klause.solver.factor.ReifiedCardinality
import com.eignex.klause.solver.factor.ReifiedIntCompare
import com.eignex.klause.solver.factor.ReifiedLinear

class Compiler {

    fun compile(def: SchemaDef): CompiledProblem = Build().run(def)

    private class Build {
        val factors = mutableListOf<Factor>()
        val boolVarIdByName = mutableMapOf<String, Int>()
        val intVarIdByName = mutableMapOf<String, Int>()
        val intDomains = mutableListOf<IntDomain>()
        val nominalIndicators = mutableMapOf<String, Map<String, Int>>()
        val floatDecoders = mutableMapOf<String, FloatSpec>()
        var numBoolVars = 0
        var numIntVars = 0
        private var auxIntCounter = 0

        fun run(def: SchemaDef): CompiledProblem {
            for (spec in def.vars) {
                when (spec) {
                    is BoolSpec -> boolVarIdByName[spec.name] = newBoolVar()
                    is NominalSpec -> {
                        val ids = LinkedHashMap<String, Int>()
                        for (label in spec.labels) ids[label] = newBoolVar()
                        nominalIndicators[spec.name] = ids
                        val lits = IntArray(ids.size)
                        var i = 0
                        for (id in ids.values) lits[i++] = Lit.make(id, positive = true)
                        factors += Cardinality.exactlyOne(lits)
                    }
                    is IntSpec -> intVarIdByName[spec.name] = newIntVar(IntDomain(spec.min, spec.max))
                    is FloatSpec -> {
                        intVarIdByName[spec.name] = newIntVar(IntDomain(0, spec.buckets - 1))
                        floatDecoders[spec.name] = spec
                    }
                }
            }

            for (nc in def.constraints) assertExpr(nc.expr, isHard = nc.isHard, weight = nc.weight)

            return CompiledProblem(
                problem = Problem(numBoolVars, numIntVars, intDomains.toTypedArray(), factors.toList()),
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

        fun assertExpr(expr: BoolExpr, isHard: Boolean, weight: Double) {
            when (expr) {
                is And -> for (c in expr.children) assertExpr(c, isHard, weight)
                is Implies -> assertExpr(Or(listOf(negate(expr.left), expr.right)), isHard, weight)
                is Iff -> {
                    assertExpr(Implies(expr.left, expr.right), isHard, weight)
                    assertExpr(Implies(expr.right, expr.left), isHard, weight)
                }
                is Or -> {
                    val lits = IntArray(expr.children.size)
                    for (i in expr.children.indices) lits[i] = lowerToLit(expr.children[i])
                    factors += Clause(lits, isHard, weight)
                }
                is AtMost -> {
                    val lits = lowerAllBool(expr.children)
                    factors += Cardinality(lits, 0, expr.k, isHard, weight)
                }
                is AtLeast -> {
                    val lits = lowerAllBool(expr.children)
                    factors += Cardinality(lits, expr.k, lits.size, isHard, weight)
                }
                is CardinalityExpr -> {
                    val lits = lowerAllBool(expr.children)
                    factors += Cardinality(lits, expr.min, expr.max, isHard, weight)
                }
                is Not, is BoolRef, is NominalEq -> {
                    factors += Clause(intArrayOf(lowerToLit(expr)), isHard, weight)
                }
                is IntCompare -> assertIntCompare(expr, isHard, weight)
                is AllDifferent -> assertAllDifferent(expr.terms, isHard, weight)
            }
        }

        private fun assertAllDifferent(terms: List<IntExpr>, isHard: Boolean, weight: Double) {
            val lifted = terms.map { lift(it) }
            for (i in lifted.indices) for (j in i + 1 until lifted.size) {
                assertExpr(IntCompare(lifted[i], IntCmpOp.NE, lifted[j]), isHard, weight)
            }
        }

        fun assertIntCompare(expr: IntCompare, isHard: Boolean, weight: Double) {
            val (op, normBound) = normalize(expr.op, 0)
            val combined = subtract(affine(lift(expr.left)), affine(lift(expr.right)))
            val coeffs = combined.coeffs
            val bound = normBound - combined.constant
            // Apply LT/GT bound shifts on top of the affine subtraction.
            val (finalOp, finalBound) = when (expr.op) {
                IntCmpOp.LT -> IntCmpOp.LE to (bound - 1)
                IntCmpOp.GT -> IntCmpOp.GE to (bound + 1)
                else -> op to bound
            }
            emitTopLevelCmp(coeffs, finalOp, finalBound, isHard, weight)
        }

        private fun emitTopLevelCmp(
            coeffs: Map<String, Int>,
            op: IntCmpOp,
            bound: Int,
            isHard: Boolean,
            weight: Double,
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
                if (!holds) factors += Clause(IntArray(0), isHard, weight)
                return
            }
            if (coeffs.size == 1) {
                val (name, c) = coeffs.entries.first()
                emitSingleVar(name, c, op, bound, isHard, weight)
                return
            }
            val (varIds, coeffArr) = coeffsToArrays(coeffs)
            when (op) {
                IntCmpOp.LE -> factors += Linear(coeffArr, varIds, LinearOp.LE, bound, isHard, weight)
                IntCmpOp.GE -> factors += Linear(coeffArr, varIds, LinearOp.GE, bound, isHard, weight)
                IntCmpOp.EQ -> factors += Linear(coeffArr, varIds, LinearOp.EQ, bound, isHard, weight)
                IntCmpOp.NE -> {
                    // Reify equality and negate: aux ↔ Σ = bound; assert ¬aux.
                    val aux = newBoolVar()
                    factors += ReifiedLinear(aux, coeffArr, varIds, LinearOp.EQ, bound, isHard, weight)
                    factors += Clause(intArrayOf(Lit.make(aux, positive = false)), isHard, weight)
                }
                IntCmpOp.LT, IntCmpOp.GT -> error("LT/GT should have been normalized away")
            }
        }

        private fun emitSingleVar(
            name: String,
            coeff: Int,
            op: IntCmpOp,
            bound: Int,
            isHard: Boolean,
            weight: Double,
        ) {
            // Σ c x ⟨op⟩ b reduces to x ⟨op'⟩ b/c (assuming exact division). Avoid the division
            // by lowering through the Linear factor when c isn't ±1.
            if (coeff == 1) {
                emitSingleVarCanonical(name, op, bound, isHard, weight)
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
                emitSingleVarCanonical(name, flipped, -bound, isHard, weight)
                return
            }
            // General coeff: emit as Linear over one variable.
            val varId = intVarOf(name)
            val linOp = when (op) {
                IntCmpOp.LE -> LinearOp.LE
                IntCmpOp.GE -> LinearOp.GE
                IntCmpOp.EQ -> LinearOp.EQ
                IntCmpOp.NE -> {
                    val aux = newBoolVar()
                    factors += ReifiedLinear(aux, intArrayOf(coeff), intArrayOf(varId), LinearOp.EQ, bound, isHard, weight)
                    factors += Clause(intArrayOf(Lit.make(aux, positive = false)), isHard, weight)
                    return
                }
                IntCmpOp.LT, IntCmpOp.GT -> error("normalized away")
            }
            factors += Linear(intArrayOf(coeff), intArrayOf(varId), linOp, bound, isHard, weight)
        }

        private fun emitSingleVarCanonical(
            name: String, op: IntCmpOp, bound: Int, isHard: Boolean, weight: Double,
        ) {
            val v = intVarOf(name)
            factors += when (op) {
                IntCmpOp.LE -> IntLeq(v, bound, isHard, weight)
                IntCmpOp.GE -> IntGeq(v, bound, isHard, weight)
                IntCmpOp.EQ -> IntEq(v, bound, isHard, weight)
                IntCmpOp.NE -> IntNeq(v, bound, isHard, weight)
                IntCmpOp.LT, IntCmpOp.GT -> error("normalized away")
            }
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
            val (finalOp, finalBound) = when (expr.op) {
                IntCmpOp.LT -> IntCmpOp.LE to (bound - 1)
                IntCmpOp.GT -> IntCmpOp.GE to (bound + 1)
                else -> op to bound
            }
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
            factors += ReifiedIntCompare(aux, v, effectiveOp, effectiveBound)
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
                        isHard = true, weight = 1.0,
                    )
                } else {
                    assertExpr(IntCompare(idxLifted, IntCmpOp.NE, IntLit(j)), isHard = true, weight = 1.0)
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
            assertExpr(Implies(cond, IntCompare(auxRef, IntCmpOp.EQ, tLifted)), isHard = true, weight = 1.0)
            assertExpr(Implies(Not(cond), IntCompare(auxRef, IntCmpOp.EQ, eLifted)), isHard = true, weight = 1.0)
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
            for (c in lifted) assertExpr(IntCompare(auxRef, op, c), isHard = true, weight = 1.0)
            val orChildren = lifted.map { IntCompare(auxRef, IntCmpOp.EQ, it) as BoolExpr }
            assertExpr(if (orChildren.size == 1) orChildren[0] else Or(orChildren), isHard = true, weight = 1.0)
            return auxRef
        }

        private fun liftAbs(child: IntExpr): IntExpr {
            val lifted = lift(child)
            val d = domainOf(lifted)
            val absMax = maxOf(if (d.min < 0) -d.min else d.min, if (d.max < 0) -d.max else d.max)
            val auxName = newAuxIntVar(IntDomain(0, absMax))
            val auxRef = IntRef(auxName)
            // z >= 0; z >= x; z >= -x; (z = x) ∨ (z = -x).
            assertExpr(IntCompare(auxRef, IntCmpOp.GE, IntLit(0)), isHard = true, weight = 1.0)
            assertExpr(IntCompare(auxRef, IntCmpOp.GE, lifted), isHard = true, weight = 1.0)
            assertExpr(IntCompare(auxRef, IntCmpOp.GE, IntScale(-1, lifted)), isHard = true, weight = 1.0)
            assertExpr(
                Or(listOf(
                    IntCompare(auxRef, IntCmpOp.EQ, lifted),
                    IntCompare(auxRef, IntCmpOp.EQ, IntScale(-1, lifted)),
                )),
                isHard = true,
                weight = 1.0,
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
