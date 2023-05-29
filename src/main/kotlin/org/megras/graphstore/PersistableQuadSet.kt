package org.megras.graphstore

interface PersistableQuadSet : QuadSet {

    /**
     * Triggers content of [PersistableQuadSet] to be stored persistently.
     */
    fun store()

    /**
     * Triggers loading of last persisted state.
     */
    fun load()

}