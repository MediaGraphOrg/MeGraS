package org.megras.segmentation

enum class SegmentationType {

    /**
     * Rectangular area specified by xmin,xmax,ymin,ymax
     */
    RECT,

    /**
     * 2d polygon specified as (x1,y1),(x2,y2),...,(xn,yn)
     */
    POLYGON,

    /**
     * SVG path
     */
    PATH,

    /**
     * basis spline specified as (x1,y1),(x2,y2),...,(xn,yn)
     */
    BSPLINE,

    /**
     * bézier spline specified as (x1,y1),(x2,y2),...,(xn,yn), where the points are
     * start, control1, control2, end, control1, control2, end, ...
     */
    BEZIER,

    /**
     * binary image mask as data URL (base64 encoding) of black-and-white image
     */
    MASK,

    /**
     * Hilbert space-filling curve specified as curve order followed by range intervals
     */
    HILBERT,

    /**
     * list of media channels to segment separated by comma
     */
    CHANNEL,

    /**
     * list of color channels to segment separated by comma
     */
    COLOR,

    /**
     * intervals specified as start1-end1,...,startn-endn
     */
    TIME,
    CHARACTER,
    PAGE,
    WIDTH,
    HEIGHT,

    /**
     * frequency range specified as lower-upper
     */
    FREQUENCY,

    /**
     * cut along a 2D line / 3D plane as 'a,b,c,d,above' or 'expression,above'
     * - 'above' indicates which side of the line/plane to keep
     * - ax + by + cz = d
     * - expression using the variables 'x', 'y', and 'z'
     */
    CUT,

    /**
     * space-time transition (rotoscoping) of an arbitrary shape:
     * t1,type,definition;...;tn,type,definition
     */
    ROTOSCOPE,

    /**
     * Wavefront OBJ file separated by comma
     */
    MESH
}