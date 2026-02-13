package org.megras.api.rest.handlers

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.javalin.http.Context
import io.javalin.openapi.*
import org.megras.api.rest.PostRequestHandler
import org.megras.api.rest.RestErrorStatus
import org.megras.data.fs.FileSystemObjectStore
import org.megras.data.fs.file.PseudoFile
import org.megras.data.graph.Quad
import org.megras.data.graph.QuadValue
import org.megras.data.graph.StringValue
import org.megras.graphstore.MutableQuadSet
import org.megras.util.FileUtil

class AddFileRequestHandler(private val quads: MutableQuadSet, private val objectStore: FileSystemObjectStore) :
    PostRequestHandler {

    @OpenApi(
        summary = "Uploads one or more files and stores them in the object store.",
        path = "/add/file",
        tags = ["Object Management"],
        operationId = OpenApiOperation.AUTO_GENERATE,
        methods = [HttpMethod.POST],
        requestBody = OpenApiRequestBody([OpenApiContent(type = "multipart/form-data")]),
        queryParams = [
            OpenApiParam(name = "metaSkip", type = Boolean::class, description = "Skip metadata extraction from file", required = false)
        ],
        responses = [
            OpenApiResponse(status = "200", description = "Returns mapping of uploaded filenames to stored object IDs", content = [OpenApiContent(type = "application/json")]),
            OpenApiResponse(status = "400", description = "Invalid request", content = [OpenApiContent(RestErrorStatus::class)])
        ]
    )
    override fun post(ctx: Context) {

        val files = ctx.uploadedFiles()

        if (files.isEmpty()) {
            throw RestErrorStatus(400, "no file")
        }

        val metaSkip = ctx.queryParam("metaSkip")?.toBoolean() ?: false

        val mapper = jacksonObjectMapper()

        val ids = files.associate { uploadedFile ->
            val mapEntry = uploadedFile.filename() to FileUtil.addFile(objectStore, quads, PseudoFile(uploadedFile), metaSkip)

            //check for metadata
            val meta = ctx.formParam(uploadedFile.filename())
            if (meta != null) {
                val metaMap = mapper.readValue(meta, Map::class.java)
                metaMap.forEach { (key, value) ->
                    if (key != null && value != null) {
                        quads.add(Quad(mapEntry.second, StringValue(key.toString()), QuadValue.of(value)))
                    }
                }
            }
            mapEntry
        }
        ctx.json(ids)

    }
}