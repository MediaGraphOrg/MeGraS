package org.megras.graphstore.db.dict

import org.megras.data.graph.DoubleValue
import org.megras.data.graph.StringValue
import org.megras.graphstore.db.QuadValueId

/**
 * Central scalar dictionary: the only authority that mints and resolves IDs
 * for non-vector, non-inline value types (URI prefix, URI suffix, string
 * literal, double literal). In a distributed backend a single node owns this
 * surface so that quad encodings `(type, id)` are interpretable on every shard.
 *
 * Caching is the responsibility of the caller (the dispatcher), not this
 * interface: the methods here are leaf storage operations only. This keeps the
 * behavior of the existing single-node path identical when the leaf operations
 * are relocated behind this interface.
 *
 * `QuadValueId` is `Pair<Int, Long>`: a type discriminator paired with a
 * per-type row id. Vector value<->id resolution is intentionally NOT part of
 * this surface — it lives with the vector-content owner (see the shard side),
 * because vector IDs are coupled to the content rows and vector content is
 * sharded by corpus.
 */
interface QuadValueDictionary {

    fun lookUpDoubleValueIds(doubleValues: Set<DoubleValue>): Map<DoubleValue, QuadValueId>
    fun lookUpStringValueIds(stringValues: Set<StringValue>): Map<StringValue, QuadValueId>
    fun lookUpPrefixIds(prefixValues: Set<String>): Map<String, Int>
    fun lookUpSuffixIds(suffixValues: Set<String>): Map<String, Long>

    fun insertDoubleValues(doubleValues: Set<DoubleValue>): Map<DoubleValue, QuadValueId>
    fun insertStringValues(stringValues: Set<StringValue>): Map<StringValue, QuadValueId>
    fun insertPrefixValues(prefixValues: Set<String>): Map<String, Int>
    fun insertSuffixValues(suffixValues: Set<String>): Map<String, Long>

    fun lookUpDoubleValues(ids: Set<Long>): Map<QuadValueId, DoubleValue>
    fun lookUpStringValues(ids: Set<Long>): Map<QuadValueId, StringValue>
    fun lookUpPrefixes(ids: Set<Int>): Map<Int, String>
    fun lookUpSuffixes(ids: Set<Long>): Map<Long, String>

    /**
     * Initialize backing storage for the dictionary's tables. Called by the
     * store before any mint/resolve operation so scalar tables exist
     * regardless of which concrete dictionary is wired.
     */
    fun setup()

    /**
     * Full-text search over string literals: returns the row IDs of string
     * literals whose stored tsvector matches `objectFilterText` (phrase query).
     * Lives on the dictionary because string literals are central; a store/
     * shard running textFilter consults this to obtain candidate object IDs and
     * then restricts its own quads table to them.
     */
    fun lookUpStringValueIdsByText(objectFilterText: String): Set<Long>
}
