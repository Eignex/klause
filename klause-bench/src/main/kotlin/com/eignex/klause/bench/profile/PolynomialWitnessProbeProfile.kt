package com.eignex.klause.bench.profile

import com.eignex.klause.bench.metric.InstalledBuild
import com.eignex.klause.bench.metric.sha256
import com.eignex.klause.bench.report.Reports
import com.eignex.klause.ir.PolynomialRealFormula
import com.eignex.klause.util.Cancellation
import java.io.File
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds

internal object PolynomialWitnessProbeProfile {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1 && args[0].startsWith("request64="))
        val request = Base64.getDecoder().decode(args[0].substringAfter('='))
        require(request.size <= 48 * 1024)
        val cases = Json.decodeFromString<List<ProbeSource>>(request.toString(Charsets.UTF_8))
        require(cases.size in 1..8 && cases.map { it.id }.distinct().size == cases.size)
        val out = File(requireNotNull(System.getenv("OUT"))).apply { mkdirs() }
        File(out, "request.json").writeBytes(request)
        Reports.writeJson(File(out, "provenance.json").path, InstalledBuild.current)
        File(out, "revision.txt").writeText(Reports.readGitSha() ?: "unavailable")
        val access = ProbeAccess()
        for ((index, source) in cases.withIndex()) {
            val bytes = Base64.getDecoder().decode(source.base64)
            require(bytes.size <= 32 * 1024 && sha256(bytes) == source.sha256)
            val directory = File(out, index.toString()).apply { mkdirs() }
            File(directory, "source.smt2").writeBytes(bytes)
            val records = (0..1).flatMap { round ->
                (if (round == 0) listOf(false, true) else listOf(true, false)).map { observed ->
                    access.measure(bytes.toString(Charsets.UTF_8), observed).copy(round = round)
                }
            }
            File(directory, "probe.json").writeText(Json.encodeToString(records))
            require(records.map { it.result to it.stats }.distinct().size == 1) {
                "Observation changed native work or producer outcome for ${source.id}"
            }
        }
    }

    internal class ProbeAccess {
        private val prefix = "com.eignex.klause.theory.qfnra."
        private val parser = Class.forName("com.eignex.klause.formats.smtlib.SmtLibPolynomial")
        private val parse = parser.getMethod("parse", String::class.java, Cancellation::class.java,
            Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
        private val sourceType = Class.forName("com.eignex.klause.formats.smtlib.SmtLibPolynomialProblem")
        private val formula = sourceType.getMethod("getFormula")
        private val realCount = sourceType.getMethod("getRealCount")
        private val boolCount = sourceType.getMethod("getBoolCount")
        private val limitsType = Class.forName("com.eignex.klause.arithmetic.polynomial.PolynomialLimits")
        private val paramsType = Class.forName(prefix + "QfNraParams")
        private val conditionType = Class.forName(prefix + "PolynomialCondition")
        private val queryType = Class.forName(prefix + "QfNraQuery")
        private val params = paramsType.getConstructor(Cancellation::class.java, java.lang.Long::class.java, limitsType)
        private val condition = conditionType.getConstructor(Map::class.java, Map::class.java, Map::class.java)
        private val query = queryType.getConstructor(PolynomialRealFormula::class.java,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, paramsType, conditionType)
        private val next = queryType.getMethod("next")
        private val stats = queryType.getMethod("getStats")
        private val work = queryType.getDeclaredField("work").apply { isAccessible = true }
        private val workType = Class.forName(prefix + "QfNraWork")
        private val spent = workType.getDeclaredField("spent").apply { isAccessible = true }
        private val coupledEnd = workType.getDeclaredField("coupledEnd").apply { isAccessible = true }
        private val probeEnd = workType.getDeclaredField("probeEnd").apply { isAccessible = true }
        private val fixings = Class.forName(prefix + "PolynomialPointFixingsKt").methods.single {
            it.name == "polynomialPointFixings"
        }

        fun measure(text: String, observed: Boolean): ProbeRecord {
            val source = parse.invoke(parser.getField("INSTANCE").get(null), text, Cancellation.Never, false, false)
            val sourceFormula = formula.invoke(source)
            val fixed = fixings.invoke(null, sourceFormula, {})
            val stops = ArrayList<ProbeStop>()
            var owner: Any? = null
            var captured = false
            val observer = Cancellation {
                val current = owner
                if (observed && current != null && !captured) {
                    val boundary = coupledEnd.get(current) as? Long
                    val charged = spent.getLong(current)
                    if (boundary != null && charged >= boundary) {
                        captured = true
                        stops += ProbeStop(charged, boundary, probeEnd.get(current) as? Long,
                            Thread.currentThread().stackTrace.map { it.toString() })
                    }
                }
                false
            }
            val settings = params.newInstance(observer or Cancellation.after(60.seconds), null,
                limitsType.getConstructor().newInstance())
            val check = query.newInstance(sourceFormula, boolCount.invoke(source), realCount.invoke(source), settings,
                condition.newInstance(emptyMap<Int, Boolean>(), fixed, fixed))
            owner = work.get(check)
            val normalized = next.invoke(check)
            val result = normalized ?: next.invoke(check)
            return ProbeRecord(0, observed, result?.javaClass?.simpleName ?: "PendingAfterProbe",
                stats.invoke(check).toString(), stops.toList())
        }
    }
}

@Serializable
private data class ProbeSource(val id: String, val base64: String, val sha256: String)

@Serializable
internal data class ProbeRecord(
    val round: Int,
    val observed: Boolean,
    val result: String,
    val stats: String,
    val stops: List<ProbeStop>,
)

@Serializable
internal data class ProbeStop(
    val workSpent: Long,
    val coupledEnd: Long,
    val probeEnd: Long?,
    val stack: List<String>,
)
