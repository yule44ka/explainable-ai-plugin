import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVPrinter
import java.io.FileReader
import java.io.FileWriter
import java.nio.file.Path
import java.nio.file.Paths

// ---------------------------------------------------------------------------
// Configuration
// ---------------------------------------------------------------------------

private val DEFAULT_INPUT: Path = Paths.get("../dataset_5topics_files.csv")
private val DEFAULT_MODELS = listOf("gpt-4.1", "gpt-4.1-mini", "gpt-4.1-nano", "gpt-4o", "gpt-4o-mini")

private val DETAIL_LEVELS = listOf("low", "medium", "high")
private val STRUCTURES = listOf("unstructured", "structured")

/** Delay between sequential API calls within one row (ms). */
private const val RATE_LIMIT_DELAY_MS = 500L

/** Number of rows processed in parallel per model. */
private const val PARALLEL_ROWS = 5

// ---------------------------------------------------------------------------
// Pricing (USD per 1M tokens)
// ---------------------------------------------------------------------------

private data class ModelPricing(val inputPerMillion: Double, val outputPerMillion: Double)

private val MODEL_PRICING: Map<String, ModelPricing> = mapOf(
    "gpt-4.1"      to ModelPricing(inputPerMillion = 2.00, outputPerMillion = 8.00),
    "gpt-4.1-mini" to ModelPricing(inputPerMillion = 0.40, outputPerMillion = 1.60),
    "gpt-4.1-nano" to ModelPricing(inputPerMillion = 0.10, outputPerMillion = 0.40),
    "gpt-4o"       to ModelPricing(inputPerMillion = 2.50, outputPerMillion = 10.00),
    "gpt-4o-mini"  to ModelPricing(inputPerMillion = 0.15, outputPerMillion = 0.60),
)

private fun calculateCost(usage: api.Usage, model: String): Double {
    val pricing = MODEL_PRICING[model] ?: return 0.0
    return (usage.prompt_tokens * pricing.inputPerMillion +
            usage.completion_tokens * pricing.outputPerMillion) / 1_000_000.0
}

// ---------------------------------------------------------------------------
// Column-name helpers
// ---------------------------------------------------------------------------

private fun modelSlug(model: String) = model.replace(".", "_").replace("-", "_")

private fun summaryCol(model: String, detail: String, structure: String) =
    "summary__${modelSlug(model)}__${detail}__${structure}"

private fun remappingCol(model: String, detail: String, structure: String) =
    "remapping__${modelSlug(model)}__${detail}__${structure}"

private fun priceCol(model: String) = "price__${modelSlug(model)}"

private fun allGeneratedHeaders(models: List<String>): List<String> = buildList {
    for (m in models) {
        for (d in DETAIL_LEVELS) for (s in STRUCTURES) {
            add(summaryCol(m, d, s))
            add(remappingCol(m, d, s))
        }
        add(priceCol(m))
    }
}

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------

private fun defaultOutputPath(inputPath: Path, models: List<String>): Path {
    val inputName = inputPath.fileName.toString().removeSuffix(".csv")
    val modelSuffix = models.joinToString("_") { modelSlug(it) }
    val parent = inputPath.parent
    val outputName = "${inputName}_generated_${modelSuffix}.csv"
    return if (parent != null) parent.resolve(outputName) else Paths.get(outputName)
}

