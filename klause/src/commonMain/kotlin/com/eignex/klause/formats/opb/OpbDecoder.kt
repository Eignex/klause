package com.eignex.klause.formats.opb

import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.factor.arithmetic.ReifiedPseudoBoolean
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.ir.BoolFoldDefinition
import com.eignex.klause.lowering.CnfLowering
import com.eignex.klause.lowering.ProblemBuilder
import com.eignex.klause.lowering.channelBoolTo01
import com.eignex.klause.lowering.tseitinAnd
import com.eignex.klause.ir.LinearOp
import com.eignex.klause.ir.Lit
import com.eignex.klause.model.PbOp
import com.eignex.klause.solver.IntDomain
import com.eignex.klause.solver.objective.LinearObjective
import com.eignex.klause.util.EmptyLongArray
import com.eignex.klause.util.IntArrayList
import com.eignex.klause.util.LongArrayList
import com.eignex.klause.util.MutableIntLongMap
import com.ionspin.kotlin.bignum.integer.BigInteger

private val longMinBig = BigInteger.fromLong(Long.MIN_VALUE)
private val longMaxBig = BigInteger.fromLong(Long.MAX_VALUE)
private fun BigInteger.fitsLong(): Boolean = this in longMinBig..longMaxBig

/** Decodes an OPB syntax document into solver data. */
internal object OpbDecoder {

    fun decode(document: OpbDocument): OpbProblem {
        val builder = Builder(document.numDeclaredVars)
        val objectiveWeights = MutableIntLongMap()
        var objectiveConstant = 0L
        var hasObjective = false
        var softTop: Long? = null
        val softCosts = LongArrayList()
        val softViolations = IntArrayList()

        for (statement in document.statements) {
            when (statement) {
                is OpbStatement.Objective -> {
                    hasObjective = true
                    for (term in statement.terms) {
                        addObjectiveTerm(
                            objectiveWeights,
                            requireLong(term.coefficient, "objective coefficient"),
                            builder.literalFor(term),
                        ) {
                            objectiveConstant += it
                        }
                    }
                }

                is OpbStatement.SoftHeader -> softTop = statement.top

                is OpbStatement.Constraint -> {
                    val relation = lowerRelation(builder, statement.relation)
                    when {
                        relation.wide && statement.softCost != null ->
                            throw OpbFormatException(
                                "OPB wide coefficients in a soft constraint are not supported",
                            )

                        relation.wide -> {
                            val ints = IntArray(relation.literals.size) { index ->
                                val literal = relation.literals[index]
                                val intVar = builder.newBinaryIntVar()
                                channelBoolTo01(
                                    builder.factors,
                                    Lit.variable(literal),
                                    intVar,
                                    Lit.isPositive(literal),
                                )
                                intVar
                            }
                            builder.factors += Linear(
                                ints,
                                relation.weights,
                                toLinearOp(relation.op),
                                relation.bound,
                            )
                        }

                        statement.softCost == null ->
                            builder.factors +=
                                PseudoBoolean(
                                    relation.longWeights(),
                                    relation.literals,
                                    relation.op,
                                    relation.longBound(),
                                )

                        else -> {
                            val sat = builder.newBool()
                            builder.factors += ReifiedPseudoBoolean(
                                sat,
                                relation.longWeights(),
                                relation.literals,
                                relation.op,
                                relation.longBound(),
                            )
                            hasObjective = true
                            objectiveWeights.addTo(sat, -statement.softCost)
                            objectiveConstant += statement.softCost
                            softCosts.add(statement.softCost)
                            softViolations.add(Lit.make(sat, positive = false))
                        }
                    }
                }
            }
        }

        softTop?.let { top ->
            if (softViolations.size > 0) {
                if (top == Long.MIN_VALUE) throw OpbFormatException("OPB soft top is too small: $top")
                builder.factors += PseudoBoolean(
                    softCosts.toLongArray(),
                    softViolations.toIntArray(),
                    PbOp.LE,
                    top - 1,
                )
            }
        }

        val objective = if (hasObjective) {
            LinearObjective(
                boolWeights = LongArray(
                    builder.numBoolVars,
                ).also { weights ->
                    objectiveWeights.forEach { v, w -> weights[v] = w }
                },
                intCoefficients = EmptyLongArray,
                constant = objectiveConstant,
            )
        } else {
            null
        }
        return OpbProblem(builder.build(), objective, builder.boolFolds, document.numDeclaredVars)
    }

    private fun lowerRelation(builder: Builder, relation: OpbRelation): Relation {
        val weights = ArrayList<BigInteger>(relation.terms.size)
        val literals = IntArrayList()
        for (term in relation.terms) {
            weights += term.coefficient
            literals.add(builder.literalFor(term))
        }
        return Relation(weights.toTypedArray(), literals.toIntArray(), relation.op, relation.bound)
    }

    private class Relation(
        val weights: Array<BigInteger>,
        val literals: IntArray,
        val op: PbOp,
        val bound: BigInteger,
    ) {
        val wide: Boolean get() = !bound.fitsLong() || weights.any { !it.fitsLong() }
        fun longWeights(): LongArray = LongArray(weights.size) { weights[it].longValue() }
        fun longBound(): Long = bound.longValue()
    }

    private class Builder(numDeclaredVars: Int) : CnfLowering {
        private val problemBuilder = ProblemBuilder()

        override val factors get() = problemBuilder.factors
        override var trueLitCache: Int
            get() = problemBuilder.trueLitCache
            set(value) {
                problemBuilder.trueLitCache = value
            }
        val numBoolVars get() = problemBuilder.numBoolVars
        val boolFolds = ArrayList<BoolFoldDefinition>()
        private val productCache = HashMap<List<Int>, Int>()

        init {
            problemBuilder.reserveBoolVars(numDeclaredVars)
            for (id in 0 until numDeclaredVars) {
                problemBuilder.bindBoolName("x${id + 1}", id)
            }
        }

        override fun newBool(): Int = problemBuilder.newBool()

        fun newBinaryIntVar(): Int = problemBuilder.newInt(IntDomain(0, 1))

        fun literalFor(term: OpbTerm): Int {
            if (term.literals.size == 1) return term.literals[0]
            val key = term.literals.sorted()
            return productCache.getOrPut(key) {
                val indicator = tseitinAnd(key)
                boolFolds += BoolFoldDefinition(
                    Lit.variable(indicator),
                    key.toIntArray(),
                    isAnd = true,
                )
                indicator
            }
        }

        fun build() = problemBuilder.build()
    }

    private inline fun addObjectiveTerm(
        weights: MutableIntLongMap,
        weight: Long,
        literal: Int,
        addConstant: (Long) -> Unit,
    ) {
        if (Lit.isPositive(literal)) {
            weights.addTo(Lit.variable(literal), weight)
        } else {
            weights.addTo(Lit.variable(literal), -weight)
            addConstant(weight)
        }
    }

    private fun requireLong(value: BigInteger, role: String): Long {
        if (!value.fitsLong()) {
            throw OpbFormatException("OPB $role exceeds the supported 64-bit range: '$value'")
        }
        return value.longValue()
    }

    private fun toLinearOp(op: PbOp): LinearOp = when (op) {
        PbOp.LE -> LinearOp.LE
        PbOp.GE -> LinearOp.GE
        PbOp.EQ -> LinearOp.EQ
    }
}
