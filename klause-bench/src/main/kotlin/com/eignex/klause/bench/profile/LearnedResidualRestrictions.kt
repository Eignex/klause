package com.eignex.klause.bench.profile

import com.eignex.klause.factor.bool.Clause
import com.eignex.klause.factor.bool.PseudoBoolean
import com.eignex.klause.ir.Factor
import com.eignex.klause.ir.Lit
import com.eignex.klause.model.PbOp
import com.eignex.klause.propagation.PropagationSession
import com.eignex.klause.util.Cancellation
import java.math.BigInteger

internal data class LearnedResidualRestriction(
    val storeIndex: Int,
    val kind: String,
    val permanentRetention: Boolean,
    val factor: Factor?,
    val support: List<Int>?,
    val discharged: Boolean,
)

internal class LearnedResidualInspection(
    val entries: List<LearnedResidualRestriction>,
    val storeCount: Int,
    val decline: String?,
) {
    val complete: Boolean get() = decline == null && entries.size == storeCount && entries.all { it.support != null }

}

// Store positions identify entries only within one observation, including across compaction.
internal object LearnedResidualRestrictions {
    private val stateField = PropagationSession::class.java.getDeclaredField("state").apply { isAccessible = true }
    private val clauseLiterals = PropagationSession::class.java.declaredMethods.single {
        it.name.startsWith("learnedClauseLiterals") && it.parameterCount == 1
    }.apply { isAccessible = true }
    private val isClause = PropagationSession::class.java.declaredMethods.single {
        it.name.startsWith("isLearnedClause") && it.parameterCount == 1
    }.apply { isAccessible = true }
    private val nativeLiteralCount = Class.forName("com.eignex.klause.propagation.NativeSatState")
        .declaredMethods.single { it.name == "literalCountOf" }.apply { isAccessible = true }

    fun inspect(
        session: PropagationSession,
        stop: Cancellation = Cancellation.Never,
        maxTerms: Int = 1_000_000,
    ): LearnedResidualInspection {
        val count = session.learnedClauseCount
        val entries = ArrayList<LearnedResidualRestriction>()
        var terms = 0L
        val state = stateField.get(session)
        val native = if (session.usesNativeSat) field(state, "nativeEngine") else null
        val store = if (native == null) {
            val db = field(state, "learned")
            field(db, "store") as List<*>
        } else null
        for (index in 0 until count) {
            if (stop()) return LearnedResidualInspection(entries, count, "interrupted")
            if (terms >= maxTerms) return LearnedResidualInspection(entries, count, "term-limit")
            if (native != null && terms + maxOf(1, nativeLiteralCount.invoke(native, index) as Int) > maxTerms) {
                return LearnedResidualInspection(entries, count, "term-limit")
            }
            val permanent = session.learnedClausePermanent(index)
            val clause = isClause.invoke(session, index) == true
            val prop = store?.get(index)
            val kind = if (clause) "Clause" else prop?.javaClass?.simpleName ?: "unknown"
            val literals = when {
                clause -> clauseLiterals.invoke(session, index) as IntArray
                kind == "PseudoBooleanPropagator" -> field(requireNotNull(prop), "literals") as IntArray
                else -> {
                    terms++
                    entries += LearnedResidualRestriction(index, kind, permanent, null, null, false)
                    continue
                }
            }
            terms += maxOf(1, literals.size)
            if (terms > maxTerms) return LearnedResidualInspection(entries, count, "term-limit")
            val weights = if (clause) null else field(requireNotNull(prop), "weights") as LongArray
            val bound = if (clause) 1L else field(requireNotNull(prop), "bound") as Long
            val op = if (clause) PbOp.GE else field(requireNotNull(prop), "op") as PbOp
            val supported = literals.all { Lit.variable(it) in 0 until session.problem.numBoolVars } &&
                (weights == null || (weights.size == literals.size && weights.all { it >= 0L })) && op == PbOp.GE
            if (!supported) {
                entries += LearnedResidualRestriction(index, kind, permanent, null, null, false)
                continue
            }
            var degree = BigInteger.valueOf(bound)
            val support = linkedSetOf<Int>()
            for (i in literals.indices) {
                if (i % 256 == 0 && stop()) return LearnedResidualInspection(entries, count, "interrupted")
                val weight = weights?.get(i) ?: 1L
                when (session.litTruth(literals[i])) {
                    true -> degree -= BigInteger.valueOf(weight)
                    null -> if (weight != 0L) support += Lit.variable(literals[i])
                    false -> Unit
                }
            }
            val factor = if (clause) Clause(literals.copyOf()) else
                PseudoBoolean(requireNotNull(weights), literals, op, bound)
            entries += LearnedResidualRestriction(
                index, kind, permanent, factor, support.sorted(), degree.signum() <= 0,
            )
        }
        return LearnedResidualInspection(entries, count, if (stop()) "interrupted" else null)
    }

    private fun field(owner: Any, name: String): Any =
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
}
