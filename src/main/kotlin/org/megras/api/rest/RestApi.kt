package org.megras.api.rest

import io.javalin.Javalin
import io.javalin.apibuilder.ApiBuilder.*
import io.javalin.http.staticfiles.Location
import io.javalin.openapi.plugin.OpenApiPlugin
import io.javalin.openapi.plugin.swagger.SwaggerPlugin
import org.megras.api.rest.handlers.*
import org.megras.data.fs.FileSystemObjectStore
import org.megras.data.model.Config
import org.megras.graphstore.MutableQuadSet

object RestApi {

    private var javalin: Javalin? = null

    fun init(config: Config, objectStore: FileSystemObjectStore, quadSet: MutableQuadSet, slQuadSet: MutableQuadSet) {

        if (javalin != null) {
            stop() //stop instance in case there already is one. should not happen, just in case
        }

        val rawObjectRequestHandler = RawObjectRequestHandler(objectStore)
        val canonicalObjectRequestHandler = CanonicalObjectRequestHandler(quadSet, objectStore)
        val cachedSegmentRequestHandler = CachedSegmentRequestHandler(quadSet, objectStore)
        val canonicalSegmentRequestHandler = CanonicalSegmentRequestHandler(quadSet, objectStore)
        val aboutObjectRequestHandler = AboutObjectRequestHandler(slQuadSet, objectStore)
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
        val sparqlUiHandler = SparqlUiHandler()
        val deleteObjectRequestHandler = DeleteObjectRequestHandler(quadSet, objectStore)
        val relevanceFeedbackQueryHandler = RelevanceFeedbackQueryHandler(quadSet)
        val rootPageHandler = RootPageHandler()
        val fileUploadPageHandler = FileUploadPageHandler()
        val addTriplesPageHandler = AddTriplesPageHandler()
        val predicateInformationHandler = PredicateInformationHandler()


        javalin = Javalin.create {

            it.http.maxRequestSize = 10 * 1024L * 1024L //10MB
            it.jetty.modifyHttpConfiguration { httpConfig ->
                httpConfig.requestHeaderSize = 65535 //64kb
            }

            it.bundledPlugins.enableCors { cors ->
                cors.addRule { corsPluginConfig ->
                    corsPluginConfig.reflectClientOrigin = true
                    corsPluginConfig.allowCredentials = true
                }
            }

            it.showJavalinBanner = false

            it.router.apiBuilder {
                get("/", rootPageHandler::get)
                get("/raw/{objectId}", rawObjectRequestHandler::get)
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
                get("/sparqlui", sparqlUiHandler::get)
                delete("/<objectId>", deleteObjectRequestHandler::delete)
                post("/query/relevancefeedback", relevanceFeedbackQueryHandler::post)
                get("/fileupload", fileUploadPageHandler::get)
                get("/addtriples", addTriplesPageHandler::get)
                get("/predicateinformation", predicateInformationHandler::get)
                get("/predicateinformation/<predicateUri>", predicateInformationHandler::get)
            }

            it.staticFiles.add{ static ->
                static.directory = "static"
                static.hostedPath = "/static"
                static.location = Location.CLASSPATH
                static.precompress = false
            }

            it.registerPlugin(
                OpenApiPlugin { openApiConfig ->
                    openApiConfig
                        .withDocumentationPath("/openapi.json")
                        .withDefinitionConfiguration { version, openApiDefinition ->
                            openApiDefinition
                                .withInfo { openApiInfo ->
                                    openApiInfo
                                        .title("MeGraS API")
                                        .version("0.01")
                                        .description("API for MediaGraphStore 0.01")
                                        .license("MIT")
                                }
                        }
                }
            )

            it.registerPlugin(SwaggerPlugin { swaggerConfig ->
                swaggerConfig.documentationPath = "/openapi.json"
                swaggerConfig.uiPath = "/swagger-ui"
            })

        }.get( //needs to be added last in order to not interfere with any other paths
            "/{objectId}", canonicalObjectRequestHandler::get
        ).exception(RestErrorStatus::class.java) { e, ctx ->
            ctx.status(e.statusCode)
            ctx.result(e.message)
        }.exception(Exception::class.java) { e, ctx ->
            e.printStackTrace()
            ctx.status(500)
            ctx.result(e.message ?: e.javaClass.name)
        }.start(config.httpPort)
    }

    fun stop() {
        javalin?.stop()
        javalin = null
    }

}