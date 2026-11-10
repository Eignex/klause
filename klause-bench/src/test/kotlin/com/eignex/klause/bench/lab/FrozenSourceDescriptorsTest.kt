package com.eignex.klause.bench.lab

import com.eignex.lab.bench.BenchCase
import com.eignex.lab.bench.BenchInput
import com.eignex.lab.bench.BenchInputRoot
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FrozenSourceDescriptorsTest {
    private val packed = "5".repeat(64)

    private fun template(
        attributes: Map<String, String> = mapOf("sourcePlainSha256" to "6".repeat(64)),
        inputs: List<BenchInput> = listOf(
            BenchInput(
                "miplib2017/timtab1CUTS.mps.zst",
                BenchInputRoot.CORPUS,
                "model",
                packed,
            ),
        ),
    ) = BenchCase(
        "timtab1CUTS",
        inputs = inputs,
        attributes = mapOf(
            "suite" to "miplib2017",
            "problem" to "timtab1CUTS",
            "format" to "MPS",
            "category" to "OPTIMIZATION",
        ) + attributes,
    )

    @Test
    fun `a complete descriptor has no violations`() {
        assertEquals(emptyList(), FrozenSourceDescriptors.violations(listOf(template())))
    }

    @Test
    fun `a missing decoded identity names the case its stored hash and the source identity receipt`() {
        val violations = FrozenSourceDescriptors.violations(listOf(template(attributes = emptyMap())))

        assertEquals(1, violations.size)
        val message = violations.single()
        assertTrue(message.startsWith("case 'timtab1CUTS': attribute sourcePlainSha256 is missing"), message)
        assertTrue("lab/source-identity.json" in message && packed in message, message)
    }

    @ParameterizedTest
    @CsvSource(
        "stored, attribute sourcePlainSha256 repeats the stored sha256",
        "malformed, is not a lowercase 64-digit SHA-256",
        "data, 'has role data, which only MINIZINC sources accept'",
        "source, frozen sources read only the CORPUS root",
        "plain, is not a packed .zst corpus member",
    )
    fun `descriptor violations explain what to correct`(defect: String, expected: String) {
        val model = BenchInput("miplib2017/timtab1CUTS.mps.zst", BenchInputRoot.CORPUS, "model", packed)
        val broken = when (defect) {
            "stored" -> template(attributes = mapOf("sourcePlainSha256" to packed))

            "malformed" -> template(attributes = mapOf("sourcePlainSha256" to "ABC"))

            "data" -> template(
                inputs = listOf(
                    model,
                    BenchInput(
                        "miplib2017/x.dzn",
                        BenchInputRoot.CORPUS,
                        "data",
                        packed,
                    ),
                ),
            )

            "source" -> template(inputs = listOf(model.copy(root = BenchInputRoot.SOURCE)))

            else -> template(inputs = listOf(model.copy(path = "miplib2017/timtab1CUTS.mps")))
        }

        val violations = FrozenSourceDescriptors.violations(listOf(broken))

        assertTrue(violations.any { expected in it }, violations.toString())
    }
}
