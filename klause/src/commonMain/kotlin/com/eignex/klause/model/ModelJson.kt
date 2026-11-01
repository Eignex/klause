package com.eignex.klause.model

import com.eignex.skema.serialization.CompactSerializer
import com.eignex.skema.serialization.JsonNode
import com.eignex.skema.serialization.JsonNodeCatalog
import com.eignex.skema.serialization.JsonNodeShape.Literal
import com.eignex.skema.serialization.JsonNodeShape.Operand
import com.eignex.skema.serialization.JsonNodeShape.Operands
import com.eignex.skema.serialization.jsonNode
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * Compact JSON vocabularies of schema entries and expressions. Each node is one operator key
 * holding its operands, a named reference is `{"ref": name}` and an integer literal is a bare
 * number. Other formats keep the sealed-class representation.
 *
 * Keys shared with Kumulant follow the portable expression profile, so one key never names
 * two operations: integer division is `truncDiv`/`truncMod`, and `div` stays real division.
 * Encoding writes the profile's two-operand shapes: [IntScale] is `mul` with the coefficient
 * first, and [IntSum], [IntMin], [IntMax], [And] and [Or] over more than two children nest
 * to the left, so `x + 1 + 2` is `{"add": [{"add": [x, 1]}, 2]}`. Decoding merges an `add`,
 * `and` or `or` chain nested in the first operand back into one node, as the DSL operators
 * build them, and reads `mul` with a numeric left operand as [IntScale], so trees built with
 * the DSL operators and receiver helpers round-trip unchanged.
 */
object ModelJson {
    /** Every model vocabulary, for composing into another [JsonNodeCatalog]. */
    val serializers: List<CompactSerializer<*>>
        get() = listOf(
            SchemaEntrySerializer,
            VarSpecSerializer,
            BoolExprSerializer,
            IntExprSerializer,
            SetExprSerializer,
            ContinuousRealExprSerializer,
        )

    /** Catalog resolving nested model vocabularies, including during JSON Schema generation. */
    val catalog: JsonNodeCatalog = JsonNodeCatalog(::resolveMul) { serializers }
}

// `mul` is IntScale when its left operand is a number, as constant multiplication encodes, else IntMul.
private fun resolveMul(candidates: List<JsonNode>, payload: JsonElement): JsonNode {
    val left = (payload as? JsonArray)?.firstOrNull() as? JsonPrimitive
    val type = if (left != null && !left.isString && left.doubleOrNull != null) IntScale::class else IntMul::class
    return candidates.singleOrNull { it.type == type } ?: throw SerializationException("ambiguous operator 'mul'")
}

private fun <T> List<T>.nestedLeft(node: (List<T>) -> T): T =
    drop(2).fold(node(take(2))) { left, right -> node(listOf(left, right)) }

private fun IntExpr.binaryChain(): IntExpr = when {
    this is IntSum && children.size > 2 -> children.nestedLeft(::IntSum)
    this is IntMin && children.size > 2 -> children.nestedLeft(::IntMin)
    this is IntMax && children.size > 2 -> children.nestedLeft(::IntMax)
    else -> this
}

// Merging mirrors the DSL, which flattens `+`, `and` and `or` but nests chained `min` and `max`.
// Children decode first, so a chain nested in the first operand is already merged.
private fun IntExpr.mergedChain(): IntExpr {
    if (this !is IntSum) return this
    val first = children.first() as? IntSum ?: return this
    return IntSum(first.children + children.drop(1))
}

private fun BoolExpr.binaryChain(): BoolExpr = when {
    this is And && children.size > 2 -> children.nestedLeft(::And)
    this is Or && children.size > 2 -> children.nestedLeft(::Or)
    else -> this
}

private fun BoolExpr.mergedChain(): BoolExpr = when (this) {
    is And -> (children.first() as? And)?.let { And(it.children + children.drop(1)) } ?: this
    is Or -> (children.first() as? Or)?.let { Or(it.children + children.drop(1)) } ?: this
    else -> this
}

