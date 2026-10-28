package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.formats.xcsp3.Xcsp3
import com.eignex.klause.localsearch.DefinitionalSweep
import com.eignex.klause.util.Cancellation
import java.io.File
import java.util.Base64
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal object ChannelViewApplicability {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size in 1..9 && args.first().startsWith("request64="))
        val request = Base64.getDecoder().decode(args.first().substringAfter('='))
        require(request.size <= 16 * 1024)
        val sources = Reports.json.parseToJsonElement(request.decodeToString()).jsonArray
        require(sources.size in 1..8)
        val embedded = args.drop(1).associate { it.substringBefore('=') to it.substringAfter('=') }
        require(embedded.size == args.size - 1 && embedded.keys.all { key ->
            (0 until sources.size).any { key == "source$it" }
        })
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        out.resolve("request.json").writeBytes(request)
        Reports.writeJson(out.resolve("provenance.json").path, InstalledBuild.reference)
        for ((index, value) in sources.withIndex()) {
            val input = value.jsonObject
            require(input.keys == setOf("suite", "problem", "sha256"))
            val bytes = trajectorySourceBytes(input.getValue("suite").jsonPrimitive.content,
                input.getValue("problem").jsonPrimitive.content, input.getValue("sha256").jsonPrimitive.content,
                embedded["source$index"])
            val directory = out.resolve("$index").apply { mkdirs() }
            directory.resolve("source.xml").writeBytes(bytes)
            val stop = Cancellation.after(30.seconds)
            val started = System.nanoTime()
            println("source $index: ${input.getValue("problem").jsonPrimitive.content}, ${bytes.size} original bytes")
            val parsed = Xcsp3.parse(bytes.decodeToString(), recoveryCancellation = stop)
            val problem = parsed.problem
            val hints = parsed.definitionHints
            println("source $index: ${problem.numIntVars} integers, ${problem.numBoolVars} Booleans, " +
                "${problem.numFactors} factors")
            val maintained = DefinitionalSweep.fromSource(problem, hints, stop)
            val network = maintained?.network(problem.numIntVars, problem.numBoolVars)
            directory.resolve("applicability.json").writeText(buildJsonObject {
                put("input", input)
                put("revision", Reports.readGitSha())
                put("sourceMode", if ("source$index" in embedded) "embedded" else "catalog")
                put("declaredSemantics", parsed.semantics.declared.toString())
                put("effectiveSemantics", parsed.semantics.effective.toString())
                put("definitionPolicy", "typed source hints only; inferred native definitions excluded")
                put("settings", problem.settings.toString())
                put("javaRuntime", System.getProperty("java.runtime.version"))
                put("os", System.getProperty("os.name"))
                put("architecture", System.getProperty("os.arch"))
                put("integers", problem.numIntVars)
                put("booleans", problem.numBoolVars)
                put("factors", problem.numFactors)
                put("sourceHintKinds", buildJsonObject {
                    for ((kind, members) in hints.groupBy { it::class.simpleName }) put(kind.toString(), members.size)
                })
                put("sourceDefinitions", buildJsonArray {
                    if (network != null) for (node in 0 until network.size) add(buildJsonObject {
                        val definition = network.node(node)
                        put("kind", definition::class.simpleName)
                        put("output", definition.out)
                        put("boolean", definition.outIsBool)
                        put("integerInputs", JsonArray(definition.intInputs.map(::JsonPrimitive)))
                        put("booleanInputs", JsonArray(definition.boolInputs.map(::JsonPrimitive)))
                    })
                })
                put("retainedSourceDefinitions", buildJsonObject {
                    for (view in ChannelView.entries) put(view.name, view.sweep(maintained, stop)?.size ?: 0)
                })
                put("inverseRoleView", inverseAssignmentView(problem, stop) != null)
                put("cancelled", stop())
                put("preparationNanos", System.nanoTime() - started)
                put("workingProjection", false)
                put("sourceVerdictClaim", false)
            }.toString())
            println("source $index: ${maintained?.size ?: 0} admitted source definitions, cancelled=${stop()}")
        }
    }
}
