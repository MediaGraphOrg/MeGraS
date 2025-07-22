package org.megras.lang.sparql

import org.apache.jena.sparql.function.FunctionRegistry
import org.megras.graphstore.MutableQuadSet
import org.megras.lang.sparql.functions.ClipTextFunction
import org.megras.lang.sparql.functions.CosineSimFunction
import org.megras.lang.sparql.functions.DayOfWeekFunction
import org.megras.lang.sparql.functions.accessors.spatial.BoundsAreaFunction
import org.megras.lang.sparql.functions.accessors.spatial.BoundsCenterFunction
import org.megras.lang.sparql.functions.accessors.spatial.DepthFunction
import org.megras.lang.sparql.functions.accessors.spatial.DimensionFunctionBase
import org.megras.lang.sparql.functions.accessors.spatial.DurationFunction
import org.megras.lang.sparql.functions.accessors.spatial.HeightFunction
import org.megras.lang.sparql.functions.accessors.spatial.SegmentAreaFunction
import org.megras.lang.sparql.functions.accessors.spatial.SegmentCenterFunction
import org.megras.lang.sparql.functions.accessors.spatial.WidthFunction
import org.megras.lang.sparql.functions.accessors.spatial.XyztFunction
import org.megras.util.Constants

class FunctionRegistrar {

    companion object {
        fun register(quadSet: MutableQuadSet) {
            // * Custom SPARQL functions
            FunctionRegistry.get().put("${Constants.SPARQL_PREFIX}#CLIP_TEXT", ClipTextFunction::class.java)
            FunctionRegistry.get().put("${Constants.SPARQL_PREFIX}#COSINE_SIM", CosineSimFunction::class.java)
            FunctionRegistry.get().put("${Constants.SPARQL_PREFIX}#DAYOFWEEK", DayOfWeekFunction::class.java)

            // * mm functions
            FunctionRegistry.get().put("${Constants.SPARQL_PREFIX}#SEGMENT_AREA", SegmentAreaFunction::class.java)
            SegmentAreaFunction.setQuads(quadSet)
            FunctionRegistry.get().put("${Constants.SPARQL_PREFIX}#BOUNDS_AREA", BoundsAreaFunction::class.java)
            BoundsAreaFunction.setQuads(quadSet)
            FunctionRegistry.get().put("${Constants.SPARQL_PREFIX}#BOUNDS_CENTER", BoundsCenterFunction::class.java)
            BoundsCenterFunction.setQuads(quadSet)
            FunctionRegistry.get().put("${Constants.SPARQL_PREFIX}#SEGMENT_CENTER", SegmentCenterFunction::class.java)
            SegmentCenterFunction.setQuads(quadSet)
            DimensionFunctionBase.setQuads(quadSet)
            FunctionRegistry.get().put("${Constants.SPARQL_PREFIX}#WIDTH", WidthFunction::class.java)
            FunctionRegistry.get().put("${Constants.SPARQL_PREFIX}#HEIGHT", HeightFunction::class.java)
            FunctionRegistry.get().put("${Constants.SPARQL_PREFIX}#DEPTH", DepthFunction::class.java)
            FunctionRegistry.get().put("${Constants.SPARQL_PREFIX}#DURATION", DurationFunction::class.java)
            FunctionRegistry.get().put("${Constants.SPARQL_PREFIX}#XYZT", XyztFunction::class.java)
            XyztFunction.setQuads(quadSet)
        }
    }
}