private fun varSpecNodes(): List<JsonNode> = listOf(
    jsonNode<BoolSpec>("bool"),
    jsonNode<PresenceSpec>("presence", Operand, "valueName"),
    jsonNode<NominalSpec>("nominal", Operand, "labels"),
    jsonNode<IntSpec>("int", Operands, "min", "max"),
    jsonNode<IntDomainSpec>("intDomain"),
    jsonNode<FloatSpec>("float"),
    jsonNode<SetSpec>("set", Operand, "universe"),
    jsonNode<MultipleSpec>("multiple", Operand, "labels"),
)

/** Compact JSON serializer of [SchemaEntry]: variable declarations plus `{"constraint": expr}`. */
object SchemaEntrySerializer : CompactSerializer<SchemaEntry>(
    "com.eignex.klause.model.SchemaEntry",
    SchemaEntry::class,
    ModelJson.catalog,
    {
        varSpecNodes() + listOf(
            ConstraintRole.REQUIRED to "constraint",
            ConstraintRole.IMPLIED to "implied",
            ConstraintRole.SYMMETRY_BREAKING to "symmetryBreaking",
        ).map { (role, key) ->
            jsonNode<NamedConstraint>(key, Operand, "expr", fixed = mapOf("role" to JsonPrimitive(role.name)))
        }
    },
)

/** Compact JSON serializer of [VarSpec], sharing the declaration keys of [SchemaEntrySerializer]. */
object VarSpecSerializer : CompactSerializer<VarSpec>(
    "com.eignex.klause.model.VarSpec",
    VarSpec::class,
    ModelJson.catalog,
    ::varSpecNodes,
)

/** Compact JSON serializer of [IntExpr]. */
object IntExprSerializer : CompactSerializer<IntExpr>(
    "com.eignex.klause.model.IntExpr",
    IntExpr::class,
    ModelJson.catalog,
    {
        listOf(
            jsonNode<IntRef>("ref", Operand, "name"),
            jsonNode<IntLit>("const", Literal, "value"),
            jsonNode<IntScale>("mul", Operands, "coeff", "child"),
            jsonNode<IntSum>("add", Operand, "children"),
            jsonNode<IntMul>("mul", Operands, "left", "right"),
            jsonNode<IntDiv>("truncDiv", Operands, "num", "den"),
            jsonNode<IntMod>("truncMod", Operands, "num", "den"),
            jsonNode<IntPower>("pow", Operands, "base", "exponent"),
            jsonNode<IntAbs>("abs", Operand, "child"),
            jsonNode<IntMin>("min", Operand, "children"),
            jsonNode<IntMax>("max", Operand, "children"),
            jsonNode<IntIfThenElse>("if", Operands, "cond", "thenE", "elseE"),
            jsonNode<IntElement>("element"),
            jsonNode<SetCard>("card", Operand, "set"),
        )
    },
) {
    override fun serialize(encoder: Encoder, value: IntExpr) {
        super.serialize(encoder, if (encoder is JsonEncoder) value.binaryChain() else value)
    }

    override fun deserialize(decoder: Decoder): IntExpr {
        val value = super.deserialize(decoder)
        return if (decoder is JsonDecoder) value.mergedChain() else value
    }
}

