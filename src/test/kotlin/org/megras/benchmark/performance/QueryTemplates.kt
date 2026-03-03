package org.megras.benchmark.performance

/**
 * Query templates for MeGraS-SYNTH performance benchmarks.
 * These templates use placeholders for parameterization.
 *
 * Based on the MeGraS-SYNTH dataset generation script:
 * - Selectivity predicates: sel001 (0.1%), sel01 (1%), sel1 (10%), sel5 (50%)
 * - Vector predicates: vec256, vec512, vec768, vec1024 (in derived namespace)
 * - Volume inflation predicates: prop_0, prop_1, ... (in synth namespace)
 * - Base embedding: clipEmbedding (in derived namespace)
 */
object QueryTemplates {

    // ============== Prefixes ==============

    const val PREFIX_IMPLICIT = "PREFIX implicit: <http://megras.org/implicit/>"
    const val PREFIX_DERIVED = "PREFIX derived: <http://megras.org/derived/>"
    const val PREFIX_SYNTH = "PREFIX synth: <http://megras.org/synth#>"
    const val PREFIX_SCHEMA = "PREFIX schema: <http://megras.org/schema#>"

    val STANDARD_PREFIXES = """
        $PREFIX_IMPLICIT
        $PREFIX_DERIVED
        $PREFIX_SYNTH
    """.trimIndent()

    // ============== Constants ==============

    /** Default vector predicate for benchmarks (512-dimensional, same as CLIP) */
    const val DEFAULT_VECTOR_PREDICATE = "vec512"

    // ============== Query Types ==============

    /**
     * Pure symbolic query - filters by a symbolic predicate only.
     * Used as baseline for cost of hybridity experiments.
     *
     * Note: Uses the selectivity markers (sel001, sel01, sel1, sel5) from the synth namespace.
     * The selectivity values are stored as "true^^String" in the TSV which MeGraS matches as "true".
     */
    fun symbolicOnly(selectivity: String = "sel01"): String = """
        $PREFIX_SYNTH
        SELECT ?s
        WHERE {
            ?s synth:$selectivity "true" .
        }
    """.trimIndent()

    /**
     * Pure vector query - performs k-NN search only.
     * Used as baseline for cost of hybridity experiments.
     *
     * Requires a subject URI to perform k-NN search on. This URI should be
     * obtained from a prior symbolic query to ensure it exists in the dataset.
     *
     * @param subjectUri The subject URI to perform k-NN search on
     * @param k Number of nearest neighbors
     * @param vectorPredicate The vector predicate to use (default: vec512)
     */
    fun vectorOnly(
        subjectUri: String,
        k: Int = 10,
        vectorPredicate: String = DEFAULT_VECTOR_PREDICATE
    ): String = """
        SELECT ?o
        WHERE {
            <$subjectUri> <http://megras.org/implicit/${k}nn/$vectorPredicate> ?o .
        }
    """.trimIndent()

    /**
     * Hybrid query - combines symbolic filter with vector search.
     * Core query pattern for cost of hybridity and pushdown experiments.
     *
     * @param selectivity The selectivity marker (e.g., "sel01" for 1%, "sel001" for 0.1%)
     * @param k Number of nearest neighbors
     * @param vectorPredicate The vector predicate to use (default: vec512)
     */
    fun hybrid(
        selectivity: String = "sel01",
        k: Int = 10,
        vectorPredicate: String = DEFAULT_VECTOR_PREDICATE
    ): String = """
        $PREFIX_SYNTH
        SELECT ?s ?o
        WHERE {
            ?s synth:$selectivity "true" .
            ?s <http://megras.org/implicit/${k}nn/$vectorPredicate> ?o .
        }
    """.trimIndent()

    /**
     * N+1 Problem Demonstration Query
     *
     * This query is specifically designed to highlight the N+1 database call problem.
     *
     * Without batching optimization (DEFAULT engine):
     * - First, all subjects matching the selectivity filter are retrieved
     * - Then, FOR EACH subject, a separate k-NN query is executed
     * - This results in 1 + N database calls (where N = number of matching subjects)
     *
     * With batching optimization (BATCHING engine):
     * - Subjects are retrieved in batches
     * - k-NN queries are batched together
     * - This significantly reduces the number of database round-trips
     *
     * The query also retrieves an additional property to make the pattern more realistic
     * and to ensure we're measuring actual data retrieval, not just query planning.
     *
     * @param selectivity The selectivity marker - higher selectivity = more N+1 calls
     * @param k Number of nearest neighbors per subject
     * @param vectorPredicate The vector predicate to use
     */
    fun nPlusOneDemo(
        selectivity: String = "sel01",
        k: Int = 10,
        vectorPredicate: String = DEFAULT_VECTOR_PREDICATE
    ): String = """
        $PREFIX_SYNTH
        SELECT ?s ?neighbor ?prop
        WHERE {
            ?s synth:$selectivity "true" .
            ?s <http://megras.org/implicit/${k}nn/$vectorPredicate> ?neighbor .
            ?s synth:prop_0 ?prop .
        }
    """.trimIndent()

    /**
     * Multi-hop N+1 Query
     *
     * An even more extreme N+1 scenario where we traverse from subjects to their
     * neighbors and then get properties of those neighbors.
     *
     * This creates a multiplicative effect:
     * - N subjects pass the filter
     * - Each subject has k neighbors
     * - Each neighbor's property is fetched
     * - Result: potentially N * k + N database calls without batching
     *
     * @param selectivity The selectivity marker
     * @param k Number of nearest neighbors
     * @param vectorPredicate The vector predicate to use
     */
    fun multiHopNPlusOne(
        selectivity: String = "sel01",
        k: Int = 10,
        vectorPredicate: String = DEFAULT_VECTOR_PREDICATE
    ): String = """
        $PREFIX_SYNTH
        SELECT ?s ?neighbor ?neighborProp
        WHERE {
            ?s synth:$selectivity "true" .
            ?s <http://megras.org/implicit/${k}nn/$vectorPredicate> ?neighbor .
            ?neighbor synth:prop_0 ?neighborProp .
        }
    """.trimIndent()

