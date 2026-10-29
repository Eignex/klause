package com.eignex.klause.bench.report

import com.eignex.lab.bench.BenchContext
import java.nio.file.Files
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals

class ReportsTest {
    @ParameterizedTest
    @ValueSource(strings = ["fam/inst", "ppp:1-12,16", "literal%3a", "back\\slash", "line\nbreak"])
    fun `report artifacts retain problem identities with portable filenames`(problem: String) {
        val root = Files.createTempDirectory("report-artifacts-")
        try {
            val directory = Files.createDirectory(root.resolve("report"))
            val stem = Reports.fileStem(problem)
            val record = directory.resolve("$stem.json")
            Reports.writeJson(record.toString(), buildJsonObject { put("problem", problem) })
            Files.writeString(directory.resolve("$stem.out"), "retained solver output")

            val artifacts = BenchContext(root).artifacts()

            assertEquals(2, artifacts.size)
            assertEquals(problem, Reports.json.parseToJsonElement(Files.readString(record))
                .jsonObject.getValue("problem").jsonPrimitive.content)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
