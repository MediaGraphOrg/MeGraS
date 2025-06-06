package org.megras.api.rest.handlers

import io.javalin.http.Context
import org.megras.api.rest.GetRequestHandler

class FileUploadPageHandler : GetRequestHandler {

    override fun get(ctx: Context) {
        val htmlContent = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>File Upload</title>
            </head>
            <body>
                <h1>Upload Files</h1>
                <form id="uploadForm" enctype="multipart/form-data">
                    <label for="fileInput">Select files:</label>
                    <input type="file" id="fileInput" name="files" multiple>
                    <br><br>
                    <button type="button" onclick="uploadFiles()">Upload</button>
                </form>

                <script>
                    async function uploadFiles() {
                        const fileInput = document.getElementById('fileInput');
                        if (fileInput.files.length === 0) {
                            alert('Please select at least one file to upload.');
                            return;
                        }
                        
                        const form = document.getElementById('uploadForm');
                        const formData = new FormData(form);
                
                        try {
                            const response = await fetch('/add/file', {
                                method: 'POST',
                                body: formData
                            });
                
                            if (response.ok) {
                                const result = await response.json();
                                const fileKey = Object.keys(result)[0]; // Get the first file key
                                const aboutPageUri = result[fileKey].value + "/about"; // Access the `value` field for the URI
                                window.location.href = aboutPageUri; // Redirect to the about page
                            } else {
                                alert('Error uploading files: ' + response.statusText);
                            }
                        } catch (error) {
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