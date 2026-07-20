package org.megras.graphstore.db.dict

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.megras.data.graph.Quad
import org.megras.data.graph.StringValue
import org.megras.graphstore.db.PostgresStore
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Live-Postgres smoke for the stage-1 scalar-dictionary split: exercises only
 * the delegation boundary that changed (scalar add/filter and the textFilter
 * text-resolution path that now consults [QuadValueDictionary]), not the
 * unchanged nearest-neighbour path. Self-skips unless a disposable PG instance
 * is listening on localhost:55433, so `./gradlew test` stays PG-free.
 */
class DictSplitSmokeTest {

    private fun pgAvailable(): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("localhost", 55433), 200); true }
    } catch (e: Exception) {
        false
    }

    private fun store(): PostgresStore {
        val conn = "localhost:55433/megras"
        val dict = PostgresDictionary(conn, "megras", "megras")
        return PostgresStore(dict, conn, "megras", "megras").also { it.setup() }
    }

    @Test
    fun scalarAddFilterAndTextFilterRoundTripThroughSplit() {
        assumeTrue(pgAvailable(), "disposable PG not listening on localhost:55433")

        val s = store()

        val subj = org.megras.data.graph.QuadValue.of("http://ex/s1")
        val pred = org.megras.data.graph.QuadValue.of("http://ex/p1")
        val strPred = org.megras.data.graph.QuadValue.of("http://ex/strp")
        val obj = org.megras.data.graph.QuadValue.of("http://ex/o1")

        s.add(Quad( subj, pred, obj))
        s.add(Quad( subj, strPred, StringValue("alpha bravo charlie")))

        // filterSubject path resolves scalar IDs through the dict
        val got = s.filterSubject(subj).toList()
        assert(got.any { it.predicate == pred && it.`object` == obj }) {
            "scalar filter did not return the inserted quad via dict split: $got"
        }

        // textFilter resolves the full-text candidate IDs from the dict, then
        // joins against the local quads table
        val tf = s.textFilter(strPred, "bravo").toList()
        assert(tf.any { it.predicate == strPred }) {
            "textFilter did not return the string-literal quad via dict split: $tf"
        }
    }
}
