package org.megras.api.rest.handlers

import io.javalin.http.Context
import org.megras.api.rest.GetRequestHandler

class AddTriplesPageHandler : GetRequestHandler {

    override fun get(ctx: Context) {
        val htmlContent = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Add Triples</title>
                <link rel="stylesheet" type="text/css" href="/static/styles.css">
                <style>
                    #triples {
                        width: 80vw;
                        height: 80vh;
                        box-sizing: border-box;
                    }
                </style>
                
            </head>
            <body>
                <h1>Add Triples</h1>
                <textarea id="triples" placeholder="Enter triples separated by tabs (<s> <p> <o>)"></textarea>
                <br>
                <button onclick="sendTriples()">Submit</button>
            </body>
            <script>
                   document.getElementById('triples').addEventListener('keydown', function(e) {
                      if (e.key == 'Tab') {
                        e.preventDefault();
                        var start = this.selectionStart;
                        var end = this.selectionEnd;

                        // set textarea value to: text before caret + tab + text after caret
                        this.value = this.value.substring(0, start) +
                          "\t" + this.value.substring(end);

                        // put caret at right position again
                        this.selectionStart =
                          this.selectionEnd = start + 1;
                      }
                    });
                    function sendTriples() {
                        const input = document.getElementById("triples").value.trim();
                        const lines = input.split("\n");
                        const triples = [];

                        lines.forEach(line => {
                            const parts = line.split("\t");
                            if (parts.length === 3) {
                                const s = parts[0].trim();
                                const p = parts[1].trim();
                                const o = parts[2].trim();

                                if (s && p && o) {
                                    triples.push({ s: s, p: p, o: o });
                                }
                            }
                        });

                        if (triples.length > 0) {
                            fetch("/add/quads", {
                                method: "POST",
                                headers: {
                                    "Content-Type": "application/json"
                                },
                                body: JSON.stringify({ quads: triples })
                            })
                            .then(response => {
                                if (response.ok) {
                                    alert("Triples added successfully!");
                                    document.getElementById("triples").value = "";
                                } else {
                                    alert("Failed to add triples: " + response.body);
                                }
                            })
                            .catch(error => {
                                console.error("Error:", error);
                                alert("An error occurred.");
                            });
                        } else {
                            alert("No valid triples found.");
                        }
                    }
                </script>
            </html>
        """.trimIndent()

        ctx.contentType("text/html")
        ctx.result(htmlContent)
    }
}