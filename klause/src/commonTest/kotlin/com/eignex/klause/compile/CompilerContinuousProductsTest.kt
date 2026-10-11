package com.eignex.klause.compile

import com.eignex.klause.factor.arithmetic.ContinuousProduct
import com.eignex.klause.factor.arithmetic.Linear
import com.eignex.klause.ir.IntDomain
import com.eignex.klause.lowering.ProblemBuilder
import com.eignex.klause.model.ContinuousRealEquality
import com.eignex.klause.model.ContinuousRealExpr
import com.eignex.klause.model.FloatLinearConstraint
import com.eignex.klause.model.FloatSpec
import com.eignex.klause.model.IntCmpOp
import com.eignex.klause.model.NamedConstraint
import com.eignex.klause.model.Not
import com.eignex.klause.model.SchemaEntry
import com.eignex.klause.schema.FloatHandle
import com.eignex.klause.schema.eq
import com.eignex.klause.schema.plus
import com.eignex.klause.schema.times
import com.eignex.klause.simplex.exact.BigFraction
import com.eignex.skema.SchemaDef
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CompilerContinuousProductsTest {
    @Test
    fun `dsl square lowers to one repeated operand and a real auxiliary`() {
        val x = FloatHandle("x", -2.0, 2.0)
        val definition = SchemaDef<SchemaEntry>(mapOf(
            "x" to FloatSpec(-2.0, 2.0, 5),
            "square" to NamedConstraint((x * x) eq 2.0),
        ))

        val compiled = definition.compile()
        val factor = compiled.problem.factors.filterIsInstance<ContinuousProduct>().single()

        assertEquals(0, factor.a)
        assertEquals(factor.a, factor.b)
        assertEquals(0, compiled.problem.numIntVars)
        assertEquals(mapOf("x" to 0), compiled.realVarIdByName)
        assertContentEquals(intArrayOf(0, factor.result), factor.variables.reals)
    }

    @Test
    fun `chained products retain their shared intermediate column`() {
        val x = FloatHandle("x", -2.0, 2.0)
        val definition = SchemaDef<SchemaEntry>(mapOf(
            "x" to FloatSpec(-2.0, 2.0, 5),
            "cube" to NamedConstraint((x * x * x) eq x),
        ))

        val products = definition.compile().problem.factors.filterIsInstance<ContinuousProduct>()

        assertEquals(2, products.size)
        assertEquals(products[0].result, products[1].a)
        assertEquals(products[0].a, products[1].b)
    }

    @Test
    fun `model ast and dsl lower to equivalent source factors`() {
        val x = FloatHandle("x", -2.0, 2.0)
        val ref = ContinuousRealExpr.Affine(mapOf("x" to 1.0))
        val ast = ContinuousRealEquality(
            ContinuousRealExpr.Multiply(ContinuousRealExpr.Multiply(ref, ref), ref), ref,
        )
        val entries = mapOf<String, SchemaEntry>("x" to FloatSpec(-2.0, 2.0, 5))

        val model = SchemaDef(entries + ("cube" to NamedConstraint(ast))).compile().problem
        val dsl = SchemaDef(entries + ("cube" to NamedConstraint((x * x * x) eq x))).compile().problem

        assertEquals(model.factors.map { it.structuralKey() }, dsl.factors.map { it.structuralKey() })
        assertContentEquals(model.realLower, dsl.realLower)
        assertContentEquals(model.realUpper, dsl.realUpper)
    }

    @Test
    fun `affine operands preserve exact rational product semantics`() {
        val x = FloatHandle("x", -2.0, 2.0)
        val compiled = SchemaDef<SchemaEntry>(mapOf(
            "x" to FloatSpec(-2.0, 2.0, 5),
            "product" to NamedConstraint(((x + 0.5) * x * 0.1) eq 0.1),
        )).compile()
        val values = MutableList(compiled.problem.numRealVars) { BigFraction.ZERO }
        values[0] = BigFraction.ofDouble(0.5)!!
        val tenth = BigFraction.ofDouble(0.1)!!
        for (id in values.indices) {
            if (compiled.problem.realLower[id] == compiled.problem.realUpper[id]) {
                values[id] = BigFraction.ofDouble(compiled.problem.realLower[id])!!
            }
        }
        for (factor in compiled.problem.factors.dropLast(1)) {
            when (factor) {
                is ContinuousProduct -> values[factor.result] = values[factor.a] * values[factor.b]
                is Linear -> {
                    val constants = assertNotNull(factor.realConstants)
                    val ids = factor.variables.reals
                    val target = ids.last()
                    var activity = BigFraction.ZERO
                    for (i in 0 until ids.lastIndex) {
                        activity += BigFraction.ofDouble(constants.realCoefficients.at(i))!! * values[ids[i]]
                    }
                    values[target] = activity - BigFraction.ofDouble(constants.bound)!!
                }
            }
        }
        val products = compiled.problem.factors.filterIsInstance<ContinuousProduct>()

        assertEquals(tenth * BigFraction.ofDouble(0.5)!!, values[products.last().result])
        assertEquals(tenth, values.last())
    }

    @Test
    fun `shared builder products use real namespace despite overlapping finite ids`() {
        val builder = ProblemBuilder()
        builder.newBool("b")
        builder.newInt(IntDomain(0, 1), "i")
        val x = builder.newReal(-1.0, 1.0, "x")
        val result = builder.newReal(0.0, 1.0, "result")

        builder.continuousProduct(x, x, result)
        val problem = builder.build()

        assertTrue(problem.factors.single().boolVars.isEmpty())
        assertTrue(problem.factors.single().intVars.isEmpty())
        assertContentEquals(intArrayOf(x, result), problem.factors.single().variables.reals)
    }

    @Test
    fun `unallocated real columns decline at shared lowering`() {
        val builder = ProblemBuilder()
        builder.newInt(IntDomain(0, 1))

        assertFailsWith<IllegalArgumentException> { builder.continuousProduct(0, 0, 0) }
    }

    @Test
    fun `products decline connected grid representations`() {
        val x = FloatHandle("x", -2.0, 2.0)
        val definition = SchemaDef<SchemaEntry>(mapOf(
            "x" to FloatSpec(-2.0, 2.0, 5), "y" to FloatSpec(-2.0, 2.0, 5),
            "product" to NamedConstraint((x * x) eq FloatHandle("y", -2.0, 2.0)),
            "strict" to NamedConstraint(FloatLinearConstraint(doubleArrayOf(1.0), listOf("y"), IntCmpOp.LT, 0.0)),
        ))

        assertFailsWith<IllegalArgumentException> { definition.compile() }
    }

    @Test
    fun `continuous equalities decline reified contexts`() {
        val x = FloatHandle("x", -2.0, 2.0)
        val definition = SchemaDef<SchemaEntry>(mapOf(
            "x" to FloatSpec(-2.0, 2.0, 5), "product" to NamedConstraint(Not((x * x) eq 1.0)),
        ))

        assertFailsWith<IllegalStateException> { definition.compile() }
    }

    @Test
    fun `continuous source trees survive model serialization`() {
        val ref = ContinuousRealExpr.Affine(mapOf("x" to 1.0))
        val equality = ContinuousRealEquality(ContinuousRealExpr.Multiply(ref, ref), ref)

        val text = Json.encodeToString(equality)

        assertEquals(equality, Json.decodeFromString<ContinuousRealEquality>(text))
    }

    @Test
    fun `nonfinite continuous constants decline before lowering`() {
        for (constant in listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException> { ContinuousRealExpr.Affine(emptyMap(), constant) }
        }
    }
}
