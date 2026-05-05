package com.eignex.klause.compile

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
import com.eignex.klause.ast.IntRef
import com.eignex.klause.ast.IntSpec
import com.eignex.klause.ast.LinearCmpOp
import com.eignex.klause.ast.LinearConstraint
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
import com.eignex.klause.solver.factor.ReifiedIntCompare

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
                is LinearConstraint -> assertLinear(expr, isHard, weight)
            }
        }

        fun assertIntCompare(expr: IntCompare, isHard: Boolean, weight: Double) {
            val left = expr.left
            val right = expr.right
            if (left is IntRef && right is IntLit) {
                val v = intVarOf(left.name)
                val (op, bound) = normalize(expr.op, right.value)
                factors += when (op) {
                    IntCmpOp.LE -> IntLeq(v, bound, isHard, weight)
                    IntCmpOp.GE -> IntGeq(v, bound, isHard, weight)
                    IntCmpOp.EQ -> IntEq(v, bound, isHard, weight)
                    IntCmpOp.NE -> IntNeq(v, bound, isHard, weight)
                    IntCmpOp.LT, IntCmpOp.GT -> error("normalize() should have rewritten LT/GT")
                }
                return
            }
            if (left is IntLit && right is IntRef) {
                assertIntCompare(IntCompare(right, swapOp(expr.op), left), isHard, weight)
                return
            }
            if (left is IntRef && right is IntRef) {
                val (op, bound) = toLinear(expr.op)
                val coeffs = intArrayOf(1, -1)
                val ids = intArrayOf(intVarOf(left.name), intVarOf(right.name))
                factors += Linear(coeffs, ids, op, bound, isHard, weight)
                return
            }
            error("Unsupported IntCompare shape: $expr")
        }

        fun assertLinear(expr: LinearConstraint, isHard: Boolean, weight: Double) {
            val ids = IntArray(expr.refs.size) { intVarOf(expr.refs[it]) }
            val coeffs = expr.coeffs.toIntArray()
            val op = when (expr.op) {
                LinearCmpOp.LE -> LinearOp.LE
                LinearCmpOp.EQ -> LinearOp.EQ
                LinearCmpOp.GE -> LinearOp.GE
            }
            factors += Linear(coeffs, ids, op, expr.bound, isHard, weight)
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
            is LinearConstraint -> error("Reified linear constraints are not yet supported (Phase B-1)")
            is AtMost, is AtLeast, is CardinalityExpr ->
                error("Nested cardinality expressions are not yet supported (Phase A)")
        }

        fun reifyIntCompare(expr: IntCompare): Int {
            val left = expr.left
            val right = expr.right
            if (left is IntRef && right is IntLit) {
                val v = intVarOf(left.name)
                val aux = newBoolVar()
                val (op, bound) = normalize(expr.op, right.value)
                factors += ReifiedIntCompare(aux, v, op, bound)
                return Lit.make(aux, positive = true)
            }
            if (left is IntLit && right is IntRef) {
                return reifyIntCompare(IntCompare(right, swapOp(expr.op), left))
            }
            error("Reified IntCompare currently supports only IntRef vs IntLit; got $expr")
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

        fun swapOp(op: IntCmpOp): IntCmpOp = when (op) {
            IntCmpOp.LE -> IntCmpOp.GE
            IntCmpOp.LT -> IntCmpOp.GT
            IntCmpOp.GE -> IntCmpOp.LE
            IntCmpOp.GT -> IntCmpOp.LT
            IntCmpOp.EQ -> IntCmpOp.EQ
            IntCmpOp.NE -> IntCmpOp.NE
        }

        fun toLinear(op: IntCmpOp): Pair<LinearOp, Int> = when (op) {
            IntCmpOp.LE -> LinearOp.LE to 0
            IntCmpOp.LT -> LinearOp.LE to -1
            IntCmpOp.GE -> LinearOp.GE to 0
            IntCmpOp.GT -> LinearOp.GE to 1
            IntCmpOp.EQ -> LinearOp.EQ to 0
            IntCmpOp.NE -> error("Linear constraints cannot directly express '!=' (var-vs-var)")
        }

        fun intVarOf(name: String): Int =
            intVarIdByName[name] ?: error("Unknown int/float variable '$name'")
    }
}

fun VariableSchema.compile(): CompiledProblem = Compiler().compile(this.definition())
