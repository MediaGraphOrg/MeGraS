package org.megras

import org.megras.api.cli.Cli
import org.megras.api.rest.RestApi
import org.megras.data.fs.FileSystemObjectStore
import org.megras.data.model.Config
import org.megras.graphstore.BinarySerializedMutableQuadSet
import org.megras.graphstore.CottontailStore
import org.megras.graphstore.TSVMutableQuadSet
import java.io.File

object MeGraS {

    @JvmStatic
    fun main(args: Array<String>) {

        val config = if (args.isNotEmpty()) {
            Config.read(File(args[0]))
        } else {
            null
        } ?: Config()

        val objectStore = FileSystemObjectStore(config.objectStoreBase)

        val quadSet = BinarySerializedMutableQuadSet("quads.bin") //TSVMutableQuadSet("quads.bzip2", true) //CottontailStore() //TSVMutableQuadSet(File("quads.tsv"))

        //quadSet.setup()

        RestApi.init(config, objectStore, quadSet)

        Cli.init(quadSet, objectStore)

        Cli.loop()

        RestApi.stop()

        quadSet.store()

    }

}