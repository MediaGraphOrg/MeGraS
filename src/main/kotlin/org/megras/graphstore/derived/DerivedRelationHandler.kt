package org.megras.graphstore.derived

import org.megras.data.graph.QuadValue
import org.megras.data.graph.URIValue

interface DerivedRelationHandler<T : QuadValue> {

    val predicate: URIValue

    /**
     * Returns true if this handler requires external services (e.g., gRPC).
     * Handlers that require external services will be skipped if those services are unavailable.
     * Default is false for backward compatibility.
     */
    val requiresExternalService: Boolean
        get() = false

    fun canDerive(subject: URIValue): Boolean

    fun derive(subject: URIValue): Collection<T>
}