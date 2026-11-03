package com.eignex.klause.bench.lab

import com.eignex.klause.bench.catalog.ProblemSource
import com.eignex.klause.bench.source.CorpusFetcher
import com.eignex.lab.bench.BenchCase
import com.eignex.lab.bench.BenchCli
import com.eignex.lab.bench.BenchContext
import com.eignex.lab.bench.BenchDescriptor
import com.eignex.lab.bench.BenchRequest
import com.eignex.lab.bench.BenchResources
import com.eignex.lab.bench.BenchResult
import com.eignex.lab.bench.BenchStatus
import com.eignex.lab.bench.BenchSuite
import java.nio.file.Files
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.eignex.klause.bench.target.BenchCli as KlauseCli

/** Pack-index discovery during SDK preparation, without source materialization or execution. */
fun main(args: Array<String>) {
    val protocolOutput = System.out
    System.setOut(System.err)
    configureLabPaths()
    BenchCli.execute(IndexedCatalogSuite, args, stdout = protocolOutput)
}

internal object IndexedCatalogSuite : BenchSuite {
    override fun describe() = BenchDescriptor("indexed-catalog", "Source metadata from complete corpus indexes")

    override fun plan(request: BenchRequest): List<BenchCase> {
        require(CorpusFetcher.declaredInputs) { "Catalog discovery requires SDK declared input planning" }
        require(request.parameters.keys.all { it in LabSettings.SELECTORS || it == "selections" })
        val settings = LabSettings(request.parameters + ("input-only" to "true"))
        val previous = System.getProperty("klause.bench.indexOnly")
        System.setProperty("klause.bench.indexOnly", "true")
        val catalog = try {
            val selected = settings.selections.flatMap { selection ->
                KlauseCli.selectProblems(selection).also { require(it.isNotEmpty()) { "No sources match $selection" } }
            }.distinctBy { it.suite to it.ref.name }
            buildJsonArray {
                for (row in selected) {
                    val source = row.ref.source
                    val collection = when (source) {
                        is ProblemSource.External -> source.collection
                        is ProblemSource.ExternalIndexed -> source.collection
                        else -> error("Catalog discovery requires an indexed external source")
                    }
                    val view = requireNotNull(CorpusFetcher.indexed(collection))
                    val file = CorpusFetcher.resolve(source) { view.root }
                    val entry = requireNotNull(view.entry(file))
                    add(buildJsonObject {
                        put("suite", row.suite)
                        put("problem", row.ref.name)
                        put("collection", collection.id)
                        put("family", row.family.substringAfter(':'))
                        put("format", row.ref.format.name)
                        put("category", row.ref.category.name)
                        put("packedPath", "${collection.id}/${entry.path}")
                        put("packedSha256", entry.sha256)
                        put("packedBytes", entry.bytes)
                    })
                }
            }.toString()
        } finally {
            if (previous == null) System.clearProperty("klause.bench.indexOnly")
            else System.setProperty("klause.bench.indexOnly", previous)
        }
        return listOf(BenchCase("indexed-catalog", parameters = mapOf("catalog" to catalog),
            resources = BenchResources(1, 2048, 60), attributes = mapOf("recordSchema" to "indexed-catalog-v1")))
    }

    override fun run(case: BenchCase, context: BenchContext): BenchResult {
        Files.writeString(context.outputDirectory.resolve("catalog.json"), case.parameters.getValue("catalog") + "\n")
        return BenchResult(case.id, BenchStatus.SUCCESS,
            artifacts = listOf(context.artifact("catalog.json", "application/json")),
            details = buildJsonObject { put("solverExecuted", false) })
    }
}
