package org.megras.api.rest.handlers

import io.javalin.http.Context
import io.javalin.openapi.HttpMethod
import io.javalin.openapi.OpenApi
import io.javalin.openapi.OpenApiContent
import io.javalin.openapi.OpenApiResponse
import org.megras.api.rest.GetRequestHandler

class FileUploadPageHandler : GetRequestHandler {

    @OpenApi(
        path = "/fileupload",
        methods = [HttpMethod.GET],
        summary = "Serves an HTML page for uploading files.",
        description = "Provides a user interface to select and upload files to the server. After successful upload via the form on this page (which posts to '/add/file'), the client-side script attempts to redirect to the 'about' page of the first uploaded file.",
        tags = ["User Interface"],
        responses = [
            OpenApiResponse(status = "200", description = "Successfully serves the HTML file upload page.", content = [OpenApiContent(type = "text/html")])
        ]
    )
    override fun get(ctx: Context) {
        val htmlContent = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>File Upload</title>
                <link rel="stylesheet" type="text/css" href="/static/styles.css">
                <style>
                    .preview-container {
                        margin: 20px 0;
                        padding: 15px;
                        border: 1px solid #ccc;
                        border-radius: 5px;
                        display: none;
                    }
                    .preview-container.active {
                        display: block;
                    }
                    .preview-media {
                        max-width: 400px;
                        max-height: 300px;
                        margin: 10px 0;
                    }
                    .annotations-section {
                        margin-top: 20px;
                        padding: 15px;
                        border: 1px solid #ccc;
                        border-radius: 5px;
                    }
                    .triple-row {
                        display: flex;
                        gap: 10px;
                        margin-bottom: 10px;
                        align-items: center;
                        flex-wrap: wrap;
                    }
                    .triple-row .input-group {
                        flex: 1;
                        min-width: 200px;
                        position: relative;
                    }
                    .triple-row input, .triple-row select {
                        width: 100%;
                        padding: 8px;
                        box-sizing: border-box;
                    }
                    .triple-row button {
                        padding: 8px 12px;
                    }
                    .remove-btn {
                        background-color: #dc3545;
                        color: white;
                        border: none;
                        cursor: pointer;
                    }
                    .add-btn, .fetch-btn {
                        background-color: #28a745;
                        color: white;
                        border: none;
                        cursor: pointer;
                        margin-top: 10px;
                        padding: 8px 12px;
                    }
                    .fetch-btn {
                        background-color: #007bff;
                        margin-left: 10px;
                    }
                    #uploadBtn {
                        margin-top: 20px;
                        padding: 10px 20px;
                        font-size: 16px;
                    }
                    .autocomplete-list {
                        position: absolute;
                        top: 100%;
                        left: 0;
                        right: 0;
                        max-height: 200px;
                        overflow-y: auto;
                        background: white;
                        border: 1px solid #ccc;
                        border-top: none;
                        z-index: 1000;
                        display: none;
                    }
                    .autocomplete-list.active {
                        display: block;
                    }
                    .autocomplete-item {
                        padding: 8px;
                        cursor: pointer;
                    }
                    .autocomplete-item:hover {
                        background-color: #f0f0f0;
                    }
                    .autocomplete-item .title {
                        font-weight: bold;
                    }
                    .autocomplete-item .uri {
                        font-size: 0.85em;
                        color: #666;
                    }
                    .quick-predicates {
                        margin-top: 10px;
                        padding: 10px;
                        background: #f9f9f9;
                        border-radius: 5px;
                    }
                    .quick-predicates label {
                        font-weight: bold;
                        display: block;
                        margin-bottom: 5px;
                    }
                    .quick-predicate-btn {
                        background-color: #6c757d;
                        color: white;
                        border: none;
                        padding: 5px 10px;
                        margin: 2px;
                        cursor: pointer;
                        border-radius: 3px;
                        font-size: 0.9em;
                    }
                    .quick-predicate-btn:hover {
                        background-color: #5a6268;
                    }
                    .upload-status {
                        display: none;
                        margin-top: 15px;
                        padding: 15px;
                        border-radius: 5px;
                        background-color: #e9ecef;
                        text-align: center;
                    }
                    .upload-status.active {
                        display: block;
                    }
                    .upload-status .spinner {
                        display: inline-block;
                        width: 20px;
                        height: 20px;
                        border: 3px solid #f3f3f3;
                        border-top: 3px solid #007bff;
                        border-radius: 50%;
                        animation: spin 1s linear infinite;
                        margin-right: 10px;
                        vertical-align: middle;
                    }
                    @keyframes spin {
                        0% { transform: rotate(0deg); }
                        100% { transform: rotate(360deg); }
                    }
                    .upload-status .status-text {
                        vertical-align: middle;
                        font-weight: bold;
                    }
                </style>
            </head>
            <body>
                <h1>Upload Files</h1>
                <form id="uploadForm" enctype="multipart/form-data">
                    <label for="fileInput">Select file:</label>
                    <input type="file" id="fileInput" name="files" onchange="previewFile()">
                </form>

