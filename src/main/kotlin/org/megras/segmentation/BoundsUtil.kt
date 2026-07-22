package org.megras.segmentation

import org.megras.data.graph.QuadValue
import org.megras.data.graph.URIValue
import org.megras.data.schema.MeGraS
import org.megras.graphstore.QuadSet

object BoundsUtil {

    // Thread-local prefetch caches for batch optimization
    private val boundsCache = ThreadLocal<HashMap<QuadValue, Bounds>>()
    private val segmentBoundsCache = ThreadLocal<HashMap<QuadValue, Bounds>>()

    /**
     * Prefetch all bounds for the BOUNDS predicate in bulk.
     * Call this before a batch of getBounds() calls, then endPrefetch() in a finally block.
     */
    fun prefetchBounds(quads: QuadSet, subjects: Collection<QuadValue>? = null) {
        val cache = HashMap<QuadValue, Bounds>()
        val boundsQuads = if (subjects != null && subjects.isNotEmpty()) {
            quads.filter(subjects, setOf(MeGraS.BOUNDS.uri), null)
        } else {
            quads.filter(null, setOf(MeGraS.BOUNDS.uri), null)
        }
        for (quad in boundsQuads) {
            try {
                cache[quad.subject] = Bounds(quad.`object`.toString().replace("^^String", ""))
            } catch (_: Exception) { }
        }
        boundsCache.set(cache)
    }

    /**
     * Prefetch all bounds for the SEGMENT_BOUNDS predicate in bulk.
     */
    fun prefetchSegmentBounds(quads: QuadSet, subjects: Collection<QuadValue>? = null) {
        val cache = HashMap<QuadValue, Bounds>()
        val boundsQuads = if (subjects != null && subjects.isNotEmpty()) {
            quads.filter(subjects, setOf(MeGraS.SEGMENT_BOUNDS.uri), null)
        } else {
            quads.filter(null, setOf(MeGraS.SEGMENT_BOUNDS.uri), null)
        }
        for (quad in boundsQuads) {
            try {
                cache[quad.subject] = Bounds(quad.`object`.toString().replace("^^String", ""))
            } catch (_: Exception) { }
        }
        segmentBoundsCache.set(cache)
    }

    /**
     * Clear prefetch caches. Always call in a finally block after prefetching.
     */
    fun endPrefetch() {
        boundsCache.remove()
        segmentBoundsCache.remove()
    }

    fun getBounds(subject: URIValue, quads: QuadSet) : Bounds? {
        // Check prefetch cache first
        val cached = boundsCache.get()?.get(subject)
        if (cached != null) return cached

        // Fall back to per-row query
        val boundsString = (quads.filter(setOf(subject), setOf(MeGraS.BOUNDS.uri), null).firstOrNull()
            ?: throw IllegalArgumentException("Invalid subject. No bounds found."))
            .`object`.toString()

        return Bounds(boundsString.replace("^^String", ""))
    }

    fun getSegmentBounds(subject: URIValue, quads: QuadSet) : Bounds? {
        // Check prefetch cache first
        val cached = segmentBoundsCache.get()?.get(subject)
        if (cached != null) return cached

        // Fall back to per-row query
        val boundsString = (quads.filter(setOf(subject), setOf(MeGraS.SEGMENT_BOUNDS.uri), null).firstOrNull()
            ?: throw IllegalArgumentException("Invalid subject. No bounds found."))
            .`object`.toString()

        return Bounds(boundsString.replace("^^String", ""))
    }
}
