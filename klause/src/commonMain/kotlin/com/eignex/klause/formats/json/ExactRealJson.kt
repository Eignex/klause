package com.eignex.klause.formats.json

import com.eignex.klause.arithmetic.polynomial.AlgebraicArithmetic
import com.eignex.klause.arithmetic.polynomial.RationalPolynomial
import com.eignex.klause.arithmetic.polynomial.PolynomialArithmetic
import com.eignex.klause.arithmetic.polynomial.PolynomialInterrupted
import com.eignex.klause.arithmetic.polynomial.PolynomialResourceLimit
import com.eignex.klause.arithmetic.real.ExactRealArithmetic
import com.eignex.klause.arithmetic.real.ExactRealExpression
import com.eignex.klause.arithmetic.real.ExactRealFunction
import com.eignex.klause.arithmetic.real.ExactRealValue
import com.eignex.klause.arithmetic.real.RealCertificationUnavailable
import com.eignex.klause.arithmetic.real.RealDomainInvalid
import com.eignex.klause.arithmetic.transcendental.TranscendentalArithmetic
import com.eignex.klause.arithmetic.transcendental.TranscendentalInterrupted
import com.eignex.klause.arithmetic.transcendental.TranscendentalResourceLimit
import com.eignex.klause.factor.codec.BigFractionSerializer
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.klause.util.Cancellation
import kotlinx.serialization.json.*

/**
 * Canonical JSON for closed [ExactRealValue] expressions. Rational leaves are fraction strings;
 * algebraic leaves select a one-based distinct real root of an ascending rational coefficient list.
 * Analytic functions, sums, products and powers use structured operator nodes. Approximations and
 * isolating intervals have no identity or decoding authority.
 */
object ExactRealJson {
    /** Encode the exact expression without numerical projection. */
    fun encodeToJsonElement(value: ExactRealValue): JsonElement = encode(value.expression)

    /** Encode the exact expression as JSON text. */
    fun encodeToString(value: ExactRealValue): String = encodeToJsonElement(value).toString()

    /** Decode and validate every root selection, exponent and analytic function domain. */
    fun decodeFromJsonElement(element: JsonElement): ExactRealValue {
        var polls = 0
        val stop = Cancellation { ++polls > 100000 }
        val algebraic = AlgebraicArithmetic(PolynomialArithmetic(stop))
        val arithmetic = ExactRealArithmetic(TranscendentalArithmetic(stop, maximumWork = 100000), algebraic)
        fun decode(node: JsonElement, depth: Int): ExactRealValue {
            require(depth <= 128) { "exact real expression is too deep" }
            if (node is JsonPrimitive) {
                require(node.isString) { "an exact rational must be a fraction string" }
                return arithmetic.rational(Json.decodeFromJsonElement(BigFractionSerializer, node))
            }
            require(node is JsonObject && node.size == 1) { "expected one exact real operator" }
            val (key, payload) = node.entries.single()
            fun operands(): List<ExactRealValue> {
                require(payload is JsonArray) { "$key requires an operand array" }
                return payload.map { decode(it, depth + 1) }
            }
            return when (key) {
                "root" -> {
                    require(payload is JsonObject && payload.keys == setOf("coefficients", "index")) {
                        "root requires coefficients and index"
                    }
                    val coefficients = (payload["coefficients"] as? JsonArray)?.map {
                        require(it is JsonPrimitive && it.isString) { "root coefficients must be fraction strings" }
                        Json.decodeFromJsonElement(BigFractionSerializer, it)
                    } ?: throw IllegalArgumentException("root coefficients must be an array")
                    require(coefficients.size in 2..1024 && coefficients.last() != BigFraction.ZERO) {
                        "root requires a nonconstant polynomial without trailing zero coefficients"
                    }
                    val index = (payload["index"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
                        ?: throw IllegalArgumentException("root index must be an integer")
                    require(index > 0) { "root index must be positive" }
                    val roots = algebraic.roots(RationalPolynomial(coefficients))
                    require(index <= roots.size) { "root index exceeds the number of distinct real roots" }
                    arithmetic.scalar(roots[index - 1])
                }
                "pi" -> {
                    require(payload is JsonObject && payload.isEmpty()) { "pi requires an empty object" }
                    arithmetic.pi()
                }
                "exp", "log", "sin", "cos" -> arithmetic.function(
                    ExactRealFunction.valueOf(key.uppercase()), decode(payload, depth + 1),
                )
                "add" -> arithmetic.add(operands())
                "multiply" -> arithmetic.multiply(operands())
                "power" -> {
                    require(payload is JsonArray && payload.size == 2) { "power requires a base and exponent" }
                    val exponent = (payload[1] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
                        ?: throw IllegalArgumentException("power exponent must be an integer")
                    require(exponent >= 0) { "power exponent must be nonnegative" }
                    arithmetic.power(decode(payload[0], depth + 1), exponent)
                }
                else -> throw IllegalArgumentException("unknown exact real operator '$key'")
            }
        }
        return try {
            decode(element, 0)
        } catch (error: RealDomainInvalid) {
            throw IllegalArgumentException("exact real function domain is invalid", error)
        } catch (error: RealCertificationUnavailable) {
            throw IllegalArgumentException("exact real function domain could not be certified", error)
        } catch (error: PolynomialInterrupted) {
            throw IllegalArgumentException("exact real decoding allowance exceeded", error)
        } catch (error: PolynomialResourceLimit) {
            throw IllegalArgumentException("exact real polynomial representation limit exceeded", error)
        } catch (error: TranscendentalInterrupted) {
            throw IllegalArgumentException("exact real decoding allowance exceeded", error)
        } catch (error: TranscendentalResourceLimit) {
            throw IllegalArgumentException("exact real analytic representation limit exceeded", error)
        }
    }

    /** Decode JSON text using the same closed expression contract. */
    fun decodeFromString(text: String): ExactRealValue = decodeFromJsonElement(Json.parseToJsonElement(text))

    private fun encode(node: ExactRealExpression): JsonElement = when (node) {
        is ExactRealExpression.Algebraic -> node.value.rational?.let { JsonPrimitive(it.toString()) }
            ?: buildJsonObject {
                put("root", buildJsonObject {
                    put("coefficients", JsonArray(node.value.definingCoefficients.map { JsonPrimitive(it.toString()) }))
                    put("index", node.value.rootIndex)
                })
            }
        ExactRealExpression.Pi -> buildJsonObject { put("pi", JsonObject(emptyMap())) }
        is ExactRealExpression.Function -> buildJsonObject {
            put(node.function.name.lowercase(), encode(node.argument))
        }
        is ExactRealExpression.Sum -> buildJsonObject { put("add", JsonArray(node.operands.map(::encode))) }
        is ExactRealExpression.Product -> buildJsonObject { put("multiply", JsonArray(node.operands.map(::encode))) }
        is ExactRealExpression.Power -> buildJsonObject {
            put("power", JsonArray(listOf(encode(node.base), JsonPrimitive(node.exponent))))
        }
    }
}