                <div id="previewContainer" class="preview-container">
                    <h3>File Preview</h3>
                    <div id="previewContent"></div>
                    
                    <div class="annotations-section">
                        <h3>Annotations (Triples)</h3>
                        
                        <div class="quick-predicates">
                            <label>Quick Add Common Metadata:</label>
                            <button type="button" class="quick-predicate-btn" onclick="addQuickTriple('http://purl.org/dc/terms/title', 'Title')">Title</button>
                            <button type="button" class="quick-predicate-btn" onclick="addQuickTriple('http://purl.org/dc/terms/description', 'Description')">Description</button>
                            <button type="button" class="quick-predicate-btn" onclick="addQuickTriple('http://purl.org/dc/terms/creator', 'Creator/Author')">Creator</button>
                            <button type="button" class="quick-predicate-btn" onclick="addQuickTriple('http://purl.org/dc/terms/date', 'YYYY-MM-DD')">Date</button>
                            <button type="button" class="quick-predicate-btn" onclick="addQuickTriple('http://purl.org/dc/terms/created', 'YYYY-MM-DDTHH:MM:SS')">Created Time</button>
                            <button type="button" class="quick-predicate-btn" onclick="addQuickTriple('http://purl.org/dc/terms/spatial', 'Location name')">Location</button>
                            <button type="button" class="quick-predicate-btn" onclick="addQuickTriple('http://purl.org/dc/terms/subject', 'Subject/Topic')">Subject</button>
                            <button type="button" class="quick-predicate-btn" onclick="addQuickTriple('http://www.w3.org/2000/01/rdf-schema#label', 'Label')">Label</button>
                            <button type="button" class="quick-predicate-btn" onclick="addQuickTriple('http://www.w3.org/2000/01/rdf-schema#comment', 'Comment')">Comment</button>
                        </div>
                        
                        <div id="triplesContainer" style="margin-top: 15px;">
                        </div>
                        
                        <button type="button" class="add-btn" onclick="addTriple()">+ Add Triple</button>
                        <button type="button" class="fetch-btn" onclick="fetchPredicates()">Fetch Predicates</button>
                    </div>
                    
                    <div id="uploadStatus" class="upload-status">
                        <span class="spinner"></span>
                        <span id="statusText" class="status-text">Uploading file...</span>
                    </div>
                    
                    <button type="button" id="uploadBtn" onclick="uploadFiles()">Upload</button>
                </div>

                <script>
                    let knownPredicates = [];
                    
                    // Predicates to ignore
                    const ignoredPrefixes = [
                        'http://megras.org/schema#',
                        'http://megras.org/exif/'
                    ];
                    
