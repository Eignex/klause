package com.eignex.klause.bench.metric

import java.io.BufferedReader
import java.io.File

internal sealed interface SolverOutput {
    fun reader(): BufferedReader

    fun copyTo(target: File)

    data class Text(val value: String) : SolverOutput {
        override fun reader(): BufferedReader = value.reader().buffered()

        override fun copyTo(target: File) = target.writeText(value)
    }

    data class Spool(val file: File) : SolverOutput {
        override fun reader(): BufferedReader = file.bufferedReader()

        override fun copyTo(target: File) {
            if (file != target) file.copyTo(target, overwrite = true)
        }
    }
}
