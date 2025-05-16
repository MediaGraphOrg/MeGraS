package org.megras.util.embeddings

import java.io.BufferedReader
import java.io.InputStreamReader

class ClipEmbeddings {
    companion object {
        private const val FOLDER_PATH = "src/main/kotlin/org/megras/util/embeddings"

        fun getTextEmbedding(text: String): FloatArray {
            try {
                val process = ProcessBuilder("python", "${FOLDER_PATH}/get_text_embedding.py", text) // Ensure correct script name/path
                    .redirectErrorStream(false) // Redirect error stream to output
                    .start()

                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val out = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    out.append(line).append("\n")
                }
                process.waitFor() // Wait for the process to complete.

                if (process.exitValue() != 0) {
                    println("Error running Python script:")
                    println(out.toString()) // Print the error output
                    return FloatArray(0)
                }

                // Parse the output (assuming it's a comma-separated list of floats)
                val floatStrings = out.toString().trim().split(",")
                return floatStrings.map { it.toFloat() }.toFloatArray()

            } catch (e: Exception) {
                println("Error: ${e.message}")
                return FloatArray(0)
            }
        }

        fun getImageEmbedding(imagePath: String): FloatArray {
            try {
                val process = ProcessBuilder("python", "${FOLDER_PATH}/get_image_embedding.py", imagePath)
                    .redirectErrorStream(false)
                    .start()

                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val output = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    output.append(line).append("\n")
                }
                process.waitFor()

                if (process.exitValue() != 0) {
                    println("Error running Python script:")
                    println(output.toString()) // Print the error output
                    return FloatArray(0)
                }

                // Parse the output
                val floatStrings = output.toString().trim().split(",")
                return floatStrings.map { it.toFloat() }.toFloatArray()

            } catch (e: Exception) {
                println("Error: ${e.message}")
                return FloatArray(0)
            }
        }
    }
}