                    // Quick predicates to exclude from suggestions (already available as buttons)
                    const quickPredicates = [
                        'http://purl.org/dc/terms/title',
                        'http://purl.org/dc/terms/description',
                        'http://purl.org/dc/terms/creator',
                        'http://purl.org/dc/terms/date',
                        'http://purl.org/dc/terms/created',
                        'http://purl.org/dc/terms/spatial',
                        'http://purl.org/dc/terms/subject',
                        'http://www.w3.org/2000/01/rdf-schema#label',
                        'http://www.w3.org/2000/01/rdf-schema#comment'
                    ];
                    
                    function shouldIgnorePredicate(uri) {
                        return ignoredPrefixes.some(prefix => uri.startsWith(prefix)) || quickPredicates.includes(uri);
                    }
                    
                    // Load predicates from the API on page load
                    async function loadPredicatesFromApi() {
                        try {
                            const response = await fetch('/query/quads', {
                                method: 'POST',
                                headers: { 'Content-Type': 'application/json' },
                                body: JSON.stringify({})
                            });
                            if (response.ok) {
                                const data = await response.json();
                                const predicateSet = new Set();
                                let ignoredCount = 0;
                                (data.results || []).forEach(quad => {
                                    // Handle both string format "<uri>" and object format {value: "uri"}
                                    let predUri = null;
                                    if (typeof quad.p === 'string') {
                                        // Remove angle brackets if present
                                        predUri = quad.p.replace(/^<|>$/g, '');
                                    } else if (quad.p && quad.p.value) {
                                        predUri = quad.p.value.replace(/^<|>$/g, '');
                                    }
                                    if (predUri) {
                                        if (shouldIgnorePredicate(predUri)) {
                                            ignoredCount++;
                                        } else {
                                            predicateSet.add(predUri);
                                        }
                                    }
                                });
                                knownPredicates = Array.from(predicateSet).map(uri => ({
                                    uri: uri,
                                    title: uri.split('/').pop().split('#').pop()
                                }));
                                console.log('Loaded ' + knownPredicates.length + ' predicates from database (ignored ' + ignoredCount + ' system predicates)');
                            }
                        } catch (err) {
                            console.log('Could not load predicates from API:', err);
                        }
                    }
                    loadPredicatesFromApi();
                    
                    function previewFile() {
                        const fileInput = document.getElementById('fileInput');
                        const previewContainer = document.getElementById('previewContainer');
                        const previewContent = document.getElementById('previewContent');
                        
                        if (fileInput.files.length === 0) {
                            previewContainer.classList.remove('active');
                            return;
                        }
                        
                        const file = fileInput.files[0];
                        const fileType = file.type;
                        previewContent.innerHTML = '';
                        
                        if (fileType.startsWith('image/')) {
                            const img = document.createElement('img');
                            img.src = URL.createObjectURL(file);
                            img.className = 'preview-media';
                            img.onload = () => URL.revokeObjectURL(img.src);
                            previewContent.appendChild(img);
                        } else if (fileType.startsWith('video/')) {
                            const video = document.createElement('video');
                            video.src = URL.createObjectURL(file);
                            video.className = 'preview-media';
                            video.controls = true;
                            previewContent.appendChild(video);
                        } else if (fileType.startsWith('audio/')) {
                            const audio = document.createElement('audio');
                            audio.src = URL.createObjectURL(file);
                            audio.controls = true;
                            previewContent.appendChild(audio);
                        } else if (fileType === 'application/pdf') {
                            const embed = document.createElement('embed');
                            embed.src = URL.createObjectURL(file);
                            embed.type = 'application/pdf';
                            embed.width = '400';
                            embed.height = '300';
                            previewContent.appendChild(embed);
                        } else {
                            previewContent.innerHTML = '<p>Preview not available for this file type: <strong>' + file.name + '</strong> (' + fileType + ')</p>';
                        }
                        
                        const fileInfo = document.createElement('p');
                        fileInfo.innerHTML = '<strong>File:</strong> ' + file.name + '<br><strong>Size:</strong> ' + formatFileSize(file.size);
                        previewContent.appendChild(fileInfo);
                        
                        previewContainer.classList.add('active');
                    }
                    
