package com.eignex.klause.formats.smtlib

import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.arithmetic.polynomial.PolynomialResourceLimit
import com.eignex.klause.arithmetic.real.ExactRealFunction
import com.eignex.klause.arithmetic.real.ExactRealValue
import com.eignex.klause.arithmetic.real.RealCertificationUnavailable
import com.eignex.klause.arithmetic.real.RealDomainInvalid
import com.eignex.klause.arithmetic.transcendental.TranscendentalInterrupted
import com.eignex.klause.arithmetic.transcendental.TranscendentalResourceLimit
import com.eignex.klause.lowering.PolynomialSourceInterrupted
import com.eignex.klause.lowering.polynomialSourceCheckpoint
import com.eignex.klause.util.ExecutionControl
import com.eignex.util.numbers.BIG_ONE
import com.eignex.util.numbers.BIG_ZERO
import com.eignex.util.numbers.BigFraction
import com.eignex.util.numbers.BigInt
import com.eignex.util.numbers.compareTo
import com.eignex.util.numbers.div
import com.eignex.util.numbers.minus
import com.eignex.util.numbers.parseBigInt
import com.eignex.util.numbers.remainderOf
import com.eignex.util.numbers.signum

internal class SmtArithmeticConstants(private val control: ExecutionControl) {
    private val arithmetic = smtExactArithmetic(control)

    fun knownRational(expression: SExpr): BigFraction? = try {
        rational(expression)
    } catch (_: TranscendentalResourceLimit) {
        null
    } catch (_: TranscendentalInterrupted) {
        throw PolynomialSourceInterrupted()
    } catch (_: PolynomialInterrupted) {
        throw PolynomialSourceInterrupted()
    }

    fun rational(expression: SExpr): BigFraction? {
        val work = ArrayDeque<Pair<SExpr, Boolean>>()
        val values = ArrayDeque<BigFraction>()
        work.addLast(expression to false)
        while (work.isNotEmpty()) {
            control.polynomialSourceCheckpoint(1)
            val (node, combine) = work.removeLast()
            when (node) {
                is SExpr.Atom -> values.addLast(atomRational(node.text) ?: return null)
                is SExpr.SList -> {
                    val head = (node.items.firstOrNull() as? SExpr.Atom)?.text
                    if ((head != "-" && head != "/") || (head == "-" && node.items.size != 2) ||
                        (head == "/" && node.items.size != 3)) return null
                    if (!combine) {
                        work.addLast(node to true)
                        for (argument in node.items.drop(1).asReversed()) work.addLast(argument to false)
                    } else {
                        val last = values.removeLast()
                        if (head == "-") values.addLast(last.negated()) else {
                            if (last.isZero) return null
                            values.addLast(arithmetic.transcendental.divide(values.removeLast(), last))
                        }
                    }
                }
            }
        }
        return values.single()
    }

    private fun atomRational(text: String): BigFraction? {
        control.polynomialSourceCheckpoint(text.length.toLong())
        val parts = text.split('.')
        if (parts.size !in 1..2 || parts.any { it.isEmpty() || it.any { c -> c !in '0'..'9' } }) return null
        val numerator = parseBigInt(parts.joinToString(""))
        var denominator = BigFraction.ONE
        if (parts.size == 2) repeat(parts[1].length) {
            control.polynomialSourceCheckpoint(1)
            denominator = arithmetic.transcendental.multiply(denominator, BigFraction.ofLong(10))
        }
        return BigFraction.of(numerator, denominator.num)
    }

    fun literal(value: BigFraction): SExpr {
        fun integer(number: BigInt): SExpr = if (number.signum() < 0) {
            form("-", SExpr.Atom(number.toString().drop(1)))
        } else SExpr.Atom(number.toString())
        return if (value.den == BIG_ONE) integer(value.num) else form("/", integer(value.num), integer(value.den))
    }

