package com.eignex.klause.cnf

import com.eignex.klause.solver.Lit
import com.eignex.klause.solver.factor.reginTarjanScc
import com.eignex.klause.util.IntArrayList

/**
 * Pure, model-preserving CNF simplification passes for bit-blasted [CnfProblem]s (#315).
 *
 * Call [subsume] on the output of [BitBlaster.compile] before handing the clauses to a SAT
 * back end. Cheap (roughly linear in the clause database per round) and always sound, so the
 * automatic bitblast→SAT pipeline runs it by default — eager encodings emit many redundant
 * clauses, especially Tseitin gates. A caller that wants the raw encoding can skip it.
 *
 * Every pass preserves the set of satisfying assignments over the CNF variables, so the
 * decode tables ([CnfProblem.boolVarToCnfVar], [CnfProblem.intVarBits], [CnfProblem.intVarMin])
 * are carried through unchanged and a model of the simplified problem decodes exactly as one
 * of the original. The passes:
 *  - **tautology removal** — a clause containing both `l` and `¬l` is always true.
 *  - **subsumption** — if clause `A ⊆ B` (as literal sets) then `B` is implied by `A` and
 *    removable; duplicate clauses are collapsed to one.
 *  - **self-subsuming resolution** — if `A = R ∪ {l}` and `B = S ∪ {¬l}` with `R ⊆ S`, then
 *    resolving on `l` yields `S`, which subsumes `B`; so `¬l` is dropped from `B`. Soundness:
 *    the only assignments this removes (all of `S` false, `l` false) already falsify `A`, so
 *    they were never models.
 */
object CnfSimplify {

    private const val DEFAULT_MAX_ROUNDS = 8

    /** Run [subsumeClauses] over [problem]'s clauses, returning a new [CnfProblem] with the
     *  same variables and decode tables. */
    fun subsume(problem: CnfProblem, maxRounds: Int = DEFAULT_MAX_ROUNDS): CnfProblem = CnfProblem(
        numVars = problem.numVars,
        clauses = subsumeClauses(problem.clauses, maxRounds),
        boolVarToCnfVar = problem.boolVarToCnfVar,
        intVarBits = problem.intVarBits,
        intVarMin = problem.intVarMin,
    )

    /**
     * Full simplification of a bit-blasted [problem]: [subsumeClauses], then equivalent-literal
     * substitution (#320), then bounded variable elimination over the **auxiliary** variables
     * (#316), with [subsumeClauses] between and after to mop up clauses the rewrites made
     * redundant.
     *
     * The variables named by the decode tables ([CnfProblem.boolVarToCnfVar],
     * [CnfProblem.intVarBits]) are *protected* — only Tseitin/aux variables are eliminated, so
     * [CnfProblem.numVars] and the decode tables are unchanged and a model still decodes
     * directly. Eliminated aux variables simply become unconstrained in the result; the
     * resolvents preserve every assignment of the protected variables.
     */
    fun simplify(problem: CnfProblem, maxRounds: Int = DEFAULT_MAX_ROUNDS): CnfProblem {
        val protectedVars = HashSet<Int>()
        for (v in problem.boolVarToCnfVar) protectedVars.add(v)
        for (bits in problem.intVarBits) for (b in bits) protectedVars.add(b)
        val subsumed = subsumeClauses(problem.clauses, maxRounds)
        val merged = subsumeClauses(substituteEquivalentLiterals(subsumed, problem.numVars, protectedVars), maxRounds)
        val eliminated = eliminateAuxVars(merged, protectedVars)
        return CnfProblem(
            numVars = problem.numVars,
            clauses = subsumeClauses(eliminated, maxRounds),
            boolVarToCnfVar = problem.boolVarToCnfVar,
            intVarBits = problem.intVarBits,
            intVarMin = problem.intVarMin,
        )
    }

