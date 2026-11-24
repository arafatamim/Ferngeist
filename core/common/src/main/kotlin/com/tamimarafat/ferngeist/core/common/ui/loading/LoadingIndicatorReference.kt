package com.tamimarafat.ferngeist.core.common.ui.loading

/**
 * Size and turn of each beat, fitted frame by frame to the Material 3 loading-indicator spec
 * video for the default seven-shape sequence. Tables are indexed by shape and wrap for custom
 * sequences, so those only inherit the average feel.
 * shortcut: values are per-shape fits to one video, re-fit if the spec video changes.
 */
internal object LoadingIndicatorReference {
    /** Container fraction covered by the side of each shape's unit-area outline. */
    private val shapeScales = floatArrayOf(0.572f, 0.6098f, 0.5893f, 0.5808f, 0.5779f, 0.6346f, 0.5885f)

    /** Radial velocity impulse of every beat's pop; the mean of the per-shape fits, which scored the same. */
    const val POP_IMPULSE = 1.57f

    /**
     * Turn of the morph leaving each shape. The last beat turns half a SoftBurst lobe further, so
     * each seven-beat loop (1008 degrees with the global spin) closes on the SoftBurst it began with.
     */
    private val turnAngles = floatArrayOf(90f, 90f, 90f, 90f, 90f, 90f, 108f)

    /** Size at [progress] between shape [index] and the next; overshoot extends past the next. */
    fun level(
        index: Int,
        progress: Float,
        shapeCount: Int,
    ): Float {
        val from = shapeScales[index % shapeScales.size]
        val to = shapeScales[(index + 1) % shapeCount % shapeScales.size]
        return from + (to - from) * progress
    }

    fun turnDegrees(index: Int): Float = turnAngles[index % turnAngles.size]
}
