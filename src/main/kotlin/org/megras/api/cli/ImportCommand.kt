package org.megras.api.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.lz4.FramedLZ4CompressorInputStream
import org.apache.commons.compress.compressors.snappy.SnappyCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream
import org.apache.jena.graph.Node
import org.apache.jena.graph.Triple
import org.apache.jena.riot.Lang
import org.apache.jena.riot.RDFParser
import org.apache.jena.riot.system.StreamRDF
import org.apache.jena.sparql.core.Quad as JenaQuad
import org.megras.data.graph.Quad
import org.megras.data.graph.QuadValue
import org.megras.graphstore.MutableQuadSet
import org.slf4j.LoggerFactory
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.time.LocalDateTime
import java.util.zip.GZIPInputStream

class ImportCommand(private val quads: MutableQuadSet) :
    CliktCommand(name = "import", help = "imports RDF data (TSV, Turtle, N-Triples, N-Quads, TriG, RDF/XML, JSON-LD)", printHelpOnEmptyArgs = true) {

    private val logger = LoggerFactory.getLogger(this.javaClass)

    private val fileName: String by option("-f", "--File", help = "Path of file or folder to be imported")
        .required()

    private val batchSize: Int by option("-b", "--batchSize").int().default(100)
    private val skip: Int by option("-s", "--skip", help = "The number of lines at the beginning of a TSV file to skip").int().default(0)
    private val recursive: Boolean by option("-r", "--recursive", help = "Scan provided folder recursively").flag(default = false)
    private val whitespaceKeep: Boolean by option("-w", "--whitespaceKeep", help = "Keep whitespaces in URIs (TSV only)").flag(default = false)

    private val limit: Long by option("-l", "--limit", help = "Maximum number of triples to import (0 = no limit)").long().default(0L)

    private val format: String by option(
        "-t", "--format",
        help = "Override format detection: auto, tsv, turtle, nt, nq, trig, rdfxml, jsonld"
    ).default("auto")

    private val compression: String by option(
        "-c", "--compression",
        help = "Decompression mode: auto, none, gzip, bzip2, xz, zstd, lz4, snappy"
    ).default("auto")

    override fun run() {

        val file = File(fileName)

        if (!file.exists() || !file.canRead()) {
            System.err.println("Cannot read file '${file.absolutePath}'")
            return
        }

        if (file.isFile) {
            processFile(file)
        } else if (file.isDirectory) {
            if (!recursive) {
                System.err.println("'${file.absolutePath}' is a directory but recursive scan flag was not provided, aborting.")
                return
            }

            file.walkTopDown().forEach {
                if (it.isFile && it.canRead()) {
                    processFile(it)
                }
            }
        }
    }

    /**
     * Process a single file: detect format, decompress if needed, parse.
     */
    private fun processFile(file: File) {
        val detectedFormat = detectFormat(file, format)
        logger.info("Processing ${file.absolutePath} as $detectedFormat (compression=$compression)")

        when (detectedFormat) {
            "tsv" -> importTsv(file)
            else -> importRdf(file, detectedFormat)
        }
    }

    // ---------------------------------------------------------------------------
    // Format detection
    // ---------------------------------------------------------------------------

    private fun detectFormat(file: File, overrideFormat: String): String {
        if (overrideFormat != "auto") return overrideFormat

        var name = file.name.lowercase()
        // Strip compression extensions
        name = name.replace(Regex("\\.(gz|bz2|xz|zst|lz4|snappy)$"), "")

        return when {
            name.endsWith(".ttl") -> "turtle"
            name.endsWith(".nt") -> "nt"
            name.endsWith(".nq") -> "nq"
            name.endsWith(".trig") -> "trig"
            name.endsWith(".rdf") || name.endsWith(".owl") || name.endsWith(".xml") -> "rdfxml"
            name.endsWith(".jsonld") || name.endsWith(".json") -> "jsonld"
            name.endsWith(".tsv") -> "tsv"
            else -> "tsv" // backward-compatible default
        }
    }

    private fun formatToJenaLang(fmt: String): Lang = when (fmt) {
        "turtle" -> Lang.TURTLE
        "nt" -> Lang.NTRIPLES
        "nq" -> Lang.NQUADS
        "trig" -> Lang.TRIG
        "rdfxml" -> Lang.RDFXML
        "jsonld" -> Lang.JSONLD
        else -> Lang.TURTLE
    }

    // ---------------------------------------------------------------------------
    // Compression
    // ---------------------------------------------------------------------------

    private fun decompressIfNeeded(inputStream: InputStream, file: File, compressionMode: String): InputStream {
        return when (compressionMode) {
            "none" -> inputStream
            "gzip" -> GZIPInputStream(inputStream)
            "bzip2" -> BZip2CompressorInputStream(inputStream)
            "xz" -> XZCompressorInputStream(inputStream)
            "zstd" -> ZstdCompressorInputStream(inputStream)
            "lz4" -> FramedLZ4CompressorInputStream(inputStream)
            "snappy" -> SnappyCompressorInputStream(inputStream)
            "auto" -> {
                val name = file.name.lowercase()
                when {
                    name.endsWith(".gz") -> GZIPInputStream(inputStream)
                    name.endsWith(".bz2") -> BZip2CompressorInputStream(inputStream)
                    name.endsWith(".xz") -> XZCompressorInputStream(inputStream)
                    name.endsWith(".zst") -> ZstdCompressorInputStream(inputStream)
                    name.endsWith(".lz4") -> FramedLZ4CompressorInputStream(inputStream)
                    name.endsWith(".snappy") -> SnappyCompressorInputStream(inputStream)
                    else -> inputStream
                }
            }
            else -> inputStream
        }
    }

    // ---------------------------------------------------------------------------
    // RDF import (Turtle, N-Triples, N-Quads, TriG, RDF/XML, JSON-LD)
    // ---------------------------------------------------------------------------

    private fun importRdf(file: File, fmt: String) {
        val jenaLang = formatToJenaLang(fmt)
        val batch = mutableSetOf<Quad>()
        var counter = 0L
        val startTimeMs = System.currentTimeMillis()

        println("${LocalDateTime.now()} Importing ${file.absolutePath} as $fmt")

        val rawStream = file.inputStream()
        val inputStream = decompressIfNeeded(rawStream, file, compression)

        val streamRdf = object : StreamRDF {
            override fun start() {}
            override fun finish() {
                // flush remaining batch
                if (batch.isNotEmpty()) {
                    quads.addAll(batch)
                    batch.clear()
                }
            }
            override fun triple(triple: Triple) {
                if (limit > 0 && counter >= limit) return

                val subject = nodeToQuadValue(triple.subject)
                val predicate = nodeToQuadValue(triple.predicate)
                val obj = nodeToQuadValue(triple.`object`)
                batch.add(Quad(subject, predicate, obj))
                counter++

                if (batch.size >= batchSize) {
                    quads.addAll(batch)
                    batch.clear()
                }
                printProgress(counter, startTimeMs, limit)
            }
            override fun quad(quad: JenaQuad) {
                if (limit > 0 && counter >= limit) return

                val subject = nodeToQuadValue(quad.subject)
                val predicate = nodeToQuadValue(quad.predicate)
                val obj = nodeToQuadValue(quad.`object`)
                batch.add(Quad(subject, predicate, obj))
                counter++

                if (batch.size >= batchSize) {
                    quads.addAll(batch)
                    batch.clear()
                }
                printProgress(counter, startTimeMs, limit)
            }
            override fun base(base: String?) {}
            override fun prefix(prefix: String?, iri: String?) {}
        }

        try {
            RDFParser.create()
                .source(InputStreamReader(inputStream, Charsets.UTF_8))
                .lang(jenaLang)
                .parse(streamRdf)
        } catch (e: Exception) {
            logger.error("Error parsing ${file.absolutePath}: ${e.message}", e)
            System.err.println("Error parsing ${file.absolutePath}: ${e.message}")
        } finally {
            inputStream.close()
        }

        val elapsed = (System.currentTimeMillis() - startTimeMs) / 1000.0
        System.err.print("\r")
        println("Imported $counter triples from ${file.absolutePath} in ${formatDuration(elapsed.toLong())}")
    }

    // ---------------------------------------------------------------------------
    // Jena Node -> QuadValue conversion
    // ---------------------------------------------------------------------------

    private fun nodeToQuadValue(node: Node): QuadValue {
        if (node.isURI) {
            return QuadValue.of("<${node.uri}>")
        }
        if (node.isLiteral) {
            val value = node.literalLexicalForm
            val datatype = node.literalDatatype
            return when (datatype?.uri) {
                "http://www.w3.org/2001/XMLSchema#integer",
                "http://www.w3.org/2001/XMLSchema#long",
                "http://www.w3.org/2001/XMLSchema#int" -> QuadValue.of(value.toLong())
                "http://www.w3.org/2001/XMLSchema#double",
                "http://www.w3.org/2001/XMLSchema#decimal",
                "http://www.w3.org/2001/XMLSchema#float" -> QuadValue.of(value.toDouble())
                else -> QuadValue.of(value)
            }
        }
        if (node.isBlank) {
            return QuadValue.of("_:${node.getBlankNodeLabel()}")
        }
        return QuadValue.of(node.toString())
    }

    // ---------------------------------------------------------------------------
    // TSV import (original logic preserved)
    // ---------------------------------------------------------------------------

    private fun importTsv(file: File) {
        val batch = mutableSetOf<Quad>()
        val splitter = "\t(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)".toRegex()

        var skip = this.skip
        var counter = 0L
        val startTimeMs = System.currentTimeMillis()

        println("${LocalDateTime.now()} Importing TSV from ${file.absolutePath}")

        val rawStream = file.inputStream()
        val inputStream = decompressIfNeeded(rawStream, file, compression)
        val reader = inputStream.bufferedReader(Charsets.UTF_8)

        try {
            reader.forEachLine { raw ->
                if (limit > 0 && counter >= limit) return@forEachLine
                if (skip-- > 0) return@forEachLine

                val line = raw.split(splitter)
                if (line.size >= 3) {
                    val values = line.map { it ->
                        if (!whitespaceKeep && it.startsWith('<') && it.endsWith('>')) {
                            QuadValue.of(it.replace(" ", "_"))
                        } else {
                            QuadValue.of(it)
                        }
                    }
                    val quad = Quad(values[0], values[1], values[2])
                    batch.add(quad)
                    ++counter

                    if (batch.size >= batchSize) {
                        quads.addAll(batch)
                        batch.clear()
                        printProgress(counter, startTimeMs, limit)
                    }
                }
            }

            if (batch.isNotEmpty()) {
                quads.addAll(batch)
            }

            val elapsed = (System.currentTimeMillis() - startTimeMs) / 1000.0
            System.err.print("\r")
            println("Imported $counter triples from ${file.absolutePath} in ${formatDuration(elapsed.toLong())}")
        } catch (e: Exception) {
            logger.error("Error reading TSV file ${file.absolutePath}: ${e.message}", e)
            System.err.println("Error reading TSV file ${file.absolutePath}: ${e.message}")
        } finally {
            reader.close()
            inputStream.close()
        }
    }

    // ---------------------------------------------------------------------------
    // Progress reporting
    // ---------------------------------------------------------------------------

    private fun printProgress(count: Long, startTimeMs: Long, limit: Long) {
        if (count % 1000L != 0L && count > 0) return

        val elapsed = (System.currentTimeMillis() - startTimeMs) / 1000.0
        val speed = if (elapsed > 0) count / elapsed else 0.0
        val elapsedStr = formatDuration(elapsed.toLong())

        val limitInfo = if (limit > 0) {
            val pct = (count * 100) / limit
            val remaining = if (speed > 0) (limit - count) / speed else 0.0
            " | $pct% of $limit | ETA ${formatDuration(remaining.toLong())}"
        } else ""

        System.err.print("\rImported $count triples | ${speed.toInt()}/s | $elapsedStr$limitInfo    ")
    }

    private fun formatDuration(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return String.format("%02d:%02d:%02d", h, m, s)
    }
}
