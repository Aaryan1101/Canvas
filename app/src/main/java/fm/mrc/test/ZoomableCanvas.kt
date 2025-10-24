package fm.mrc.test

import android.content.Context
import android.util.AttributeSet
import android.view.*
import android.widget.EditText
import android.widget.FrameLayout
import android.graphics.Color
import android.util.TypedValue
import android.widget.OverScroller
import android.graphics.drawable.GradientDrawable
import android.view.inputmethod.InputMethodManager
import fm.mrc.test.FullCanvasData // NEW: Import required for the new persistence structure

class ZoomableCanvas @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    // Helper function to convert DPs to pixels
    private fun dpToPx(dp: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics
    ).toInt()

    // --- RESIZING CONSTANTS & STATE ---
    private val RESIZE_HANDLE_DP = 20
    private val RESIZE_SLOP_PX = dpToPx(RESIZE_HANDLE_DP) // 20dp boundary for resize detection
    private val RESIZE_MIN_DP = 50
    private val RESIZE_MIN_PX = dpToPx(RESIZE_MIN_DP) // Minimum size for the box

    private val RESIZE_NONE = 0
    private val RESIZE_LEFT = 1
    private val RESIZE_TOP = 2
    private val RESIZE_RIGHT = 4
    private val RESIZE_BOTTOM = 8

    private var resizeMode = RESIZE_NONE // Stores which side is being dragged
    private var initialChildWidth = 0
    private var initialChildHeight = 0
    private var initialChildX = 0f
    private var initialChildY = 0f
    // --- END RESIZING CONSTANTS & STATE ---

    // --- DRAGGING VARIABLES ---
    private var lastChildTouchX = 0f
    private var lastChildTouchY = 0f
    private var originalChildPosX = 0f
    private var originalChildPosY = 0f
    // --- END DRAGGING VARIABLES ---

    private val CANVAS_SIZE_MULTIPLIER = 50

    // Constants for EditText sizing
    private val TEXT_BOX_WIDTH_DP = 200
    private val TEXT_BOX_HEIGHT_DP = 100
    private val TEXT_BOX_WIDTH_PX = dpToPx(TEXT_BOX_WIDTH_DP)
    private val TEXT_BOX_HEIGHT_PX = dpToPx(TEXT_BOX_HEIGHT_DP)

    // This container holds all user-created elements and is the view that gets scaled/panned.
    private val contentContainer: FrameLayout = FrameLayout(context).apply {
        // Start with WRAP_CONTENT; massive size will be set in onSizeChanged
        layoutParams = LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        setBackgroundColor(Color.TRANSPARENT)
    }

    private var scaleFactor = 1.0f
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    // NEW PERSISTENCE STATE: Now holds the CanvasState directly
    private var deferredCanvasState: CanvasState? = null
    private var hasInitialLayout = false

    // For momentum scrolling
    private val scroller = OverScroller(context)
    private var velocityTracker: VelocityTracker? = null

    // Animation loop for momentum scrolling
    private val scrollRunnable = object : Runnable {
        override fun run() {
            if (scroller.computeScrollOffset()) {
                contentContainer.translationX = scroller.currX.toFloat()
                contentContainer.translationY = scroller.currY.toFloat()

                postOnAnimation(this)
                invalidate()
            } else {
                contentContainer.setLayerType(LAYER_TYPE_NONE, null)
            }
        }
    }

    // Detectors
    private val scaleDetector: ScaleGestureDetector
    private val gestureDetector: GestureDetector

    init{
        scaleDetector = ScaleGestureDetector(context, ScaleListener())
        gestureDetector = GestureDetector(context, GestureListener())

        // The ZoomableCanvas itself stays fixed, full-screen, and black.
        setBackgroundColor(context.getColor(android.R.color.black))

        addView(contentContainer)
    }

    // --- HELPER FUNCTION FOR DESIGN ---
    private fun createBorderDrawable(): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.TRANSPARENT) // Transparent background
            setStroke(dpToPx(3), Color.WHITE) // Heavy white outline (3dp)
            cornerRadius = dpToPx(8).toFloat() // Slight rounding for modern look
        }
    }
    // --- END NEW HELPER FUNCTION ---

    // --- RESIZE MODE DETECTION HELPER ---
    /**
     * Determines if the touch point (localX, localY) is near an edge or corner of the child View.
     */
    private fun getResizeMode(child: View, localX: Float, localY: Float): Int {
        var mode = RESIZE_NONE

        // Check LEFT edge
        if (localX < RESIZE_SLOP_PX) {
            mode = mode or RESIZE_LEFT
        }
        // Check RIGHT edge
        if (localX > child.width - RESIZE_SLOP_PX) {
            mode = mode or RESIZE_RIGHT
        }
        // Check TOP edge
        if (localY < RESIZE_SLOP_PX) {
            mode = mode or RESIZE_TOP
        }
        // Check BOTTOM edge
        if (localY > child.height - RESIZE_SLOP_PX) {
            mode = mode or RESIZE_BOTTOM
        }

        return mode
    }
    // --- END RESIZE MODE DETECTION HELPER ---

    // --- TOUCH LISTENER FOR DRAGGABLE/RESIZABLE CHILDREN ---
    private val childTouchListener = OnTouchListener { view, event ->
        val child = view as View // The EditText being dragged
        val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 1. Determine if the touch is a resize attempt
                resizeMode = getResizeMode(child, event.x, event.y)

                if (resizeMode != RESIZE_NONE) {
                    // Start RESIZE mode
                    initialChildWidth = child.width
                    initialChildHeight = child.height
                    initialChildX = child.x
                    initialChildY = child.y
                    lastChildTouchX = event.rawX
                    lastChildTouchY = event.rawY
                } else {
                    // Start DRAG mode (Existing logic)
                    originalChildPosX = child.x
                    originalChildPosY = child.y
                    lastChildTouchX = event.rawX
                    lastChildTouchY = event.rawY
                }
                return@OnTouchListener true
            }
            MotionEvent.ACTION_MOVE -> {
                val deltaRawX = event.rawX - lastChildTouchX
                val deltaRawY = event.rawY - lastChildTouchY

                if (resizeMode != RESIZE_NONE) {
                    // RESIZE LOGIC
                    val dX = deltaRawX / scaleFactor
                    val dY = deltaRawY / scaleFactor

                    var newX = initialChildX
                    var newY = initialChildY
                    var newW = initialChildWidth
                    var newH = initialChildHeight

                    // Apply width/X change
                    if (resizeMode and RESIZE_LEFT != 0) {
                        newW = (initialChildWidth - dX).toInt().coerceAtLeast(RESIZE_MIN_PX)
                        // Only change X if width can decrease
                        if (newW > RESIZE_MIN_PX || initialChildWidth - dX > initialChildWidth) {
                            newX = initialChildX + dX
                        } else {
                            newX = child.x // Anchor X if minimum size reached
                        }
                    } else if (resizeMode and RESIZE_RIGHT != 0) {
                        newW = (initialChildWidth + dX).toInt().coerceAtLeast(RESIZE_MIN_PX)
                    }

                    // Apply height/Y change
                    if (resizeMode and RESIZE_TOP != 0) {
                        newH = (initialChildHeight - dY).toInt().coerceAtLeast(RESIZE_MIN_PX)
                        // Only change Y if height can decrease
                        if (newH > RESIZE_MIN_PX || initialChildHeight - dY > initialChildHeight) {
                            newY = initialChildY + dY
                        } else {
                            newY = child.y // Anchor Y if minimum size reached
                        }
                    } else if (resizeMode and RESIZE_BOTTOM != 0) {
                        newH = (initialChildHeight + dY).toInt().coerceAtLeast(RESIZE_MIN_PX)
                    }

                    // Apply changes
                    val params = child.layoutParams as LayoutParams
                    params.width = newW
                    params.height = newH
                    child.layoutParams = params

                    // Position is only updated for LEFT/TOP resizing
                    if (resizeMode and RESIZE_LEFT != 0 || resizeMode and RESIZE_TOP != 0) {
                        child.x = newX
                        child.y = newY
                    }

                    // Update initial state for next move event
                    lastChildTouchX = event.rawX
                    lastChildTouchY = event.rawY
                    initialChildWidth = newW
                    initialChildHeight = newH
                    initialChildX = child.x
                    initialChildY = child.y

                    return@OnTouchListener true

                } else {
                    // DRAG LOGIC (Only runs if resizeMode is NONE)
                    val deltaRawX = event.rawX - lastChildTouchX
                    val deltaRawY = event.rawY - lastChildTouchY

                    // Calculate the child's new position: original pos + (raw displacement / scale)
                    child.x = originalChildPosX + deltaRawX / scaleFactor
                    child.y = originalChildPosY + deltaRawY / scaleFactor

                    return@OnTouchListener true
                }
            }
            MotionEvent.ACTION_UP -> {
                // Reset resize mode
                resizeMode = RESIZE_NONE

                val deltaRawX = event.rawX - lastChildTouchX
                val deltaRawY = event.rawY - lastChildTouchY

                // Check if the total movement was greater than the touch slop (i.e., it was a drag)
                val isDrag = (deltaRawX * deltaRawX + deltaRawY * deltaRawY) > (touchSlop * touchSlop)

                if (isDrag) {
                    // It was a drag/resize. Consume the event to suppress the keyboard.
                    return@OnTouchListener true
                } else {
                    // It was a tap. Request focus to bring up the keyboard for typing.
                    child.requestFocus()
                    // Return false to allow the system to handle the focus change normally.
                    return@OnTouchListener false
                }
            }
            else -> false
        }
    }
    // --- END TOUCH LISTENER ---

    /**
     * Correct calculation for touch detection on existing EditTexts.
     * Converts screen coordinates back to contentContainer's local, scaled coordinates.
     */
    private fun isTouchOnEditText(ev: MotionEvent): Boolean {
        for (i in 0 until contentContainer.childCount) {
            val child = contentContainer.getChildAt(i)
            if (child is EditText) {
                // Convert screen coordinates to container coordinates
                val containerX = (ev.x - contentContainer.translationX) / scaleFactor
                val containerY = (ev.y - contentContainer.translationY) / scaleFactor

                if (containerX >= child.x && containerX <= child.x + child.width &&
                    containerY >= child.y && containerY <= child.y + child.height) {
                    return true
                }
            }
        }
        return false
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        return when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Intercept if touch is NOT on an EditText, allowing the Canvas to pan/zoom/double-tap.
                // If the touch IS on an EditText, we let the EditText's listener handle it for dragging/typing.
                !isTouchOnEditText(ev)
            }
            else -> super.onInterceptTouchEvent(ev)
        }
    }

    // FIX: Set the massive fixed size, and initial translation. Pivot is set to 0,0.
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)

        // 1. Determine the massive size
        val largeSize = Math.max(w, h) * CANVAS_SIZE_MULTIPLIER

        // 2. Apply massive size to the container
        val params = contentContainer.layoutParams
        params.width = largeSize
        params.height = largeSize
        contentContainer.layoutParams = params

        // 3. Set pivot to (0,0) so focal point scaling works correctly.
        contentContainer.pivotX = 0f
        contentContainer.pivotY = 0f

        // 4. Center the massive container in the screen view (Default position)
        contentContainer.translationX = (w - largeSize) / 2f
        contentContainer.translationY = (h - largeSize) / 2f

        // 5. Check for deferred state to apply after layout is ready
        if (deferredCanvasState != null && !hasInitialLayout) {
            applyDeferredState(deferredCanvasState!!)
            deferredCanvasState = null // Clear the deferred state
            hasInitialLayout = true
        }
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener(){
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            contentContainer.setLayerType(LAYER_TYPE_HARDWARE, null)
            return super.onScaleBegin(detector)
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val prevScale = scaleFactor
            scaleFactor *= detector.scaleFactor

            scaleFactor = Math.max(0.1f, Math.min(scaleFactor, 10.0f))

            val focusX = detector.focusX
            val focusY = detector.focusY

            // 1. Calculate the coordinate on the contentContainer currently under the focal point (F_x, F_y)
            val contentCoordX = (focusX - contentContainer.translationX) / prevScale
            val contentCoordY = (focusY - contentContainer.translationY) / prevScale

            // 2. Calculate the new translation required to keep contentCoord fixed under the focal point
            contentContainer.translationX = focusX - (contentCoordX * scaleFactor)
            contentContainer.translationY = focusY - (contentCoordY * scaleFactor)

            contentContainer.scaleX = scaleFactor
            contentContainer.scaleY = scaleFactor

            invalidate()
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            contentContainer.setLayerType(LAYER_TYPE_NONE, null)
        }
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener(){
        override fun onFling(
            e1: MotionEvent?, e2: MotionEvent,
            velocityX: Float, velocityY: Float
        ): Boolean {
            contentContainer.setLayerType(LAYER_TYPE_HARDWARE, null)

            scroller.fling(
                contentContainer.translationX.toInt(),
                contentContainer.translationY.toInt(),
                velocityX.toInt(),
                velocityY.toInt(),
                Int.MIN_VALUE, Int.MAX_VALUE,
                Int.MIN_VALUE, Int.MAX_VALUE
            )
            postOnAnimation(scrollRunnable)
            return true
        }

        override fun onDown(e: MotionEvent): Boolean {
            if (!scroller.isFinished) {
                scroller.forceFinished(true)
                contentContainer.setLayerType(LAYER_TYPE_NONE, null)
            }
            return true
        }

        // --- Handle single tap on the background ---
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            // Check if any EditText is currently focused
            val focusedView = findFocus()

            if (focusedView is EditText) {
                // 1. Clear focus from the EditText
                focusedView.clearFocus()

                // 2. Hide the keyboard
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(windowToken, 0)

                return true
            }
            // Let the tap event continue for other uses (like double tap)
            return super.onSingleTapUp(e)
        }
        // --- END single tap handling ---

        override fun onDoubleTap(e: MotionEvent): Boolean {
            // Use the new helper function for consistency
            val newTextBox = createNewEditText().apply {
                // Position is calculated relative to the massive contentContainer's origin
                x = (e.x - contentContainer.translationX) / scaleFactor - (TEXT_BOX_WIDTH_PX / 2f) / scaleFactor
                y = (e.y - contentContainer.translationY) / scaleFactor - (TEXT_BOX_HEIGHT_PX / 2f) / scaleFactor
            }

            contentContainer.addView(newTextBox)

            return true
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        velocityTracker?.addMovement(event)

        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                velocityTracker = VelocityTracker.obtain().apply { addMovement(event) }

                lastTouchX = event.x
                lastTouchY = event.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && !scroller.isFinished) {
                    scroller.forceFinished(true)
                }

                if (!scaleDetector.isInProgress) {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY

                    contentContainer.translationX += dx
                    contentContainer.translationY += dy

                    lastTouchX = event.x
                    lastTouchY = event.y

                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocityTracker?.recycle()
                velocityTracker = null
            }
        }

        return true
    }

    // --- PERSISTENCE HELPER AND METHODS ---

    /**
     * Resets the canvas to an empty, default state (used when creating a new file).
     * NEW PUBLIC METHOD
     */
    fun resetState() {
        contentContainer.removeAllViews()
        // Reset zoom
        scaleFactor = 1.0f
        contentContainer.scaleX = 1.0f
        contentContainer.scaleY = 1.0f

        // Reset pan (re-centering)
        val largeSize = Math.max(width, height) * CANVAS_SIZE_MULTIPLIER
        contentContainer.translationX = (width - largeSize) / 2f
        contentContainer.translationY = (height - largeSize) / 2f

        invalidate()
    }


    /**
     * Helper to create an EditText with all the standard properties, used for new notes and loading.
     */
    private fun createNewEditText(initialText: String = ""): EditText { // MODIFIED: Changed default argument to empty string
        return EditText(context).apply {
            // Standard size for initial creation (will be overridden if loading saved data)
            layoutParams = LayoutParams(TEXT_BOX_WIDTH_PX, TEXT_BOX_HEIGHT_PX)

            hint = "Start typing..."
            setText(initialText) // Set the text
            background = createBorderDrawable()
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            setPadding(dpToPx(10), dpToPx(10), dpToPx(10), dpToPx(10))
            gravity = Gravity.TOP or Gravity.START
            isFocusableInTouchMode = true
            setOnClickListener { requestFocus() }
            setOnTouchListener(childTouchListener)
        }
    }

    /**
     * Gathers the current state of the canvas and all notes for saving.
     */
    fun getCurrentState(): CanvasState {
        val notes = mutableListOf<NoteData>()
        for (i in 0 until contentContainer.childCount) {
            val child = contentContainer.getChildAt(i)
            if (child is EditText) {
                notes.add(
                    NoteData(
                        text = child.text.toString(),
                        x = child.x,
                        y = child.y,
                        // FIX: Use measured dimensions, which are final size after layout
                        width = child.measuredWidth.coerceAtLeast(RESIZE_MIN_PX),
                        height = child.measuredHeight.coerceAtLeast(RESIZE_MIN_PX)
                    )
                )
            }
        }
        return CanvasState(
            notes = notes,
            scaleFactor = scaleFactor,
            translationX = contentContainer.translationX,
            translationY = contentContainer.translationY
        )
    }

    /**
     * Applies the loaded state to the canvas, recreating all notes.
     * This method is called from MainActivity.
     */
    fun applyState(state: CanvasState) {
        if (width == 0 || height == 0) {
            // View is not yet measured/laid out. Defer the state application.
            deferredCanvasState = state
            return
        }

        // If layout is ready, apply immediately
        applyDeferredState(state)
    }

    /**
     * Private worker function to actually apply the state when layout is guaranteed.
     */
    private fun applyDeferredState(state: CanvasState) {
        // 1. Clear any existing children
        contentContainer.removeAllViews()

        // 2. Apply canvas scale and pan position
        scaleFactor = state.scaleFactor
        contentContainer.scaleX = scaleFactor
        contentContainer.scaleY = scaleFactor
        contentContainer.translationX = state.translationX
        contentContainer.translationY = state.translationY

        // 3. Recreate notes from the saved data
        state.notes.forEach { noteData ->
            val newTextBox = createNewEditText(noteData.text).apply {
                // Apply saved dimensions and position
                layoutParams = LayoutParams(noteData.width, noteData.height)
                x = noteData.x
                y = noteData.y
            }
            contentContainer.addView(newTextBox)
        }
        invalidate()
    }
}
