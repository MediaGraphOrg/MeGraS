package org.megras.api.rest

import io.javalin.http.Context

interface DeleteRequestHandler {

    fun delete(ctx: Context)

}