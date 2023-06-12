package org.megras.api.rest

import io.javalin.Javalin
import io.javalin.apibuilder.ApiBuilder.*
import io.javalin.http.HandlerType
import io.javalin.http.Header
import io.javalin.openapi.plugin.OpenApiPlugin
import io.javalin.openapi.plugin.OpenApiPluginConfiguration
import io.javalin.openapi.plugin.swagger.SwaggerConfiguration
import io.javalin.openapi.plugin.swagger.SwaggerPlugin
import org.megras.api.rest.handlers.*
import org.megras.data.fs.FileSystemObjectStore
import org.megras.data.model.Config
import org.megras.graphstore.MutableQuadSet

object RestApi {

    private var javalin: Javalin? = null

    fun init(config: Config, objectStore: FileSystemObjectStore, quadSet: MutableQuadSet) {

        if (javalin != null) {
            stop() //stop instance in case there already is one. should not happen, just in case
        }

        val rawObjectRequestHandler = RawObjectRequestHandler(objectStore)
        val canonicalObjectRequestHandler = CanonicalObjectRequestHandler(quadSet, objectStore)
        val cachedSegmentRequestHandler = CachedSegmentRequestHandler(quadSet, objectStore)
        val canonicalSegmentRequestHandler = CanonicalSegmentRequestHandler(quadSet, objectStore)
        val aboutObjectRequestHandler = AboutObjectRequestHandler(quadSet, objectStore)
        val objectPreviewRequestHandler = ObjectPreviewRequestHandler(quadSet, objectStore)
        val addFileRequestHandler = AddFileRequestHandler(quadSet, objectStore)
        val addQuadRequestHandler = AddQuadRequestHandler(quadSet)
        val basicQueryHandler = BasicQueryHandler(quadSet)
        val textQueryHandler = TextQueryHandler(quadSet)
        val subjectQueryHandler = SubjectQueryHandler(quadSet)
        val predicateQueryHandler = PredicateQueryHandler(quadSet)
        val objectQueryHandler = ObjectQueryHandler(quadSet)
        val knnQueryHandler = KnnQueryHandler(quadSet)
        val pathQueryHandler = PathQueryHandler(quadSet)
        val deleteObjectRequestHandler = DeleteObjectRequestHandler(quadSet, objectStore)


        javalin = Javalin.create {

            it.http.maxRequestSize = 10 * 1024L * 1024L //10MB

            it.plugins.enableCors { cors ->
                cors.add { corsPluginConfig ->
                    corsPluginConfig.anyHost()
                }
                cors.add {corsPluginConfig ->
                    corsPluginConfig.reflectClientOrigin = true
                }
            }
            it.showJavalinBanner = false

            it.plugins.register(
                OpenApiPlugin(
                    OpenApiPluginConfiguration()
                        .withDocumentationPath("/swagger-docs")
                        .withDefinitionConfiguration { _, u ->
                            u.withOpenApiInfo { t ->
                                t.title = "MeGraS API"
                                t.version = "0.01"
                                t.description = "API for MediaGraphStore 0.01"
                            }
                        }
                )
            )

            it.plugins.register(
                SwaggerPlugin(
                    SwaggerConfiguration().apply {
                        //this.version = "4.10.3"
                        this.documentationPath = "/swagger-docs"
                        this.uiPath = "/swagger-ui"
                    }
                )
            )

        }.routes {
            get("/raw/{objectId}", rawObjectRequestHandler::get)
            get("/{objectId}", canonicalObjectRequestHandler::get)
            get("/<objectId>/about", aboutObjectRequestHandler::get)
            get("/<objectId>/preview", objectPreviewRequestHandler::get)
            get("/{objectId}/segment/{segmentation}/{segmentDefinition}/segment/{nextSegmentation}/{nextSegmentDefinition}/<tail>", canonicalSegmentRequestHandler::get)
            get("/{objectId}/segment/{segmentation}/{segmentDefinition}/segment/{nextSegmentation}/{nextSegmentDefinition}", canonicalSegmentRequestHandler::get)
            get("/{objectId}/segment/{segmentation}/{segmentDefinition}", canonicalSegmentRequestHandler::get)
            get("/{objectId}/c/{segmentId}/segment/{segmentation}/{segmentDefinition}/segment/{nextSegmentation}/{nextSegmentDefinition}/<tail>", canonicalSegmentRequestHandler::get)
            get("/{objectId}/c/{segmentId}/segment/{segmentation}/{segmentDefinition}/segment/{nextSegmentation}/{nextSegmentDefinition}", canonicalSegmentRequestHandler::get)
            get("/{objectId}/c/{segmentId}/segment/{segmentation}/{segmentDefinition}", canonicalSegmentRequestHandler::get)
            get("/{objectId}/c/{segmentId}*", cachedSegmentRequestHandler::get)
            post("/add/file", addFileRequestHandler::post)
            post("/add/quads", addQuadRequestHandler::post)
            post("/query/quads", basicQueryHandler::post)
            post("/query/text", textQueryHandler::post)
            post("/query/subject", subjectQueryHandler::post)
            post("/query/predicate", predicateQueryHandler::post)
            post("/query/object", objectQueryHandler::post)
            post("/query/knn", knnQueryHandler::post)
            post("/query/path", pathQueryHandler::post)
            delete("/<objectId>", deleteObjectRequestHandler::delete)
        }.exception(RestErrorStatus::class.java) { e, ctx ->
            ctx.status(e.statusCode)
            ctx.result(e.message)
        }.exception(Exception::class.java) { e, ctx ->
            e.printStackTrace()
        }.start(config.httpPort)

        javalin?.before {
            if (it.method() == HandlerType.OPTIONS) {
                it.header("Origin")?.let { origin -> it.header(Header.ACCESS_CONTROL_ALLOW_ORIGIN, origin) };
            }
        }
    }

    fun stop() {
        javalin?.stop()
        javalin = null
    }

}