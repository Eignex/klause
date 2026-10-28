package com.eignex.klause.theory.qfnia

import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialLimits
import com.eignex.klause.ir.NonlinearIntegerProblem
import com.eignex.klause.ir.PolynomialExpression
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.ir.PolynomialRelation
import com.eignex.klause.util.BIG_ONE
import com.eignex.klause.util.BigInt
import com.eignex.klause.util.bigIntOf
import com.eignex.klause.util.div
import com.eignex.klause.util.gcd
import com.eignex.klause.util.magnitudeBitLength
import com.eignex.klause.util.times
import com.eignex.klause.util.toLong
import com.eignex.klause.util.rem as integerRemainder

internal data class NonlinearIntegerResidueCounts(
    val rings: Long = 0,
    val tuples: Long = 0,
    val work: Long = 0,
    val refuted: Long = 0,
    val declined: Long = 0,
) {
    operator fun plus(other: NonlinearIntegerResidueCounts): NonlinearIntegerResidueCounts =
        NonlinearIntegerResidueCounts(rings + other.rings, tuples + other.tuples, work + other.work,
            refuted + other.refuted, declined + other.declined)
}

// Every node denotes an integer numerator divided by its positive, assignment-independent denominator.
// Clearing at each edge preserves a polynomial circuit without expansion or division in the residue ring.
internal class NonlinearIntegerResidues(
    private val problem: NonlinearIntegerProblem,
    private val arithmetic: PolynomialArithmetic,
    limits: PolynomialLimits,
) {
    private val capacity = minOf(MAX_CELLS, limits.maxDenseCoefficients ?: MAX_CELLS,
        (limits.maxMatrixCells ?: MAX_CELLS.toLong()).coerceAtMost(MAX_CELLS.toLong()).toInt())
    private val nodes = ArrayList<ResidueNode>()
    private val indices = HashMap<ResidueExpressionKey, Int>()
    private val variables = LinkedHashMap<Int, Int>()
    private val equations = ArrayList<Pair<Int, Int>>()
    private var multivariate = false
    private var cells = 0
    var stats = NonlinearIntegerResidueCounts()
        private set

    fun refutes(): Boolean = try {
        prepare()
        if (!multivariate) false else {
            reserve(nodes.size * 3 + nodes.sumOf { it.args.size } + equations.size * 2 + variables.size)
            var refuted = false
            for (imagesOnly in listOf(true, false)) {
                for (modulus in MODULI) {
                    step()
                    if (!hasSupport(modulus, imagesOnly)) {
                        // A final containing poll is required before publishing a completed negative fact.
                        step()
                        stats = stats.copy(refuted = stats.refuted + 1)
                        refuted = true
                        break
                    }
                }
                if (refuted) break
            }
            refuted
        }
    } catch (_: ResidueDecline) {
        stats = stats.copy(declined = stats.declined + 1)
        false
    } finally {
        nodes.clear()
        indices.clear()
        variables.clear()
        equations.clear()
    }

    private fun step() {
        arithmetic.step()
        stats = stats.copy(work = stats.work + 1)
        if (stats.work >= MAX_WORK) throw ResidueDecline()
    }

    private fun reserve(count: Int) {
        step()
        if (count > capacity - cells) throw ResidueDecline()
        cells += count
    }

    private fun prepare() {
        val pending = ArrayDeque<PolynomialRealFormula>()
        reserve(1)
        pending.addLast(problem.formula)
        reserve(problem.integerCoordinates.size)
        val integers = problem.integerCoordinates.map { step(); it }.toSet()
        while (pending.isNotEmpty()) {
            step()
            when (val formula = pending.removeLast()) {
                is PolynomialRealFormula.And -> {
                    reserve(formula.operandCount)
                    val children = formula.operands
                    pending.addAll(children)
                }
                is PolynomialRealFormula.Atom -> if (formula.relation == PolynomialRelation.EQ) {
                    val left = compile(formula.left, integers)
                    val right = compile(formula.right, integers)
                    multivariate = multivariate ||
                        minOf(nodes[left].firstVariable, nodes[right].firstVariable) <
                        maxOf(nodes[left].lastVariable, nodes[right].lastVariable)
                    reserve(2)
                    equations.add(left to right)
                }
                else -> Unit
            }
        }
    }

    private fun compile(root: PolynomialExpression, integers: Set<Int>): Int {
        val pending = ArrayDeque<Pair<PolynomialExpression, Boolean>>()
        reserve(1)
        pending.addLast(root to false)
        while (pending.isNotEmpty()) {
            step()
            val (expression, combine) = pending.removeLast()
            if (expression is PolynomialExpression.Constant) {
                chargeBits(expression.numerator)
                chargeBits(expression.denominator)
            }
            val key = ResidueExpressionKey(expression)
            if (key in indices) continue
            val childCount = when (expression) {
                is PolynomialExpression.Sum -> expression.operandCount
                is PolynomialExpression.Product -> expression.operandCount
                is PolynomialExpression.Power -> 1
                else -> 0
            }
            if (childCount > capacity - cells) throw ResidueDecline()
            val children = when (expression) {
                is PolynomialExpression.Sum -> expression.operands
                is PolynomialExpression.Product -> expression.operands
                is PolynomialExpression.Power -> listOf(expression.base)
                else -> emptyList()
            }
            if (!combine && children.isNotEmpty()) {
                reserve(children.size + 1)
                pending.addLast(expression to true)
                for (child in children.asReversed()) pending.addLast(child to false)
                continue
            }
            reserve(children.size + 6)
            val args = children.map { indices.getValue(ResidueExpressionKey(it)) }
            val denominator = when (expression) {
                is PolynomialExpression.Constant -> expression.denominator
                is PolynomialExpression.Power -> power(nodes[args.single()].denominator, expression.exponent)
                is PolynomialExpression.Sum -> args.fold(BIG_ONE) { value, child ->
                    commonDenominator(value, nodes[child].denominator)
                }
                else -> args.fold(BIG_ONE) { value, child -> multiply(value, nodes[child].denominator) }
            }
            val variable = if (expression is PolynomialExpression.Variable) {
                if (expression.id.index !in integers) throw ResidueDecline()
                variables.getOrPut(expression.id.index) { variables.size }
            } else -1
            indices[key] = nodes.size
            nodes.add(ResidueNode(expression, denominator, args, variable,
                if (variable >= 0) variable else args.minOfOrNull { nodes[it].firstVariable } ?: Int.MAX_VALUE,
                if (variable >= 0) variable else args.maxOfOrNull { nodes[it].lastVariable } ?: -1))
        }
        return indices.getValue(ResidueExpressionKey(root))
    }

    private fun chargeBits(value: BigInt) {
        val bits = value.magnitudeBitLength()
        if (bits > MAX_BITS) throw ResidueDecline()
        repeat((bits + 63) / 64) { step() }
        reserve((bits + 63) / 64)
    }

    private fun multiply(left: BigInt, right: BigInt): BigInt {
        step()
        if (left.magnitudeBitLength().toLong() + right.magnitudeBitLength() > MAX_BITS) throw ResidueDecline()
        return (left * right).also(::chargeBits)
    }

    private fun commonDenominator(left: BigInt, right: BigInt): BigInt {
        step()
        return multiply(left / left.gcd(right), right)
    }

    private fun power(value: BigInt, exponent: Int): BigInt {
        var result = BIG_ONE
        var base = value
        var remaining = exponent
        while (remaining != 0) {
            step()
            if (remaining and 1 != 0) result = multiply(result, base)
            remaining = remaining ushr 1
            if (remaining != 0) base = multiply(base, base)
        }
        return result
    }

    private fun hasSupport(modulus: Int, imagesOnly: Boolean): Boolean {
        val bigModulus = bigIntOf(modulus)
        fun residue(value: BigInt): Int {
            step()
            return (value.integerRemainder(bigModulus).toLong().toInt() + modulus) % modulus
        }
        val constants = IntArray(nodes.size)
        val scales = nodes.mapIndexed { id, node ->
            step()
            val expression = node.expression
            if (expression is PolynomialExpression.Constant) constants[id] = residue(expression.numerator)
            if (expression is PolynomialExpression.Sum) node.args.map { child ->
                residue(node.denominator / nodes[child].denominator)
            } else emptyList()
        }
        val equalityScales = equations.map { (left, right) ->
            val denominator = commonDenominator(nodes[left].denominator, nodes[right].denominator)
            residue(denominator / nodes[left].denominator) to residue(denominator / nodes[right].denominator)
        }
        if (imagesOnly) return imagesOverlap(modulus, constants, scales, equalityScales)
        val tuple = IntArray(variables.size)
        val values = IntArray(nodes.size)
        while (true) {
            step()
            stats = stats.copy(tuples = stats.tuples + 1)
            for ((id, node) in nodes.withIndex()) {
                step()
                values[id] = when (val expression = node.expression) {
                    is PolynomialExpression.Constant -> constants[id]
                    is PolynomialExpression.Variable -> tuple[node.variable]
                    is PolynomialExpression.Sum -> node.args.indices.fold(0) { value, index ->
                        step()
                        (value + values[node.args[index]] * scales[id][index]) % modulus
                    }
                    is PolynomialExpression.Product -> node.args.fold(1) { value, child ->
                        step()
                        value * values[child] % modulus
                    }
                    is PolynomialExpression.Power -> modularPower(
                        values[node.args.single()], expression.exponent, modulus,
                    )
                }
            }
            val supported = equations.indices.all { index ->
                step()
                val (left, right) = equations[index]
                val (a, b) = equalityScales[index]
                values[left] * a % modulus == values[right] * b % modulus
            }
            if (supported) {
                stats = stats.copy(rings = stats.rings + 1)
                return true
            }
            var coordinate = tuple.lastIndex
            while (coordinate >= 0) {
                step()
                if (++tuple[coordinate] < modulus) break
                tuple[coordinate--] = 0
            }
            if (coordinate < 0) {
                stats = stats.copy(rings = stats.rings + 1)
                return false
            }
        }
    }

    private fun imagesOverlap(
        modulus: Int,
        constants: IntArray,
        scales: List<List<Int>>,
        equalityScales: List<Pair<Int, Int>>,
    ): Boolean {
        reserve(nodes.size)
        val images = IntArray(nodes.size)
        for ((id, node) in nodes.withIndex()) {
            step()
            images[id] = when (val expression = node.expression) {
                is PolynomialExpression.Constant -> 1 shl constants[id]
                is PolynomialExpression.Variable -> (1 shl modulus) - 1
                is PolynomialExpression.Power -> mapImage(images[node.args.single()], modulus) {
                    modularPower(it, expression.exponent, modulus)
                }
                is PolynomialExpression.Sum -> node.args.indices.fold(1) { image, index ->
                    combineImages(image, images[node.args[index]], modulus) { a, b ->
                        (a + b * scales[id][index]) % modulus
                    }
                }
                is PolynomialExpression.Product -> node.args.fold(2) { image, child ->
                    combineImages(image, images[child], modulus) { a, b -> a * b % modulus }
                }
            }
        }
        for (index in equations.indices) {
            step()
            val (left, right) = equations[index]
            val (a, b) = equalityScales[index]
            val leftImage = mapImage(images[left], modulus) { it * a % modulus }
            val rightImage = mapImage(images[right], modulus) { it * b % modulus }
            if (leftImage and rightImage == 0) {
                stats = stats.copy(rings = stats.rings + 1)
                return false
            }
        }
        return true
    }

    private inline fun mapImage(image: Int, modulus: Int, transform: (Int) -> Int): Int {
        var result = 0
        for (value in 0 until modulus) {
            step()
            if (image and (1 shl value) != 0) result = result or (1 shl transform(value))
        }
        return result
    }

    // Independent operand images overapproximate aliases; only disjoint images carry proof authority.
    private inline fun combineImages(left: Int, right: Int, modulus: Int, combine: (Int, Int) -> Int): Int {
        var result = 0
        for (a in 0 until modulus) {
            step()
            if (left and (1 shl a) == 0) continue
            for (b in 0 until modulus) {
                step()
                if (right and (1 shl b) != 0) result = result or (1 shl combine(a, b))
            }
        }
        return result
    }

    private fun modularPower(value: Int, exponent: Int, modulus: Int): Int {
        var result = 1
        var base = value
        var remaining = exponent
        while (remaining != 0) {
            step()
            if (remaining and 1 != 0) result = result * base % modulus
            remaining = remaining ushr 1
            if (remaining != 0) base = base * base % modulus
        }
        return result
    }
}

private class ResidueNode(
    val expression: PolynomialExpression,
    val denominator: BigInt,
    val args: List<Int>,
    val variable: Int,
    val firstVariable: Int,
    val lastVariable: Int,
)

// Structural equality can walk an unadmitted source subtree outside the charged DAG traversal.
private class ResidueExpressionKey(private val expression: PolynomialExpression) {
    override fun hashCode(): Int = expression.hashCode()
    override fun equals(other: Any?): Boolean = other is ResidueExpressionKey && expression === other.expression
}

private class ResidueDecline : RuntimeException()
private val MODULI = intArrayOf(2, 3, 4, 5, 8)
private const val MAX_WORK = 65_536L
private const val MAX_CELLS = 16_384
private const val MAX_BITS = 4_096
