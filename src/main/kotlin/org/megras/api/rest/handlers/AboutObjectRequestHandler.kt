package org.megras.api.rest.handlers

import io.javalin.http.ContentType
import io.javalin.http.Context
import io.javalin.openapi.*
import org.megras.api.rest.GetRequestHandler
import org.megras.api.rest.RestErrorStatus
import org.megras.data.fs.FileSystemObjectStore
import org.megras.data.graph.LocalQuadValue
import org.megras.data.graph.Quad
import org.megras.data.graph.QuadValue
import org.megras.data.graph.StringValue
import org.megras.data.graph.URIValue
import org.megras.data.graph.VectorValue
import org.megras.data.model.MediaType
import org.megras.data.schema.MeGraS
import org.megras.graphstore.BasicMutableQuadSet
import org.megras.graphstore.QuadSet
import org.megras.id.ObjectId
import org.megras.segmentation.Bounds
import org.megras.segmentation.SegmentationType
import org.megras.segmentation.SegmentationUtil
import org.megras.segmentation.type.Segmentation

class AboutObjectRequestHandler(private val quads: QuadSet, private val objectStore: FileSystemObjectStore) : GetRequestHandler {

    @OpenApi(
        path = "/{objectId}/about",
        methods = [HttpMethod.GET],
        summary = "Provides information and a preview about a specific object and its related data.",
        tags = ["Object Information"],
        pathParams = [
            OpenApiParam(name = "objectId", type = String::class, description = "The ID of the object to retrieve information about."
            )
        ],
        responses = [
            OpenApiResponse(status = "200", description = "Successfully serves an HTML page with details about the object.", content = [OpenApiContent(type = "text/html")]),
            OpenApiResponse(status = "404", description = "Object not found.")
        ]
    )
    override fun get(ctx: Context) {

        val objectId = ObjectId(ctx.pathParam("objectId"))

        val relevant = quads.filter(setOf(objectId), null,null) + quads.filter(null, null, setOf(objectId))

        if (relevant.isEmpty()) {
            throw RestErrorStatus.notFound
        }

        val buf = StringBuilder()

        buf.append(
          """
              <!DOCTYPE html>
              <head>
                  <title>About '$objectId'</title>
                  <link rel="stylesheet" type="text/css" href="/static/styles.css">
              </head>
              <body>
              
          """.trimIndent()
        )

        val extended = BasicMutableQuadSet()

        var parent = quads.filter(setOf(objectId), setOf(MeGraS.SEGMENT_OF.uri), null).firstOrNull()?.`object`
        while (parent != null) {
            extended.addAll(quads.filter(setOf(parent), null, null))
            parent = quads.filter(setOf(parent), setOf(MeGraS.SEGMENT_OF.uri), null).firstOrNull()?.`object`
        }

        fun getSegmentation(subject: URIValue) : Segmentation? {
            val typeDefinition = quads.filter(setOf(subject), setOf(MeGraS.SEGMENT_TYPE.uri, MeGraS.SEGMENT_DEFINITION.uri), null)
            val type = typeDefinition.filter(setOf(subject), setOf(MeGraS.SEGMENT_TYPE.uri), null)
                .firstOrNull()?.`object`.toString().removeSuffix("^^String")
            val definition = typeDefinition.filter(setOf(subject), setOf(MeGraS.SEGMENT_DEFINITION.uri), null)
                .firstOrNull()?.`object`.toString().removeSuffix("^^String")
            return SegmentationUtil.parseSegmentation(type, definition)
        }

        val children = quads.filter(null, setOf(MeGraS.SEGMENT_OF.uri), setOf(objectId)).map { it.subject as URIValue }.toSet()
        val imgBounds = Bounds(
            quads.filter(setOf(objectId), setOf(MeGraS.BOUNDS.uri), null)
                .firstOrNull()?.`object`.toString().removeSuffix("^^String")
        )

        val mediaType = (relevant.filterPredicate(MeGraS.MEDIA_TYPE.uri).firstOrNull()?.`object` ?: extended.filterPredicate(MeGraS.MEDIA_TYPE.uri).firstOrNull()?.`object`) as? StringValue
        val mimeType = (relevant.filterPredicate(MeGraS.CANONICAL_MIME_TYPE.uri).firstOrNull()?.`object` ?: extended.filterPredicate(MeGraS.CANONICAL_MIME_TYPE.uri).firstOrNull()?.`object`) as? StringValue

        when(mediaType?.value) {
            MediaType.IMAGE.name -> {
                var svg = ""
                if (children.isNotEmpty()) {
                    val colorPalette = listOf(
                        "#e6194b", "#3cb44b", "#ffe119", "#4363d8", "#f58231", "#911eb4", "#46f0f0", "#f032e6",
                        "#bcf60c", "#fabebe", "#008080", "#e6beff", "#9a6324", "#fffac8", "#800000", "#aaffc3"
                    )
                    svg = "<svg width='100%' height='100%' style='position: absolute; top: 0; left: 0;' xmlns:xlink='http://www.w3.org/1999/xlink'>\n"

                    // Sort children by area and store segmentation
                    val sortedChildrenWithSegmentation = children.mapNotNull { child ->
                        val segmentation = getSegmentation(child)
                        segmentation?.bounds?.let { bounds ->
                            val area = (bounds.getMaxX() - bounds.getMinX()) * (bounds.getMaxY() - bounds.getMinY())
                            Triple(child, segmentation, area)
                        }
                    }.sortedBy { -it.third } // Sort by area (third element of Triple)

                    sortedChildrenWithSegmentation.forEachIndexed { idx, (child, segmentation, _) ->
                        val color = colorPalette[idx % colorPalette.size]
                        val aboutUrl = "${child.value}/about"

                        when (segmentation.segmentationType) {
                            SegmentationType.RECT -> {
                                val svgPath = segmentation.getDefinition()
                                val coords = svgPath.split(",").map { it.toDouble() }
                                val x = coords[0]
                                val y = imgBounds.getMaxY() - coords[3]
                                val width = coords[1] - coords[0]
                                val height = coords[3] - coords[2]
                                svg += """
                                    <a xlink:href='$aboutUrl' target='_blank'>
                                        <rect x='$x' y='$y' width='$width' height='$height' style='fill:$color; stroke:black; stroke-width:2; fill-opacity:0.25; stroke-opacity:0.8; cursor:pointer;' />
                                    </a>
                                """.trimIndent()
                            }
                            SegmentationType.POLYGON -> {
                                val svgPath = segmentation.getDefinition()
                                val points = svgPath.split("),(")
                                    .map { it.replace("(", "").replace(")", "") }
                                    .map { point ->
                                        val (x, y) = point.split(",").map(String::toDouble)
                                        "$x,${imgBounds.getMaxY() - y}"
                                    }
                                svg += """
                                    <a xlink:href='$aboutUrl' target='_blank'>
                                        <polygon points='${points.joinToString(" ")}' style='fill:$color; stroke:black; stroke-width:2; fill-opacity:0.25; stroke-opacity:0.8; cursor:pointer;' />
                                    </a>
                                """.trimIndent()
                            }
                            SegmentationType.PATH -> {
                                val svgPath = segmentation.getDefinition()
                                val adjustedPath = svgPath.replace(Regex("""([MLQZ])(\d+\.?\d*),(\d+\.?\d*)""")) {
                                    val command = it.groupValues[1]
                                    val x = it.groupValues[2].toDouble()
                                    val y = it.groupValues[3].toDouble()
                                    "$command$x,${imgBounds.getMaxY() - y}"
                                }
                                svg += """
                                    <a xlink:href='$aboutUrl' target='_blank'>
                                        <path d='$adjustedPath' style='fill:$color; stroke:black; stroke-width:2; fill-opacity:0.25; stroke-opacity:0.8; cursor:pointer;' />
                                    </a>
                                """.trimIndent()
                            }
                            else -> {
                                // FIXME Unsupported segmentation type, skip this child
                                return@forEachIndexed
                            }
                        }
                    }
                    svg += "</svg>"
                }
                buf.append("""
                    <div class='media-container'>
                        <div style="position: relative; display: inline-block;">
                            <img src='${objectId.toPath()}' alt='Image preview' style='display: block; margin: 0 auto; max-width: 100%; max-height: 600px; border-radius: 4px;'/>
                            <div id="segments-overlay" style="position: absolute; top: 0; left: 0; width: 100%; height: 100%; pointer-events: none;">
                                <div style="width: 100%; height: 100%; position: absolute; top: 0; left: 0; pointer-events: auto;">
                                    ${svg}
                                </div>
                            </div>
                        </div>
                        <button class="segments-toggle-btn" onclick="toggleSegments()">Toggle Segments</button>
                    </div>
                    <script>
                        function toggleSegments() {
                            var overlay = document.getElementById('segments-overlay');
                            if (overlay.style.display === 'none') {
                                overlay.style.display = '';
                            } else {
                                overlay.style.display = 'none';
                            }
                        }
                    </script>
                """.trimIndent())
            }

            MediaType.VIDEO.name -> {
                buf.append(
                    """
                    <div class='media-container'>
                        <video controls>
                          <source src='${objectId.toPath()}' type='${mimeType?.value}'>
                          Your browser does not support the video tag.
                        </video>
                    </div>
                """.trimIndent()
                )
            }

            MediaType.AUDIO.name -> {
                buf.append(
                    """
                    <div class='media-container'>
                        <audio controls>
                          <source src='${objectId.toPath()}' type='${mimeType?.value}'>
                          Your browser does not support the audio tag.
                        </audio>
                    </div>
                """.trimIndent()
                )
            }

            MediaType.TEXT.name, MediaType.DOCUMENT.name -> {
                buf.append("<div class='media-container'><embed src='${objectId.toPath()}'></div>")
            }

            else -> {/* no preview */
            }
        }

        // Helper to escape a string for HTML display
        fun esc(s: String): String = s
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

        // Extract a predicate namespace (up to last '#' or '/') to group by; return null if not a URI
        fun predicateNamespace(p: QuadValue): String? = when (p) {
            is URIValue -> {
                val s = p.value
                val idxHash = s.lastIndexOf('#')
                val idxSlash = s.lastIndexOf('/')
                val idx = if (idxHash > idxSlash) idxHash else idxSlash
                if (idx >= 0) s.substring(0, idx + 1) else s
            }
            else -> null
        }

        // Helper to render a single quad row, optionally as part of a collapsible group
        fun appendRow(q: Quad, groupClass: String? = null, hidden: Boolean = false, isLastInGroup: Boolean = false) {
            if (groupClass != null) {
                val extraClass = if (isLastInGroup) " group-last-row" else ""
                buf.append("<tr class='" + groupClass + " group-content" + extraClass + "'" + (if (hidden) " style='display:none;'" else "") + ">")
            } else {
                buf.append("<tr>")
            }
            buf.append("<td>" + q.subject.toHtml() + "</td>")
            buf.append("<td>" + q.predicate.toPredHtml() + "</td>")
            buf.append("<td>" + q.`object`.toHtml() + "</td>")
            buf.append("</tr>\n")
        }

        // Sort a list of quads alphabetically by predicate for consistent rendering
        fun rowsSortedByPredicate(rows: List<Quad>): List<Quad> =
            rows.sortedBy { it.predicate.toString().lowercase() }

        // construct a list of all the relevant triples in a html table with collapsible predicate groups
        buf.append("<br><h2>Node Neighborhood</h2>")
        buf.append("\n<table>\n")
        buf.append("<tr><th>Subject</th><th>Predicate</th><th>Object</th></tr>\n")

        val relevantList = relevant // grouping + per-group sort handles order
        // Special-case: collect all segmentOf predicate rows into their own group ("Segments")
        val segmentOfUri = "http://megras.org/schema#segmentOf"
        val segmentOfGroupRows = relevantList.filter { it.predicate is URIValue && it.predicate.value == segmentOfUri }
        val baseList = relevantList.filterNot { it.predicate is URIValue && it.predicate.value == segmentOfUri }
        val groupsByNs = baseList.groupBy { predicateNamespace(it.predicate) }

        // Determine which namespaces would be grouped (size >= 3)
        val groupedCandidates = groupsByNs.filter { it.key != null && it.value.size >= 3 }
        // Remaining rows that would not be grouped
        val ungroupedRowsNode = groupsByNs.flatMap { (ns, rows) ->
            val shouldGroup = ns != null && rows.size >= 3
            if (!shouldGroup) rows else emptyList()
        }

        // Do not group if there is only one group and no other triples
        val potentialGroupCount = groupedCandidates.size + if (segmentOfGroupRows.isNotEmpty()) 1 else 0
        val doGroupNode = potentialGroupCount > 1 || ungroupedRowsNode.isNotEmpty()

        if (doGroupNode) {
            var groupCounter = 0

            // Render the special Segments group first if present
            if (segmentOfGroupRows.isNotEmpty()) {
                val gid = "nodepred-group-" + (++groupCounter)
                buf.append("<tr class='group-header' style='background:#f6f6f6; cursor:pointer;' onclick=\"toggleGroup('" + gid + "')\"><td colspan='3'><span id='tw-" + gid + "' class='tw-arrow'>&gt;</span> Segments (" + segmentOfGroupRows.size + ")</td></tr>\n")
                val sorted = rowsSortedByPredicate(segmentOfGroupRows)
                sorted.forEachIndexed { idx, q ->
                    appendRow(q, gid, hidden = true, isLastInGroup = idx == sorted.lastIndex)
                }
            }

            // Render predicate-namespace groups (3+ items), with MeGraS namespace first
            val preferredNs = "http://megras.org/schema#"
            groupedCandidates
                .toList()
                .sortedWith(compareBy<Pair<String?, List<Quad>>> { it.first != preferredNs }.thenBy { it.first!! })
                .forEach { (ns, rows) ->
                    val nsDisplay = esc(ns!!.replace(LocalQuadValue.defaultPrefix, "/"))
                    val gid = "nodepred-group-" + (++groupCounter)
                    buf.append("<tr class='group-header' style='background:#f6f6f6; cursor:pointer;' onclick=\"toggleGroup('" + gid + "')\"><td colspan='3'><span id='tw-" + gid + "' class='tw-arrow'>&gt;</span> " + nsDisplay + " (" + rows.size + ")</td></tr>\n")
                    val sorted = rowsSortedByPredicate(rows)
                    sorted.forEachIndexed { idx, q ->
                        appendRow(q, gid, hidden = true, isLastInGroup = idx == sorted.lastIndex)
                    }
                }

            // Then, render all remaining rows (no namespace or small groups) as normal rows, globally sorted by predicate
            rowsSortedByPredicate(ungroupedRowsNode).forEach { q -> appendRow(q) }
        } else {
            // Render all rows flat if only one would-be group and nothing else
            rowsSortedByPredicate(relevantList.toList()).forEach { q -> appendRow(q) }
        }
        buf.append("</table>\n")

        if (extended.isNotEmpty()) {
            buf.append("<br><h2>Ancestor Neighborhood</h2>")
            buf.append("\n<table>\n")
            buf.append("<tr><th>Subject</th><th>Predicate</th><th>Object</th></tr>\n")

            val extendedList = extended // grouping + per-group sort handles order
            val groupsBySubject = extendedList.groupBy { it.subject }

            // Determine which subjects would be grouped (size >= 3)
            val groupedSubjects = groupsBySubject.filter { it.value.size >= 3 }
            val singletonRows = groupsBySubject.filter { it.value.size < 3 }.flatMap { it.value }

            // Do not group if there is only one group and no other triples
            val doGroupAncestor = groupedSubjects.size > 1 || singletonRows.isNotEmpty()

            if (doGroupAncestor) {
                var ancCounter = 0
                // Render subject groups with 3+ rows as collapsible, sorted by subject
                groupedSubjects
                    .toList()
                    .sortedBy { it.first.toString() }
                    .forEach { (subj, rows) ->
                        val gid = "ancestor-subj-group-" + (++ancCounter)
                        buf.append("<tr class='group-header' style='background:#f6f6f6; cursor:pointer;' onclick=\"toggleGroup('" + gid + "')\"><td colspan='3'><span id='tw-" + gid + "' class='tw-arrow'>&gt;</span> " + subj.toHtml() + " (" + rows.size + ")</td></tr>\n")
                        val sorted = rowsSortedByPredicate(rows)
                        sorted.forEachIndexed { idx, q ->
                            appendRow(q, gid, hidden = true, isLastInGroup = idx == sorted.lastIndex)
                        }
                    }

                // Render singleton subjects as normal rows, globally sorted by predicate
                rowsSortedByPredicate(singletonRows).forEach { q -> appendRow(q) }
            } else {
                // Only one would-be group and nothing else: render all rows flat
                rowsSortedByPredicate(extendedList.toList()).forEach { q -> appendRow(q) }
            }
            buf.append("</table>\n")
        }


        /*buf.append("\n<br><textarea readonly style='width: 100%; min-height: 500px; resize: vertical;'>\n")
        relevant.sortedBy { it.subject.toString().length }.forEach {
            buf.append("${it.subject} ${it.predicate} ${it.`object`}\n")
        }
        buf.append("</textarea>")*/

        buf.append(
            """
            <style>
                /* Visual indicator for the last row in a group */
                .group-last-row td { border-bottom: 2px solid #dcdcdc; }
                .tw-arrow { display: inline-block; width: 1ch; text-align: center; margin-right: 6px; }
            </style>
            <script>
                function toggleGroup(groupId) {
                    var rows = document.getElementsByClassName(groupId);
                    var anyVisible = false;
                    for (var i = 0; i < rows.length; i++) {
                        if (rows[i].style.display !== 'none') { anyVisible = true; break; }
                    }
                    for (var i = 0; i < rows.length; i++) {
                        rows[i].style.display = anyVisible ? 'none' : '';
                    }
                    var tw = document.getElementById('tw-' + groupId);
                    if (tw) { tw.textContent = anyVisible ? '>' : 'v'; }
                }
            </script>
            </body>
            </html>
        """.trimIndent())

        ctx.contentType(ContentType.TEXT_HTML.mimeType)
        ctx.result(buf.toString())
    }
}