                    function formatFileSize(bytes) {
                        if (bytes === 0) return '0 Bytes';
                        const k = 1024;
                        const sizes = ['Bytes', 'KB', 'MB', 'GB'];
                        const i = Math.floor(Math.log(bytes) / Math.log(k));
                        return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
                    }
                    
                    function createTripleRowHTML(predicate = '', objectValue = '') {
                        const id = 'triple-' + Date.now() + '-' + Math.random().toString(36).substr(2, 9);
                        return `
                            <div class="triple-row" id="${'$'}{id}">
                                <div class="input-group">
                                    <input type="text" placeholder="Predicate URI" class="predicate-input" value="${'$'}{predicate}" oninput="showPredicateSuggestions(this)" onfocus="showPredicateSuggestions(this)" onblur="hideAutocomplete(this)">
                                    <div class="autocomplete-list predicate-autocomplete"></div>
                                </div>
                                <div class="input-group">
                                    <input type="text" placeholder="Object value" class="object-input" value="${'$'}{objectValue}">
                                </div>
                                <button type="button" class="remove-btn" onclick="removeTriple(this)">✕</button>
                            </div>
                        `;
                    }
                    
                    function addTriple(predicate = '', objectValue = '') {
                        const container = document.getElementById('triplesContainer');
                        const row = document.createElement('div');
                        row.innerHTML = createTripleRowHTML(predicate, objectValue);
                        container.appendChild(row.firstElementChild);
                    }
                    
                    function addQuickTriple(predicateUri, placeholder) {
                        const container = document.getElementById('triplesContainer');
                        const row = document.createElement('div');
                        row.innerHTML = createTripleRowHTML(predicateUri, '');
                        const rowElement = row.firstElementChild;
                        rowElement.querySelector('.object-input').placeholder = placeholder;
                        container.appendChild(rowElement);
                    }
                    
                    function removeTriple(button) {
                        button.closest('.triple-row').remove();
                    }
                    
                    function showPredicateSuggestions(input) {
                        const value = input.value.toLowerCase();
                        const autocompleteList = input.parentElement.querySelector('.predicate-autocomplete');
                        
                        if (knownPredicates.length === 0) {
                            autocompleteList.classList.remove('active');
                            return;
                        }
                        
                        const filtered = knownPredicates.filter(p => 
                            p.title.toLowerCase().includes(value) || 
                            p.uri.toLowerCase().includes(value)
                        ).slice(0, 10);
                        
                        if (filtered.length === 0 || value === '') {
                            autocompleteList.classList.remove('active');
                            return;
                        }
                        
                        autocompleteList.innerHTML = filtered.map(p => `
                            <div class="autocomplete-item" onmousedown="selectPredicate(this, '${'$'}{p.uri}')">
                                <div class="title">${'$'}{p.title}</div>
                                <div class="uri">${'$'}{p.uri}</div>
                            </div>
                        `).join('');
                        
                        autocompleteList.classList.add('active');
                    }
                    
                    function selectPredicate(item, uri) {
                        const row = item.closest('.triple-row');
                        const input = row.querySelector('.predicate-input');
                        input.value = uri;
                        item.closest('.autocomplete-list').classList.remove('active');
                    }
                    
                    function hideAutocomplete(input) {
                        setTimeout(() => {
                            const autocompleteList = input.parentElement.querySelector('.autocomplete-list');
                            if (autocompleteList) {
                                autocompleteList.classList.remove('active');
                            }
                        }, 200);
                    }
                    