    /**
     * Equivalent-literal substitution via strongly-connected components of the binary-implication
     * graph (#320). Each binary clause `(a ∨ b)` is the implication pair `¬a → b`, `¬b → a`;
     * literals in the same SCC imply each other and are therefore globally equivalent, so all but
     * one per class can be substituted away. If a literal and its negation land in the same SCC
     * the formula is UNSAT, signalled by a single empty clause.
     *
     * Only **aux** literals are substituted away: a class's representative is a [protectedVars]
     * literal when the class has exactly one protected variable (so the protected variable stays
     * and aux variables fold onto it); a class spanning two or more distinct protected variables
     * is left untouched (substituting either away would break its decode), its equivalence already
     * enforced by the existing clauses. Substituted aux variables become free — sound over the
     * protected projection for the same reason as [eliminateAuxVars].
     */
    fun substituteEquivalentLiterals(clauses: List<IntArray>, numVars: Int, protectedVars: Set<Int>): List<IntArray> {
        if (numVars == 0) return clauses
        val sccId = reginTarjanScc(buildImplicationGraph(clauses, numVars), 2 * numVars)
        for (v in 0 until numVars) {
            if (sccId[Lit.make(v, true)] == sccId[Lit.make(v, false)]) return listOf(IntArray(0)) // l ⟺ ¬l
        }
        val repLit = chooseRepresentatives(sccId, numVars, protectedVars)
        val out = ArrayList<IntArray>(clauses.size)
        for (clause in clauses) {
            val mapped = IntArray(clause.size) { repLit[clause[it]] }
            out.add(normalize(mapped) ?: continue) // a substitution-induced tautology is always true
        }
        return out
    }

    /** Literal-implication graph: node = MiniSAT literal in `0 until 2*numVars`; each binary
     *  clause `(a ∨ b)` adds `¬a → b` and `¬b → a`. Longer/unit clauses carry no binary
     *  implication and are ignored here. */
    private fun buildImplicationGraph(clauses: List<IntArray>, numVars: Int): Array<IntArrayList> {
        val adj = Array(2 * numVars) { IntArrayList() }
        for (clause in clauses) {
            if (clause.size != 2) continue
            val a = clause[0]
            val b = clause[1]
            adj[Lit.negate(a)].add(b)
            adj[Lit.negate(b)].add(a)
        }
        return adj
    }

    /** Representative literal per SCC, consistent under negation (`rep(¬l) == ¬rep(l)`), with
     *  aux literals folded onto a protected representative where one exists. */
    private fun chooseRepresentatives(sccId: IntArray, numVars: Int, protectedVars: Set<Int>): IntArray {
        val total = 2 * numVars
        val members = HashMap<Int, MutableList<Int>>()
        for (lit in 0 until total) members.getOrPut(sccId[lit]) { ArrayList() }.add(lit)
        val repLit = IntArray(total) { it }
        val done = HashSet<Int>()
        for ((scc, mem) in members) {
            if (scc in done || mem.size == 1) continue
            val complement = sccId[Lit.negate(mem[0])]
            val protectedHere = mem.map { Lit.variable(it) }.filter { it in protectedVars }.distinct()
            if (protectedHere.size >= 2) {
                done.add(scc)
                done.add(complement) // leave both classes self-mapped: can't drop either protected var
                continue
            }
            val rep = if (protectedHere.size == 1) mem.first { Lit.variable(it) == protectedHere[0] } else mem[0]
            for (m in mem) {
                repLit[m] = rep
                repLit[Lit.negate(m)] = Lit.negate(rep)
            }
            done.add(scc)
            done.add(complement)
        }
        return repLit
    }

    /**
     * Bounded variable elimination (#316), SatELite-style, restricted to variables absent from
     * [protectedVars]. Eliminates one variable per inner step:
     *  - **pure literal** (appears in only one polarity): satisfy it and drop its clauses;
     *  - **resolution**: replace the clauses on `x` with the non-tautological resolvents, but
     *    only when that does not grow the clause count (`|resolvents| ≤ |pos| + |neg|`).
     *
     * Eliminating `x` removes every clause mentioning it, so the result no longer constrains
     * `x` — sound because the resolvents preserve satisfiability over the remaining variables
     * and any of their models extends to `x`. Iterates until no eliminable variable remains
     * (bounded by the variable count).
     */
    fun eliminateAuxVars(clauses: List<IntArray>, protectedVars: Set<Int>): List<IntArray> {
        val live = ArrayList<IntArray?>(clauses.size)
        for (clause in clauses) {
            val normalized = normalize(clause) ?: continue
            live.add(normalized)
        }
        val eliminated = HashSet<Int>()
        while (eliminateOne(live, protectedVars, eliminated)) {
            // keep eliminating until a full scan finds no eliminable variable
        }
        val out = ArrayList<IntArray>(live.size)
        for (clause in live) if (clause != null) out.add(clause)
        return out
    }