// TODO: move this to a common place
private fun QuadValue.toHtml(): String {
    // if it is a URI, make it clickable
    // if it is a literal, return the value
    // if it is a vector, return the value as a string and add a tooltip with the full value
    return when (this) {
        is URIValue -> {
            // Make URI values clickable by replacing angle brackets with HTML entities
            // and wrapping them in an anchor tag
            val displayValue = toString().replace("<", "&lt;").replace(">", "&gt;").replace(LocalQuadValue.defaultPrefix, "/")
            "<a href='$value/about'>$displayValue</a>"
        }
        is VectorValue -> {
            // For vector values, show them in a more readable form with a tooltip
            val shortDisplay = if (length > 8) {
                "[${toString().substring(1, 50)}...]"
            } else {
                toString()
            }
            "<span title='${toString()}'>$shortDisplay</span>"
        }
        else -> {
            // For other types (StringValue, DoubleValue, etc.), just return string representation
            toString()
        }
    }
}

// TODO: move this to a common place
private fun QuadValue.toPredHtml(): String {
    // if it is a URI, make it clickable
    // if it is a literal, return the value
    // if it is a vector, return the value as a string and add a tooltip with the full value
    return when (this) {
        is URIValue -> {
            // Make URI values clickable by replacing angle brackets with HTML entities
            // and wrapping them in an anchor tag
            val displayValue = toString().replace("<", "&lt;").replace(">", "&gt;").replace(LocalQuadValue.defaultPrefix, "/")
            val linkValue = value
            "<a href='$linkValue' target='_blank'>$displayValue</a>"
        }
        else -> {
            // For other types (StringValue, DoubleValue, etc.), just return string representation
            toString()
        }
    }
}
