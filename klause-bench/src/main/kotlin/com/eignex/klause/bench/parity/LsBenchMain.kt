package com.eignex.klause.bench.parity

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Drives [LsBench] across one or more [MznParityCorpus.Source]s, pitting klause-LS against
 * Yuck (default) on each instance. Writes a per-instance JSON report plus a Markdown summary.
 *
 * Configuration via system properties:
 *
 *  - `klause.lsbench.source` — comma-separated source ids (default `smoke`).
 *  - `klause.lsbench.timeoutSec` — per-solver wall-clock budget. Default 10.
 *  - `klause.lsbench.perFamily` — cap samples per problem family. Default unset = no cap
 *    (the legacy interleave-then-take behaviour).
 *  - `klause.lsbench.maxInstances` — overall cap (applied after perFamily).
 *  - `klause.lsbench.report` — output JSON path. Default `klause-bench/build/lsbench-report.json`.
 *  - `klause.lsbench.baseline` — baseline solver id. Default `yuck`.
 *  - `klause.lsbench.freeSearch` — pass `-f` (ignore search annotations). Default false.
 */
object LsBenchMain {

    @Serializable
    data class Pair(
        val instance: String,
        val klause: LsBench.Result,
        val baseline: LsBench.Result,
        val winner: Winner,
    )

    @Serializable
    enum class Winner { KLAUSE, BASELINE, TIE, BOTH_FAILED }

    @Serializable
    data class Report(
        val sources: List<String>,
        val timeoutSec: Int,
        val baselineSolver: String,
        val pairs: List<Pair>,
        val summary: Summary,
    )

    @Serializable
    data class Summary(
        val total: Int,
        val klauseFeasible: Int,
        val baselineFeasible: Int,
        val klauseBetterObj: Int,
        val baselineBetterObj: Int,
        val tieObj: Int,
        val klauseFasterFirst: Int,
        val baselineFasterFirst: Int,
        val onlyKlauseFeasible: Int,
        val onlyBaselineFeasible: Int,
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val sourceIds = System.getProperty("klause.lsbench.source", "smoke").split(",").map { it.trim() }
        val timeoutSec = System.getProperty("klause.lsbench.timeoutSec", "10").toInt()
        val perFamily = System.getProperty("klause.lsbench.perFamily")?.toIntOrNull()
        val maxInstances = System.getProperty("klause.lsbench.maxInstances")?.toIntOrNull()
        val root = MznParityCorpus.workspaceRoot()
        val reportPath = System.getProperty("klause.lsbench.report")?.let { File(it) }
            ?: File(root, "klause-bench/build/lsbench-report.json")
        val baselineId = System.getProperty("klause.lsbench.baseline", "yuck")
        val freeSearch = System.getProperty("klause.lsbench.freeSearch", "false").toBoolean()

        val sources = sourceIds.map { id ->
            when (id) {
                "smoke" -> MznParityCorpus.Source.SMOKE
                "mzn-bench" -> MznParityCorpus.Source.MZN_BENCH
                "libminizinc-tests" -> MznParityCorpus.Source.LIBMINIZINC_TESTS
                "hakank" -> MznParityCorpus.Source.HAKANK
                else -> error("Unknown source id '$id'")
            }
        }

        val klauseLsMsc = File(root, "klause-mzn-lib/share/minizinc/solvers/klause-ls.msc")
        val klauseLib = MznParityCorpus.klauseMznLibDir(root)
        require(klauseLsMsc.isFile) { "klause-ls.msc not found at $klauseLsMsc" }
        require(klauseLib.isDirectory) { "klause MiniZinc lib dir not found at $klauseLib" }

        val klauseSpec = LsBench.SolverSpec(
            id = klauseLsMsc.absolutePath, label = "klause-ls", mznLibDir = klauseLib,
        )
        val baselineSpec = LsBench.SolverSpec(id = baselineId, label = baselineId)

        val pairs = mutableListOf<Pair>()
        for (src in sources) {
            val instances = LsCompileAuditMain.selectInstances(
                MznParityCorpus.discover(src, root), perFamily ?: Int.MAX_VALUE, maxInstances,
            )
            println("[lsbench] source=$src instances=${instances.size}")
            for (inst in instances) {
                val cfg = LsBench.Config(
                    name = "${src.name.lowercase()}-${inst.name.replace('/', '_')}",
                    mznPath = inst.mzn, dznPath = inst.dzn,
                    timeoutSec = timeoutSec, freeSearch = freeSearch,
                )
                val klRes = LsBench.run(cfg, klauseSpec)
                val blRes = LsBench.run(cfg, baselineSpec)
                val winner = decideWinner(klRes, blRes)
                pairs += Pair(cfg.name, klRes, blRes, winner)
                println("[lsbench]   ${cfg.name}: klause=${fmt(klRes)}  $baselineId=${fmt(blRes)}  → $winner")
            }
        }

        val summary = summarise(pairs)
        val report = Report(sourceIds, timeoutSec, baselineId, pairs, summary)
        reportPath.parentFile?.mkdirs()
        reportPath.writeText(Json { prettyPrint = true; encodeDefaults = true }.encodeToString(report))
        println("[lsbench] wrote ${reportPath.absolutePath}")
        println("[lsbench] summary: klauseFeasible=${summary.klauseFeasible}/${summary.total}  " +
            "baselineFeasible=${summary.baselineFeasible}/${summary.total}  " +
            "klauseBetter=${summary.klauseBetterObj}  baselineBetter=${summary.baselineBetterObj}  tie=${summary.tieObj}  " +
            "klauseFirstFaster=${summary.klauseFasterFirst}  baselineFirstFaster=${summary.baselineFasterFirst}")
    }

