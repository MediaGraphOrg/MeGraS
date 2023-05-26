package org.megras

import org.megras.api.cli.Cli
import org.megras.api.rest.RestApi
import org.megras.data.fs.FileSystemObjectStore
import org.megras.data.model.Config
import org.megras.graphstore.db.CottontailStore
import org.megras.graphstore.HybridMutableQuadSet
import org.megras.graphstore.TSVMutableQuadSet
import org.megras.graphstore.db.PostgresStore
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

        //val tsvSet = TSVMutableQuadSet("quads.tsv", false)

        val postgresStore = PostgresStore()
        val cottontailStore = CottontailStore()

        val quadSet = HybridMutableQuadSet(postgresStore, cottontailStore) //TSVMutableQuadSet(File("quads.tsv"))

        postgresStore.setup()
        cottontailStore.setup()

        RestApi.init(config, objectStore, quadSet)

        Cli.init(quadSet, objectStore)

        Cli.loop()

        RestApi.stop()

        //tsvSet.store()

    }

}