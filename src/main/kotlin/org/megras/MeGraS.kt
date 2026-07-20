package org.megras

import org.megras.api.cli.Cli
import org.megras.api.rest.RestApi
import org.megras.data.fs.FileSystemObjectStore
import org.megras.data.model.Config
import org.megras.graphstore.db.CottontailStore
import org.megras.graphstore.HybridMutableQuadSet
import org.megras.graphstore.TSVMutableQuadSet
import org.megras.graphstore.db.PostgresStore
import org.megras.graphstore.db.dict.PostgresDictionary
import org.megras.graphstore.db.ClusterQuadSet
import org.megras.graphstore.db.shard.PostgresShard
import org.megras.graphstore.db.shard.QuadHashShardPolicy
import org.megras.graphstore.db.shard.RoundRobinShardPolicy
import org.megras.graphstore.db.shard.PrefixShardPolicy
import org.megras.graphstore.db.shard.SplitShardPolicy
import org.megras.graphstore.db.shard.TrivialShardPolicy
import org.megras.graphstore.derived.DerivedRelationMutableQuadSet
import org.megras.graphstore.derived.DerivedRelationRegistrar
import org.megras.graphstore.derived.DerivedRelationIngester
import org.megras.graphstore.implicit.ImplicitRelationMutableQuadSet
import org.megras.graphstore.implicit.ImplicitRelationRegistrar
import org.megras.lang.sparql.FunctionRegistrar
import org.megras.lang.sparql.SparqlUtil
import org.megras.segmentation.media.AudioVideoSegmenter
import org.megras.util.ServiceConfig
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.concurrent.thread
import org.megras.graphstore.derived.QuadSetAware

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

        // Initialize service config (gRPC host/port)
        ServiceConfig.setFrom(config)

        // Configure SPARQL query engine
        SparqlUtil.configureQueryEngine(config.sparqlQueryEngine)

        AudioVideoSegmenter.setConfig(config)

        val objectStore = FileSystemObjectStore(config.objectStoreBase)

        val slQuadSet = when (config.backend) {
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
                val pgConnStr = "${config.postgresConnection!!.host}:${config.postgresConnection.port}/${config.postgresConnection.database}"
                val dict = PostgresDictionary(pgConnStr, config.postgresConnection.user, config.postgresConnection.password)
                val postgresStore = PostgresStore(
                    dict,
                    pgConnStr,
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

                val pgConnStr = "${config.postgresConnection!!.host}:${config.postgresConnection.port}/${config.postgresConnection.database}"
                val dict = PostgresDictionary(pgConnStr, config.postgresConnection.user, config.postgresConnection.password)
                val postgresStore = PostgresStore(
                    dict,
                    pgConnStr,
                    config.postgresConnection.user,
                    config.postgresConnection.password
                )
                postgresStore.setup()

                HybridMutableQuadSet(postgresStore, cottontailStore)

            }

            Config.StorageBackend.CLUSTER -> {
                val cluster = config.clusterConnection!!
                val dict = PostgresDictionary(cluster.dictEndpoint, "megras", "megras")
                when (cluster.policy) {
                    Config.ClusterPolicy.TRIVIAL -> {
                        require(cluster.shardEndpoints.size == 1) {
                            "TRIVIAL cluster policy requires exactly one shard endpoint"
                        }
                        val shard = PostgresShard(cluster.shardEndpoints.single(), "megras", "megras")
                        ClusterQuadSet(dict, TrivialShardPolicy(shard)).also {
                            dict.setup(); shard.setup()
                        }
                    }
                    Config.ClusterPolicy.SPLIT -> {
                        require(cluster.shardEndpoints.size >= 2) {
                            "SPLIT cluster policy requires >= 2 shard endpoints, got ${cluster.shardEndpoints.size}"
                        }
                        val shards = cluster.shardEndpoints.map { PostgresShard(it, "megras", "megras") }
                        ClusterQuadSet(dict, SplitShardPolicy(shards)).also {
                            dict.setup()
                            shards.forEach { it.setup() }
                        }
                    }
                    Config.ClusterPolicy.ROUND_ROBIN -> {
                        require(cluster.shardEndpoints.size >= 2) {
                            "ROUND_ROBIN cluster policy requires >= 2 shard endpoints, got ${cluster.shardEndpoints.size}"
                        }
                        val shards = cluster.shardEndpoints.map { PostgresShard(it, "megras", "megras") }
                        ClusterQuadSet(dict, RoundRobinShardPolicy(shards)).also {
                            dict.setup()
                            shards.forEach { it.setup() }
                        }
                    }
                    Config.ClusterPolicy.QUAD_HASH -> {
                        require(cluster.shardEndpoints.size >= 2) {
                            "QUAD_HASH cluster policy requires >= 2 shard endpoints, got ${cluster.shardEndpoints.size}"
                        }
                        val shards = cluster.shardEndpoints.map { PostgresShard(it, "megras", "megras") }
                        ClusterQuadSet(dict, QuadHashShardPolicy(shards)).also {
                            dict.setup()
                            shards.forEach { it.setup() }
                        }
                    }
                    Config.ClusterPolicy.PREFIX -> {
                        require(cluster.shardEndpoints.size >= 2) {
                            "PREFIX cluster policy requires >= 2 shard endpoints, got ${cluster.shardEndpoints.size}"
                        }
                        val shards = cluster.shardEndpoints.map { PostgresShard(it, "megras", "megras") }
                        ClusterQuadSet(dict, PrefixShardPolicy(shards)).also {
                            dict.setup()
                            shards.forEach { it.setup() }
                        }
                    }
                }
            }
        }

        var quadSet = slQuadSet
        val derivedRelationRegistrar = DerivedRelationRegistrar(quadSet, objectStore)
        quadSet = DerivedRelationMutableQuadSet(quadSet, derivedRelationRegistrar.getHandlers())
        val implicitRelationRegistrar = ImplicitRelationRegistrar(objectStore)
        quadSet = ImplicitRelationMutableQuadSet(quadSet, implicitRelationRegistrar.getHandlers(), implicitRelationRegistrar.getRegexHandlers())

        // Inject the final (derived + implicit) QuadSet into handlers that need to query derived relations internally
        derivedRelationRegistrar.getHandlers().forEach { handler ->
            if (handler is QuadSetAware) {
                handler.setQuadSet(quadSet)
            }
        }

        // Create the derived relation ingester for eager computation at ingest time
        val derivedIngester = DerivedRelationIngester(derivedRelationRegistrar.getHandlers(), quadSet)

        RestApi.init(config, objectStore, quadSet, slQuadSet, derivedIngester)

        FunctionRegistrar.register(quadSet)

        Cli.init(quadSet, objectStore, slQuadSet, derivedIngester)

        Cli.loop()

        RestApi.stop()


    }

}
