package com.eignex.klause.solver.search

import com.eignex.klause.ir.SourceIntId
import com.eignex.klause.solver.snapshotValues
import com.eignex.klause.util.parseBigInt
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SearchModelKeyTest {
    @Test
    fun `extension-defined typed keys infer their contributed value`() {
        data class FunctionValue(val symbol: String) : TypedSearchValueKey<String> {
            override val valueType: KClass<String> get() = String::class
        }
        val key = FunctionValue("f")
        val session = SearchSession(listOf(object : SearchComponent {
            override fun contributeModel(model: SearchModel, context: SearchContext) {
                model.contribute(key, "store")
            }
        }))

        val value: String? = session.model().valueOf(key)

        assertEquals("store", value)
    }

    @Test
    fun `assembly captures borrowed extension state once`() {
        data class ArrayValue(val entries: List<Long>)
        val key = SearchModelKey(ArrayValue::class) { ArrayValue(snapshotValues(it.entries)) }
        val entries = mutableListOf(3L)
        val session = SearchSession(listOf(object : SearchComponent {
            override fun contributeModel(model: SearchModel, context: SearchContext) {
                model.contribute(key, ArrayValue(entries))
            }
        }))
        val model = session.model()

        entries[0] = 7

        assertEquals(listOf(3L), model.valueOf(key)?.entries)
    }

    @Test
    @Suppress("DEPRECATION")
    fun `legacy writes cannot put an incompatible value under a typed key`() {
        val key = SearchModelKey(String::class)
        val session = SearchSession(listOf(object : SearchComponent {
            override fun contributeModel(model: SearchModel, context: SearchContext) {
                model.put(key, 1)
            }
        }))

        assertFailsWith<IllegalArgumentException> { session.model() }
    }

    @Test
    fun `typed contributions still have one owning component`() {
        val key = SearchModelKey(String::class)
        val components = List(2) {
            object : SearchComponent {
                override fun contributeModel(model: SearchModel, context: SearchContext) {
                    model.contribute(key, "value")
                }
            }
        }

        assertFailsWith<IllegalStateException> { SearchSession(components).model() }
    }

    @Test
    fun `a retained assembler cannot add values to a published model`() {
        val key = SearchModelKey(String::class)
        var retained: SearchModel? = null
        val session = SearchSession(listOf(object : SearchComponent {
            override fun contributeModel(model: SearchModel, context: SearchContext) {
                retained = model
            }
        }))
        val published = session.model()

        checkNotNull(retained).contribute(key, "late")

        assertNull(published.valueOf(key))
    }

    @Test
    fun `typed integer values preserve coordinates outside Long`() {
        val key = SearchExactIntValue(SourceIntId(0))
        val wide = parseBigInt("9223372036854775808")
        val session = SearchSession(listOf(object : SearchComponent {
            override fun contributeModel(model: SearchModel, context: SearchContext) {
                model.contribute(key, wide)
            }
        }))

        assertEquals(wide, session.model().valueOf(key))
    }
}
