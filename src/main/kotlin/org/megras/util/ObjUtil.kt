package org.megras.util

import com.ezylang.evalex.Expression
import de.javagl.obj.*
import org.megras.api.rest.RestErrorStatus
import org.megras.segmentation.Bounds
import org.megras.segmentation.type.CutSegmentation
import org.megras.segmentation.type.TranslateDirection
import java.awt.geom.Path2D
import java.util.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class Vertex(val x: Float, val y: Float) {
    fun almostEqual(other: Vertex): Boolean {
        val eps = 0.001
        return abs(this.x - other.x) < eps && abs(this.y - other.y) < eps
    }
}

object ObjUtil {

    /**
     * Finds the X,Y,Z bounds of the mesh
     */
    fun computeBounds(obj: Obj): Bounds {
        val b = floatArrayOf(
            Float.MAX_VALUE, Float.MIN_VALUE,
            Float.MAX_VALUE, Float.MIN_VALUE,
            Float.MAX_VALUE, Float.MIN_VALUE
        )

        // Compute bounds from vertices that are part of faces
        for (f in 0 until obj.numFaces) {
            val face = obj.getFace(f)
            for (v in 0 until face.numVertices) {
                val vertex = obj.getVertex(face.getVertexIndex(v))

                b[0] = min(vertex.x, b[0])
                b[1] = max(vertex.x, b[1])
                b[2] = min(vertex.y, b[2])
                b[3] = max(vertex.y, b[3])
                b[4] = min(vertex.z, b[4])
                b[5] = max(vertex.z, b[5])
            }
        }

        return Bounds()
            .addX(b[0].toDouble(), b[1].toDouble())
            .addY(b[2].toDouble(), b[3].toDouble())
            .addZ(b[4].toDouble(), b[5].toDouble())
    }

    /**
     * Translate a mesh by x,y,z in positive or negative direction
     */
    fun translate(obj: Obj, x: Float, y: Float, z: Float, direction: TranslateDirection): Obj {
        val newObj = Objs.create()
        for (i in 0 until obj.numVertices) {
            val vertex = obj.getVertex(i)
            when (direction) {
                TranslateDirection.POSITIVE -> newObj.addVertex(vertex.x + x, vertex.y + y, vertex.z + z)
                TranslateDirection.NEGATIVE -> newObj.addVertex(vertex.x - x, vertex.y - y, vertex.z - z)
            }
        }
        for (j in 0 until obj.numFaces) {
            val face = obj.getFace(j)
            newObj.addFace(face)
        }

        return newObj
    }

    /**
     * Scale a mesh by x,y,z factors
     */
    fun scale(obj: Obj, x: Float, y: Float, z: Float): Obj {
        val newObj = Objs.create()
        for (i in 0 until obj.numVertices) {
            val vertex = obj.getVertex(i)
            newObj.addVertex(vertex.x * x, vertex.y * y, vertex.z * z)
        }
        for (j in 0 until obj.numFaces) {
            val face = obj.getFace(j)
            newObj.addFace(face)
        }

        return newObj
    }

