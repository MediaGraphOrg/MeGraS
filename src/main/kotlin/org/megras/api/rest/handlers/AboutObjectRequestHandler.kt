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
                  <meta charset="UTF-8">
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
        val itemBounds = Bounds(
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
                    val svgWidth = itemBounds.getMaxX()
                    val svgHeight = itemBounds.getMaxY()
                    svg = "<svg width='100%' height='100%' viewBox='0 0 $svgWidth $svgHeight' preserveAspectRatio='none' style='position: absolute; top: 0; left: 0;' xmlns:xlink='http://www.w3.org/1999/xlink'>\n"

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
                                val y = itemBounds.getMaxY() - coords[3]
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
                                        "$x,${itemBounds.getMaxY() - y}"
                                    }
                                svg += """
                                    <a xlink:href='$aboutUrl' target='_blank'>
                                        <polygon points='${points.joinToString(" ")}' style='fill:$color; stroke:black; stroke-width:2; fill-opacity:0.25; stroke-opacity:0.8; cursor:pointer;' />
                                    </a>
                                """.trimIndent()
                            }
                            SegmentationType.BEZIER, SegmentationType.BSPLINE -> {
                                val svgPath = segmentation.getDefinition()
                                val points = svgPath.split("),(")
                                    .map { it.replace("(", "").replace(")", "") }
                                    .map { point ->
                                        val (x, y) = point.split(",").map(String::toDouble)
                                        "$x,${itemBounds.getMaxY() - y}"
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
                                    "$command$x,${itemBounds.getMaxY() - y}"
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

            MediaType.TEXT.name -> {
                // Build absolute character intervals from child segments (CHARACTER)
                val charIntervals: MutableList<Pair<Int, Int>> = mutableListOf()
                // Also keep mapping to child about URLs for click-through
                val charLinks: MutableList<Triple<Int, Int, String>> = mutableListOf()
                if (children.isNotEmpty()) {
                    children.forEach { child ->
                        val seg = getSegmentation(child)
                        when (seg) {
                            is org.megras.segmentation.type.Character -> {
                                val absSeg = if (seg.isRelative) {
                                    // Convert to absolute using this object's bounds (T dimension = text length)
                                    (seg.toAbsolute(itemBounds) as? org.megras.segmentation.type.Character) ?: seg
                                } else seg
                                val aboutUrl = "${child.value}/about"
                                // Parse definition "l-h,l-h,..." to intervals
                                absSeg.getDefinition().split(",").forEach { part ->
                                    val r = part.trim()
                                    if (r.isNotEmpty()) {
                                        val bounds = r.split("-")
                                        if (bounds.size == 2) {
                                            val l = bounds[0].toDoubleOrNull()?.toInt()
                                            val h = bounds[1].toDoubleOrNull()?.toInt()
                                            if (l != null && h != null && h > l) {
                                                charIntervals.add(l to h)
                                                charLinks.add(Triple(l, h, aboutUrl))
                                            }
                                        }
                                    }
                                }
                            }
                            else -> { /* ignore non-character segments for text preview */ }
                        }
                    }
                }

                // JSON for client-side highlighting
                val intervalsJson = charIntervals.joinToString(prefix = "[", postfix = "]") { "[${it.first},${it.second}]" }
                // JSON for clickable segments: [ [l,h,"/child/about"], ... ]
                val segmentsJson = charLinks.joinToString(prefix = "[", postfix = "]") {
                    val url = it.third.replace("\\", "\\\\").replace("\"", "\\\"")
                    "[${it.first},${it.second},\"$url\"]"
                }

                buf.append(
                    """
                    <div class='media-container'>
                        <div style="position: relative; display: block; text-align: left;">
                            <pre id="text-preview" class="text-preview" style="margin: 0; max-height: 600px; overflow: auto; padding: 12px; border-radius: 4px;">
                                Loading text…
                            </pre>
                        </div>
                        <button class="segments-toggle-btn" onclick="toggleTextSegments()">Toggle Segments</button>
                    </div>
                    <script>
                        (function(){
                            const src = '${objectId.toPath()}';
                            const preview = document.getElementById('text-preview');
                            const intervals = $intervalsJson; // [ [start,end], ... ] absolute character offsets
                            const clickable = $segmentsJson;   // [ [start,end,href], ... ]

                            function computeDisjointWithLinks(ranges){
                                // Build events with href associations
                                const events = [];
                                ranges.forEach(r => {
                                    if (Array.isArray(r) && r.length >= 2){
                                        const s = r[0]|0, e = r[1]|0; if (e > s){
                                            const href = r[2];
                                            events.push({ pos: s, type: 'start', href });
                                            events.push({ pos: e, type: 'end', href });
                                        }
                                    }
                                });
                                if (events.length === 0) return [];
                                events.sort((a,b) => a.pos - b.pos || (a.type==='end'? -1:1)); // end before start at same pos when closing region first
                                const out = [];
                                const active = new Map(); // href -> count
                                let prev = events[0].pos;
                                let i = 0;
                                while (i < events.length){
                                    const pos = events[i].pos;
                                    if (pos > prev && active.size > 0){
                                        out.push({ start: prev, end: pos, hrefs: Array.from(active.keys()) });
                                    }
                                    // process all events at this pos
                                    while (i < events.length && events[i].pos === pos){
                                        const ev = events[i];
                                        if (ev.type === 'start') {
                                            active.set(ev.href, (active.get(ev.href) || 0) + 1);
                                        } else {
                                            const cur = (active.get(ev.href) || 0) - 1;
                                            if (cur <= 0) active.delete(ev.href); else active.set(ev.href, cur);
                                        }
                                        i++;
                                    }
                                    prev = pos;
                                }
                                return out;
                            }

                            function computeDisjoint(regs){
                                // Sweep-line to produce disjoint segments with coverage count
                                const events = [];
                                regs.forEach(r => { if (Array.isArray(r) && r.length===2){ events.push([r[0], +1]); events.push([r[1], -1]); }});
                                if (events.length === 0) return [];
                                events.sort((a,b) => a[0]-b[0] || b[1]-a[1]); // start(+1) before end(-1) at same pos
                                const out = [];
                                let cur = 0, prev = events[0][0];
                                for (let i=0;i<events.length;i++){
                                    const [pos, delta] = events[i];
                                    if (pos > prev && cur > 0) out.push({start: prev, end: pos, count: cur});
                                    cur += delta;
                                    prev = pos;
                                }
                                return out;
                            }

                            function render(text){
                                // Build content with highlighted spans
                                const regions = computeDisjoint(intervals);
                                const linkRegions = computeDisjointWithLinks(clickable);
                                // Clamp to text length
                                const len = text.length;
                                regions.forEach(r => { r.start = Math.max(0, Math.min(len, r.start)); r.end = Math.max(0, Math.min(len, r.end)); });
                                linkRegions.forEach(r => { r.start = Math.max(0, Math.min(len, r.start)); r.end = Math.max(0, Math.min(len, r.end)); });
                                // Merge invalid/empty
                                const filtered = regions.filter(r => r.end > r.start).sort((a,b)=>a.start-b.start);
                                const frag = document.createDocumentFragment();
                                let pos = 0;
                                for (let i=0;i<filtered.length;i++){
                                    const r = filtered[i];
                                    if (r.start > pos) frag.appendChild(document.createTextNode(text.slice(pos, r.start)));
                                    const span = document.createElement('span');
                                    span.className = 'text-segment' + (r.count > 1 ? ' overlap' : '');
                                    span.appendChild(document.createTextNode(text.slice(r.start, r.end)));
                                    // find hrefs for this region (by overlap with linkRegions)
                                    const hrefs = [];
                                    for (let j=0;j<linkRegions.length;j++){
                                        const lr = linkRegions[j];
                                        if (lr.end <= r.start) continue;
                                        if (lr.start >= r.end) break;
                                        lr.hrefs.forEach(h => { if (!hrefs.includes(h)) hrefs.push(h); });
                                    }
                                    if (hrefs.length > 0){
                                        span.style.cursor = 'pointer';
                                        span.addEventListener('click', function(ev){
                                            // open first matching about link in new tab
                                            const href = hrefs[0];
                                            if (href) window.open(href, '_blank');
                                            ev.stopPropagation();
                                            ev.preventDefault();
                                        });
                                        span.title = hrefs.length === 1 ? 'Open segment details' : 'Multiple segments overlap; opening first';
                                    }
                                    frag.appendChild(span);
                                    pos = r.end;
                                }
                                if (pos < len) frag.appendChild(document.createTextNode(text.slice(pos)));
                                // Replace content safely (preserve whitespace and not interpret as HTML)
                                preview.textContent = '';
                                preview.innerHTML = '';
                                preview.appendChild(frag);
                            }

                            function init(){
                                fetch(src, { credentials: 'same-origin' }).then(r => r.text()).then(t => {
                                    render(t);
                                }).catch(() => {
                                    // Fallback: show without highlighting
                                    preview.textContent = 'Failed to load text.';
                                });
                            }

                            window.toggleTextSegments = function(){
                                const hidden = preview.classList.toggle('segments-hidden');
                                // When hidden, also remove tooltips and disable keyboard focus hints
                                const spans = preview.querySelectorAll('.text-segment');
                                spans.forEach(function(span){
                                    if (hidden) {
                                        if (span.hasAttribute('title')) {
                                            span.setAttribute('data-title', span.getAttribute('title') || '');
                                            span.removeAttribute('title');
                                        }
                                    } else {
                                        const t = span.getAttribute('data-title');
                                        if (t !== null) {
                                            if (t) span.setAttribute('title', t);
                                            span.removeAttribute('data-title');
                                        }
                                    }
                                });
                            };

                            if (document.readyState === 'loading') {
                                document.addEventListener('DOMContentLoaded', init);
                            } else {
                                init();
                            }
                        })();
                    </script>
                """.trimIndent()
                )
            }

            MediaType.DOCUMENT.name -> {
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
                /* Text preview and segment styles */
                .text-preview { background: #fff; color: inherit; white-space: pre-wrap; font: inherit; }
                .text-segment { background-color: rgba(255, 215, 0, 0.35); border-bottom: 2px solid rgba(255, 215, 0, 0.85); cursor: pointer; }
                .text-segment.overlap { background-color: rgba(255, 215, 0, 0.55); box-shadow: inset 0 -3px 0 rgba(255, 215, 0, 0.85); }
                .segments-hidden .text-segment { background: transparent !important; border-color: transparent !important; box-shadow: none !important; pointer-events: none; cursor: text; }
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
