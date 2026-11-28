package com.eignex.klause.portfolio

// role is null for a recipe outside the catalogs; make builds a fresh config for every slot the arm takes.
internal class RosterCandidate(val role: ArmRole?, val make: () -> WorkerConfig)

// Past its arms the rank repeats `repeating` on fresh seeds. A pinned rank is one the caller chose, whose first
// arm is seated whatever roles the model needs.
internal class RosterRank(
    val arms: List<RosterCandidate>,
    val repeating: List<RosterCandidate> = arms,
    val pinned: Boolean = false,
) {
    fun at(position: Int): RosterCandidate? = arms.getOrNull(position)
        ?: repeating.takeIf { it.isNotEmpty() }?.let { it[(position - arms.size) % it.size] }
}

// needed says the roster seated the arm for its role; otherwise it filled a slot by rank.
internal class SeatedArm(val config: WorkerConfig, val role: ArmRole?, val needed: Boolean = false) {
    val report: String get() = if (needed) checkNotNull(role).label else listOfNotNull("rank", role?.label)
        .joinToString("/")
}

// The best arm of a role is the first to declare it when the engines' ranks are read one position at a time, in
// the order given. The roster takes the first arm of every pinned rank in lead order, the best arm of every needed
// role, then fills by rank with the engines taking turns. No step reads the roster size, so a smaller roster is a
// prefix of a larger one.
internal object PortfolioRoster {
    fun seat(
        needed: List<ArmRole>,
        ranks: List<RosterRank>,
        count: Int,
        lead: List<RosterRank> = ranks,
    ): List<SeatedArm> {
        require(count >= 1) { "count must be ≥ 1" }
        val taken = ranks.map { BooleanArray(it.arms.size) }
        val seated = ArrayList<RosterCandidate>(count)
        for (rank in lead) {
            if (!rank.pinned || rank.arms.isEmpty() || seated.size == count) continue
            taken[ranks.indexOf(rank)][0] = true
            seated += rank.arms[0]
        }
        val pinned = seated.size
        var filled = pinned
        val depth = ranks.maxOf { it.arms.size }
        for (role in needed) {
            if (seated.size == count) break
            best@ for (position in 0 until depth) {
                for ((engine, rank) in ranks.withIndex()) {
                    val arm = rank.arms.getOrNull(position) ?: continue
                    if (taken[engine][position] || arm.role != role) continue
                    taken[engine][position] = true
                    seated += arm
                    filled = seated.size
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
        return seated.mapIndexed { seat, arm ->
            SeatedArm(arm.make(), arm.role, needed = seat in pinned until filled)
        }
    }
}
