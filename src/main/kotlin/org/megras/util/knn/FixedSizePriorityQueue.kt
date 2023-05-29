package org.megras.util.knn

import java.util.*

class FixedSizePriorityQueue<T>(private val maxSize: Int, comparator: Comparator<T>) : TreeSet<T>(comparator) {

    init {
        require(maxSize > 0){ "maxSize must be positive" }
    }

    private val elementsLeft: Int
        get() = maxSize - size

    override fun add(element: T): Boolean {
        return if (elementsLeft <= 0 && size == 0) {
            // max size was initiated to zero => just return false
            false
        } else if (elementsLeft > 0) {
            // queue isn't full => add element and decrement elementsLeft
            super.add(element)
        } else {
            // there is already 1 or more elements => compare to the least
            val compared = super.comparator().compare(this.last(), element)
            if (compared > 0) {
                // new element is larger than the least in queue => pull the least and add new one to queue
                pollLast()
                super.add(element)
                true
            } else {
                // new element is less than the least in queue => return false
                false
            }
        }
    }
}