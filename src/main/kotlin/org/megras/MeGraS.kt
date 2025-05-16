package org.megras

import org.megras.api.cli.Cli
import org.megras.api.rest.RestApi
import org.megras.data.fs.FileSystemObjectStore
import org.megras.data.model.Config
import org.megras.graphstore.db.CottontailStore
import org.megras.graphstore.HybridMutableQuadSet
import org.megras.graphstore.TSVMutableQuadSet
import org.megras.graphstore.db.PostgresStore
import org.megras.graphstore.derived.DerivedRelationMutableQuadSet
import org.megras.graphstore.derived.DerivedRelationRegistrar
import org.megras.graphstore.implicit.ImplicitRelationMutableQuadSet
import org.megras.graphstore.implicit.ImplicitRelationRegistrar
import org.megras.lang.sparql.FunctionRegistrar
import org.megras.segmentation.media.AudioVideoSegmenter
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.concurrent.thread

object MeGraS {

    @JvmStatic
    fun main(args: Array<String>) {

        val logger = LoggerFactory.getLogger(MeGraS::class.java)

        val config = Config.read(
            if (args.isNotEmpty()) {
                File(args[0])
            } else {
                logger.info("no config file specified, trying ./config.json as a default")
                File("config.json")
            }
        ) ?: Config().also {
            logger.info("using default config")
        }

        AudioVideoSegmenter.setConfig(config)

        val objectStore = FileSystemObjectStore(config.objectStoreBase)

        var quadSet = when (config.backend) {
            Config.StorageBackend.FILE -> {
                val set = TSVMutableQuadSet(config.fileStore!!.filename, config.fileStore.compression)
                // ensure that latest state of quads is persisted on shutdown
                Runtime.getRuntime().addShutdownHook(thread(start = false) {
                    set.store()
                })
                set
            }

            Config.StorageBackend.COTTONTAIL -> {
                val cottontailStore = CottontailStore(
                    config.cottontailConnection!!.host, config.cottontailConnection.port
                )
                cottontailStore.setup()
                cottontailStore
            }

            Config.StorageBackend.POSTGRES -> {
                val postgresStore = PostgresStore(
                    "${config.postgresConnection!!.host}:${config.postgresConnection.port}/${config.postgresConnection.database}",
                    config.postgresConnection.user,
                    config.postgresConnection.password
                )
                postgresStore.setup()
                postgresStore
            }

            Config.StorageBackend.HYBRID -> {
                val cottontailStore = CottontailStore(
                    config.cottontailConnection!!.host, config.cottontailConnection.port
                )
                cottontailStore.setup()

                val postgresStore = PostgresStore(
                    "${config.postgresConnection!!.host}:${config.postgresConnection.port}/${config.postgresConnection.database}",
                    config.postgresConnection.user,
                    config.postgresConnection.password
                )
                postgresStore.setup()

                HybridMutableQuadSet(postgresStore, cottontailStore)

            }
        }

        val implicitRelationRegistrar = ImplicitRelationRegistrar(objectStore)
        quadSet = ImplicitRelationMutableQuadSet(quadSet, implicitRelationRegistrar.getHandlers())
        val derivedRelationRegistrar = DerivedRelationRegistrar(quadSet, objectStore)
        quadSet = DerivedRelationMutableQuadSet(quadSet, derivedRelationRegistrar.getHandlers())

        RestApi.init(config, objectStore, quadSet)

        FunctionRegistrar.register(quadSet)

        Cli.init(quadSet, objectStore)

        Cli.loop()

        RestApi.stop()


    }

}