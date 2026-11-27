package com.eignex.klause.propagation.difference

import com.eignex.klause.arithmetic.difference.DifferenceEdge
import com.eignex.klause.factor.codec.FactorCodec
import com.eignex.klause.factor.codec.factorCodec
import com.eignex.klause.ir.Problem
import kotlinx.serialization.Serializable

@Serializable
private class DiagnosticDifferenceSystem(
    val edges: List<DiagnosticDifferenceEdge>,
    val privateVertices: Boolean,
)

@Serializable
private class DiagnosticDifferenceEdge(
    val source: Int,
    val target: Int,
    val bound: String,
    val guard: Int,
    val domainBound: Boolean,
    val index: DiagnosticDifferenceIndex? = null,
)

@Serializable
private class DiagnosticDifferenceIndex(val variable: Int, val value: String)

internal fun differenceSystemDiagnosticBytesLowerBound(problem: Problem): Long {
    val shortestEdge = """{"source":0,"target":0,"bound":"0","guard":0,"domainBound":true}""".length
    return problem.factors.sumOf { factor ->
        if (factor is DifferenceSystem) factor.edges.size.toLong() * shortestEdge else 0L
    }
}

internal fun differenceSystemDiagnosticCodec(): FactorCodec<*, *> =
    factorCodec<DifferenceSystem, DiagnosticDifferenceSystem>(
        "diagnosticDifferenceSystem",
        toPayload = { system ->
            val edges = system.edges.mapIndexed { id, edge ->
                val index = system.indexGuards[id]?.let { DiagnosticDifferenceIndex(it.variable, it.value.toString()) }
                DiagnosticDifferenceEdge(edge.source, edge.target, edge.bound.toString(), edge.guard,
                    edge.domainBound, index)
            }
            DiagnosticDifferenceSystem(edges, system.privateVertices)
        },
        fromPayload = { payload ->
            val indexGuards = buildMap {
                payload.edges.forEachIndexed { id, edge ->
                    edge.index?.let { put(id, DifferenceIndexGuard(it.variable, it.value.toLong())) }
                }
            }
            val edges = payload.edges.map { DifferenceEdge(it.source, it.target, it.bound.toLong(), it.guard,
                it.domainBound) }
            DifferenceSystem(edges, payload.privateVertices, indexGuards)
        },
    )
