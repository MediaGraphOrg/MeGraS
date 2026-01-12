package org.megras.api.rest.handlers

import io.javalin.http.Context
import io.javalin.openapi.*
import org.megras.api.rest.GetRequestHandler
import org.megras.segmentation.SegmentationType

class SegmenterUiHandler : GetRequestHandler {

    @OpenApi(
        path = "/segmenterui",
        methods = [HttpMethod.GET],
        summary = "Serves the image segmenter UI.",
        tags = ["User Interface"],
        responses = [
            OpenApiResponse(status = "200", description = "HTML page with the image segmenter", content = [OpenApiContent(type = "text/html")])
        ]
    )
    override fun get(ctx: Context) {
        val imageSegmentationTypes = listOf(
            SegmentationType.RECT,
            SegmentationType.POLYGON,
            SegmentationType.PATH,
            SegmentationType.BSPLINE,
            SegmentationType.BEZIER
        )

        val optionsHtml = imageSegmentationTypes.joinToString("") {
            "<option value=\"${it.name}\">${it.name}</option>"
        }

        val htmlContent =
            """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                    <meta charset="UTF-8">
                    <title>MeGraS Image Segmenter</title>
                    <link rel="stylesheet" type="text/css" href="/static/styles.css">
                    <style>
                        /* Styles for core functionality if not fully covered by external CSS */
                        .image-container {
                            margin-top: 20px;
                            position: relative; /* Crucial for overlay */
                            border: 1px solid #ddd;
                            display: inline-block; /* Fit to image size */
                            overflow: hidden; /* Contain the canvas */
                        }
                        #objectImage {
                            display: block;
                            max-width: 100%;
                            height: auto;
                        }
                        #previewCanvas {
                            position: absolute; /* Crucial for overlay */
                            top: 0;
                            left: 0;
                            width: 100%;
                            height: 100%;
                            cursor: crosshair;
                            z-index: 1; /* Ensure canvas is above image but contained */
                        }
                        .hidden {
                            display: none;
                        }
                        .controls-group {
                            position: relative;
                            z-index: 10;
                        }
                        /* Ensure input fields have a reasonable width */
                        input[type="text"], select, textarea {
                            box-sizing: border-box; /* Includes padding and border in the element's total width and height */
                        }
                        input[type="text"]#objectIdentifier { /* More specific selector for potentially longer field */
                             width: calc(100% - 22px); /* Default from previous, adjust if global style is different */
                        }
                        textarea#segmentationDefinition {
                            resize: vertical; /* Allow vertical resize, default for most browsers */
                            width: ;
                        }
                    </style>
                </head>
                <body>
                    <center>
                        <div class="container">
                            <h1>Image Segmenter</h1>
    
                            <div class="controls-group">
                                <label for="objectIdentifier">Object Identifier:</label>
                                <input type="text" id="objectIdentifier" name="objectIdentifier" placeholder="Enter object identifier" style="text-align:center;"/>
                                <br><br>
                                <button onclick="loadImage()">Load Image</button>
                            </div>
    
                            <div id="segmentationControls" class="hidden">
                                <div class="image-container">
                                    <img id="objectImage" src="#" alt="Object image" />
                                    <canvas id="previewCanvas" width="0" height="0"></canvas>
                                </div>
    
                                <div class="controls-group">
                                    <br><br>
                                    <label for="segmentationType">Segmentation Type:</label>
                                    <select id="segmentationType" name="segmentationType">
                                        $optionsHtml
                                    </select>
                                    <br><br>
    
                                    <label for="segmentationDefinition">Segmentation Definition:</label>
                                    <br>
                                    <textarea id="segmentationDefinition" name="segmentationDefinition" rows="3" placeholder="Define segmentation based on type..." style="width:40%; text-align:center;"></textarea>
                                    <br><br>
                                    <p id="drawingHint" style="font-size: 0.9em; color: #666;"></p>

                                    <button onclick="clearDrawing()">Clear Drawing</button>
                                    <button onclick="createSegment()">Create Segment</button>
                                </div>
                            </div>
                        </div>
                    </center>

                    <script>
                        const objectIdentifierInput = document.getElementById('objectIdentifier');
                        const objectImage = document.getElementById('objectImage');
                        const previewCanvas = document.getElementById('previewCanvas');
                        const segmentationControls = document.getElementById('segmentationControls');
                        const segmentationTypeSelect = document.getElementById('segmentationType');
                        const segmentationDefinitionInput = document.getElementById('segmentationDefinition');
                        const drawingHint = document.getElementById('drawingHint');
                        const ctx = previewCanvas.getContext('2d');

                        // Interactive drawing state
                        let isDrawing = false;
                        let startX = 0;
                        let startY = 0;
                        let polygonPoints = []; // For polygon/path clicking

                        const typePlaceholders = {
                            'RECT': 'xmin,xmax,ymin,ymax\n(e.g., 10,100,20,120)',
                            'POLYGON': 'x1,y1,x2,y2,...\n(e.g., 10,10,100,10,100,100,10,100)',
                            'PATH': 'SVG Path data\n(e.g., M10 10 H90 V90 H10 Z)',
                            'BSPLINE': 'x1,y1,x2,y2,...\n(control points, e.g., 10,10,20,50,50,60)',
                            'BEZIER': 'x1,y1,x2,y2,...\n(start,c1,c2,end,... e.g., 0,0,20,80,80,20,100,100)'
                        };

                        const drawingHints = {
                            'RECT': 'Click and drag on the image to draw a rectangle.',
                            'POLYGON': 'Click on the image to add polygon points.',
                            'PATH': 'Click on the image to add path points. Double-click to close the path.',
                            'BSPLINE': 'Click on the image to add B-spline control points.',
                            'BEZIER': 'Click on the image to add Bezier control points (groups of 4: start, ctrl1, ctrl2, end).'
                        };

                        function updateDefinitionPlaceholder() {
                            const selectedType = segmentationTypeSelect.value;
                            segmentationDefinitionInput.placeholder = typePlaceholders[selectedType] || 'Enter definition';
                            drawingHint.textContent = drawingHints[selectedType] || '';
                        }

                        function clearDrawing() {
                            polygonPoints = [];
                            segmentationDefinitionInput.value = '';
                            clearCanvas();
                        }

                        segmentationTypeSelect.addEventListener('change', () => {
                            segmentationDefinitionInput.value = ''; // Reset definition
                            polygonPoints = []; // Reset polygon points
                            updateDefinitionPlaceholder();
                            previewSegment(); // Update preview (will clear canvas)
                        });

                        segmentationDefinitionInput.addEventListener('input', previewSegment); // Dynamic preview

                        function loadImage() {
                            const objectIdentifier = objectIdentifierInput.value;
                            if (!objectIdentifier) {
                                alert('Please enter an Object Identifier.');
                                return;
                            }
                            // Assuming API serves raw object at /<identifier>
                            objectImage.src = '/' + objectIdentifier;
                            objectImage.onload = () => {
                                previewCanvas.width = objectImage.naturalWidth;
                                previewCanvas.height = objectImage.naturalHeight;
                                segmentationControls.classList.remove('hidden');
                                polygonPoints = []; // Reset polygon points
                                updateDefinitionPlaceholder();
                                clearCanvas();
                            };
                            objectImage.onerror = () => {
                                alert('Failed to load image. Check Object Identifier and ensure it is an image.');
                                segmentationControls.classList.add('hidden');
                            };
                        }

                        function clearCanvas() {
                            ctx.clearRect(0, 0, previewCanvas.width, previewCanvas.height);
                        }

                        function previewSegment() {
                            if (objectImage.src.endsWith('#') || !objectImage.naturalWidth) { // No image loaded or image not yet loaded
                                return;
                            }
                            clearCanvas();
                            const type = segmentationTypeSelect.value;
                            const definition = segmentationDefinitionInput.value;
                            if (!definition) return;

                            ctx.strokeStyle = 'red';
                            ctx.lineWidth = 2;
                            
                            try {
                                // Note: y-coordinates are transformed for a bottom-left origin
                                const canvasHeight = previewCanvas.height;

                                if (type === 'RECT') {
                                    const coords = definition.split(',').map(s => parseFloat(s.trim()));
                                    if (coords.length === 4 && coords.every(c => !isNaN(c))) {
                                        // xmin, xmax, ymin, ymax
                                        const x = coords[0];
                                        const y = canvasHeight - coords[3]; // ymax from bottom
                                        const width = coords[1] - coords[0];
                                        const height = coords[3] - coords[2]; // ymax - ymin
                                        ctx.beginPath();
                                        ctx.rect(x, y, width, height);
                                        ctx.stroke();
                                    } else {
                                        console.error('Invalid RECT definition. Expected xmin,xmax,ymin,ymax');
                                        return;
                                    }
                                } else if (type === 'POLYGON') {
                                    const points = definition.split(',').map(s => parseFloat(s.trim()));
                                    if (points.length >= 4 && points.length % 2 === 0 && points.every(p => !isNaN(p))) {
                                        ctx.beginPath();
                                        ctx.moveTo(points[0], canvasHeight - points[1]);
                                        for (let i = 2; i < points.length; i += 2) {
                                            ctx.lineTo(points[i], canvasHeight - points[i+1]);
                                        }
                                        ctx.closePath();
                                        ctx.stroke();
                                    } else {
                                        console.error('Invalid POLYGON definition. Expected pairs of coordinates x1,y1,x2,y2,...');
                                        return;
                                    }
                                } else if (type === 'BSPLINE') {
                                    const points = definition.split(',').map(s => parseFloat(s.trim()));
                                    if (points.length >= 4 && points.length % 2 === 0 && points.every(p => !isNaN(p))) {
                                        // Draw control points and control polygon
                                        ctx.strokeStyle = 'rgba(255, 0, 0, 0.3)';
                                        ctx.setLineDash([5, 5]);
                                        ctx.beginPath();
                                        ctx.moveTo(points[0], canvasHeight - points[1]);
                                        for (let i = 2; i < points.length; i += 2) {
                                            ctx.lineTo(points[i], canvasHeight - points[i+1]);
                                        }
                                        ctx.stroke();
                                        ctx.setLineDash([]);
                                        
                                        // Draw control points as circles
                                        ctx.fillStyle = 'blue';
                                        for (let i = 0; i < points.length; i += 2) {
                                            ctx.beginPath();
                                            ctx.arc(points[i], canvasHeight - points[i+1], 5, 0, 2 * Math.PI);
                                            ctx.fill();
                                        }
                                        
                                        // Draw B-spline curve (cubic uniform B-spline)
                                        if (points.length >= 8) { // Need at least 4 control points
                                            ctx.strokeStyle = 'red';
                                            ctx.lineWidth = 2;
                                            ctx.beginPath();
                                            
                                            const numPoints = points.length / 2;
                                            const curvePoints = [];
                                            
                                            // Generate curve points using cubic B-spline basis
                                            for (let i = 0; i <= numPoints - 4; i++) {
                                                for (let t = 0; t <= 1; t += 0.05) {
                                                    const t2 = t * t;
                                                    const t3 = t2 * t;
                                                    
                                                    // Cubic B-spline basis functions
                                                    const b0 = (1 - 3*t + 3*t2 - t3) / 6;
                                                    const b1 = (4 - 6*t2 + 3*t3) / 6;
                                                    const b2 = (1 + 3*t + 3*t2 - 3*t3) / 6;
                                                    const b3 = t3 / 6;
                                                    
                                                    const px = b0 * points[(i)*2] + b1 * points[(i+1)*2] + b2 * points[(i+2)*2] + b3 * points[(i+3)*2];
                                                    const py = b0 * points[(i)*2+1] + b1 * points[(i+1)*2+1] + b2 * points[(i+2)*2+1] + b3 * points[(i+3)*2+1];
                                                    curvePoints.push({x: px, y: py});
                                                }
                                            }
                                            
                                            if (curvePoints.length > 0) {
                                                ctx.moveTo(curvePoints[0].x, canvasHeight - curvePoints[0].y);
                                                for (let i = 1; i < curvePoints.length; i++) {
                                                    ctx.lineTo(curvePoints[i].x, canvasHeight - curvePoints[i].y);
                                                }
                                                ctx.stroke();
                                            }
                                        }
                                    } else {
                                        console.error('Invalid BSPLINE definition. Expected pairs of coordinates x1,y1,x2,y2,...');
                                        return;
                                    }
                                } else if (type === 'BEZIER') {
                                    const points = definition.split(',').map(s => parseFloat(s.trim()));
                                    if (points.length >= 4 && points.length % 2 === 0 && points.every(p => !isNaN(p))) {
                                        // Draw control points and control polygon
                                        ctx.strokeStyle = 'rgba(255, 0, 0, 0.3)';
                                        ctx.setLineDash([5, 5]);
                                        ctx.beginPath();
                                        ctx.moveTo(points[0], canvasHeight - points[1]);
                                        for (let i = 2; i < points.length; i += 2) {
                                            ctx.lineTo(points[i], canvasHeight - points[i+1]);
                                        }
                                        ctx.stroke();
                                        ctx.setLineDash([]);
                                        
                                        // Draw control points as circles
                                        ctx.fillStyle = 'blue';
                                        for (let i = 0; i < points.length; i += 2) {
                                            ctx.beginPath();
                                            ctx.arc(points[i], canvasHeight - points[i+1], 5, 0, 2 * Math.PI);
                                            ctx.fill();
                                        }
                                        
                                        // Draw Bezier curve using canvas bezierCurveTo
                                        // Cubic Bezier: groups of 4 points (start, ctrl1, ctrl2, end)
                                        ctx.strokeStyle = 'red';
                                        ctx.lineWidth = 2;
                                        ctx.beginPath();
                                        
                                        const numPoints = points.length / 2;
                                        if (numPoints >= 4) {
                                            ctx.moveTo(points[0], canvasHeight - points[1]);
                                            
                                            // Process in groups of 4 points for cubic Bezier
                                            for (let i = 0; i + 3 < numPoints; i += 3) {
                                                const p1x = points[(i+1)*2], p1y = canvasHeight - points[(i+1)*2+1];
                                                const p2x = points[(i+2)*2], p2y = canvasHeight - points[(i+2)*2+1];
                                                const p3x = points[(i+3)*2], p3y = canvasHeight - points[(i+3)*2+1];
                                                ctx.bezierCurveTo(p1x, p1y, p2x, p2y, p3x, p3y);
                                            }
                                            ctx.stroke();
                                        } else if (numPoints >= 3) {
                                            // Quadratic Bezier with 3 points
                                            ctx.moveTo(points[0], canvasHeight - points[1]);
                                            ctx.quadraticCurveTo(
                                                points[2], canvasHeight - points[3],
                                                points[4], canvasHeight - points[5]
                                            );
                                            ctx.stroke();
                                        } else if (numPoints === 2) {
                                            // Just a line
                                            ctx.moveTo(points[0], canvasHeight - points[1]);
                                            ctx.lineTo(points[2], canvasHeight - points[3]);
                                            ctx.stroke();
                                        }
                                    } else {
                                        console.error('Invalid BEZIER definition. Expected pairs of coordinates x1,y1,x2,y2,...');
                                        return;
                                    }
                                } else if (type === 'PATH') {
                                    ctx.save();
                                    ctx.translate(0, canvasHeight);
                                    ctx.scale(1, -1); // Flip Y-axis for path drawing if path coords are bottom-left origin
                                    
                                    // lineWidth might need adjustment if negative scaling affects it, though typically it's absolute.
                                    // If lines become too thin/thick, set ctx.lineWidth after scale or scale it inversely.
                                    // For now, assume standard behavior.
                                    
                                    const path = new Path2D(definition);
                                    ctx.beginPath(); // Path2D does not start a path on the context by itself for stroke
                                    ctx.stroke(path);
                                    ctx.restore();
                                }
                            } catch (e) {
                                console.error('Error drawing preview:', e);
                                alert('Could not draw preview. Check definition format. Error: ' + e.message);
                            }
                        }
                        
                        function createSegment() {
                            const objectIdentifier = objectIdentifierInput.value;
                            const type = segmentationTypeSelect.value;
                            const definition = segmentationDefinitionInput.value;

                            if (!objectIdentifier || !type || !definition) {
                                alert('Please ensure Object Identifier, Segmentation Type, and Definition are provided.');
                                return;
                            }
                            // Construct the URL for the endpoint: /{objectId}/segment/{segmentation}/{segmentDefinition}
                            const segmentUrl = '/'+objectIdentifier+'/segment/'+type+'/'+encodeURIComponent(definition);
                            window.location.href = segmentUrl;
                        }

                        // Initialize placeholder on load
                        updateDefinitionPlaceholder();

                        // Get mouse position relative to canvas in image coordinates
                        function getMousePos(e) {
                            const rect = previewCanvas.getBoundingClientRect();
                            const scaleX = previewCanvas.width / rect.width;
                            const scaleY = previewCanvas.height / rect.height;
                            const x = Math.round((e.clientX - rect.left) * scaleX);
                            const y = Math.round((e.clientY - rect.top) * scaleY);
                            // Convert to bottom-left origin (y = canvasHeight - y)
                            const yFlipped = previewCanvas.height - y;
                            return { x, yFlipped };
                        }

                        // Mouse down handler
                        previewCanvas.addEventListener('mousedown', (e) => {
                            const type = segmentationTypeSelect.value;
                            const pos = getMousePos(e);

                            if (type === 'RECT') {
                                isDrawing = true;
                                startX = pos.x;
                                startY = pos.yFlipped;
                            }
                            // For POLYGON and PATH, clicking adds points (handled in mouseup)
                        });

                        // Mouse move handler
                        previewCanvas.addEventListener('mousemove', (e) => {
                            const type = segmentationTypeSelect.value;
                            
                            if (type === 'RECT' && isDrawing) {
                                const pos = getMousePos(e);
                                const xmin = Math.min(startX, pos.x);
                                const xmax = Math.max(startX, pos.x);
                                const ymin = Math.min(startY, pos.yFlipped);
                                const ymax = Math.max(startY, pos.yFlipped);
                                
                                segmentationDefinitionInput.value = xmin + ',' + xmax + ',' + ymin + ',' + ymax;
                                previewSegment();
                            }
                        });

                        // Mouse up handler
                        previewCanvas.addEventListener('mouseup', (e) => {
                            const type = segmentationTypeSelect.value;
                            const pos = getMousePos(e);

                            if (type === 'RECT') {
                                if (isDrawing) {
                                    isDrawing = false;
                                    const xmin = Math.min(startX, pos.x);
                                    const xmax = Math.max(startX, pos.x);
                                    const ymin = Math.min(startY, pos.yFlipped);
                                    const ymax = Math.max(startY, pos.yFlipped);
                                    
                                    segmentationDefinitionInput.value = xmin + ',' + xmax + ',' + ymin + ',' + ymax;
                                    previewSegment();
                                }
                            } else if (type === 'POLYGON' || type === 'BSPLINE' || type === 'BEZIER') {
                                // Add point to polygon/bspline/bezier
                                polygonPoints.push(pos.x, pos.yFlipped);
                                // Update definition: x1,y1,x2,y2,...
                                segmentationDefinitionInput.value = polygonPoints.join(',');
                                previewSegment();
                            } else if (type === 'PATH') {
                                // Add point to path
                                polygonPoints.push(pos.x, pos.yFlipped);
                                // Build SVG path: M x1 y1 L x2 y2 L x3 y3 ...
                                let pathStr = '';
                                for (let i = 0; i < polygonPoints.length; i += 2) {
                                    if (i === 0) {
                                        pathStr = 'M' + polygonPoints[i] + ' ' + polygonPoints[i+1];
                                    } else {
                                        pathStr += ' L' + polygonPoints[i] + ' ' + polygonPoints[i+1];
                                    }
                                }
                                segmentationDefinitionInput.value = pathStr;
                                previewSegment();
                            }
                        });

                        // Cancel drawing if mouse leaves canvas
                        previewCanvas.addEventListener('mouseleave', () => {
                            if (isDrawing) {
                                isDrawing = false;
                            }
                        });

                        // Double-click to close polygon/path
                        previewCanvas.addEventListener('dblclick', (e) => {
                            const type = segmentationTypeSelect.value;
                            
                            if (type === 'POLYGON' && polygonPoints.length >= 6) {
                                // Close polygon by adding Z to make it explicit (already closed in preview)
                                // Definition is already set, just trigger preview
                                previewSegment();
                            } else if (type === 'PATH' && polygonPoints.length >= 4) {
                                // Add Z to close the path
                                segmentationDefinitionInput.value += ' Z';
                                previewSegment();
                            }
                        });
                    </script>
                </body>
                </html>
        """.trimIndent()
        ctx.html(htmlContent)
    }
}