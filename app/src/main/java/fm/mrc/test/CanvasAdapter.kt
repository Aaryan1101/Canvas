package fm.mrc.test

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CanvasAdapter(
    private val clickListener: (CanvasEntry) -> Unit
) : RecyclerView.Adapter<CanvasAdapter.CanvasViewHolder>() {

    // The list of files to display
    private var canvasList: List<CanvasEntry> = emptyList()

    // Date formatter for last modified timestamp
    private val dateFormat = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.getDefault())

    // Function to update the list and refresh the RecyclerView
    fun updateList(newList: List<CanvasEntry>) {
        canvasList = newList
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CanvasViewHolder {
        // Inflate the canvas_list_item.xml layout
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.canvas_list_item, parent, false)
        return CanvasViewHolder(view)
    }

    override fun onBindViewHolder(holder: CanvasViewHolder, position: Int) {
        val entry = canvasList[position]
        holder.bind(entry, clickListener, dateFormat)
    }

    override fun getItemCount(): Int = canvasList.size

    class CanvasViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        // Find views in the layout
        private val titleTextView: TextView = itemView.findViewById(R.id.text_view_title)
        private val modifiedTextView: TextView = itemView.findViewById(R.id.text_view_last_modified)

        fun bind(
            entry: CanvasEntry,
            clickListener: (CanvasEntry) -> Unit,
            dateFormat: SimpleDateFormat
        ) {
            titleTextView.text = entry.title

            // Format the timestamp for display
            val formattedDate = dateFormat.format(Date(entry.lastModified))
            modifiedTextView.text = itemView.context.getString(
                R.string.last_modified_format, formattedDate // Assuming you added this string format
            )

            // Set the click listener on the entire item view
            itemView.setOnClickListener {
                clickListener(entry)
            }
        }
    }
}