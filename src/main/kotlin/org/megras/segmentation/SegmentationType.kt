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
     * slice of a 2D line / 3D plane specified as a,b,c,d,above
     * such ax + by + cz = d and 'above' indicates which side of the line/plane to keep
     */
    SLICE,

    /**
     * space-time transition (rotoscoping) of an arbitrary shape
     */
    ROTOSCOPE,

    MESH
}