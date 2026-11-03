package com.eignex.klause.bench.lab

import com.eignex.klause.bench.catalog.Category
import com.eignex.klause.bench.catalog.Format
import com.eignex.lab.bench.BenchCase
import com.eignex.lab.bench.BenchCli
import com.eignex.lab.bench.BenchContext
import com.eignex.lab.bench.BenchDescriptor
import com.eignex.lab.bench.BenchInputRoot
import com.eignex.lab.bench.BenchProtocol
import com.eignex.lab.bench.BenchRequest
import com.eignex.lab.bench.BenchResult
import com.eignex.lab.bench.BenchStatus
import com.eignex.lab.bench.BenchSuite
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Ordinary installed-CLI solves over frozen single-file corpus inputs. */
fun main(args: Array<String>) {
    val protocolOutput = System.out
    System.setOut(System.err)
    configureLabPaths()
    BenchCli.execute(FrozenSourceSuite, args, stdout = protocolOutput)
}

internal object FrozenSourceSuite : BenchSuite {
    override fun describe() = BenchDescriptor("frozen-source", "Ordinary solves without corpus discovery")

    override fun plan(request: BenchRequest): List<BenchCase> {
        require(request.parameters.keys.none { it in LabSettings.SELECTORS || it == "selections" })
        val settings = LabSettings(request.parameters - "cases")
        require(!settings.inputOnly && !settings.propagationControls)
        val templates = BenchProtocol.json.decodeFromString<List<BenchCase>>(request.parameters.getValue("cases"))
        require(templates.size in 1..8 && templates.map { it.id }.distinct().size == templates.size)
        require(templates.map { it.attributes["suite"] to it.attributes["problem"] }.distinct().size == templates.size)
        require(templates.size.toLong() * settings.engines.size * settings.seeds.size * settings.repeats <= 128)
        return templates.flatMap { template ->
            val attributes = template.attributes.filterKeys { !it.startsWith("lab") }
            val suite = attributes.getValue("suite")
            val problem = attributes.getValue("problem")
            require(suite.isNotBlank() && problem.isNotBlank())
            require(Format.valueOf(attributes.getValue("format")) in
                setOf(Format.DIMACS, Format.WCNF, Format.MPS, Format.XCSP3))
            Category.valueOf(attributes.getValue("category"))
            require(attributes.getValue("sourcePlainSha256").matches(Regex("[a-f0-9]{64}")))
            val input = template.inputs.single()
            require(input.root == BenchInputRoot.CORPUS && input.role == "model" && input.path.endsWith(".zst"))
            require(input.sha256?.matches(Regex("[a-f0-9]{64}")) == true)
            settings.engines.flatMap { engine ->
                settings.seeds.flatMap { seed ->
                    (0 until settings.repeats).map { repeat ->
                        val variant = if (settings.engines.size > 1) "$engine/" else ""
                        val key = "$suite/$problem/$variant$seed/$repeat"
                        template.copy(
                            id = "$suite/$problem/$engine/$seed/$repeat",
                            parameters = settings.caseParameters(engine, seed),
                            resources = settings.resources(),
                            attributes = attributes + mapOf("comparisonKey" to key, "pairKey" to key,
                                "seed" to seed.toString(), "repeat" to repeat.toString(),
                                "budgetMs" to settings.timeout.toString(), "recordSchema" to "solver-v1",
                                "propagationControl" to "", "validationPolicy" to "reported-result-v1"),
                        )
                    }
                }
            }
        }
    }

    override fun run(case: BenchCase, context: BenchContext): BenchResult {
        val result = KlauseLabSuite.run(case, context)
        val observed = result.details?.get("sourceHashes")?.jsonObject?.get("model")?.jsonPrimitive?.content
        return if (observed == case.attributes.getValue("sourcePlainSha256")) result else result.copy(
            status = BenchStatus.FAILED,
            message = listOfNotNull(result.message,
                "Plain source identity missing or different from frozen input; original result retained")
                .joinToString("; "),
        )
    }
}
