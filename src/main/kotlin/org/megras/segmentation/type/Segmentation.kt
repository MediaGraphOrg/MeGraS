package org.megras.segmentation.type

import org.megras.segmentation.Bounds
import org.megras.segmentation.SegmentationType

enum class TranslateDirection {
    POSITIVE,
    NEGATIVE
}

enum class DirectionalRelationModel {
    CENTER,
    BOUNDING_BOX,
}

sealed interface Segmentation {
    val segmentationType: SegmentationType?
    var bounds: Bounds

    companion object {
        val DEFAULT_DIRECTIONAL_MODEL: DirectionalRelationModel = DirectionalRelationModel.BOUNDING_BOX
    }

    /**
     * Attempts to compare equivalence of this segmentation to another one.
     * In cases where the segmentations could be equivalent, but not enough information is available, `false` is returned.
     */
    fun equivalentTo(rhs: Segmentation): Boolean

    /**
     * Attempts to determine if another segmentation is contained in this one.
     * In cases where the segmentations could be containing, but not enough information is available, `false` is returned.
     */
    fun contains(rhs: Segmentation): Boolean

    /**
     * Checks if the segmentation fully contains the medium
     */
    fun contains(rhs: Bounds): Boolean

    /**
     * Attempts to determine if this segmentation intersects another one.
     * In cases where the segmentations could be intersecting, but not enough information is available, `false` is returned.
     */
    fun orthogonalTo(rhs: Segmentation): Boolean {
        return this.bounds.orthogonalTo(rhs.bounds)
    }

    fun within(rhs: Segmentation): Boolean {
        return rhs.contains(this) && (this.getDefinition() != rhs.getDefinition())
    }

    fun covers(rhs: Segmentation): Boolean {
        return rhs.contains(this)
    }

    fun beside(rhs: Segmentation): Boolean {
        return this.left(rhs) || this.right(rhs)
    }

    fun disjoint(rhs: Segmentation): Boolean{
        return !this.orthogonalTo(rhs)
    }

    fun overlaps(rhs: Segmentation): Boolean {
        return this.orthogonalTo(rhs)
    }

    fun above(rhs: Segmentation): Boolean {
        return when (DEFAULT_DIRECTIONAL_MODEL) {
            DirectionalRelationModel.CENTER -> {
                this.bounds.getCenterY() > rhs.bounds.getCenterY()
            }
            DirectionalRelationModel.BOUNDING_BOX -> {
                this.bounds.getMinY() > rhs.bounds.getMaxY()
            }
        }
    }

    fun below(rhs: Segmentation): Boolean {
        return when (DEFAULT_DIRECTIONAL_MODEL) {
            DirectionalRelationModel.CENTER -> {
                this.bounds.getCenterY() < rhs.bounds.getCenterY()
            }
            DirectionalRelationModel.BOUNDING_BOX -> {
                this.bounds.getMaxY() < rhs.bounds.getMinY()
            }
        }
    }

    fun crosses(rhs: Segmentation): Boolean {
        return this.orthogonalTo(rhs)
    }

    fun left(rhs: Segmentation): Boolean {
        return when (DEFAULT_DIRECTIONAL_MODEL) {
            DirectionalRelationModel.CENTER -> {
                this.bounds.getCenterX() < rhs.bounds.getCenterX()
            }
            DirectionalRelationModel.BOUNDING_BOX -> {
                if (this.bounds.hasZ() && rhs.bounds.hasZ()) {
                    this.bounds.getMaxX() < rhs.bounds.getMinX() &&
                    this.bounds.getMaxZ() > rhs.bounds.getMinZ() &&
                    this.bounds.getMinZ() < rhs.bounds.getMaxZ()
                } else {
                    this.bounds.getMaxX() < rhs.bounds.getMinX()
                }
            }
        }
    }

    fun right(rhs: Segmentation): Boolean {
        return when (DEFAULT_DIRECTIONAL_MODEL) {
            DirectionalRelationModel.CENTER -> {
                this.bounds.getCenterX() > rhs.bounds.getCenterX()
            }
            DirectionalRelationModel.BOUNDING_BOX -> {
                return if (this.bounds.hasZ() && rhs.bounds.hasZ()) {
                    this.bounds.getMinX() > rhs.bounds.getMaxX() &&
                    this.bounds.getMaxZ() > rhs.bounds.getMinZ() &&
                    this.bounds.getMinZ() < rhs.bounds.getMaxZ()
                } else {
                    this.bounds.getMinX() > rhs.bounds.getMaxX()
                }
            }
        }
    }

    fun touches(rhs: Segmentation): Boolean {
        // TODO: Implement touches logic in child classes
        return false
    }

    fun translate(by: Bounds, direction: TranslateDirection = TranslateDirection.POSITIVE): Segmentation {
        return this
    }

    fun getType(): String = segmentationType?.name?.lowercase() ?: ""

    fun getDefinition(): String

    fun toURI() = "segment/" + getType() + "/" + getDefinition()

    fun getArea(): Double {
        TODO("Not yet implemented")
    }
}