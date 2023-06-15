package org.megras.data.mime

enum class MimeType(val mimeString: String) {

    //binary
    OCTET_STREAM("octet-stream"),

    //text
    CSS("text/css"),
    CSV("text/csv"),
    HTML("text/html"),
    JS("application/javascript"),
    JSON("application/json"),
    YAML("text/yaml"),
    TEXT("text/plain"),

    //images
    BMP("image/bmp"),
    GIF("image/gif"),
    JPEG_I("image/jpeg"),
    PNG("image/png"),
    SVG("image/svg+xml"),
    TIFF("image/tiff"),

    //audio
    AAC("audio/x-aac"),
    ADP("audio/adpcm"),
    AIF("audio/x-aiff"),
    AU("audio/basic"),
    MPEG_A("audio/mpeg"),
    MIDI("audio/midi"),
    MP4_A("audio/mp4"),
    OGG_A("audio/ogg"),
    WAV("audio/x-wav"),
    WAX("audio/x-ms-wax"),
    WMA("audio/x-ms-wma"),
    WEBM_A("audio/webm"),

    //video
    MOV("video/quicktime"),
    MP4("video/mp4"),
    OGG("video/ogg"),
    WEBM("video/webm"),
    AVI("video/avi"),
    MKV("video/x-matroska"),

    PDF("application/pdf"),

    OBJ("model/obj");

    companion object {
        val extensionMap = mapOf(
            "css" to CSS,
            "csv" to CSV,
            "htm" to HTML,
            "html" to HTML,
            "js" to JS,
            "json" to JSON,
            "yml" to YAML,
            "yaml" to YAML,
            "log" to TEXT,
            "text" to TEXT,
            "txt" to TEXT,

            "bmp" to BMP,
            "gif" to GIF,
            "jpe" to JPEG_I,
            "jpeg" to JPEG_I,
            "jpg" to JPEG_I,
            "png" to PNG,
            "svg" to SVG,
            "svgz" to SVG,
            "tif" to TIFF,
            "tiff" to TIFF,

            "aac" to AAC,
            "adp" to ADP,
            "aif" to AIF,
            "aifc" to AIF,
            "aiff" to AIF,
            "au" to AU,
            "snd" to AU,
            "m2a" to MPEG_A,
            "m3a" to MPEG_A,
            "mp2" to MPEG_A,
            "mp2a" to MPEG_A,
            "mp3" to MPEG_A,
            "mpga" to MPEG_A,
            "kar" to MIDI,
            "mid" to MIDI,
            "midi" to MIDI,
            "rmi" to MIDI,
            "mp4a" to MP4_A,
            "oga" to OGG_A,
            "ogg" to OGG,
            "spx" to OGG_A,
            "wav" to WAV,
            "wax" to WAX,
            "wma" to WMA,

            "mov" to MOV,
            "mp4" to MP4,
            "m4v" to MP4,
            "avi" to AVI,
            "ogv" to OGG,
            "ogx" to OGG,
            "webm" to WEBM,
            "mkv" to MKV,

            "pdf" to PDF,

            "obj" to OBJ

        )

        fun mimeType(fileExtension: String) = extensionMap[fileExtension.lowercase()] ?: OCTET_STREAM

    }





}