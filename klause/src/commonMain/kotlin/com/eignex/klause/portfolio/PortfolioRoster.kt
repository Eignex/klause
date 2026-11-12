package com.eignex.klause.portfolio

/** An arm a roster can seat: the [role] it declares, or null for a recipe outside the catalogs, and [make],
 *  which builds a fresh config for every slot the arm takes. */
internal class RosterCandidate(val role: ArmRole?, val make: () -> WorkerConfig)

/** One engine's candidates in rank order. Past their end the rank repeats [repeating] on fresh seeds. */
internal class RosterRank(val arms: List<RosterCandidate>, val repeating: List<RosterCandidate> = arms) {
    fun at(position: Int): RosterCandidate? = arms.getOrNull(position)
        ?: repeating.takeIf { it.isNotEmpty() }?.let { it[(position - arms.size) % it.size] }
}

/** An arm in a roster and the role it declares. */
internal class SeatedArm(val config: WorkerConfig, val role: ArmRole?)

/**
 * Seats a roster from declared roles. The best arm of a role is the first to declare it when the engines' ranks
 * are read one position at a time, in the order given. The roster takes the best arm of every needed role, then
 * fills by rank: the engines take turns, each seating its next arm. Neither step reads the roster size, so a
 * smaller roster is a prefix of a larger one.
 */
internal object PortfolioRoster {
    fun seat(needed: List<ArmRole>, ranks: List<RosterRank>, count: Int): List<SeatedArm> {
        require(count >= 1) { "count must be ≥ 1" }
        val taken = ranks.map { BooleanArray(it.arms.size) }
        val seated = ArrayList<RosterCandidate>(count)
        val depth = ranks.maxOf { it.arms.size }
        for (role in needed) {
            if (seated.size == count) break
            best@ for (position in 0 until depth) {
                for ((engine, rank) in ranks.withIndex()) {
                    val arm = rank.arms.getOrNull(position) ?: continue
                    if (taken[engine][position] || arm.role != role) continue
                    taken[engine][position] = true
                    seated += arm
                    break@best
                }
            }
        }
        val next = IntArray(ranks.size)
        while (seated.size < count) {
            val before = seated.size
            for ((engine, rank) in ranks.withIndex()) {
                if (seated.size == count) break
                while (next[engine] < rank.arms.size && taken[engine][next[engine]]) next[engine]++
                seated += rank.at(next[engine]++) ?: continue
            }
            if (seated.size == before) break
        }
        return seated.map { SeatedArm(it.make(), it.role) }
    }
}
