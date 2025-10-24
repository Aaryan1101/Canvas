package fm.mrc.test

import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.drawerlayout.widget.DrawerLayout
import androidx.core.view.GravityCompat
import androidx.appcompat.app.ActionBarDrawerToggle
import com.google.android.material.appbar.MaterialToolbar
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import android.widget.Button
import android.view.View
import android.widget.EditText
import android.app.AlertDialog
import android.widget.Toast
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.SerializationException
import android.util.Log
import java.io.File
import java.io.IOException

class MainActivity : AppCompatActivity() {
    private lateinit var zoomableCanvas: ZoomableCanvas
    private lateinit var drawerLayout: DrawerLayout

    // PROPERTIES FOR FILE MANAGEMENT
    private lateinit var canvasAdapter: CanvasAdapter
    private var currentEditingFile: CanvasEntry? = null
    private val DEFAULT_CANVAS_FILE_NAME = "default_canvas.json"
    private val TAG = "MainActivity"

    // JSON configuration instance for serialization (use ignoreUnknownKeys for robustness)
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    // Callback for handling the back gesture/button press
    private val drawerBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.closeDrawer(GravityCompat.START)
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.layout)

        onBackPressedDispatcher.addCallback(this, drawerBackCallback)

        // 1. Initialize views
        drawerLayout = findViewById(R.id.drawer_layout)
        val toolbar: MaterialToolbar = findViewById(R.id.toolbar)
        zoomableCanvas = findViewById(R.id.zoomable_canvas)

        // --- Drawer Setup ---
        drawerLayout.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) {
                drawerBackCallback.isEnabled = true
                loadCanvasList() // IMPORTANT: Refresh the list every time the drawer opens
            }
            override fun onDrawerClosed(drawerView: View) {
                drawerBackCallback.isEnabled = false
            }
        })
        setSupportActionBar(toolbar)
        val toggle = ActionBarDrawerToggle(
            this, drawerLayout, toolbar,
            R.string.navigation_drawer_open,
            R.string.navigation_drawer_close
        )
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()
        // --- End Drawer Setup ---


        // 2. Setup Dynamic Menu Logic
        val recyclerView: RecyclerView = findViewById(R.id.canvas_list_recycler_view)
        val newCanvasButton: Button = findViewById(R.id.button_new_canvas)
        val renameButton: Button? = findViewById(R.id.button_rename_canvas)

        // Setup Adapter and RecyclerView
        canvasAdapter = CanvasAdapter { entry ->
            // On Click: Load existing canvas file
            startNewCanvasSession(entry.fileName, entry.title)
            drawerLayout.closeDrawer(GravityCompat.START)
        }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = canvasAdapter

        // Set listener for New Canvas button
        newCanvasButton.setOnClickListener {
            // Trigger naming dialog for a new file
            showNameDialog(isNewFile = true)
        }

        // Set listener for Rename button (if it exists in nav_drawer.xml)
        renameButton?.setOnClickListener {
            // Trigger naming dialog for the currently open file
            showNameDialog(isNewFile = false)
        }

        // 3. Initial Load: Try to load the default file on first run
        if (currentEditingFile == null) {
            // IMPORTANT: Set initial title and start the session (which calls loadCanvasState())
            startNewCanvasSession(DEFAULT_CANVAS_FILE_NAME, "Current Canvas")
        }

        // 4. Defer the load operation until the view is fully laid out
        zoomableCanvas.post {
            ensureCurrentFileMetadataExists() // NEW: Check/Update metadata first
            loadCanvasState()
        }

        // Ensure the list is populated after setup
        loadCanvasList()
    }

    /**
     * NEW: Ensures the current file (especially old default ones) has the FullCanvasData structure.
     * This fixes the "empty list" problem after file structure changes.
     */
    private fun ensureCurrentFileMetadataExists() {
        val fileName = currentEditingFile?.fileName ?: DEFAULT_CANVAS_FILE_NAME
        val file = File(filesDir, fileName)

        // Only proceed if the file actually exists
        if (!file.exists()) return

        try {
            // 1. Try to read the file as the new FullCanvasData structure
            val jsonString = file.readText()
            json.decodeFromString<FullCanvasData>(jsonString)
            // If decoding succeeds, the file is in the correct new format. Do nothing.
        } catch (e: Exception) {
            // 2. If decoding fails (SerializationException, etc.), the file is in the old (CanvasState only) format.
            Log.w(TAG, "Existing file $fileName is in old format. Forcing save to update metadata.")
            // Temporarily load old state (which is all that is possible) and save it in the new format.
            // This relies on the currentEditingFile being set correctly in startNewCanvasSession.
            saveCanvasState()
            // Note: We don't call loadCanvasState() here; let the post-check call handle the load.
        }
    }


    // --- NEW FUNCTIONALITY: NAMING DIALOGS ---

    /**
     * Shows an AlertDialog to get a name for a new or existing canvas.
     */
    private fun showNameDialog(isNewFile: Boolean) {
        val titleText = if (isNewFile) "Name Your New Canvas" else "Rename Canvas"
        val editText = EditText(this).apply {
            hint = "Enter canvas title"
            // Pre-fill if renaming an existing file
            if (!isNewFile && currentEditingFile != null) {
                setText(currentEditingFile!!.title)
            }
        }

        AlertDialog.Builder(this)
            .setTitle(titleText)
            .setView(editText)
            .setPositiveButton("OK") { _, _ ->
                val newTitle = editText.text.toString().trim()
                if (newTitle.isNotEmpty()) {
                    if (isNewFile) {
                        // Create new file entry and session
                        val newEntry = CanvasEntry.createNewFileEntry()
                        // Ensure the new canvas starts empty (FIX: clears old visual data)
                        zoomableCanvas.resetState()
                        startNewCanvasSession(newEntry.fileName, newTitle)

                        // CRITICAL FIX: Save the empty canvas immediately and refresh the list
                        saveCanvasState()
                        loadCanvasList()

                    } else if (currentEditingFile != null) {
                        // Rename existing file
                        renameCurrentFile(newTitle)
                        loadCanvasList() // CRITICAL FIX: Refresh list after renaming
                    }
                } else {
                    Toast.makeText(this, "Title cannot be empty.", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Renames the currently open file entry and updates the toolbar title.
     */
    private fun renameCurrentFile(newTitle: String) {
        if (currentEditingFile == null) return

        // 1. Update the in-memory entry with the new title and timestamp
        currentEditingFile = currentEditingFile!!.copy(
            title = newTitle,
            lastModified = System.currentTimeMillis()
        )

        // 2. Update the toolbar title immediately
        supportActionBar?.title = newTitle

        // 3. Save the state immediately (which ensures the new title is persisted in the file)
        saveCanvasState()

        // 4. We will rely on loadCanvasList() being called separately for the refresh
        Toast.makeText(this, "Canvas renamed to '$newTitle'", Toast.LENGTH_SHORT).show()
    }

    // --- FILE MANAGEMENT FUNCTIONS ---

    /**
     * Sets the file name and title to be used for saving/loading and triggers the load state.
     */
    private fun startNewCanvasSession(fileName: String, title: String) {
        // 1. If we are switching files, first save the CURRENT file's state
        if (currentEditingFile != null && currentEditingFile?.fileName != fileName) {
            saveCanvasState()
        }

        // 2. Set the new file being edited
        currentEditingFile = CanvasEntry(
            title = title,
            fileName = fileName,
            lastModified = System.currentTimeMillis()
        )

        // 3. Update the toolbar title
        supportActionBar?.title = title

        // 4. If the canvas is ready, trigger the load immediately
        if (::zoomableCanvas.isInitialized) {
            loadCanvasState()
        }
    }

    /**
     * Scans internal storage for all saved canvas files and updates the menu list.
     */
    private fun loadCanvasList() {
        val entries = CanvasEntry.getSavedEntries(filesDir)
        canvasAdapter.updateList(entries)
    }

    // --- PERSISTENCE FUNCTIONS ---

    override fun onPause() {
        saveCanvasState()
        super.onPause()
    }

    private fun saveCanvasState() {
        // Use the filename from the current editing session
        val fileNameToSave = currentEditingFile?.fileName ?: DEFAULT_CANVAS_FILE_NAME

        try {
            val state = zoomableCanvas.getCurrentState()

            // NEW: Create the wrapper object containing the entry and state
            val fullData = FullCanvasData(
                entry = currentEditingFile ?: CanvasEntry("Default", fileNameToSave),
                state = state
            )

            val jsonString = json.encodeToString(fullData) // Use local 'json' instance
            val file = File(filesDir, fileNameToSave)
            file.writeText(jsonString)

            Log.i(TAG, "Canvas state successfully saved to $fileNameToSave.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save canvas state for $fileNameToSave.", e)
            e.printStackTrace()
        }
    }

    private fun loadCanvasState() {
        val fileNameToLoad = currentEditingFile?.fileName ?: DEFAULT_CANVAS_FILE_NAME
        val file = File(filesDir, fileNameToLoad)

        if (!file.exists()) {
            Log.i(TAG, "File $fileNameToLoad not found. Resetting canvas state.")
            zoomableCanvas.resetState()
            return
        }

        try {
            val jsonString = file.readText()

            // NEW: Deserialize the wrapper object
            val fullData = json.decodeFromString<FullCanvasData>(jsonString)

            // CRITICAL: Update currentEditingFile from the data we just loaded
            currentEditingFile = fullData.entry.copy(lastModified = file.lastModified())
            supportActionBar?.title = currentEditingFile!!.title // Update toolbar title

            // Apply the contained canvas state
            zoomableCanvas.applyState(fullData.state)

            Log.i(TAG, "Canvas state successfully loaded from $fileNameToLoad.")
        } catch (e: SerializationException) {
            Log.e(TAG, "Serialization error during loading file $fileNameToLoad. Corrupted file deleted.", e)
            file.delete()
        } catch (e: IOException) {
            Log.e(TAG, "IO error reading saved file $fileNameToLoad.", e)
        } catch (e: Exception) {
            Log.e(TAG, "Unknown error during canvas state loading for $fileNameToLoad.", e)
        }
    }
}
