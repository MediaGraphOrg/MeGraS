package org.megras.api.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import org.megras.data.fs.FileSystemObjectStore
import org.megras.data.fs.file.PseudoFile
import org.megras.graphstore.MutableQuadSet
import org.megras.graphstore.PersistableQuadSet
import org.megras.util.FileUtil
import java.io.File

class AddFileCommand(private val quads: MutableQuadSet, private val objectStore: FileSystemObjectStore) : CliktCommand(name = "add", printHelpOnEmptyArgs = true, help = "Adds a media file as a graph node") {

    private val fileNames: List<String> by option("-f", "--File", help = "Path of file or folder to be added").multiple(required = true)

    private val recursive: Boolean by option("-r", "--recursive", help = "Scan provided folder recursively").flag(default = false)

    override fun run() {

        for (fileName in fileNames) {

            val file = File(fileName)

            if (!file.exists() || !file.canRead()) {
                System.err.println("Cannot read file '${file.absolutePath}'")
                return
            }

            if (file.isFile) {
                val id = FileUtil.addFile(objectStore, quads, PseudoFile(file)).uri

                println("Added file '${file.absolutePath}' with id '${id}'")
            } else if (file.isDirectory) {

                if (!recursive) {
                    System.err.println("'${file.absolutePath}' is a directory but recursive scan flag was not provided, aborting.")
                    return
                }

                file.walkTopDown().forEach {

                    if (it.isFile && it.canRead()) {
                        val id = FileUtil.addFile(objectStore, quads, PseudoFile(it)).uri
                        println("Added file '${it.absolutePath}' with id '${id}'")
                    }
                }
            }

            if (quads is PersistableQuadSet) {
                quads.store()
            }

        }


    }

}