    /**
     * Scalability test query - combines vector search with property access.
     * Used for graph volume scalability experiments.
     */
    fun scalabilityQuery(k: Int = 10, vectorPredicate: String = DEFAULT_VECTOR_PREDICATE): String = """
        $PREFIX_SYNTH
        SELECT ?s ?o ?val
        WHERE {
            ?s <http://megras.org/implicit/${k}nn/$vectorPredicate> ?o .
            ?s synth:prop_0 ?val .
        }
    """.trimIndent()

    /**
     * Vector dimensionality test query - targets specific vector predicates.
     * Used for vector dimensionality scalability experiments.
     *
     * The dataset contains vectors at dimensions: 256, 512, 768, 1024
     * Predicates are: <http://megras.org/derived/vec256>, etc.
     *
     * @param k Number of nearest neighbors
     * @param vectorPredicate The dimension-specific vector predicate (e.g., vec256, vec512, vec768, vec1024)
     * @param subjectUri Optional specific subject URI to query from
     */
    fun dimensionalityQuery(
        k: Int = 10,
        vectorPredicate: String,
        subjectUri: String? = null
    ): String = if (subjectUri != null) {
        """
        SELECT ?o
        WHERE {
            <$subjectUri> <http://megras.org/implicit/${k}nn/$vectorPredicate> ?o .
        }
        """.trimIndent()
    } else {
        """
        SELECT ?s ?o
        WHERE {
            ?s <http://megras.org/implicit/${k}nn/$vectorPredicate> ?o .
        }
        """.trimIndent()
    }

    // ============== Selectivity Markers ==============

    /**
     * Available selectivity markers in MeGraS-SYNTH dataset.
     *
     * Based on the generation script:
     * - sel001: first 1 subject (0.1% of 1000)
     * - sel01: first 10 subjects (1% of 1000)
     * - sel1: first 100 subjects (10% of 1000)
     * - sel5: first 500 subjects (50% of 1000)
     *
     * Predicate format: <http://megras.org/synth#selXXX>
     * Value: "true^^String"
     */
    object Selectivity {
        const val SEL_0_1_PERCENT = "sel001"   // 0.1% selectivity (1 subject)
        const val SEL_1_PERCENT = "sel01"      // 1% selectivity (10 subjects)
        const val SEL_10_PERCENT = "sel1"      // 10% selectivity (100 subjects)
        const val SEL_50_PERCENT = "sel5"      // 50% selectivity (500 subjects)

        val ALL = listOf(SEL_0_1_PERCENT, SEL_1_PERCENT, SEL_10_PERCENT, SEL_50_PERCENT)
        val ALL_WITH_VALUES = mapOf(
            SEL_0_1_PERCENT to 0.001,
            SEL_1_PERCENT to 0.01,
            SEL_10_PERCENT to 0.1,
            SEL_50_PERCENT to 0.5
        )
    }

    // ============== Vector Predicates ==============

    /**
     * Vector predicates for different dimensionalities.
     *
     * Based on the generation script:
     * - vec256: first 256 components of clipEmbedding
     * - vec512: same as clipEmbedding (512d)
     * - vec768: clipEmbedding + 256 random noise
     * - vec1024: clipEmbedding + 512 random noise
     *
     * Predicate format: <http://megras.org/derived/vecXXX>
     */
    object VectorPredicates {
        const val CLIP_EMBEDDING = "clipEmbedding"  // Standard CLIP (512d) - in base dataset
        const val VEC_256 = "vec256"                // 256-dimensional
        const val VEC_512 = "vec512"                // 512-dimensional (same as CLIP)
        const val VEC_768 = "vec768"                // 768-dimensional
        const val VEC_1024 = "vec1024"              // 1024-dimensional

        val DIMENSIONALITY_TEST = listOf(VEC_256, VEC_512, VEC_768, VEC_1024)
        val DIMENSIONALITY_VALUES = mapOf(
            VEC_256 to 256,
            VEC_512 to 512,
            VEC_768 to 768,
            VEC_1024 to 1024
        )
    }

    // ============== Dataset Files ==============

    /**
     * Dataset file paths for different configurations.
     *
     * Files generated by the MeGraS-SYNTH dataset creation script:
     * - BASE: Original dataset with all data including clipEmbeddings
     * - BASE_NO_EMBEDDINGS: Metadata only (no clipEmbeddings)
     * - EMBEDDINGS_ONLY: Just the clipEmbedding triples
     * - INFLATED_*: Full inflated variants with synthetic vectors and selectivity markers
     */
    object DatasetFiles {
        const val BASE = "MeGraS-SYNTH-base.tsv"
        const val BASE_NO_EMBEDDINGS = "MeGraS-SYNTH-base-no-embeddings.tsv"
        const val EMBEDDINGS_ONLY = "MeGraS-SYNTH-embeddings.tsv"

        const val INFLATED_100K = "MeGraS-SYNTH-inflated-100k.tsv"
        const val INFLATED_1M = "MeGraS-SYNTH-inflated-1M.tsv"
        const val INFLATED_10M = "MeGraS-SYNTH-inflated-10M.tsv"

        val VOLUME_VARIANTS = mapOf(
            "100k" to INFLATED_100K,
            "1M" to INFLATED_1M,
            "10M" to INFLATED_10M
        )
    }
}