                    async function fetchPredicates() {
                        try {
                            const response = await fetch('/query/quads', {
                                method: 'POST',
                                headers: { 'Content-Type': 'application/json' },
                                body: JSON.stringify({})
                            });
                            if (response.ok) {
                                const data = await response.json();
                                const predicateSet = new Set();
                                let ignoredCount = 0;
                                (data.results || []).forEach(quad => {
                                    // Handle both string format "<uri>" and object format {value: "uri"}
                                    let predUri = null;
                                    if (typeof quad.p === 'string') {
                                        // Remove angle brackets if present
                                        predUri = quad.p.replace(/^<|>$/g, '');
                                    } else if (quad.p && quad.p.value) {
                                        predUri = quad.p.value.replace(/^<|>$/g, '');
                                    }
                                    if (predUri) {
                                        if (shouldIgnorePredicate(predUri)) {
                                            ignoredCount++;
                                        } else {
                                            predicateSet.add(predUri);
                                        }
                                    }
                                });
                                knownPredicates = Array.from(predicateSet).map(uri => ({
                                    uri: uri,
                                    title: uri.split('/').pop().split('#').pop()
                                }));
                                alert('Loaded ' + knownPredicates.length + ' predicates from database (ignored ' + ignoredCount + ' system predicates). Start typing in a predicate field to see suggestions.');
                            } else {
                                alert('Could not fetch predicates from API.');
                            }
                        } catch (e) {
                            alert('Error fetching predicates: ' + e.message);
                        }
                    }
                    
                    function getTriples() {
                        const triples = [];
                        const rows = document.querySelectorAll('.triple-row');
                        rows.forEach(row => {
                            const predicate = row.querySelector('.predicate-input').value.trim();
                            const object = row.querySelector('.object-input').value.trim();
                            if (predicate && object) {
                                triples.push({ predicate, object });
                            }
                        });
                        return triples;
                    }
                    
                    async function uploadFiles() {
                        const fileInput = document.getElementById('fileInput');
                        if (fileInput.files.length === 0) {
                            alert('Please select a file to upload.');
                            return;
                        }
                        
                        const form = document.getElementById('uploadForm');
                        const formData = new FormData(form);
                        const uploadBtn = document.getElementById('uploadBtn');
                        const uploadStatus = document.getElementById('uploadStatus');
                        const statusText = document.getElementById('statusText');
                        
                        // Disable button and show status
                        uploadBtn.disabled = true;
                        uploadStatus.classList.add('active');
                        statusText.textContent = 'Uploading file...';
                
                        try {
                            const response = await fetch('/add/file', {
                                method: 'POST',
                                body: formData
                            });
                
                            if (response.ok) {
                                const result = await response.json();
                                const fileKey = Object.keys(result)[0];
                                const fileUri = result[fileKey].value;
                                
                                // Add annotations as triples
                                const triples = getTriples();
                                if (triples.length > 0) {
                                    statusText.textContent = 'Adding ' + triples.length + ' annotation(s)...';
                                    
                                    const triplePromises = triples.map(triple => 
                                        fetch('/add/quads', {
                                            method: 'POST',
                                            headers: {
                                                'Content-Type': 'application/json'
                                            },
                                            body: JSON.stringify({
                                                quads: [{
                                                    id: null,
                                                    s: '<' + fileUri + '>',
                                                    p: '<' + triple.predicate + '>',
                                                    o: triple.object + '^^String'
                                                }]
                                            })
                                        })
                                    );
                                    
                                    try {
                                        await Promise.all(triplePromises);
                                        console.log('Successfully added ' + triples.length + ' triples');
                                    } catch (tripleError) {
                                        console.error('Error adding triples:', tripleError);
                                        alert('Warning: Some annotations may not have been saved.');
                                    }
                                }
                                
                                statusText.textContent = 'Redirecting...';
                                const aboutPageUri = fileUri + "/about";
                                window.location.href = aboutPageUri;
                            } else {
                                uploadStatus.classList.remove('active');
                                uploadBtn.disabled = false;
                                alert('Error uploading files: ' + response.statusText);
                            }
                        } catch (error) {
                            uploadStatus.classList.remove('active');
                            uploadBtn.disabled = false;
                            alert('Error: ' + error.message);
                        }
                    }
                </script>
            </body>
            </html>
        """.trimIndent()

        ctx.contentType("text/html")
        ctx.result(htmlContent)
    }
}