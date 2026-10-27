package com.eignex.klause.model

import com.eignex.skema.serialization.CompactSerializer
import com.eignex.skema.serialization.JsonNode
import com.eignex.skema.serialization.JsonNodeCatalog
import com.eignex.skema.serialization.JsonNodeShape.Literal
import com.eignex.skema.serialization.JsonNodeShape.Operand
import com.eignex.skema.serialization.JsonNodeShape.Operands
import com.eignex.skema.serialization.jsonNode
import kotlinx.serialization.json.JsonPrimitive

/**
 * Compact JSON vocabularies of schema entries and expressions. Each node is one operator key
 * holding its operands, a named reference is `{"ref": name}` and an integer literal is a bare
 * number. Other formats keep the sealed-class representation.
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
    val catalog: JsonNodeCatalog = JsonNodeCatalog { serializers }
}

private fun varSpecNodes(): List<JsonNode> = listOf(
    jsonNode<BoolSpec>("bool"),
    jsonNode<PresenceSpec>("presence", Operand, "valueName"),
    jsonNode<NominalSpec>("nominal", Operand, "labels"),
    jsonNode<IntSpec>("int", Operands, "min", "max"),
    jsonNode<FloatSpec>("float"),
    jsonNode<SetSpec>("set", Operand, "universe"),
    jsonNode<MultipleSpec>("multiple", Operand, "labels"),
)

/** Compact JSON serializer of [SchemaEntry]: variable declarations plus `{"constraint": expr}`. */
object SchemaEntrySerializer : CompactSerializer<SchemaEntry>(
    "com.eignex.klause.model.SchemaEntry",
    SchemaEntry::class,
    ModelJson.catalog,
    { varSpecNodes() + jsonNode<NamedConstraint>("constraint", Operand, "expr") },
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
            jsonNode<IntScale>("scale", Operands, "coeff", "child"),
            jsonNode<IntSum>("add", Operand, "children"),
            jsonNode<IntMul>("mul", Operands, "left", "right"),
            jsonNode<IntDiv>("div", Operands, "num", "den"),
            jsonNode<IntMod>("mod", Operands, "num", "den"),
            jsonNode<IntPower>("pow", Operands, "base", "exponent"),
            jsonNode<IntAbs>("abs", Operand, "child"),
            jsonNode<IntMin>("min", Operand, "children"),
            jsonNode<IntMax>("max", Operand, "children"),
            jsonNode<IntIfThenElse>("if", Operands, "cond", "thenE", "elseE"),
            jsonNode<IntElement>("element"),
            jsonNode<SetCard>("card", Operand, "set"),
        )
    },
)

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
            jsonNode<GccExprOpt>("gccOpt"),
        )
    },
)

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
