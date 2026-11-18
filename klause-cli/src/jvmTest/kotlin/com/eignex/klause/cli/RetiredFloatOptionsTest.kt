package com.eignex.klause.cli

import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@RunWith(Parameterized::class)
class RetiredFloatOptionsTest(private val arguments: Array<String>) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{index}")
        fun options(): List<Array<Any>> = listOf(
            arrayOf("--float-semantics", "grid"),
            arrayOf("--float-buckets", "3"),
            arrayOf("--float-scale", "1000"),
            arrayOf("--float-grid-lo", "0"),
            arrayOf("--float-grid-hi", "1"),
            arrayOf("--unbounded-float-lo", "0"),
            arrayOf("--unbounded-float-hi", "1"),
        ).map { arrayOf<Any>(it) }
    }

    @Test
    fun `retired grid options should not be accepted as source controls`() {
        val common = CommonOptions()

        if (arguments.first() == "--float-semantics") {
            assertFailsWith<CliUsageException> { parseArgs(arguments, commonFlagSpecs(common)) {} }
        } else {
            val diagnostic = captureStderr { parseArgs(arguments, commonFlagSpecs(common)) {} }
            assertTrue("ignoring unknown flag ${arguments.first()}" in diagnostic, diagnostic)
        }
    }
}
