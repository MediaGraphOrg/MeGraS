package org.megras.util.knn

class DistancePairComparator<T : Pair<Double, Any>> : Comparator<T> {

    override fun compare(o1: T, o2: T): Int = o1.first.compareTo(o2.first)

}