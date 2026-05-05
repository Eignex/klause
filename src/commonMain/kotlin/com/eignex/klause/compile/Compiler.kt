package com.eignex.klause.compile

import com.eignex.klause.ast.And
import com.eignex.klause.ast.AtLeast
import com.eignex.klause.ast.AtMost
import com.eignex.klause.ast.BoolExpr
import com.eignex.klause.ast.BoolRef
import com.eignex.klause.ast.BoolSpec
import com.eignex.klause.ast.CardinalityExpr
import com.eignex.klause.ast.Iff
import com.eignex.klause.ast.Implies
import com.eignex.klause.ast.NominalEq
import com.eignex.klause.ast.NominalSpec
import com.eignex.klause.ast.Not
import com.eignex.klause.ast.Or
import com.eignex.klause.ast.SchemaDef
import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.solver.Factor
import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.Problem
import com.eignex.klause.solver.factor.Cardinality
import com.eignex.klause.solver.factor.Clause

class Compiler {

    fun compile(def: SchemaDef): CompiledProblem = Build().run(def)

    private class Build {
        val factors = mutableListOf<Factor>()
        val varIdByName = mutableMapOf<String, Int>()
        val nominalIndicators = mutableMapOf<String, Map<String, Int>>()
        var numVars = 0

        fun run(def: SchemaDef): CompiledProblem {
            for (spec in def.vars) {
                when (spec) {
                    is BoolSpec -> varIdByName[spec.name] = newVar()
                    is NominalSpec -> {
                        val ids = LinkedHashMap<String, Int>()
                        for (label in spec.labels) ids[label] = newVar()
                        nominalIndicators[spec.name] = ids
                        val lits = IntArray(ids.size)
                        var i = 0
                        for (id in ids.values) lits[i++] = Lit.make(id, positive = true)
                        factors += Cardinality.exactlyOne(lits)
                    }
                }
            }

            for (nc in def.constraints) {
                assertExpr(nc.expr, isHard = nc.isHard, weight = nc.weight)
            }

            return CompiledProblem(
                problem = Problem(numVars, factors.toList()),
                varIdByName = varIdByName.toMap(),
                nominalIndicators = nominalIndicators.mapValues { it.value.toMap() },
            )
        }

        fun newVar(): Int = numVars++

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
                    val lits = lowerAll(expr.children)
                    factors += Cardinality(lits, 0, expr.k, isHard, weight)
                }
                is AtLeast -> {
                    val lits = lowerAll(expr.children)
                    factors += Cardinality(lits, expr.k, lits.size, isHard, weight)
                }
                is CardinalityExpr -> {
                    val lits = lowerAll(expr.children)
                    factors += Cardinality(lits, expr.min, expr.max, isHard, weight)
                }
                is Not, is BoolRef, is NominalEq -> {
                    factors += Clause(intArrayOf(lowerToLit(expr)), isHard, weight)
                }
            }
        }

        fun lowerAll(children: List<BoolExpr>): IntArray {
            val lits = IntArray(children.size)
            for (i in children.indices) lits[i] = lowerToLit(children[i])
            return lits
        }

        fun lowerToLit(expr: BoolExpr): Int = when (expr) {
            is BoolRef -> {
                val id = varIdByName[expr.name] ?: error("Unknown Boolean variable '${expr.name}'")
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
            is AtMost, is AtLeast, is CardinalityExpr ->
                error("Nested cardinality expressions are not yet supported (Phase A)")
        }

        fun tseitinAnd(children: List<BoolExpr>): Int {
            val aux = newVar()
            val auxLit = Lit.make(aux, true)
            val childLits = lowerAll(children)
            for (cl in childLits) factors += Clause(intArrayOf(Lit.negate(auxLit), cl))
            val big = IntArray(childLits.size + 1)
            big[0] = auxLit
            for (i in childLits.indices) big[i + 1] = Lit.negate(childLits[i])
            factors += Clause(big)
            return auxLit
        }

        fun tseitinOr(children: List<BoolExpr>): Int {
            val aux = newVar()
            val auxLit = Lit.make(aux, true)
            val childLits = lowerAll(children)
            for (cl in childLits) factors += Clause(intArrayOf(Lit.negate(cl), auxLit))
            val big = IntArray(childLits.size + 1)
            big[0] = Lit.negate(auxLit)
            for (i in childLits.indices) big[i + 1] = childLits[i]
            factors += Clause(big)
            return auxLit
        }

        fun tseitinIff(l: Int, r: Int): Int {
            val aux = newVar()
            val auxLit = Lit.make(aux, true)
            // aux ↔ (l ↔ r):
            //   aux → (l → r):   ¬aux ∨ ¬l ∨ r
            //   aux → (r → l):   ¬aux ∨ ¬r ∨ l
            //   ¬aux → ¬(l ↔ r): aux ∨ l ∨ r   AND   aux ∨ ¬l ∨ ¬r
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
    }
}

fun VariableSchema.compile(): CompiledProblem = Compiler().compile(this.definition())