    /**
     * Slice a mesh in the Z dimension
     * Returns an XY path of the outline of the slice
     */
    fun slice(obj: Obj, z: Float): Path2D.Float? {
        val lines: MutableList<Pair<Vertex, Vertex>> = LinkedList()

        // iterate all faces and all vertices
        for (f in 0 until obj.numFaces) {
            val face = obj.getFace(f)

            val intersections: MutableSet<Vertex> = mutableSetOf()
            for (v in 0 until face.numVertices) {
                val v1 = obj.getVertex(face.getVertexIndex(v))
                val v2 = obj.getVertex(face.getVertexIndex((v + 1) % face.numVertices))
                if (v1.z < z && v2.z < z || v1.z > z && v2.z > z) {
                    continue  // edge does not intersect z-coordinate
                }

                // compute (interpolate) intersection point and add to list
                val t = (z - v1.z) / (v2.z - v1.z)
                if (v1.z == z) {
                    intersections.add(Vertex(v1.x, v1.y))
                } else if (v2.z == z) {
                    intersections.add(Vertex(v2.x, v2.y))
                } else if (v1.z < z && v2.z > z) {
                    intersections.add(interpolate(v1, v2, t))
                } else if (v1.z > z && v2.z < z) {
                    intersections.add(interpolate(v2, v1, 1 - t))
                }
            }
            // fully intersected faces have two intersection points
            // (special case: only one corner of the face is an intersection)
            if (intersections.size == 2 || intersections.size == 1) {
                lines.add(Pair(intersections.first(), intersections.last()))
            }
        }

        if (lines.size == 0) {
            return null
        }

        // build path
        val path = Path2D.Float()

        // start with an arbitrary point
        var current = lines.first().first
        path.moveTo(current.x, current.y)

        while (lines.size > 0) {
            // test if the current point is connected to an unvisited point by a line
            var hasNext = false
            for (line in lines) {
                if (current.almostEqual(line.first)) {
                    current = line.second
                    hasNext = true
                } else if (current.almostEqual(line.second)) {
                    current = line.first
                    hasNext = true
                }
                // if yes, add it to the polygon and start next round
                if (hasNext) {
                    path.lineTo(current.x, current.y)
                    lines.remove(line)
                    break
                }
            }
            // if no, consider that polygon completed and start a new one
            if (!hasNext) {
                current = lines.first().first
                path.moveTo(current.x, current.y)
            }
        }

        return path
    }

    fun segmentCut(obj: Obj, s: CutSegmentation): Obj {
        return this.segmentCut(obj, s.expression, s.above)
    }

    /**
     * Cut a mesh by an equation expression and retain one of the two cut sides
     */
    fun segmentCut(obj: Obj, expression: Expression, above: Boolean): Obj {
        val segmentedObj = Objs.create()
        (0 until obj.numVertices).forEach { v -> segmentedObj.addVertex(obj.getVertex(v)) }

        val coverVertices = mutableListOf<Int>() // vertices along the cut plane
        for (f in 0 until obj.numFaces) {
            val face = obj.getFace(f)

            val faceVertices = mutableListOf<Int>()
            val keepVertices = mutableListOf<Int>()
            for (v in 0 until face.numVertices) {
                faceVertices.add(face.getVertexIndex(v))
                val v1 = obj.getVertex(face.getVertexIndex(v))
                val v2 = obj.getVertex(face.getVertexIndex((v + 1) % face.numVertices))

                val d1 = expression.with("x", v1.x).with("y", v1.y).with("z", v1.z).evaluate().numberValue.toFloat()
                val d2 = expression.with("x", v2.x).with("y", v2.y).with("z", v2.z).evaluate().numberValue.toFloat()

                if (d1 >= 0 == above) {
                    keepVertices.add(face.getVertexIndex(v))
                }
                if (d1 == 0F) {
                    coverVertices.add(face.getVertexIndex(v))
                }

                if (d1 * d2 <= 0) {
                    val t = -d1 / (d2 - d1)
                    val x = v1.x + t * (v2.x - v1.x)
                    val y = v1.y + t * (v2.y - v1.y)
                    val z = v1.z + t * (v2.z - v1.z)
                    segmentedObj.addVertex(x, y, z)
                    keepVertices.add(segmentedObj.numVertices - 1)
                    coverVertices.add(segmentedObj.numVertices - 1)
                }
            }

            if (keepVertices.isNotEmpty()) {
                if (faceVertices == keepVertices) {
                    segmentedObj.addFace(face)
                } else {
                    segmentedObj.addFace(*keepVertices.toIntArray())
                }
            }
        }
        // cover cut with another face
        // TODO: cover case when cut is not planar
        segmentedObj.addFace(*coverVertices.toIntArray())
        return ObjUtils.convertToRenderable(segmentedObj)
    }

