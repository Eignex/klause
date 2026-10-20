package com.eignex.klause.bench.metric

import com.eignex.klause.bench.report.Reports
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.floor

/**
 * MiniZinc Challenge pairwise scoring of saved solve records. Objectives compare exactly, so values
 * past binary64 precision still order correctly; time breaks ties only in complete mode.
 */
internal object RecordComparison {

    /** One shared problem's outcome: [comparison] is the sign of A against B, [scoreA] and [scoreB]
     *  are the Borda points, [unsound] flags A beating a proven optimum or SAT against UNSAT. */
    data class Outcome(
        val comparison: Int,
        val scoreA: Double,
        val scoreB: Double,
        val unsound: Boolean,
        val timeA: Long,
        val timeB: Long,
    )

    fun compare(a: JsonObject, b: JsonObject, complete: Boolean): Outcome {
        val av = objective(a)
        val bv = objective(b)
        val quality = (if (av == null || bv == null) 0 else av.compareTo(bv).sign()) *
            (if (a.flag("maximize") == true) 1 else -1)
        val comparison = when {
            solved(a) != solved(b) -> if (solved(a)) 1 else -1
            !solved(a) -> 0
            complete && optimal(a) != optimal(b) -> if (optimal(a)) 1 else -1
            else -> quality
        }
        val at = timeUsed(a)
        val bt = timeUsed(b)
        val tieA = if (complete && at + bt != 0L) bt.toDouble() / (at + bt) else 0.5
        val pa = if (!solved(a) || comparison < 0) 0.0 else if (comparison > 0) 1.0 else tieA
        val pb = if (!solved(b) || comparison > 0) 0.0 else if (comparison < 0) 1.0 else 1 - tieA
        val unsound = (quality > 0 && b.flag("proven") == true) ||
            (a.flag("feasible") == true && b.flag("feasible") == false)
        return Outcome(comparison, pa, pb, unsound, at, bt)
    }

    /** Scores every problem present in both record directories and renders the report. */
    fun render(dirA: File, dirB: File, complete: Boolean): String {
        val byProblem = read(dirB).associateBy { it.text("problem") }
        val rows = read(dirA).mapNotNull { a ->
            byProblem[a.text("problem")]?.let { b -> Triple(a, b, compare(a, b, complete)) }
        }
        val mode = if (complete) "complete" else "incomplete"
        return buildString {
            appendLine("=== MiniZinc Challenge $mode scoring:  ${dirA.name}  vs  ${dirB.name}   (${rows.size} shared) ===")
            val unsound = rows.filter { it.third.unsound }.map { it.first.text("problem") }
            if (unsound.isNotEmpty()) {
                appendLine("  !! UNSOUND (A beats a proven optimum / SAT-vs-UNSAT): " + unsound.joinToString(", "))
            }
            for ((a, b, o) in rows) {
                val direction = if (a.flag("maximize") == true) " (max)" else ""
                appendLine(
                    "  A${if (o.scoreA >= o.scoreB) '+' else ' '}${format(o.scoreA)} [${a.text("problem")}]$direction " +
                        "A=${display(a)}${if (optimal(a)) "!" else ""}@${o.timeA}ms  " +
                        "B=${display(b)}${if (optimal(b)) "!" else ""}@${o.timeB}ms",
                )
            }
            val wins = rows.count { it.third.comparison > 0 }
            val losses = rows.count { it.third.comparison < 0 }
            val ties = rows.count { it.third.comparison == 0 && solved(it.first) && solved(it.second) }
            val unknown = rows.count { !solved(it.first) }
            val missed = rows.filter { solved(it.second) && !solved(it.first) }.map { it.first.text("problem") }
            appendLine()
            appendLine(
                "  BORDA SCORE:  ${dirA.name} = ${format(rows.sumOf { it.third.scoreA })}   " +
                    "${dirB.name} = ${format(rows.sumOf { it.third.scoreB })}   (of ${rows.size})",
            )
            appendLine("  A strict wins $wins, strict losses $losses, ties $ties, A-unsolved $unknown")
            append("  B-solved that A did not: ${missed.size}")
            if (missed.isNotEmpty()) append("  -> " + missed.joinToString(", "))
            appendLine()
        }
    }

    private fun read(directory: File): List<JsonObject> =
        directory.listFiles { file -> file.isFile && file.name.endsWith(".json") }.orEmpty()
            .sortedBy { it.name }
            .map { Reports.json.parseToJsonElement(it.readText()).jsonObject }

    private fun objective(record: JsonObject): ExactObjective? =
        (record.text("exactObjective") ?: record.text("objective"))?.let(ExactObjective::parse)

    private fun solved(record: JsonObject): Boolean = record["feasible"].let { it != null && it != JsonNull }

    private fun optimal(record: JsonObject): Boolean =
        record.flag("proven") == true || record.flag("feasible") == false ||
            (record.text("kind") == "satisfy" && solved(record))

    private fun timeUsed(record: JsonObject): Long {
        if (solved(record)) {
            record.long("timeToBestMs")?.let { return it }
            val solveTime = (record["stats"] as? JsonObject)?.text("solveTime")?.toDoubleOrNull()
            if (solveTime != null && solveTime.isFinite() && solveTime >= 0) return floor(solveTime * 1000).toLong()
            record.long("elapsedMs")?.let { return it }
        }
        return record.long("budgetMs") ?: 0
    }

    private fun display(record: JsonObject): String = when {
        record.flag("feasible") == false -> "UNSAT"
        else -> listOf("exactObjective", "objective", "feasible").firstNotNullOfOrNull { record.text(it) }
            ?.lowercase() ?: "-"
    }

    private fun format(value: Double): String =
        BigDecimal(value).setScale(2, RoundingMode.HALF_EVEN).stripTrailingZeros().toPlainString()

    private fun Int.sign(): Int = compareTo(0)

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.flag(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
}
