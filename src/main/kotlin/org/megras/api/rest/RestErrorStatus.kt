package org.megras.api.rest

class RestErrorStatus(val statusCode: Int, override val message: String): Exception() {

    companion object {
        val notFound = RestErrorStatus(404, "Not found.")
        val invalidSegmentation = RestErrorStatus(403, "Invalid segmentation.")
        val emptySegment = RestErrorStatus(404, "Empty segmentation.")
        val noAudio = RestErrorStatus(404, "No audio found in file.")
        val noVideo = RestErrorStatus(404, "No video found in file.")
        val unknownMediaType = RestErrorStatus(404, "Unknown media type.")
    }

}