package org.megras.api.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import org.megras.data.graph.Quad
import org.megras.data.graph.QuadValue
import org.megras.graphstore.MutableQuadSet
import java.io.File

class ImportCommand(private val quads: MutableQuadSet) : CliktCommand(name = "import", help = "imports a TSV file or folder in triple format", printHelpOnEmptyArgs = true) {

    private val fileName: String by option("-f", "--File", help = "Path of TSV file or folder to be read for import")
        .required()

    private val batchSize: Int by option("-b", "--batchSize").int().default(100)
    private val skip: Int by option("-s", "--skip", help = "The number of lines at the beginning of the file to skip").int().default(0)
    private val recursive: Boolean by option("-r", "--recursive", help = "Scan provided folder recursively").flag(default = false)

    override fun run() {

        val file = File(fileName)

        if (!file.exists() || !file.canRead()) {
            System.err.println("Cannot read file '${file.absolutePath}'")
            return
        }

        val batch = mutableSetOf<Quad>()

        val splitter = "\t(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)".toRegex()

        var skip = this.skip

        fun addFromFile(file: File) {
            var counter = 0
            println("Adding file from ${file.absolutePath}")
            file.forEachLine { raw ->

                if (skip-- > 0) {
                    return@forEachLine
                }

                val line = raw.split(splitter)
                if (line.size >= 3) {
                    val values = line.map { QuadValue.of(it) }
                    val quad = Quad(values[0], values[1], values[2])
                    batch.add(quad)
                    ++counter
                    if (batch.size >= batchSize) {
                        quads.addAll(batch)
                        batch.clear()
                        println("processed $counter lines")
                    }
                }
            }

            if (batch.isNotEmpty()) {
                quads.addAll(batch)
            }

            println("Done reading $counter lines")
        }

        if (file.isFile) {
            addFromFile(file)
        } else if (file.isDirectory) {
            if (!recursive) {
                System.err.println("'${file.absolutePath}' is a directory but recursive scan flag was not provided, aborting.")
                return
            }

            file.walkTopDown().forEach {
                if (it.isFile && it.canRead()) {
                    addFromFile(it)
                }
            }
        }


    }

}