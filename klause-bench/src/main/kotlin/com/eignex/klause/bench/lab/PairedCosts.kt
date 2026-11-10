package com.eignex.klause.bench.lab

import com.eignex.klause.bench.lab.PairedAudit.ACCEPTED
import com.eignex.klause.bench.lab.PairedAudit.int
import com.eignex.klause.bench.lab.PairedAudit.text
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Outcomes and observed costs of retained rows, each case and attempt counted once per scope. */
internal object PairedCosts {
    fun outcomes(rows: List<AuditedRow>, arm: String): ArmOutcomes {
        val records = rows.filter { it.state in ACCEPTED }
            .flatMap { row -> row.cases.filter { it.arm == arm && it.status == PairedRows.DONE } }
        val native = records.groupingBy { case ->
            val record = requireNotNull(case.record)
            when {
                record.feasible == false && record.proven -> "proven-infeasible"
                record.feasible == true && record.proven && record.kind == "optimize" -> "proven-optimum"
                record.feasible == true && PairedRows.terminal(record.proven, record.kind, true) -> "satisfied"
                record.feasible == true -> "feasible"
                else -> "unknown"
            }
        }.eachCount().toSortedMap()
        val witnesses = records.groupingBy { case ->
            when {
                case.checkState == null -> "unrequested"
                case.checkState == "valid" && !case.witnessBound -> "valid-unbound"
                else -> case.checkState
            }
        }.eachCount().toSortedMap()
        return ArmOutcomes(
            rows = records.size,
            native = native,
            checkedWitnesses = witnesses,
            withdrawnByChecker = records.count { it.checkState == "invalid" },
            nativeProofsWithoutIndependentProof = records.count { it.record?.proven == true },
        )
    }

    fun costs(rows: List<AuditedRow>, arm: String, commands: List<JsonObject>): CaseCosts =
        costs(rows.filter { it.state in ACCEPTED }.flatMap { row -> row.cases.filter { it.arm == arm } }, commands)

    fun costs(cases: List<AuditedCase>, commands: List<JsonObject>): CaseCosts {
        val unique = cases.distinctBy { it.index }
        val wall = commands.associate { command ->
            val started = text(command, "startedAt")?.toLongOrNull()
            val finished = text(command, "finishedAt")?.toLongOrNull()
            int(command, "index") to if (started != null && finished != null) finished - started else null
        }
        return CaseCosts(
            cases = unique.size,
            solverElapsedMs = total(unique.map { it.record?.elapsedMs?.toDouble() }),
            cliCpuSeconds = total(unique.map { it.cliCpuSeconds }),
            adapterCpuSeconds = total(unique.map { it.adapterCpuSeconds }),
            commandWallMs = total(unique.map { wall[it.index]?.toDouble() }),
            checkerElapsedMs = total(unique.map { it.checkerElapsedMs?.toDouble() }),
        )
    }

    fun attempts(execution: JsonArray): AttemptSummary {
        val entries = execution.map { it.jsonObject }
        val identities = entries.map { entry ->
            text(entry["session"] as? JsonObject, "id") to text(entry, "generation")
        }
        val repeated = identities.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        val unique = entries.zip(identities).distinctBy { it.second }.map { it.first }
        val classes = unique.groupBy { text(it["session"] as? JsonObject, "executionClass") ?: "unknown" }
            .map { (executionClass, attempts) -> attemptCosts(executionClass, attempts) }
        val solveRows = unique.filter { text(it["session"] as? JsonObject, "executionClass") == "solve" }
            .sumOf { committed(it) }
        return AttemptSummary(
            classes = classes,
            committedRows = solveRows,
            errors = repeated.map { (id, generation) -> "attempt $id generation $generation listed more than once" },
        )
    }

    private fun attemptCosts(executionClass: String, attempts: List<JsonObject>): AttemptCosts {
        val uncommitted = attempts.filter { committed(it) == 0 }
        return AttemptCosts(
            executionClass = executionClass,
            attempts = attempts.size,
            committed = attempts.size - uncommitted.size,
            uncommitted = uncommitted.size,
            elapsedMs = total(attempts.map(::elapsed)),
            cpuSeconds = total(attempts.map(::cpuSeconds)),
            uncommittedElapsedMs = total(uncommitted.map(::elapsed)),
            scopes = attempts.mapNotNull { text(it["resources"] as? JsonObject, "scope") }.distinct().sorted(),
        )
    }

    private fun committed(attempt: JsonObject): Int = text(attempt, "committed")?.toIntOrNull() ?: 0

    private fun elapsed(attempt: JsonObject): Double? =
        text(attempt["resources"] as? JsonObject, "elapsedMs")?.toDoubleOrNull()

    private fun cpuSeconds(attempt: JsonObject): Double? {
        val counters = (attempt["resources"] as? JsonObject)?.get("counters") as? JsonObject
        val usage = text(counters, "cpu.stat")?.lineSequence()?.firstOrNull { it.startsWith("usage_usec ") }
        return usage?.substringAfter(' ')?.toDoubleOrNull()?.div(MICROS)
    }

    private fun total(values: List<Double?>) =
        CostTotal(values.filterNotNull().sum(), values.count { it != null }, values.count { it == null })

    private const val MICROS = 1_000_000.0
}
