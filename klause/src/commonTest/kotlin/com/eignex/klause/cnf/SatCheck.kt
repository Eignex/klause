package com.eignex.klause.cnf

import com.eignex.klause.solver.Lit

/** Test-only SAT oracle: unit propagation + brute-force over residual unassigned vars. */
internal object SatCheck {

    /** [fixed] is a flat `[var, value(0|1), var, value(0|1), ...]` array. */
    fun isSat(numVars: Int, clauses: List<IntArray>, fixed: IntArray): Boolean {
        val assign = IntArray(numVars)
        var i = 0
        while (i < fixed.size) {
            val v = fixed[i]; val target = if (fixed[i + 1] == 1) 1 else -1
            if (assign[v] != 0 && assign[v] != target) return false
            assign[v] = target
            i += 2
        }

        if (!propagate(clauses, assign)) return false

        var unassignedCount = 0
        for (a in assign) if (a == 0) unassignedCount++
        if (unassignedCount > 20) {
            error("Unit propagation left $unassignedCount unassigned vars; brute force capped at 20.")
        }
        if (unassignedCount == 0) {
            return clauses.all { clause ->
                clause.any { lit ->
                    val v = Lit.variable(lit)
                    val sign = if (Lit.isPositive(lit)) 1 else -1
                    assign[v] == sign
                }
            }
        }

        val unassignedIds = IntArray(unassignedCount)
        var p = 0
        for (v in 0 until numVars) if (assign[v] == 0) unassignedIds[p++] = v

        val snapshot = assign.copyOf()
        val total = 1 shl unassignedCount
        for (mask in 0 until total) {
            snapshot.copyInto(assign)
            for (j in 0 until unassignedCount) {
                assign[unassignedIds[j]] = if ((mask shr j) and 1 == 1) 1 else -1
            }
            val ok = clauses.all { clause ->
                clause.any { lit ->
                    val v = Lit.variable(lit)
                    val sign = if (Lit.isPositive(lit)) 1 else -1
                    assign[v] == sign
                }
            }
            if (ok) return true
        }
        return false
    }

    private fun propagate(clauses: List<IntArray>, assign: IntArray): Boolean {
        var changed = true
        while (changed) {
            changed = false
            for (clause in clauses) {
                if (clause.isEmpty()) return false
                var unitLit = 0
                var unitCount = 0
                var satisfied = false
                for (lit in clause) {
                    val v = Lit.variable(lit)
                    val sign = if (Lit.isPositive(lit)) 1 else -1
                    val a = assign[v]
                    if (a == 0) {
                        unitLit = lit
                        unitCount++
                        if (unitCount > 1) break
                    } else if (a == sign) {
                        satisfied = true
                        break
                    }
                }
                if (satisfied) continue
                when (unitCount) {
                    0 -> return false
                    1 -> {
                        val v = Lit.variable(unitLit)
                        assign[v] = if (Lit.isPositive(unitLit)) 1 else -1
                        changed = true
                    }
                }
            }
        }
        return true
    }
}
