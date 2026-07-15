# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this project is

MeGraS (**Media**-**Gra**ph **S**tore) is the storage and query-processing engine of the MediaGraph project. It stores multimodal knowledge graphs (including their media components — images, audio/video, 3D meshes, documents) as RDF quads and extends SPARQL so media content participates directly in queries (k-NN, segmentation-derived/non-materialized relations, spatial/temporal reasoning over segments). Written in Kotlin, runs on the JVM (Java 17 toolchain, requires JRE 8+).

## Build, run, test

- **Build / distribution:** `./gradlew distZip` → unpack archive in `build/distributions`. (Alternatively `./gradlew shadowJar` produces a fat jar `build/libs/megras-*-all.jar`; `mainClassName = org.megras.MeGraS`.)
- **Run:** `./gradlew run` passes no config; the app loads `./config.json` if present. You cannot pass a config-file argument via `gradlew run` easily — run the distribution/jar directly (`java -jar ... <configFile>`) when a specific config is needed.
- **Tests:** `./gradlew test` (JUnit Platform / Jupiter). Run a single test class/method: `./gradlew test --tests "org.megras.<FullyQualifiedClassName>"` or `--tests "*.ClassName.methodName"`.
- **Protobuf/gRPC:** `src/main/proto` sources live in `python_grpc_server/protos` (wired via `sourceSets.main.proto`); Gradle generates Java+Kotlin stubs. Do not hand-edit `python_grpc_server/generated`.
- **Python helper services** (CLIP/SigLIP embedders, OCR, docling): separate process — `cd python_grpc_server && pip install -r requirements.txt && python server.py`. MeGraS talks to it via gRPC; `ServiceConfig` holds host/port. Several derived/implicit handlers degrade silently when this server is unavailable.
- **ffmpeg** must be on `PATH` for audio/video operations.

Toolchain is pinned to JDK 17 (`java.toolchain` / `kotlin.jvmToolchain(17)`); stick to 17 to avoid kapt/module-access issues.

## Configuration & backends

Config is a JSON file (parsed into `org.megras.data.model.Config`); if none is supplied and `./config.json` is absent, defaults are used. `Config.StorageBackend` selects the graph store (`FILE`, `COTTONTAIL`, `POSTGRES`, `HYBRID`); the enum's `init` block validates that the corresponding connection config is non-null. The `sparqlQueryEngine` field chooses `"DEFAULT"` (plain Jena) vs `"BATCHING"` (custom op executor). Do not reference backend-specific behavior without gating on this enum.

## Architecture

### Startup wiring (`org.megras.MeGraS`)
`MeGraS.main` is the composition root and the place to understand how layers stack. It:
1. Loads `Config`, initializes `ServiceConfig` (gRPC) and `SparqlUtil.configureQueryEngine(...)`.
2. Constructs the storage-layer `MutableQuadSet` (`slQuadSet`) per `Config.StorageBackend`.
3. Wraps it: `quadSet = DerivedRelationMutableQuadSet(slQuadSet, derivedHandlers...)`, then `quadSet = ImplicitRelationMutableQuadSet(quadSet, implicitHandlers, regexHandlers)`.
4. Calls `QuadSetAware.setQuadSet(quadSet)` on any derived handler needing the fully-wrapped set (handlers are constructed against the inner set, then retrofitted).
5. Starts `RestApi`, registers SPARQL custom functions (`FunctionRegistrar`), initializes `Cli`.

This wrapping order matters: the outer `QuadSet` exposes derived/implicit relations as if they were materialized triples, so query code only ever sees one `QuadSet` interface regardless of where a relation comes from.

### `QuadSet` is the central abstraction
`org.megras.graphstore.QuadSet` is the only thing query/handler code touches. Its interface is intentionally backend-agnostic: `filterSubject/Predicate/Object`, `filter(s,p,o)` with null=any, `nearestNeighbor(...)`, `textFilter(...)`, `getId`, `distinctObjects/Subjects`. Implementations span:
- In-memory/file: `BasicQuadSet`/`BasicMutableQuadSet`, `BinarySerializedMutableQuadSet`, `TSVMutableQuadSet` (FILE backend; shutdown hook persists), `IndexedMutableQuadSet`.
- DB-backed: `AbstractDbStore` ← `CottontailStore`, `PostgresStore`; `HybridMutableQuadSet` splits work across Postgres+Cottontail.
- Relation overlays: `DerivedRelationMutableQuadSet`, `ImplicitRelationMutableQuadSet`.

Data model: `Quad(id, subject, predicate, object)` where every term is a `QuadValue` sealed hierarchy (`URIValue`, `LocalQuadValue`, `StringValue`, `LongValue`, `DoubleValue`, `TemporalValue`, `DoubleVectorValue`, `LongVectorValue`, `FloatVectorValue`, `VectorValue`). `QuadValue.of(...)` parses canonical lexical forms (incl. `<...>` IRIs, `^^Type` suffixes, `[...]^^Vec` vectors); reuse it rather than hand-rolling parsers. `LocalQuadValue` carries MeGraS-local IDs (the `http://localhost/...` and default-prefix forms).