    fun fold(operator: String, arguments: List<SExpr>): SExpr? = try {
        foldExact(operator, arguments)
    } catch (_: RealCertificationUnavailable) {
        null
    } catch (_: RealDomainInvalid) {
        null
    } catch (_: TranscendentalResourceLimit) {
        null
    } catch (_: PolynomialResourceLimit) {
        null
    } catch (_: TranscendentalInterrupted) {
        throw PolynomialSourceInterrupted()
    } catch (_: PolynomialInterrupted) {
        throw PolynomialSourceInterrupted()
    }

    private fun foldExact(operator: String, arguments: List<SExpr>): SExpr? {
        if (operator == "not") return boolean(arguments.single())?.let { SExpr.Atom((!it).toString()) }
        if (operator == "ite") return boolean(arguments[0])?.let { arguments[if (it) 1 else 2] }
        val numbers = arguments.map(::rational)
        if (operator in setOf("to_int", "is_int") && numbers.single() == null) {
            val exact = closedReal(arguments.single()) ?: return null
            for (terms in listOf(4, 8, 16, 32)) {
                val enclosure = arithmetic.enclose(exact, terms)
                val lower = floor(enclosure.lower)
                val upper = floor(enclosure.upper)
                if (lower == upper) return if (operator == "to_int") literal(BigFraction.of(lower, BIG_ONE))
                else if (arithmetic.compare(exact, arithmetic.rational(BigFraction.of(lower, BIG_ONE))) == 0)
                    SExpr.Atom("true") else if (enclosure.lower > BigFraction.of(lower, BIG_ONE))
                    SExpr.Atom("false")
                else null
            }
            return null
        }
        if (numbers.any { it == null }) return null
        val values = numbers.filterNotNull()
        return when (operator) {
            "+" -> literal(values.fold(BigFraction.ZERO, arithmetic.transcendental::add))
            "*" -> literal(values.fold(BigFraction.ONE, arithmetic.transcendental::multiply))
            "-" -> literal(
                if (values.size == 1) values.single().negated()
                else values.drop(1).fold(values.first()) { value, term ->
                    arithmetic.transcendental.add(value, term.negated())
                }
            )
            "to_real" -> literal(values.single())
            "to_int" -> literal(BigFraction.of(floor(values.single()), BIG_ONE))
            "is_int" -> SExpr.Atom((values.single().den == BIG_ONE).toString())
            else -> null
        }
    }

    private fun closedReal(root: SExpr): ExactRealValue? {
        val work = ArrayDeque<Pair<SExpr, Boolean>>()
        val values = ArrayDeque<ExactRealValue>()
        work.addLast(root to false)
        while (work.isNotEmpty()) {
            control.polynomialSourceCheckpoint(1)
            val (node, combine) = work.removeLast()
            val number = rational(node)
            if (number != null) {
                values.addLast(arithmetic.rational(number))
                continue
            }
            val list = node as? SExpr.SList ?: return null
            val head = (list.items.firstOrNull() as? SExpr.Atom)?.text ?: return null
            if (head !in setOf("exp", "log", "sin", "cos", "+", "-", "*")) return null
            if (!combine) {
                work.addLast(node to true)
                list.items.drop(1).asReversed().forEach { work.addLast(it to false) }
            } else {
                val args = List(list.items.size - 1) { values.removeLast() }.reversed()
                values.addLast(when (head) {
                    "+" -> arithmetic.add(args)
                    "*" -> arithmetic.multiply(args)
                    "-" -> if (args.size == 1) arithmetic.negate(args.single()) else
                        arithmetic.add(listOf(args.first()) + args.drop(1).map(arithmetic::negate))
                    else -> arithmetic.function(ExactRealFunction.valueOf(head.uppercase()), args.single())
                })
            }
        }
        return values.single()
    }

    private fun floor(value: BigFraction): BigInt {
        val quotient = value.num / value.den
        return if (value.num.signum() < 0 && value.num.remainderOf(value.den) != BIG_ZERO) quotient - BIG_ONE
        else quotient
    }

    private fun boolean(expression: SExpr): Boolean? = (expression as? SExpr.Atom)?.text?.let {
        when (it) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }
    private fun form(operator: String, vararg arguments: SExpr): SExpr =
        SExpr.SList(listOf(SExpr.Atom(operator)) + arguments)
}
