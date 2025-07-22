package org.megras.api.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import org.megras.graphstore.MutableQuadSet
import org.megras.graphstore.db.AbstractDbStore
import org.slf4j.LoggerFactory
import java.io.BufferedWriter
import java.io.FileWriter
import java.time.LocalDateTime

class DumpCommand(private val quads: MutableQuadSet) : CliktCommand(name = "dump", help = "dumps all triple to a TSV file", printHelpOnEmptyArgs = true) {

    private val logger = LoggerFactory.getLogger(this.javaClass)

    private val fileName: String by option("-f", "--File", help = "Path of TSV file to be used for dump")
        .required()

    private val batchSize: Int by option("-b", "--batchSize").int().default(100000)

    override fun run() {
        if (quads !is AbstractDbStore) {
            println("Dump is only supported for database stores.")
            return
        }

        val startTime = System.currentTimeMillis()
        println("${LocalDateTime.now()} Starting database dump to TSV ...")

        val writer = BufferedWriter(FileWriter(fileName))
        quads.dump(writer, batchSize)
        writer.close()

        val duration = (System.currentTimeMillis() - startTime) / 1000.0
        println("\nDump complete in $duration seconds.")
    }
}