    /** Eliminate one eliminable aux variable, mutating [live] in place. Returns whether one
     *  was eliminated. */
    private fun eliminateOne(
        live: MutableList<IntArray?>,
        protectedVars: Set<Int>,
        eliminated: HashSet<Int>,
    ): Boolean {
        val posOcc = HashMap<Int, MutableList<Int>>()
        val negOcc = HashMap<Int, MutableList<Int>>()
        for (i in live.indices) {
            val clause = live[i] ?: continue
            for (lit in clause) {
                val occ = if (Lit.isPositive(lit)) posOcc else negOcc
                occ.getOrPut(Lit.variable(lit)) { ArrayList() }.add(i)
            }
        }
        val vars = HashSet<Int>()
        vars.addAll(posOcc.keys)
        vars.addAll(negOcc.keys)
        for (v in vars) {
            if (v in protectedVars || v in eliminated) continue
            val pIdx = posOcc[v].orEmpty()
            val nIdx = negOcc[v].orEmpty()
            if (pIdx.isEmpty() || nIdx.isEmpty()) {
                // Pure literal: satisfy v, drop every clause that mentions it.
                for (i in pIdx) live[i] = null
                for (i in nIdx) live[i] = null
                eliminated.add(v)
                return true
            }
            val resolvents = resolveOnVar(live, v, pIdx, nIdx) ?: continue // growth guard failed
            for (i in pIdx) live[i] = null
            for (i in nIdx) live[i] = null
            for (clause in resolvents) live.add(clause)
            eliminated.add(v)
            return true
        }
        return false
    }

    /** Non-tautological resolvents of every (pos, neg) clause pair on variable [v], or `null`
     *  if there are more of them than the clauses being replaced (the BVE growth guard). */
    private fun resolveOnVar(live: List<IntArray?>, v: Int, pIdx: List<Int>, nIdx: List<Int>): List<IntArray>? {
        val budget = pIdx.size + nIdx.size
        val resolvents = ArrayList<IntArray>(budget)
        for (pi in pIdx) {
            val p = live[pi] ?: continue
            for (ni in nIdx) {
                val n = live[ni] ?: continue
                val resolvent = resolve(p, n, v) ?: continue // tautology
                resolvents.add(resolvent)
                if (resolvents.size > budget) return null
            }
        }
        return resolvents
    }

    /** Resolve [p] (contains `+v`) and [n] (contains `-v`) on [v]: their union minus the two
     *  pivot literals, normalized. `null` if the result is a tautology. */
    private fun resolve(p: IntArray, n: IntArray, v: Int): IntArray? {
        val merged = IntArray(p.size + n.size)
        var w = 0
        val posV = Lit.make(v, true)
        val negV = Lit.make(v, false)
        for (lit in p) if (lit != posV) merged[w++] = lit
        for (lit in n) if (lit != negV) merged[w++] = lit
        return normalize(if (w == merged.size) merged else merged.copyOf(w))
    }

    /**
     * Tautology removal + subsumption + self-subsuming resolution over a raw clause list,
     * iterated to a fixpoint (capped at [maxRounds]). Literals are MiniSAT-encoded. The
     * returned clauses are sorted and duplicate-free; an empty clause (formula is UNSAT) is
     * preserved verbatim.
     */
    fun subsumeClauses(clauses: List<IntArray>, maxRounds: Int = DEFAULT_MAX_ROUNDS): List<IntArray> {
        val live = ArrayList<IntArray?>(clauses.size)
        val sigList = ArrayList<Long>(clauses.size)
        for (clause in clauses) {
            val normalized = normalize(clause) ?: continue // tautology
            live.add(normalized)
            sigList.add(signature(normalized))
        }
        val sigs = LongArray(sigList.size) { sigList[it] }

        var round = 0
        var changed = true
        while (changed && round < maxRounds) {
            round++
            val occ = buildOccurrences(live)
            val subsumed = subsumptionPass(live, sigs, occ)
            val strengthened = selfSubsumptionPass(live, sigs, occ)
            changed = subsumed || strengthened
        }

        val out = ArrayList<IntArray>(live.size)
        for (clause in live) if (clause != null) out.add(clause)
        return out
    }

    /** Literal → indices of live clauses containing it. Rebuilt each round so deletions and
     *  strengthenings from the prior round are reflected. */
    private fun buildOccurrences(live: List<IntArray?>): HashMap<Int, MutableList<Int>> {
        val occ = HashMap<Int, MutableList<Int>>()
        for (i in live.indices) {
            val clause = live[i] ?: continue
            for (lit in clause) occ.getOrPut(lit) { ArrayList() }.add(i)
        }
        return occ
    }