fun main(args: Array<String>) {
    val inputPath = if (args.size > 0) Paths.get(args[0]) else DEFAULT_INPUT

    // args[1] is the output path only when it ends with ".csv"; otherwise treat it as the first model name
    val hasExplicitOutput = args.size > 1 && args[1].endsWith(".csv")
    val models = when {
        hasExplicitOutput && args.size > 2 -> args.drop(2)
        !hasExplicitOutput && args.size > 1 -> args.drop(1)
        else -> DEFAULT_MODELS
    }

    val apiKey = System.getenv("OPENAI_API_KEY")
        ?: error("OPENAI_API_KEY environment variable is not set.")

    val service = LLMService(apiKey = apiKey)

    // Read input CSV once — shared across all models
    val inputFile = inputPath.toFile()
    val inputRecords = FileReader(inputFile).use {
        CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build()
            .parse(it).records.toList()
    }
    val baseHeaders = FileReader(inputFile).use {
        CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build()
            .parse(it).headerNames
    }

    println("Input:  $inputPath  (${inputRecords.size} rows)")
    println("Models: $models")
    println()

    for (model in models) {
        val outputPath = if (hasExplicitOutput) Paths.get(args[1]) else defaultOutputPath(inputPath, listOf(model))
        val modelHeaders = baseHeaders + allGeneratedHeaders(listOf(model))

        println("=== [$model] → $outputPath ===")

        // Resume support: load already-processed keys from existing output
        val outputFile = outputPath.toFile()
        val processedKeys = mutableSetOf<String>()
        val outputExists = outputFile.exists() && outputFile.length() > 0
        if (outputExists) {
            FileReader(outputFile).use {
                CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build()
                    .parse(it).forEach { row -> processedKeys.add(rowKey(row.toMap())) }
            }
            println("  Resuming: ${processedKeys.size} rows already done, skipping them.\n")
        }

        FileWriter(outputFile, outputExists).use { fw ->
            val printer = CSVPrinter(fw, CSVFormat.DEFAULT.builder()
                .apply { if (!outputExists) setHeader(*modelHeaders.toTypedArray()) }
                .build())

            val semaphore = Semaphore(PARALLEL_ROWS)
            val writeMutex = Mutex()

            runBlocking {
                val jobs = inputRecords.mapIndexed { idx, record ->
                    async(Dispatchers.IO) {
                        val rowMap = record.toMap()
                        val key = rowKey(rowMap)
                        val label = rowMap["name"] ?: rowMap["file_path"] ?: "row ${idx + 1}"

                        if (key in processedKeys) {
                            println("  [${idx + 1}/${inputRecords.size}] skip: $label")
                            return@async
                        }

                        semaphore.withPermit {
                            println("  [${idx + 1}/${inputRecords.size}] processing: $label")

                            val result = mutableMapOf<String, String>()
                            baseHeaders.forEach { col -> result[col] = rowMap[col] ?: "" }

                            val code = rowMap["code"] ?: ""
                            val fileContext = rowMap["description"] ?: ""
                            var totalCost = 0.0

                            println("    [$label] generating summaries …")
                            val summaryResult = service.generateCodeSummary(code, fileContext, model)
                                .onFailure { e -> println("    [$label] ERROR summaries: ${e.message}") }
                                .getOrNull()

                            val summaries = summaryResult?.first
                            summaryResult?.second?.let { usage -> totalCost += calculateCost(usage, model) }

                            kotlinx.coroutines.delay(RATE_LIMIT_DELAY_MS)

                            for (detail in DETAIL_LEVELS) {
                                for (structure in STRUCTURES) {
                                    val summaryText = summaries?.fieldValue("${detail}_${structure}") ?: ""
                                    result[summaryCol(model, detail, structure)] = summaryText

                                    if (summaryText.isBlank()) {
                                        result[remappingCol(model, detail, structure)] = ""
                                        continue
                                    }

                                    println("    [$label] remapping $detail/$structure …")
                                    val mappingResult = service.buildSummaryMapping(code, summaryText, model = model)
                                        .onFailure { e -> println("    [$label] ERROR remapping $detail/$structure: ${e.message}") }
                                        .getOrNull()

                                    result[remappingCol(model, detail, structure)] =
                                        Json.encodeToString(mappingResult?.first ?: emptyList())
                                    mappingResult?.second?.let { usage -> totalCost += calculateCost(usage, model) }

                                    kotlinx.coroutines.delay(RATE_LIMIT_DELAY_MS)
                                }
                            }

                            result[priceCol(model)] = "%.6f".format(totalCost)

                            writeMutex.withLock {
                                printer.printRecord(modelHeaders.map { result[it] ?: "" })
                                fw.flush()
                                val costStr = "%.5f".format(totalCost)
                                println("  [${idx + 1}/${inputRecords.size}] done: $label  \$$costStr")
                            }
                        }
                    }
                }
                jobs.awaitAll()
            }
        }

        println("  Done → $outputPath\n")
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private fun rowKey(row: Map<String, String>) =
    "${row["name"] ?: ""}|${row["file_path"] ?: ""}"

/** Return the value of a CodeSummary field by its snake_case name. */
private fun CodeSummary.fieldValue(key: String): String = when (key) {
    "low_unstructured"    -> low_unstructured
    "low_structured"      -> low_structured
    "medium_unstructured" -> medium_unstructured
    "medium_structured"   -> medium_structured
    "high_unstructured"   -> high_unstructured
    "high_structured"     -> high_structured
    else -> ""
}
