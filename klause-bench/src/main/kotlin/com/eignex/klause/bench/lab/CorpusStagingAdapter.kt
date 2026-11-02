package com.eignex.klause.bench.lab

import com.eignex.klause.bench.profile.CorpusStagingControls
import com.eignex.klause.bench.profile.CorpusStagingProfile
import com.eignex.lab.bench.BenchCase
import com.eignex.lab.bench.BenchCli
import com.eignex.lab.bench.BenchContext
import com.eignex.lab.bench.BenchDescriptor
import com.eignex.lab.bench.BenchInput
import com.eignex.lab.bench.BenchInputRoot
import com.eignex.lab.bench.BenchProtocol
import com.eignex.lab.bench.BenchRequest
import com.eignex.lab.bench.BenchResources
import com.eignex.lab.bench.BenchResult
import com.eignex.lab.bench.BenchStatus
import com.eignex.lab.bench.BenchSuite
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Corpus IO diagnostics using frozen, hash-verified lab inputs. */
fun main(args: Array<String>) {
    val protocolOutput = System.out
    System.setOut(System.err)
    configureLabPaths()
    BenchCli.execute(CorpusStagingSuite, args, stdout = protocolOutput)
}

internal object CorpusStagingSuite : BenchSuite {
    override fun describe() = BenchDescriptor("corpus-staging", "Source IO diagnostics without solver execution")

    override fun plan(request: BenchRequest): List<BenchCase> {
        require(request.parameters.keys - setOf("order", "staging-target", "staging-buffer-kib") ==
            setOf("inputs", "timeout-ms"))
        val controls = CorpusStagingControls(request.parameters["order"] ?: "forward",
            request.parameters["staging-target"] ?: "cache",
            (request.parameters["staging-buffer-kib"] ?: "8").toInt())
        val budget = request.parameters.getValue("timeout-ms").toLong().also { require(it in 1..10_000) }
        val inputs = BenchProtocol.json.decodeFromString<List<FrozenStagingInput>>(
            request.parameters.getValue("inputs"),
        )
        require(inputs.size in 1..8 && inputs.map { it.path }.distinct().size == inputs.size)
        return inputs.map { input ->
            require(input.suite.isNotBlank() && input.problem.isNotBlank() && input.path.endsWith(".zst"))
            require(input.plainSha256.matches(Regex("[a-f0-9]{64}")))
            val variant = "${controls.order}/${controls.stagingTarget}/${controls.bufferKiB}"
            BenchCase("${input.suite}/${input.problem}/staging/$variant",
                parameters = mapOf("timeout-ms" to budget.toString(),
                    "plain-sha256" to input.plainSha256, "order" to controls.order,
                    "staging-target" to controls.stagingTarget, "staging-buffer-kib" to controls.bufferKiB.toString()),
                resources = BenchResources(1, 4096, 280),
                inputs = listOf(BenchInput(input.path, BenchInputRoot.CORPUS, "model", input.packedSha256)),
                attributes = mapOf("suite" to input.suite, "problem" to input.problem, "format" to input.format,
                    "comparisonKey" to "${input.suite}/${input.problem}",
                    "pairKey" to "${input.suite}/${input.problem}",
                    "budgetMs" to budget.toString(), "recordSchema" to "corpus-staging-v1"))
        }
    }

    override fun run(case: BenchCase, context: BenchContext): BenchResult {
        val out = context.outputDirectory.resolve("report").toFile().apply { mkdirs() }
        out.resolve("input.json").writeText(BenchProtocol.json.encodeToString(BenchCase.serializer(), case))
        val failure = runCatching {
            LabInputs.verify(case, context)
            val model = case.inputs.single { it.role == "model" }
            CorpusStagingProfile.capture(
                LabInputs.path(model, context).toFile(),
                out,
                case.parameters.getValue("timeout-ms").toLong(),
                expectedHash = case.parameters.getValue("plain-sha256"),
                controls = CorpusStagingControls(case.parameters.getValue("order"),
                    case.parameters.getValue("staging-target"), case.parameters.getValue("staging-buffer-kib").toInt()),
            )
        }.exceptionOrNull()
        return BenchResult(case.id, if (failure == null) BenchStatus.SUCCESS else BenchStatus.FAILED,
            artifacts = context.artifacts(),
            details = buildJsonObject {
                put("solverExecuted", false)
                put("throughputQualification", false)
                put("inputDigest", case.attributes["labInputDigest"])
                put("failure", failure?.toString())
            }, message = failure?.toString())
    }
}

@Serializable
internal data class FrozenStagingInput(
    val suite: String,
    val problem: String,
    val format: String,
    val path: String,
    val packedSha256: String,
    val plainSha256: String,
)