    fun generateCuboid(minX: Float, maxX: Float, minY: Float, maxY: Float, minZ: Float, maxZ: Float): Obj {
        val obj = Objs.create()

        obj.addVertex(minX, minY, minZ)
        obj.addVertex(maxX, minY, minZ)
        obj.addVertex(maxX, maxY, minZ)
        obj.addVertex(minX, maxY, minZ)
        obj.addVertex(minX, minY, maxZ)
        obj.addVertex(maxX, minY, maxZ)
        obj.addVertex(maxX, maxY, maxZ)
        obj.addVertex(minX, maxY, maxZ)

        obj.addFace(0, 1, 2, 3)
        obj.addFace(4, 5, 6, 7)
        obj.addFace(0, 1, 5, 4)
        obj.addFace(1, 2, 6, 5)
        obj.addFace(2, 3, 7, 6)
        obj.addFace(3, 0, 4, 7)

        return obj
    }

    /**
     * Triangulate a mesh and sort it by ascending vertices and faces
     */
    fun sortMesh(obj: Obj): Obj {
        val obj = ObjUtils.triangulate(obj)

        // Collect vertices
        val vertices = mutableListOf<FloatTuple>()
        for (v in 0 until obj.numVertices) {
            val vertex = obj.getVertex(v)
            vertices.add(vertex)
        }

        // Sort vertices ascending and keep track of their old and new index
        val sorter = ObjVertexSorter(vertices)
        val oldToNewIndex = sorter.getMapping()

        // Collect faces with the updated vertex indices and sort them by index sum
        val faces = mutableListOf<IntArray>()
        for (f in 0 until obj.numFaces) {
            val face = obj.getFace(f)
            val newFaceIndices = mutableListOf<Int>()
            for (v in 0 until face.numVertices) {
                val newFaceIndex = oldToNewIndex[face.getVertexIndex(v)] ?: throw RestErrorStatus.invalidSegmentation
                newFaceIndices.add(newFaceIndex)
            }
            faces.add(newFaceIndices.toIntArray())
        }
        val sortedFaces = faces.sortedWith(compareBy({ it[0] }, { it[1] }, { it[2] }))

        // Add the next vertices and faces to a new obj object
        val sortedObj = Objs.create()
        sorter.sortedIndices.forEach { i -> sortedObj.addVertex(vertices[i]) }
        sortedFaces.forEach { face ->
            val newFace = ObjFaces.create(face, null, null)
            sortedObj.addFace(newFace)
        }

        return sortedObj
    }

    fun contains(obj: Obj, minX: Float, maxX: Float, minY: Float, maxY: Float, minZ: Float, maxZ: Float): Boolean {
        val res = BooleanArray(6) { false }

        for (v in 0 until obj.numVertices) {
            val vertex = obj.getVertex(v)
            if (!res[0] && vertex.x <= minX) res[0] = true
            if (!res[1] && vertex.x >= maxX) res[1] = true
            if (!res[2] && vertex.y <= minY) res[2] = true
            if (!res[3] && vertex.y <= maxY) res[3] = true
            if (!res[4] && vertex.z <= minZ) res[4] = true
            if (!res[5] && vertex.z <= maxZ) res[5] = true
        }
        // TODO: also check faces, not just vertices
        return res.all { it }
    }

    private class ObjVertexSorter(private val array: List<FloatTuple>) : Comparator<Int> {
        val sortedIndices = array.indices.sortedWith(this).toMutableList()

        fun getMapping(): HashMap<Int, Int> {
            val indexMap = hashMapOf<Int, Int>()
            sortedIndices.forEachIndexed { index, i -> indexMap[i] = index }
            return indexMap
        }

        override fun compare(index1: Int, index2: Int): Int {
            return compareValuesBy(array[index1], array[index2], { it.x }, { it.y }, { it.z })
        }
    }

    private fun interpolate(v1: FloatTuple, v2: FloatTuple, t: Float): Vertex {
        val x = v1.x + (v2.x - v1.x) * t
        val y = v1.y + (v2.y - v1.y) * t
        return Vertex(x, y)
    }
}