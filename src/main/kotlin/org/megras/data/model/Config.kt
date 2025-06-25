package org.megras.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File

@Serializable
data class Config(
    val objectStoreBase: String = "store",
    val hostName: String = "localhost",
    val httpPort: Int = 8080,
    val backend: StorageBackend = StorageBackend.FILE,
    val fileStore: FileGraphStorage? = FileGraphStorage(),
    val ffmpeg: FfmpegConfig = FfmpegConfig(),
    val cottontailConnection: CottontailConnection? = null,
    val postgresConnection: PostgresConnection? = null
) {

    init {
        when(backend) {
            StorageBackend.FILE -> {
                require(fileStore != null) { "fileStore cannot be null" }
            }
            StorageBackend.COTTONTAIL -> {
                require(cottontailConnection != null) { "cottontailConnection cannot be null" }
            }
            StorageBackend.POSTGRES -> {
                require(postgresConnection != null) { "postgresConnection cannot be null" }
            }
            StorageBackend.HYBRID -> {
                require(cottontailConnection != null) { "cottontailConnection cannot be null" }
                require(postgresConnection != null) { "postgresConnection cannot be null" }
            }
        }
    }

    companion object{

        private val logger = LoggerFactory.getLogger(Config::class.java)

        fun read(file: File): Config? {
            return try{
                Json.decodeFromString(serializer(), file.readText(Charsets.UTF_8))
            } catch (e: Exception) {
                logger.error("cannot parse config provided in file '${file.absolutePath}'", e)
                null
            }
        }
    }

    @Serializable
    enum class StorageBackend {
        FILE,
        COTTONTAIL,
        POSTGRES,
        HYBRID
    }

    @Serializable
    data class FileGraphStorage (
        val filename: String = "quads.tsv",
        val compression: Boolean = false
    )

    @Serializable
    data class CottontailConnection(
        val host: String = "localhost",
        val port: Int = 1865
    )

    @Serializable
    data class PostgresConnection(
        val host: String = "localhost",
        val port: Int = 5432,
        val database: String = "megras",
        val user: String,
        val password: String,
        val dumpOnStartup: Boolean = false
    )

    @Serializable
    data class FfmpegConfig(
        val ffmpegPath: String? = null,
        val ffprobePath: String? = null
    )

}
