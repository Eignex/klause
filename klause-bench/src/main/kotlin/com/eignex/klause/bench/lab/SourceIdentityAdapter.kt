package com.eignex.klause.bench.lab

import com.eignex.klause.bench.source.CorpusFiles
import com.eignex.lab.bench.BenchCase
import com.eignex.lab.bench.BenchCli
import com.eignex.lab.bench.BenchContext
import com.eignex.lab.bench.BenchDescriptor
import com.eignex.lab.bench.BenchInputRoot
import com.eignex.lab.bench.BenchProtocol
import com.eignex.lab.bench.BenchRequest
import com.eignex.lab.bench.BenchResources
import com.eignex.lab.bench.BenchResult
import com.eignex.lab.bench.BenchStatus
import com.eignex.lab.bench.BenchSuite
import java.io.InterruptedIOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.HexFormat
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Plain-byte identities for selected, hash-verified lab inputs without model execution. */
fun main(args: Array<String>) {
    val protocolOutput = System.out
    System.setOut(System.err)
    configureLabPaths()
    BenchCli.execute(SourceIdentitySuite, args, stdout = protocolOutput)
}

internal object SourceIdentitySuite : BenchSuite {
    override fun describe() = BenchDescriptor("source-identity", "Decoded input hashes without solver execution")

    override fun plan(request: BenchRequest): List<BenchCase> {
        require(request.parameters.keys == setOf("cases"))
        val cases = BenchProtocol.json.decodeFromString<List<BenchCase>>(request.parameters.getValue("cases"))
        require(cases.size in 1..8 && cases.map { it.id }.distinct().size == cases.size)
        return cases.map { case ->
            val input = case.inputs.single()
            require(input.root == BenchInputRoot.CORPUS && input.role == "model")
            require(input.sha256?.matches(Regex("[a-f0-9]{64}")) == true)
            require(case.attributes.getValue("suite").isNotBlank() && case.attributes.getValue("problem").isNotBlank())
            case.copy(parameters = emptyMap(), resources = BenchResources(1, 2048, 300),
                attributes = case.attributes.filterKeys { !it.startsWith("lab") } +
                    mapOf("recordSchema" to "source-identity-v1"))
        }
    }

    override fun run(case: BenchCase, context: BenchContext): BenchResult {
        val report = context.outputDirectory.resolve("source-identity.json")
        Files.deleteIfExists(report)
        val input = case.inputs.single()
        require(input.root == BenchInputRoot.CORPUS && input.role == "model" && input.sha256 != null)
        LabInputs.verify(case, context, cacheDirectory = null)
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        CorpusFiles.open(LabInputs.path(input, context).toFile()).use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Source hashing interrupted")
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
                bytes += count
            }
        }
        val details = buildJsonObject {
            put("suite", case.attributes.getValue("suite"))
            put("problem", case.attributes.getValue("problem"))
            put("path", input.path)
            put("packedSha256", input.sha256)
            put("plainSha256", HexFormat.of().formatHex(digest.digest()))
            put("plainBytes", bytes)
            put("solverExecuted", false)
        }
        Files.writeString(report, "$details\n")
        return BenchResult(case.id, BenchStatus.SUCCESS, details = details,
            artifacts = listOf(context.artifact("source-identity.json", "application/json")))
    }
}
