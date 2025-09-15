package org.megras.util

import org.megras.data.model.Config

object ServiceConfig {
    @Volatile var grpcHost: String = "localhost"
        private set
    @Volatile var grpcPort: Int = 50051
        private set

    fun setFrom(config: Config) {
        this.grpcHost = config.grpcConnection.host
        this.grpcPort = config.grpcConnection.port
    }
}