### Object store is separate from the graph
`org.megras.data.fs.ObjectStore` / `FileSystemObjectStore` hold the bytes of media objects referenced by `LocalQuadValue` IDs. Graph storage and object storage are decoupled — many handlers take both `(QuadSet, ObjectStore)`.

### Segmentation
`org.megras.segmentation.type.Segmentation` (subtype hierarchy: relative, 1D, 2D, 3D, cut, reduction, preprocess, hilbert…) defines how a media object is partitioned. `seg.media.*Segmenter` (audio-video, document, image, mesh, text, video-shape) produce `SegmentationResult`s. Segment IDs are addressable as graph terms and compositional in URL routes (see the layered `/segment/.../segment/...` and `/and/` / `/or/` intersection/union patterns in `RestApi` and `CanonicalSegmentRequestHandler`).

### Derived vs implicit relations (two extension points)
Both are registered statically and surfaced through their `MutableQuadSet` wrappers; **neither persists triples** — they compute on demand.

- **Derived** (`graphstore.derived`): keyed by a *fixed* predicate URI; a handler computes the object value for a given subject from media/object-store content (e.g. `AverageColorHandler`, `ClipEmbeddingHandler`, `OcrHandler`, `Page/Figure/Table/ParagraphHandler`). Added via `DerivedRelationRegistrar`; handlers implementing `QuadSetAware` get the wrapped `QuadSet`.
- **Implicit** (`graphstore.implicit`): keyed by predicate URI patterns; a handler yields *subjects* matching a relationship — spatial (contains/within/overlaps/above/below/left/right/...), temporal (precedes/meets/overlaps/after/starts/finishes/equals over objects or segments), sibling-segment, near-duplicate, and k-NN (`GenericKnn*`, `ClipKnn*`, incl. regex variants). Registered in `ImplicitRelationRegistrar`; many spatial/temporal handlers are commented out by default — uncomment deliberately.

When adding a relation: pick derived (compute an object for one subject) vs implicit (enumerate subjects for a predicate), subclass the matching handler, register it in the corresponding registrar. Respect the `Constants` prefixes (`DERIVED_PREFIX`, `IMPLICIT_PREFIX`, `TEMPORAL_*`, `SPATIAL_SEGMENT_PREFIX`, `MM_PREFIX`).

### SPARQL layer
SPARQL is implemented on Apache Jena. `JenaGraphWrapper(quadSet)` adapts a `QuadSet` into a Jena `Graph`; `graphBaseFind` translates triple patterns to `QuadSet.filter(...)`. Custom functions are registered globally via `FunctionRegistry.put("${Constants.SPARQL_PREFIX}#NAME", ...)` in `FunctionRegistrar` — several spatial/accessor functions (`SEGMENT_AREA`, `BOUNDS_CENTER`, `WIDTH/HEIGHT/DEPTH/DURATION`, `XYZT`, ...) are stateful and receive the `QuadSet` through static setters at registration time. The `batch/` subpackage implements an alternate `QueryEngineFactory` (`BatchingQueryEngineFactory.register()` at startup when `BATCHING` is configured) that batches result-set fetches to avoid N+1 DB calls — changes to query planning belong here, not in the Jena wrapper. `lang.ResultTable` represents intermediate query results.

### REST API
`org.megras.api.rest.RestApi` (Javalin) owns route definitions and wires each route to a handler in `api.rest.handlers`. OpenAPI/Swagger annotations are processed by kapt; `/openapi.json`, `/swagger-ui`, `/sparqlui`, `/predicateinformation` serve specs/UIs. Request/response DTOs live in `api.rest.data` (`ApiQuad`, `ApiKnnQuery`, `ApiPathQuery`, `ApiSparqlResult*`, …). Handlers consistently take `(quadSet, objectStore)` or `(slQuadSet, ...)` — note the distinction: some handlers (`AboutObjectRequestHandler`, `GraphNeighborhoodHandler`) intentionally bypass derived/implicit overlays and query `slQuadSet` directly. CORS is reflected-origin with credentials; max request 10 MB, header 64 KB.

### Schemas / well-known vocabularies
`org.megras.data.schema.{MeGraS,Nlp,SchemaOrg}` define fixed predicate URIs used across the codebase; prefer referencing these constants over literals. `docs/predicates.json` documents the predicate vocabulary and is packaged as a resource alongside `GETTING_STARTED.md`.

## Conventions

- Persist `Quad`s and serialize terms through the `QuadValue` machinery, not ad-hoc string ops. Mind that `LocalQuadValue` encodings differ from general `URIValue`s.
- When adding REST endpoints, follow the existing handler pattern and add the route in `RestApi`; keep `slQuadSet` vs wrapped-`quadSet` semantics intentional (overlay-bypassing endpoints use `slQuadSet`).
- Custom SPARQL functions go through `FunctionRegistrar` and must be reachable via `Constants.SPARQL_PREFIX`/`MM_PREFIX`; stateful functions store their `QuadSet` via a static setter called at registration.
- Backends must implement the full `QuadSet`/`MutableQuadSet`/`PersistableQuadSet` contracts (incl. `nearestNeighbor`, `textFilter`, distinct-object optimizations); do not assume a particular backend in caller code.
- Default request-review against both offensive-defensive symmetry is irrelevant here — this is an open-source data store; optimize for clarity and the layering/wrap-order constraints above.
