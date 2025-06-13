package org.megras.api.rest.handlers

import io.javalin.http.Context
import org.megras.api.rest.GetRequestHandler
import org.megras.segmentation.SegmentationType

class SegmenterUiHandler : GetRequestHandler {
    override fun get(ctx: Context) {
        val imageSegmentationTypes = listOf(
            SegmentationType.RECT,
            SegmentationType.POLYGON,
            SegmentationType.PATH,
            //SegmentationType.BSPLINE,
            //SegmentationType.BEZIER
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
                            pointer-events: none; /* Allows clicks to pass through if needed */
                        }
                        .hidden {
                            display: none;
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
                                    <canvas id="previewCanvas"></canvas>
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
                        const ctx = previewCanvas.getContext('2d');

                        const typePlaceholders = {
                            'RECT': 'xmin,xmax,ymin,ymax\n(e.g., 10,100,20,120)',
                            'POLYGON': 'x1,y1,x2,y2,...\n(e.g., 10,10,100,10,100,100,10,100)',
                            'PATH': 'SVG Path data\n(e.g., M10 10 H90 V90 H10 Z)',
                            'BSPLINE': 'x1,y1,x2,y2,...\n(control points, e.g., 10,10,20,50,50,60)',
                            'BEZIER': 'x1,y1,x2,y2,...\n(start,c1,c2,end,... e.g., 0,0,20,80,80,20,100,100)'
                        };

                        function updateDefinitionPlaceholder() {
                            const selectedType = segmentationTypeSelect.value;
                            segmentationDefinitionInput.placeholder = typePlaceholders[selectedType] || 'Enter definition';
                        }

                        segmentationTypeSelect.addEventListener('change', () => {
                            segmentationDefinitionInput.value = ''; // Reset definition
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
                                } else if (type === 'POLYGON' || type === 'BSPLINE' || type === 'BEZIER') {
                                    const points = definition.split(',').map(s => parseFloat(s.trim()));
                                    if (points.length >= 4 && points.length % 2 === 0 && points.every(p => !isNaN(p))) {
                                        ctx.beginPath();
                                        ctx.moveTo(points[0], canvasHeight - points[1]);
                                        for (let i = 2; i < points.length; i += 2) {
                                            ctx.lineTo(points[i], canvasHeight - points[i+1]);
                                        }
                                        if (type === 'POLYGON') {
                                           ctx.closePath();
                                        }
                                        ctx.stroke();
                                        // For BSPLINE and BEZIER, this is a simplified preview connecting the points.
                                    } else {
                                        console.error('Invalid ' + type + ' definition. Expected pairs of coordinates x1,y1,x2,y2,...');
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
                    </script>
                </body>
                </html>
        """.trimIndent()
        ctx.html(htmlContent)
    }
}