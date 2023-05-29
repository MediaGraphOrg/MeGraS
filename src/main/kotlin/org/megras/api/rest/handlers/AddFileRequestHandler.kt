package org.megras.api.rest.handlers

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.javalin.http.Context
import org.megras.api.rest.PostRequestHandler
import org.megras.api.rest.RestErrorStatus
import org.megras.data.fs.FileSystemObjectStore
import org.megras.data.fs.file.PseudoFile
import org.megras.data.graph.Quad
import org.megras.data.graph.QuadValue
import org.megras.data.graph.StringValue
import org.megras.graphstore.MutableQuadSet
import org.megras.util.AddFileUtil

class AddFileRequestHandler(private val quads: MutableQuadSet, private val objectStore: FileSystemObjectStore) :
    PostRequestHandler {

    override fun post(ctx: Context) {

        val files = ctx.uploadedFiles()

        if (files.isEmpty()) {
            throw RestErrorStatus(400, "no file")
        }

        val mapper = jacksonObjectMapper()

        val ids = files.associate { uploadedFile ->
            val mapEntry = uploadedFile.filename() to AddFileUtil.addFile(objectStore, quads, PseudoFile(uploadedFile))

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