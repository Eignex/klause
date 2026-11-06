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

/** Ordinary installed-CLI solves over frozen corpus inputs. */
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
            val format = Format.valueOf(attributes.getValue("format"))
            require(format in setOf(Format.DIMACS, Format.WCNF, Format.MPS, Format.OPB, Format.MINIZINC))
            Category.valueOf(attributes.getValue("category"))
            require(attributes.getValue("sourcePlainSha256").matches(Regex("[a-f0-9]{64}")))
            val model = template.inputs.single { it.role == "model" }
            val data = template.inputs.singleOrNull { it.role == "data" }
            require(template.inputs.size == 1 + if (data == null) 0 else 1)
            require(template.inputs.all {
                it.root == BenchInputRoot.CORPUS && it.sha256?.matches(Regex("[a-f0-9]{64}")) == true
            })
            if (format == Format.MINIZINC) {
                require(model.path.endsWith(".mzn") && (data == null || data.path.endsWith(".dzn")))
                if (data != null) {
                    require(attributes["sourceDataPlainSha256"]?.matches(Regex("[a-f0-9]{64}")) == true)
                }
            } else {
                require(data == null && model.path.endsWith(".zst"))
            }
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
        val observed = result.details?.get("sourceHashes")?.jsonObject
        val identities = mapOf("model" to case.attributes.getValue("sourcePlainSha256")) +
            if (case.inputs.any { it.role == "data" }) {
                mapOf("data" to case.attributes.getValue("sourceDataPlainSha256"))
            } else emptyMap()
        return if (identities.all { (role, expected) -> observed?.get(role)?.jsonPrimitive?.content == expected }) {
            result
        } else result.copy(
            status = BenchStatus.FAILED,
            message = listOfNotNull(result.message,
                "Plain source identity missing or different from frozen input; original result retained")
                .joinToString("; "),
        )
    }
}
