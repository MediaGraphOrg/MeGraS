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
 * Caching lives INSIDE this interface's leaf operations (value<->id caches
 * per scalar kind), so every caller benefits uniformly; callers do not
 * maintain their own scalar caches. getOrAdd methods compose lookUp + insert
 * (both cache-inclusive) so add paths do not throw on already-present terms.
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

    /**
     * Lookup-then-insert (getOrAdd) for each scalar kind: returns the id of
     * every requested value, minting ids only for values not already present.
     * Composes the cache-inclusive lookUp + insert leaves, so add paths do not
     * throw on already-present terms (a corpus where two quads share a scalar
     * term is the normal case). Defaults belong here, not in callers, to keep
     * the add-path uniform across the single-node forwarders and the cluster
     * substrate.
     */
    fun getOrAddDoubleValueIds(doubleValues: Set<DoubleValue>): Map<DoubleValue, QuadValueId> {
        val found = lookUpDoubleValueIds(doubleValues)
        if (found.size == doubleValues.size) return found
        return found + insertDoubleValues(doubleValues.filter { it !in found }.toSet())
    }

    fun getOrAddStringValueIds(stringValues: Set<StringValue>): Map<StringValue, QuadValueId> {
        val found = lookUpStringValueIds(stringValues)
        if (found.size == stringValues.size) return found
        return found + insertStringValues(stringValues.filter { it !in found }.toSet())
    }

    fun getOrAddPrefixValues(prefixValues: Set<String>): Map<String, Int> {
        val found = lookUpPrefixIds(prefixValues)
        if (found.size == prefixValues.size) return found
        return found + insertPrefixValues(prefixValues.filter { it !in found }.toSet())
    }

    fun getOrAddSuffixValues(suffixValues: Set<String>): Map<String, Long> {
        val found = lookUpSuffixIds(suffixValues)
        if (found.size == suffixValues.size) return found
        return found + insertSuffixValues(suffixValues.filter { it !in found }.toSet())
    }

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
