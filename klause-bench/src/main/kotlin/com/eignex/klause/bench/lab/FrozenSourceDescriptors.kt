package com.eignex.klause.bench.lab

import com.eignex.klause.bench.catalog.Category
import com.eignex.klause.bench.catalog.Format
import com.eignex.klause.bench.source.CorpusFiles
import com.eignex.lab.bench.BenchCase
import com.eignex.lab.bench.BenchInputRoot
import java.io.File

/**
 * Descriptor rules for frozen-source `cases` templates, shared by planning and offline pre-submission checks.
 * Every violation names its case and the record that supplies the missing identity; nothing is derived here.
 */
internal object FrozenSourceDescriptors {
    const val MAX_CASES = 8

    val FORMATS = setOf(Format.DIMACS, Format.WCNF, Format.MPS, Format.OPB, Format.MINIZINC)

    private val SHA256 = Regex("[a-f0-9]{64}")

    /** The template attributes planning retains; `lab*` attributes belong to an earlier plan. */
    fun attributes(template: BenchCase): Map<String, String> = template.attributes.filterKeys { !it.startsWith("lab") }

    /** All violations in [templates], empty when frozen planning accepts them. */
    fun violations(templates: List<BenchCase>): List<String> = buildList {
        if (templates.size !in 1..MAX_CASES) {
            add("descriptor lists ${templates.size} cases; frozen-source plans accept 1 to $MAX_CASES")
        }
        templates.groupingBy { it.id }.eachCount().filterValues { it > 1 }.forEach { (id, count) ->
            add("case id '$id' appears $count times; give each template a distinct id")
        }
        templates.groupBy { attributes(it)["suite"] to attributes(it)["problem"] }.filterValues { it.size > 1 }
            .forEach { (key, cases) ->
                add(
                    "cases ${cases.joinToString { "'${it.id}'" }} all name ${key.first}/${key.second}; " +
                        "each suite/problem may appear once",
                )
            }
        for (template in templates) addAll(violations(template).map { "case '${template.id}': $it" })
    }

    private fun violations(template: BenchCase): List<String> = buildList {
        val attributes = attributes(template)
        for (key in listOf("suite", "problem")) {
            if (attributes[key].isNullOrBlank()) add("attribute $key is missing or blank")
        }
        val format = attributes["format"]?.let { name -> Format.entries.firstOrNull { it.name == name } }
        when {
            attributes["format"] == null -> add("attribute format is missing; declare one of ${FORMATS.joinToString()}")

            format !in FORMATS ->
                add("attribute format '${attributes["format"]}' is not one of ${FORMATS.joinToString()}")
        }
        val category = attributes["category"]
        if (category == null || Category.entries.none { it.name == category }) {
            add("attribute category '${category.orEmpty()}' is not one of ${Category.entries.joinToString()}")
        }
        val models = template.inputs.filter { it.role == "model" }
        val data = template.inputs.filter { it.role == "data" }
        if (models.size != 1) add("needs exactly one input with role model, found ${models.size}")
        if (data.size > 1) add("accepts at most one input with role data, found ${data.size}")
        template.inputs.filter { it.role != "model" && it.role != "data" }.forEach {
            add("input ${it.path} has role '${it.role}'; frozen sources accept only model and data inputs")
        }
        for (input in template.inputs) {
            if (input.root != BenchInputRoot.CORPUS) {
                add("input ${input.path} is under root ${input.root}; frozen sources read only the CORPUS root")
            }
            if (input.sha256?.matches(SHA256) != true) {
                add(
                    "input ${input.path} has no lowercase SHA-256 of its stored bytes; copy it from " +
                        "klause-bench/corpus-index/${input.path.substringBefore('/')}.tsv.gz",
                )
            }
        }
        models.singleOrNull()?.let { addAll(plainIdentity(attributes, "sourcePlainSha256", it.path, it.sha256)) }
        if (format == Format.MINIZINC) {
            models.singleOrNull()?.takeUnless { it.path.endsWith(".mzn") }?.let {
                add("MINIZINC model ${it.path} is not a .mzn file")
            }
            data.singleOrNull()?.let {
                if (!it.path.endsWith(".dzn")) add("MINIZINC data ${it.path} is not a .dzn file")
                addAll(plainIdentity(attributes, "sourceDataPlainSha256", it.path, it.sha256))
            }
        } else if (format != null) {
            data.forEach { add("input ${it.path} has role data, which only MINIZINC sources accept") }
            models.singleOrNull()?.takeUnless { CorpusFiles.isCompressed(File(it.path)) }?.let {
                add("$format model ${it.path} is not a packed ${CorpusFiles.SUFFIX} corpus member")
            }
        }
    }

    private fun plainIdentity(attributes: Map<String, String>, key: String, path: String, packed: String?) = buildList {
        val plain = attributes[key]
        when {
            plain == null -> add(
                "attribute $key is missing; record the decoded SHA-256 that " +
                    "lab/source-identity.json reports for $path${packed?.let { " (stored sha256 $it)" }.orEmpty()}",
            )

            !plain.matches(SHA256) -> add("attribute $key '$plain' is not a lowercase 64-digit SHA-256")

            plain == packed && CorpusFiles.isCompressed(File(path)) ->
                add(
                    "attribute $key repeats the stored sha256 of compressed input $path; record the decoded " +
                        "SHA-256 that lab/source-identity.json reports instead",
                )
        }
    }
}
