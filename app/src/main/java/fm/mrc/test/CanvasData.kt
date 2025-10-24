package fm.mrc.test

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.SerializationException
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * Data class to store the persistent state of a single editable note.
 */
@Serializable
data class NoteData(
    val text: String,
    val x: Float,
    val y: Float,
    val width: Int,
    val height: Int
)

/**
 * Data class to store the persistent state of the entire canvas (zoom/pan state).
 */
@Serializable
data class CanvasState(
    val notes: List<NoteData>,
    val scaleFactor: Float,
    val translationX: Float,
    val translationY: Float
)

/**
 * NEW: Wrapper class to hold both the state and the metadata (title) for persistence.
 * This is the object saved to the file.
 */
@Serializable
data class FullCanvasData(
    val entry: CanvasEntry,
    val state: CanvasState
)

/**
 * Data class representing a saved file entry for display on the home screen.
 */
@Serializable
data class CanvasEntry(
    val title: String,
    val fileName: String,
    val lastModified: Long = System.currentTimeMillis()
) {
    companion object {
        private const val FILE_EXTENSION = ".json"

        /**
         * Creates a new, unique filename and title for a new canvas.
         */
        fun createNewFileEntry(): CanvasEntry {
            val timestamp = System.currentTimeMillis()
            val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            val formattedDate = dateFormat.format(Date(timestamp))
            val uniqueId = Random.nextInt(1000)

            // Filename format: "canvas_20251024_184730_123.json"
            val fileName = "canvas_${formattedDate}_${uniqueId}${FILE_EXTENSION}"
            val title = "New Canvas ${formattedDate}"

            return CanvasEntry(
                title = title,
                fileName = fileName,
                lastModified = timestamp
            )
        }

        /**
         * Scans the application's internal directory and returns a list of all saved CanvasEntry metadata.
         * Now reads the saved title directly from the JSON content.
         */
        fun getSavedEntries(filesDir: File): List<CanvasEntry> {
            val json = Json { ignoreUnknownKeys = true } // Add robustness for future file changes

            return filesDir.listFiles { _, name ->
                name.startsWith("canvas_") && name.endsWith(FILE_EXTENSION)
            }?.mapNotNull { file ->
                try {
                    val jsonString = file.readText()
                    // Deserialize the wrapper class to get the saved title (entry)
                    val fullData = json.decodeFromString<FullCanvasData>(jsonString)

                    // Use the entry data saved inside the file, but update the lastModified
                    // timestamp using the file system's timestamp, just in case.
                    fullData.entry.copy(lastModified = file.lastModified())

                } catch (e: SerializationException) {
                    // Ignore files that are corrupt or can't be read
                    null
                } catch (e: IOException) {
                    // Ignore files that cause IO errors
                    null
                } catch (e: Exception) {
                    // Catch any other exceptions
                    null
                }
            }?.sortedByDescending { it.lastModified } ?: emptyList()
        }
    }
}