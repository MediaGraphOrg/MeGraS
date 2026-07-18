package org.megras.graphstore.db.shard

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue
import org.megras.data.graph.Quad
import org.megras.data.graph.StringValue
import org.megras.graphstore.Distance
import org.megras.graphstore.db.ClusterQuadSet
import org.megras.graphstore.db.dict.PostgresDictionary
import org.megras.data.graph.QuadValue
import java.net.InetSocketAddress
import java.net.Socket

/**
 * End-to-end expectation test for the cluster substrate against a disposable
 * live PG: add -> filter / nearestNeighbor / textFilter must produce correct
 * results through the ClusterQuadSet -> TrivialShardPolicy -> PostgresShard /
 * PostgresDictionary seam. Self-skips unless localhost:55433 is listening, so
 * `./gradlew test` stays PG-free.
 *
 * TODO(parity): a direct-vs-cluster parity test (ClusterQuadSet results equal
 * PostgresStore results) needs either two databases or a drop-schema reset
 * protocol, because both stores create the `quads` table on the same DB and
 * cannot coexist. This test asserts correct cluster-path behavior only.
 */
class ClusterPathExpectationTest {

    private fun pgAvailable(): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("localhost", 55433), 200); true }
    } catch (e: Exception) {
        false
    }

    private fun cluster(): ClusterQuadSet {
        val conn = "localhost:55433/megras"
        val dict = PostgresDictionary(conn, "megras", "megras")
        val shard = PostgresShard(conn, "megras", "megras")
        // trivial policy with one shard collapses to the single-node semantics
        return ClusterQuadSet(dict, TrivialShardPolicy(shard)).also {
            dict.setup(); shard.setup()
        }
    }

    @Test
    fun scalarAddFilterRoundTrip() {
        assumeTrue(pgAvailable(), "disposable PG not listening on localhost:55433")
        val s = cluster()
        val subj = QuadValue.of("http://ex/s1")
        val pred = QuadValue.of("http://ex/p1")
        val obj = QuadValue.of("http://ex/o1")
        s.add(Quad(null, subj, pred, obj))
        assertTrue(s.filterSubject(subj).any { it.predicate == pred && it.`object` == obj })
    }

    @Test
    fun vectorNearestNeighborRoundTrip() {
        assumeTrue(pgAvailable(), "disposable PG not listening on localhost:55433")
        val s = cluster()
        val subj = QuadValue.of("http://ex/vs1")
        val pred = QuadValue.of("http://ex/vp1")
        val vobj = QuadValue.of(floatArrayOf(1f, 0f, 0f, 0f))
        s.add(Quad(null, subj, pred, vobj))
        val query = QuadValue.of(floatArrayOf(1f, 0f, 0f, 0f)) as org.megras.data.graph.VectorValue
        assertTrue(s.nearestNeighbor(pred, query, 10, Distance.COSINE).any { it.subject == subj })
    }

    @Test
    fun textFilterRoundTrip() {
        assumeTrue(pgAvailable(), "disposable PG not listening on localhost:55433")
        val s = cluster()
        val subj = QuadValue.of("http://ex/ts1")
        val strPred = QuadValue.of("http://ex/sp1")
        s.add(Quad(null, subj, strPred, StringValue("alpha bravo charlie")))
        assertTrue(s.textFilter(strPred, "bravo").any { it.predicate == strPred })
    }
}