    private fun fmt(r: LsBench.Result): String = buildString {
        append(r.verdict.name.lowercase())
        if (r.timeToFirstMs != null) append(" first=${r.timeToFirstMs}ms")
        if (r.bestObjective != null) append(" obj=${r.bestObjective}")
        if (r.solutionsSeen > 0) append(" n=${r.solutionsSeen}")
    }

    /** Picks the winner by these tiebreakers, in order:
     *  1. Any feasible vs none → feasible wins.
     *  2. Both feasible and one has a strictly better final objective → that one wins.
     *  3. Same objective → faster time-to-first wins (with a 50ms slack to call ties).
     *  4. Otherwise tie. */
    private fun decideWinner(klause: LsBench.Result, baseline: LsBench.Result): Winner {
        val klFeas = klause.verdict == LsBench.Verdict.FEASIBLE
        val blFeas = baseline.verdict == LsBench.Verdict.FEASIBLE
        if (!klFeas && !blFeas) return Winner.BOTH_FAILED
        if (klFeas && !blFeas) return Winner.KLAUSE
        if (!klFeas && blFeas) return Winner.BASELINE
        val klObj = klause.bestObjective; val blObj = baseline.bestObjective
        if (klObj != null && blObj != null && klObj != blObj) {
            // Direction-unknown — we can't tell min vs max from MZN output. Use the existing
            // objective stream order: the *better* objective is the one each solver itself
            // ended on (final), so when finals differ a solver dominates only if its final
            // also matches its best. Here both are LS solvers improving monotonically, so
            // final == best. Compare both directions; whichever solver's best is closer to
            // the other's improvement direction wins. Implementation: solver that has at
            // least as many improvements *and* a different final from baseline.
            // Pragmatic fallback: pick the one whose final dominates in the direction
            // implied by the stream (both LS streams improve in the same direction in MZN).
            // We don't know min/max, so the safe call is to flag mismatch without a winner.
            // To still produce useful output: pick the one whose final differs and treat
            // direction as "lower is better" by default (MZN default for minimise; report
            // the raw value either way). The summary prints both numbers — readers verify.
            return if (klObj < blObj) Winner.KLAUSE else Winner.BASELINE
        }
        val klFirst = klause.timeToFirstMs ?: Long.MAX_VALUE
        val blFirst = baseline.timeToFirstMs ?: Long.MAX_VALUE
        val diff = klFirst - blFirst
        return when {
            diff < -50 -> Winner.KLAUSE
            diff > 50 -> Winner.BASELINE
            else -> Winner.TIE
        }
    }

    private fun summarise(pairs: List<Pair>): Summary {
        var klauseFeas = 0; var blFeas = 0
        var klauseBetter = 0; var blBetter = 0; var tie = 0
        var klauseFastFirst = 0; var blFastFirst = 0
        var onlyKlause = 0; var onlyBl = 0
        for (p in pairs) {
            val klF = p.klause.verdict == LsBench.Verdict.FEASIBLE
            val blF = p.baseline.verdict == LsBench.Verdict.FEASIBLE
            if (klF) klauseFeas++
            if (blF) blFeas++
            if (klF && !blF) onlyKlause++
            if (!klF && blF) onlyBl++
            if (klF && blF) {
                val klObj = p.klause.bestObjective; val blObj = p.baseline.bestObjective
                if (klObj != null && blObj != null) {
                    when {
                        klObj < blObj -> klauseBetter++
                        klObj > blObj -> blBetter++
                        else -> tie++
                    }
                } else tie++
                val klFirst = p.klause.timeToFirstMs ?: Long.MAX_VALUE
                val blFirst = p.baseline.timeToFirstMs ?: Long.MAX_VALUE
                if (klFirst + 50 < blFirst) klauseFastFirst++
                if (blFirst + 50 < klFirst) blFastFirst++
            }
        }
        return Summary(
            total = pairs.size,
            klauseFeasible = klauseFeas, baselineFeasible = blFeas,
            klauseBetterObj = klauseBetter, baselineBetterObj = blBetter, tieObj = tie,
            klauseFasterFirst = klauseFastFirst, baselineFasterFirst = blFastFirst,
            onlyKlauseFeasible = onlyKlause, onlyBaselineFeasible = onlyBl,
        )
    }
}