/** Compact JSON serializer of [BoolExpr]; [IntCompare] takes its operator's key, such as `{"ge": [l, r]}`. */
object BoolExprSerializer : CompactSerializer<BoolExpr>(
    "com.eignex.klause.model.BoolExpr",
    BoolExpr::class,
    ModelJson.catalog,
    {
        listOf(
            jsonNode<BoolRef>("ref", Operand, "name", fixed = mapOf("negated" to JsonPrimitive(false))),
            jsonNode<BoolRef>("notRef", Operand, "name", fixed = mapOf("negated" to JsonPrimitive(true))),
            jsonNode<NominalEq>("nominalEq", Operands, "name", "label"),
            jsonNode<Not>("not", Operand, "child"),
            jsonNode<And>("and", Operand, "children"),
            jsonNode<Or>("or", Operand, "children"),
            jsonNode<XorExpr>("xor", Operand, "children"),
            jsonNode<Implies>("implies", Operands, "left", "right"),
            jsonNode<Iff>("iff", Operands, "left", "right"),
            jsonNode<AtMost>("atMost"),
            jsonNode<AtLeast>("atLeast"),
            jsonNode<CardinalityExpr>("cardinality"),
            jsonNode<PseudoBooleanExpr>("pseudoBoolean"),
        ) + IntCmpOp.entries.map { op ->
            jsonNode<IntCompare>(
                op.name.lowercase(),
                Operands,
                "left",
                "right",
                fixed = mapOf("op" to JsonPrimitive(op.name)),
            )
        } + listOf(
            jsonNode<FloatLinearConstraint>("floatLinear"),
            jsonNode<ContinuousRealEquality>("realEq", Operands, "left", "right"),
            jsonNode<AllDifferent>("allDifferent", Operand, "terms"),
            jsonNode<SymmetricAllDifferent>("symmetricAllDifferent"),
            jsonNode<InverseChannel>("inverse"),
            jsonNode<TableConstraint>("table"),
            jsonNode<RegularExpr>("regular"),
            jsonNode<CostRegularExpr>("costRegular"),
            jsonNode<MddExpr>("mdd"),
            jsonNode<CostMddExpr>("costMdd"),
            jsonNode<CircuitExpr>("circuit"),
            jsonNode<SubcircuitExpr>("subcircuit"),
            jsonNode<CumulativeExpr>("cumulative"),
            jsonNode<DisjunctiveExpr>("disjunctive"),
            jsonNode<SortExpr>("sort"),
            jsonNode<IncreasingExpr>("increasing"),
            jsonNode<DiffnExpr>("diffn"),
            jsonNode<SetIn>("in", Operands, "elem", "set"),
            jsonNode<SetNominalIn>("labelIn", Operands, "label", "set"),
            jsonNode<SetSubsetOf>("subset", Operands, "left", "right"),
            jsonNode<SetDisjoint>("disjoint", Operands, "left", "right"),
            jsonNode<SetEq>("setEq", Operands, "left", "right"),
            jsonNode<AllDifferentOpt>("allDifferentOpt"),
            jsonNode<CumulativeExprOpt>("cumulativeOpt"),
            jsonNode<DisjunctiveExprOpt>("disjunctiveOpt"),
            jsonNode<NValueExprOpt>("nValueOpt"),
            jsonNode<NValueExpr>("nValue"),
            jsonNode<GccExprOpt>("gccOpt"),
            jsonNode<LexExpr>("lexLess"),
            jsonNode<VariableCumulativeExpr>("cumulativeVars"),
            jsonNode<FactorConstraint>("factor"),
        )
    },
) {
    override fun serialize(encoder: Encoder, value: BoolExpr) {
        super.serialize(encoder, if (encoder is JsonEncoder) value.binaryChain() else value)
    }

    override fun deserialize(decoder: Decoder): BoolExpr {
        val value = super.deserialize(decoder)
        return if (decoder is JsonDecoder) value.mergedChain() else value
    }
}

/** Compact JSON serializer of [SetExpr]. */
object SetExprSerializer : CompactSerializer<SetExpr>(
    "com.eignex.klause.model.SetExpr",
    SetExpr::class,
    ModelJson.catalog,
    {
        listOf(
            jsonNode<SetRef>("ref", Operand, "name"),
            jsonNode<SetLiteral>("elements", Operand, "elements"),
            jsonNode<SetNominalLiteral>("labels", Operand, "labels"),
            jsonNode<SetUnion>("union", Operands, "left", "right"),
            jsonNode<SetIntersect>("intersect", Operands, "left", "right"),
            jsonNode<SetDiff>("diff", Operands, "left", "right"),
        )
    },
)

/** Compact JSON serializer of [ContinuousRealExpr]. */
object ContinuousRealExprSerializer : CompactSerializer<ContinuousRealExpr>(
    "com.eignex.klause.model.ContinuousRealExpr",
    ContinuousRealExpr::class,
    ModelJson.catalog,
    {
        listOf(
            jsonNode<ContinuousRealExpr.Affine>("affine"),
            jsonNode<ContinuousRealExpr.Multiply>("mul", Operands, "left", "right"),
            jsonNode<ContinuousRealExpr.Power>("pow", Operands, "base", "exponent"),
        )
    },
)