    /** Delete every clause subsumed by another. Returns whether anything was removed. */
    private fun subsumptionPass(
        live: MutableList<IntArray?>,
        sigs: LongArray,
        occ: HashMap<Int, MutableList<Int>>,
    ): Boolean {
        var changed = false
        for (si in live.indices) {
            val s = live[si] ?: continue
            if (s.isEmpty()) continue // the UNSAT marker: keep it, but it subsumes nothing via a pivot
            val candidates = occ[pivotLiteral(s, occ)] ?: continue
            val sigS = sigs[si]
            for (di in candidates) {
                if (di == si) continue
                val d = live[di] ?: continue
                if (s.size > d.size) continue
                // Among equal-size clauses (only possible when identical) keep the earliest.
                if (s.size == d.size && si > di) continue
                if (sigS and sigs[di].inv() != 0L) continue
                if (isSubset(s, d)) {
                    live[di] = null
                    changed = true
                }
            }
        }
        return changed
    }

    /** Strengthen clauses by self-subsuming resolution. Returns whether anything changed. */
    private fun selfSubsumptionPass(
        live: MutableList<IntArray?>,
        sigs: LongArray,
        occ: HashMap<Int, MutableList<Int>>,
    ): Boolean {
        var changed = false
        for (si in live.indices) {
            val s = live[si] ?: continue
            for (l in s) {
                val negList = occ[Lit.negate(l)] ?: continue
                for (di in negList) {
                    if (di == si) continue
                    val d = live[di] ?: continue
                    if (selfSubsumes(s, l, d)) {
                        val strengthened = removeLiteral(d, Lit.negate(l))
                        live[di] = strengthened
                        sigs[di] = signature(strengthened)
                        changed = true
                    }
                }
            }
        }
        return changed
    }

    /** The literal of [s] occurring in the fewest clauses — cheapest pivot to scan. */
    private fun pivotLiteral(s: IntArray, occ: HashMap<Int, MutableList<Int>>): Int {
        var best = s[0]
        var bestSize = occ[s[0]]?.size ?: 0
        for (k in 1 until s.size) {
            val size = occ[s[k]]?.size ?: 0
            if (size < bestSize) {
                bestSize = size
                best = s[k]
            }
        }
        return best
    }

    /** Sort, drop duplicate literals, and reject tautologies (a var with both polarities).
     *  Returns `null` for a tautological clause, the canonical clause otherwise. */
    private fun normalize(clause: IntArray): IntArray? {
        if (clause.isEmpty()) return clause
        val sorted = clause.copyOf()
        sorted.sort()
        val res = IntArray(sorted.size)
        var w = 0
        for (lit in sorted) {
            if (w > 0) {
                if (res[w - 1] == lit) continue // duplicate literal
                if (Lit.variable(res[w - 1]) == Lit.variable(lit)) return null // l and ¬l
            }
            res[w++] = lit
        }
        return if (w == res.size) res else res.copyOf(w)
    }

    /** Literal-fingerprint for a fast subset reject: `A ⊆ B` requires `sig(A) & ~sig(B) == 0`. */
    private fun signature(clause: IntArray): Long {
        var sig = 0L
        for (lit in clause) sig = sig or (1L shl (lit and 63))
        return sig
    }

    /** `a ⊆ b` for ascending-sorted literal arrays. */
    private fun isSubset(a: IntArray, b: IntArray): Boolean {
        var i = 0
        var j = 0
        while (i < a.size) {
            if (j >= b.size) return false
            when {
                a[i] == b[j] -> {
                    i++
                    j++
                }

                a[i] > b[j] -> j++

                else -> return false // a[i] < b[j]: a[i] absent from b
            }
        }
        return true
    }

    /** Whether resolving [s] and [d] on [l] (with `¬l ∈ d`) yields a clause subsuming [d];
     *  i.e. every literal of [s] except [l] is already in [d]. Re-checks `¬l ∈ d` so a stale
     *  occurrence entry (a clause strengthened earlier this round) is handled safely. */
    private fun selfSubsumes(s: IntArray, l: Int, d: IntArray): Boolean {
        if (!contains(d, Lit.negate(l))) return false
        for (lit in s) {
            if (lit == l) continue
            if (!contains(d, lit)) return false
        }
        return true
    }

    private fun contains(arr: IntArray, x: Int): Boolean {
        var lo = 0
        var hi = arr.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val v = arr[mid]
            when {
                v == x -> return true
                v < x -> lo = mid + 1
                else -> hi = mid - 1
            }
        }
        return false
    }

    /** [d] with the single occurrence of [lit] removed; stays sorted. */
    private fun removeLiteral(d: IntArray, lit: Int): IntArray {
        val res = IntArray(d.size - 1)
        var w = 0
        for (x in d) if (x != lit) res[w++] = x
        return res
    }
}
