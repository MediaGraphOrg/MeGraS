package org.megras.api.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import org.megras.data.fs.FileSystemObjectStore
import org.megras.data.fs.StoredObjectDescriptor
import org.megras.data.fs.file.PseudoFile
import org.megras.data.graph.Quad
import org.megras.data.mime.MimeType
import org.megras.data.model.MediaType
import org.megras.data.schema.MeGraS
import org.megras.graphstore.MutableQuadSet
import org.megras.graphstore.PersistableQuadSet
import org.megras.id.IdUtil
import org.megras.util.AddFileUtil
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO

class AddFileCommand(private val quads: MutableQuadSet, private val objectStore: FileSystemObjectStore) : CliktCommand(name = "add") {

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
                val id = AddFileUtil.addFile(objectStore, quads, PseudoFile(file)).uri

                println("Added file '${file.absolutePath}' with id '${id}'")
            } else if (file.isDirectory) {

                if (!recursive) {
                    System.err.println("'${file.absolutePath}' is a directory but recursive scan flag was not provided, aborting.")
                    return
                }

                file.walkTopDown().forEach {

                    if (it.isFile && it.canRead()) {
                        val id = AddFileUtil.addFile(objectStore, quads, PseudoFile(it)).uri
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