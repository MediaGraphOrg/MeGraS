package org.megras.segmentation

enum class SegmentationType {

    /**
     * Rectangular area or volume, specified by (xmin, ymin), (xmax, ymax)
     * or (xmin, ymin, zmin), (xmax, ymax, zmax)
     */
    RECT,

    /**
     * 2d polygon defined by a closed series of (x, y) points
     */
    POLYGON,

    /**
     * SVG path
     */
    PATH,

    BSPLINE,

    BEZIER,

    /**
     * Binary mask for each pixel
     */
    MASK,

    HILBERT,

    CHANNEL,

    COLOR,

    TIME,

    CHARACTER,

    PAGE,

    WIDTH,

    HEIGHT,

    FREQUENCY,

    /**
     * cut along a 2D line / 3D plane as 'a,b,c,d,above' or 'expression,above'
     * - 'above' indicates which side of the line/plane to keep
     * - ax + by + cz = d
     * - expression using the variables 'x', 'y', and 'z'
     */
    CUT,

    /**
     * space-time transition (rotoscoping) of an arbitrary shape
     */
    ROTOSCOPE,

    MESH
}