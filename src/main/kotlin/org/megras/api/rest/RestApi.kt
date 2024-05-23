package org.megras.api.rest

import io.javalin.Javalin
import io.javalin.apibuilder.ApiBuilder.*
import io.javalin.openapi.OpenApiLicense
import io.javalin.openapi.plugin.OpenApiPlugin
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
        val sparqlQueryHandler = SparqlQueryHandler(quadSet)
        val deleteObjectRequestHandler = DeleteObjectRequestHandler(quadSet, objectStore)
        val relevanceFeedbackQueryHandler = RelevanceFeedbackQueryHandler(quadSet, quadSet)


        javalin = Javalin.create {

            it.http.maxRequestSize = 10 * 1024L * 1024L //10MB

            it.bundledPlugins.enableCors { cors ->
                cors.addRule { corsPluginConfig ->
                    corsPluginConfig.reflectClientOrigin = true
                    corsPluginConfig.allowCredentials = true
                }
            }

            it.showJavalinBanner = false



            it.registerPlugin(
                OpenApiPlugin { oapConfig ->
                    oapConfig
                        .withDocumentationPath("/openapi.json")
                        .withDefinitionConfiguration { _, openApiDef ->
                            openApiDef
                                .withInfo { info ->
                                    info.title = "MeGraS API"
                                    info.version = "0.01"
                                    info.description = "API for MediaGraphStore 0.01"
                                    val license = OpenApiLicense()
                                    license.name = "MIT"
                                    info.license = license
                                }
                        }
                }
            )

            it.registerPlugin(SwaggerPlugin { swaggerConfig ->
                swaggerConfig.documentationPath = "/openapi.json"
                swaggerConfig.uiPath = "/swagger-ui"
            })

            it.router.apiBuilder {
                get("/raw/{objectId}", rawObjectRequestHandler::get)
                get("/{objectId}", canonicalObjectRequestHandler::get)
                get("/<objectId>/about", aboutObjectRequestHandler::get)
                get("/<objectId>/preview", objectPreviewRequestHandler::get)
                get(
                    "/{objectId}/segment/{segmentation}/{segmentDefinition}/segment/{nextSegmentation}/{nextSegmentDefinition}/<tail>",
                    canonicalSegmentRequestHandler::get
                )
                get(
                    "/{objectId}/segment/{segmentation}/{segmentDefinition}/segment/{nextSegmentation}/{nextSegmentDefinition}",
                    canonicalSegmentRequestHandler::get
                )
                get("/{objectId}/segment/{segmentation}/{segmentDefinition}", canonicalSegmentRequestHandler::get)
                get(
                    "/{objectId}/c/{segmentId}/segment/{segmentation}/{segmentDefinition}/segment/{nextSegmentation}/{nextSegmentDefinition}/<tail>",
                    canonicalSegmentRequestHandler::get
                )
                get(
                    "/{objectId}/c/{segmentId}/segment/{segmentation}/{segmentDefinition}/segment/{nextSegmentation}/{nextSegmentDefinition}",
                    canonicalSegmentRequestHandler::get
                )
                get(
                    "/{objectId}/c/{segmentId}/segment/{segmentation}/{segmentDefinition}",
                    canonicalSegmentRequestHandler::get
                )
                get(
                    "/{objectId}/segment/{segmentation1}/{segmentDefinition1}/and/{segmentation2}/{segmentDefinition2}",
                    canonicalSegmentRequestHandler::intersection
                )
                get(
                    "/{objectId}/c/{segmentId}/segment/{segmentation1}/{segmentDefinition1}/and/{segmentation2}/{segmentDefinition2}",
                    canonicalSegmentRequestHandler::intersection
                )
                get(
                    "/{objectId}/segment/{segmentation1}/{segmentDefinition1}/or/{segmentation2}/{segmentDefinition2}",
                    canonicalSegmentRequestHandler::union
                )
                get(
                    "/{objectId}/c/{segmentId}/segment/{segmentation1}/{segmentDefinition1}/or/{segmentation2}/{segmentDefinition2}",
                    canonicalSegmentRequestHandler::union
                )
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
                get("/query/sparql", sparqlQueryHandler::get)
                delete("/<objectId>", deleteObjectRequestHandler::delete)
                post("/query/relevancefeedback", relevanceFeedbackQueryHandler::post)
            }
        }.exception(RestErrorStatus::class.java) { e, ctx ->
            ctx.status(e.statusCode)
            ctx.result(e.message)
        }.exception(Exception::class.java) { e, ctx ->
            e.printStackTrace()
        }.start(config.httpPort)
    }

    fun stop() {
        javalin?.stop()
        javalin = null
    }

}