package com.eignex.klause.compile

import com.eignex.klause.backtrack.BacktrackSolver
import com.eignex.klause.factor.codec.FactorCodecs
import com.eignex.klause.factor.codec.MissingFactorCodecException
import com.eignex.klause.factor.codec.factorCodec
import com.eignex.klause.factor.global.LexLess
import com.eignex.klause.factor.global.ValuePrecede
import com.eignex.klause.factor.scheduling.Cumulative
import com.eignex.klause.factor.symmetry.SymmetryHandling
import com.eignex.klause.formats.json.JsonSchema
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.FactorKind
import com.eignex.klause.ir.IntVars
import com.eignex.klause.ir.StructuralKey
import com.eignex.klause.ir.VarList
import com.eignex.klause.ir.VarRemap
import com.eignex.klause.model.FactorConstraint
import com.eignex.klause.propagation.bake
import com.eignex.klause.schema.VariableSchema
import com.eignex.klause.schema.cumulative
import com.eignex.klause.schema.factor
import com.eignex.klause.schema.iff
import com.eignex.klause.schema.lexLeq
import com.eignex.klause.schema.lexLt
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompilerNativeFactorsTest {
    @Test
    fun `integer declarations should keep long endpoints open sides and sparse values`() {
        class S : VariableSchema() {
            val wide by intVar(-5_000_000_000L, 5_000_000_000L)
            val open by openIntVar(min = 0)
            val sparse by intVar(listOf(1L, 3L, 1_000_000_000_000_000L))
        }
        val compiled = S().compile()
        val problem = compiled.problem
        val (wide, open, sparse) = listOf("wide", "open", "sparse").map { compiled.intVarIdByName.getValue(it) }

        assertEquals(-5_000_000_000L to 5_000_000_000L, problem.intBounds.lower(wide) to problem.intBounds.upper(wide))
        assertTrue(problem.intBounds.lower(open) == 0L && problem.intBounds.isOpenUpper(open))
        val values = requireNotNull(problem.intDomainOrNull(sparse))
        assertTrue(3L in values && 1_000_000_000_000_000L in values)
        assertFalse(2L in values)
    }

    @Test
    fun `a top-level lexicographic order should post one native factor`() {
        class S : VariableSchema() {
            val x by intVar(0, 3)
            val y by intVar(0, 3)
            val z by intVar(0, 3)
            val weak by constraint { lexLeq(listOf(x, y), listOf(y, z)) }
            val strict by constraint { lexLt(listOf(x), listOf(z)) }
        }

        val factors = S().compile().problem.factors

        assertEquals(listOf(false, true), factors.map { (it as LexLess).strict })
    }

    @Test
    fun `a reified lexicographic order should keep both truth values`() {
        class S : VariableSchema() {
            val b by boolVar()
            val x by intVar(0, 1)
            val y by intVar(0, 1)
            val relation by constraint { b iff lexLt(listOf(x), listOf(y)) }
        }
        val compiled = S().compile()
        val choice = compiled.boolVarIdByName.getValue("b")
        val (x, y) = listOf("x", "y").map { compiled.intVarIdByName.getValue(it) }

        val samples = BacktrackSolver(compiled.problem.bake()).enumerate().toList()

        assertEquals(4, samples.map { it.intValue(x) to it.intValue(y) }.toSet().size)
        assertTrue(samples.all { it.boolValue(choice) == (it.intValue(x) < it.intValue(y)) })
    }

    @Test
    fun `a variable cumulative should name its duration demand and capacity columns`() {
        class S : VariableSchema() {
            val start by intVar(0, 9)
            val duration by intVar(1, 4)
            val demand by intVar(0, 2)
            val capacity by intVar(1, 3)
            val schedule by constraint { cumulative(listOf(start), listOf(duration), listOf(demand), capacity) }
        }
        val compiled = S().compile()

        val factor = compiled.problem.factors.single() as Cumulative

        val ids = compiled.intVarIdByName
        assertEquals(listOf(ids.getValue("duration")), factor.durationVars.toList())
        assertEquals(listOf(ids.getValue("demand")), factor.resourceVars.toList())
        assertEquals(ids.getValue("capacity"), factor.capacityVar)
        assertEquals(listOf(4L, 2L, 3L), listOf(factor.durations[0], factor.resources[0], factor.capacity))
    }

    @Test
    fun `a factor constraint with an unbound local id should fail to compile`() {
        class S : VariableSchema() {
            val x by intVar(0, 3)
            val order by constraint { factor(ValuePrecede(1, 2, intArrayOf(0, 1)), ints = listOf(x)) }
        }

        assertFailsWith<IllegalArgumentException> { S().compile() }
    }

    @Test
    fun `a factor constraint with an unknown key should fail to compile`() {
        class S : VariableSchema() {
            val x by intVar(0, 3)
            val marker by constraint {
                FactorConstraint(JsonObject(mapOf("marker" to JsonObject(emptyMap()))), ints = listOf("x"))
            }
        }

        assertFailsWith<MissingFactorCodecException> { S().compile() }
    }

    @Test
    fun `a custom codec passed to compilation should post its factor`() {
        val codecs = FactorCodecs.builtin + markerCodec
        class S : VariableSchema() {
            val x by intVar(0, 3)
            val y by intVar(0, 3)
            val marker by constraint { factor(Marker(0), ints = listOf(y), codecs = codecs) }
        }
        val compiled = S().compile(factorCodecs = codecs)

        val marker = compiled.problem.factors.single() as Marker

        assertEquals(compiled.intVarIdByName.getValue("y"), marker.x)
    }

    @Test
    fun `a symmetry factor should preserve generators bound to higher columns`() {
        class S : VariableSchema() {
            val unusedInt by intVar(0, 1)
            val x by intVar(0, 1)
            val y by intVar(0, 1)
            val unusedBool by boolVar()
            val b by boolVar()
            val c by boolVar()
            val symmetry by constraint {
                factor(
                    SymmetryHandling(listOf(intArrayOf(1, 0) to intArrayOf(1, 0))),
                    ints = listOf(x, y),
                    bools = listOf(b, c),
                )
            }
        }

        val factor = S().compile().problem.factors.single() as SymmetryHandling

        assertEquals(listOf(0, 2, 1), factor.generators.single().first.toList())
        assertEquals(listOf(0, 2, 1), factor.generators.single().second.toList())
    }

    @Test
    fun `an absent wide integer should retain its declared value`() {
        val definition = JsonSchema.parse(
            """{"entries":{
                "x":{"intDomain":{"lower":5000000000,"upper":5000000000}},
                "p":{"presence":"x"},
                "absent":{"constraint":{"notRef":"p"}}
            }}""",
        )
        val compiled = definition.compile()

        val sample = BacktrackSolver(compiled.problem.bake()).enumerate().single()

        assertFalse(sample.boolValue(compiled.boolVarIdByName.getValue("p")))
        assertEquals(5_000_000_000L, sample.intValue(compiled.intVarIdByName.getValue("x")))
    }

    @Test
    fun `an absent sparse integer should pin to a declared value`() {
        val definition = JsonSchema.parse(
            """{"entries":{
                "x":{"intDomain":{"lower":-2,"upper":3,"values":[[-2,-2],[3,3]]}},
                "p":{"presence":"x"},
                "absent":{"constraint":{"notRef":"p"}}
            }}""",
        )
        val compiled = definition.compile()

        val sample = BacktrackSolver(compiled.problem.bake()).enumerate().single()

        assertFalse(sample.boolValue(compiled.boolVarIdByName.getValue("p")))
        assertEquals(3L, sample.intValue(compiled.intVarIdByName.getValue("x")))
    }
}

private class Marker(val x: Int) : Factor {
    override val variables: VarList = IntVars(intArrayOf(x))

    override fun remap(mapping: VarRemap): Factor = Marker(mapping.int(x))

    override fun structuralKey(): StructuralKey = StructuralKey.of(FactorKind.LINEAR) { int(x) }
}

@Serializable
private class MarkerPayload(val x: Int)

private val markerCodec = factorCodec<Marker, MarkerPayload>("marker", { MarkerPayload(it.x) }, { Marker(it.x) })
