package org.megras.api.rest.handlers

import io.javalin.http.Context
import org.megras.api.rest.GetRequestHandler

class AddQuadsPageHandler : GetRequestHandler {

    override fun get(ctx: Context) {
        val htmlContent = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Add Triples</title>
                <style>
                    #triples {
                        width: 80vw;
                        height: 80vh;
                        box-sizing: border-box;
                    }
                </style>
                <script>
                    function sendQuads() {
                        const input = document.getElementById("triples").value.trim();
                        const lines = input.split("\n");
                        const quads = [];

                        lines.forEach(line => {
                            const parts = line.split("\t");
                            if (parts.length === 3) {
                                const s = parts[0].trim();
                                const p = parts[1].trim();
                                const o = parts[2].trim();

                                if (s && p && o) {
                                    quads.push({ s: s, p: p, o: o });
                                }
                            }
                        });

                        if (quads.length > 0) {
                            fetch("/add/quads", {
                                method: "POST",
                                headers: {
                                    "Content-Type": "application/json"
                                },
                                body: JSON.stringify({ quads: quads })
                            })
                            .then(response => {
                                if (response.ok) {
                                    alert("Quads added successfully!");
                                    document.getElementById("triples").value = "";
                                } else {
                                    alert("Failed to add quads.");
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
            </head>
            <body>
                <h1>Add Quads</h1>
                <textarea id="triples" placeholder="Enter triples separated by tabs (<s> <p> <o>)"></textarea>
                <br>
                <button onclick="sendQuads()">Submit</button>
            </body>
            </html>
        """.trimIndent()

        ctx.contentType("text/html")
        ctx.result(htmlContent)
    }
}