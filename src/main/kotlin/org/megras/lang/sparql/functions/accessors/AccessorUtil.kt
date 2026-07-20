package org.megras.lang.sparql.functions.accessors

import org.megras.data.graph.TemporalValue
import org.megras.data.graph.URIValue
import org.megras.data.schema.MeGraS
import org.megras.graphstore.QuadSet
import org.megras.segmentation.Bounds
import java.time.Instant
import java.time.ZoneId

object AccessorUtil {

    /**
     * Extracts the temporal start of a subject's time interval.
     *
     * Strategy (in order):
     * 1. Look for any [TemporalValue] quads on the subject (e.g. EXIF timestamps like CreateDate).
     *    The earliest temporal value is returned.
     * 2. Fall back to [MeGraS.SEGMENT_BOUNDS] or [MeGraS.BOUNDS]: parse the T dimension and
     *    convert [Bounds.getMinT] to a [TemporalValue] (seconds-since-epoch).
     * 3. Return null if no temporal data is found.
     */
    fun getStart(subject: URIValue, quads: QuadSet): TemporalValue? {
        // 1. Explicit TemporalValue quads (EXIF timestamps, etc.)
        val temporalValues = getTemporalValues(subject, quads)
        if (temporalValues.isNotEmpty()) {
            return temporalValues.minOrNull()
        }

        // 2. Fall back to bounds T dimension
        return getBoundsT(subject, quads)?.first
    }

    /**
     * Extracts the temporal end of a subject's time interval.
     *
     * Strategy mirrors [getStart], using the latest temporal value or the T-max of bounds.
     */
    fun getEnd(subject: URIValue, quads: QuadSet): TemporalValue? {
        // 1. Explicit TemporalValue quads (EXIF timestamps, etc.)
        val temporalValues = getTemporalValues(subject, quads)
        if (temporalValues.isNotEmpty()) {
            return temporalValues.maxOrNull()
        }

        // 2. Fall back to bounds T dimension
        return getBoundsT(subject, quads)?.second
    }

    /**
     * Collects all [TemporalValue] objects associated with [subject] in [quads].
     */
    private fun getTemporalValues(subject: URIValue, quads: QuadSet): List<TemporalValue> {
        return quads.filter(setOf(subject), null, null)
            .map { it.`object` }
            .filterIsInstance<TemporalValue>()
    }

    /**
     * Tries to extract start/end from the T dimension of bounds stored on [subject].
     * Checks SEGMENT_BOUNDS first (for segments), then BOUNDS (for objects).
     * Returns a pair of (start, end) as [TemporalValue], or null if no T dimension exists.
     */
    private fun getBoundsT(subject: URIValue, quads: QuadSet): Pair<TemporalValue, TemporalValue>? {
        // Try SEGMENT_BOUNDS first, then BOUNDS
        for (predicate in listOf(MeGraS.SEGMENT_BOUNDS.uri, MeGraS.BOUNDS.uri)) {
            val boundsQuad = quads.filter(setOf(subject), setOf(predicate), null).firstOrNull()
            if (boundsQuad != null) {
                val boundsString = boundsQuad.`object`.toString().replace("^^String", "")
                val bounds = try {
                    Bounds(boundsString)
                } catch (_: Exception) {
                    continue
                }
                if (bounds.hasT()) {
                    val start = epochSecToTemporal(bounds.getMinT())
                    val end = epochSecToTemporal(bounds.getMaxT())
                    return start to end
                }
            }
        }
        return null
    }

    private fun epochSecToTemporal(seconds: Double): TemporalValue {
        val instant = Instant.ofEpochMilli((seconds * 1000).toLong())
        return TemporalValue(instant.atZone(ZoneId.systemDefault()).toOffsetDateTime())
    }
}