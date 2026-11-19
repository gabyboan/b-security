package com.bsecurity.app

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.ceil
import kotlin.math.max

/** A persistent bottom bar. Keep the same instance when changing page content. */
class SecurityNavigation @JvmOverloads constructor(
    context: Context,
    private val onSelect: (Int) -> Unit = {},
) : FrameLayout(context) {
    private val ink = Color.rgb(21, 32, 43)
    private val muted = Color.rgb(95, 108, 123)
    private val inset = dp(4).toFloat()
    private val indicator = RectF()
    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink }
    private val clipPath = Path()
    private val items = mutableListOf<FrameLayout>()
    private var selectedIndex = 0
    private var indicatorLeft = inset
    private var indicatorAnimator: ValueAnimator? = null
    private var indicatorReady = false

    private val labels = intArrayOf(
        R.string.nav_status, R.string.nav_network, R.string.nav_gps,
        R.string.nav_control, R.string.nav_family,
    )
    private val ids = intArrayOf(
        R.id.nav_status, R.id.nav_network, R.id.nav_gps,
        R.id.nav_control, R.id.nav_family,
    )
    private val icons = intArrayOf(
        R.drawable.nav_status_icon, R.drawable.nav_network_icon, R.drawable.nav_gps_icon,
        R.drawable.nav_control_icon, R.drawable.nav_family_icon,
    )

    // White artwork is clipped to the moving dark pill. This keeps every label visible
    // throughout a transition, including when the user rapidly changes direction.
    private val selectedArtwork = object : FrameLayout(context) {
        override fun dispatchDraw(canvas: Canvas) {
            if (indicator.isEmpty) return
            val save = canvas.save()
            clipPath.reset()
            clipPath.addRoundRect(indicator, dp(18).toFloat(), dp(18).toFloat(), Path.Direction.CW)
            canvas.clipPath(clipPath)
            super.dispatchDraw(canvas)
            canvas.restoreToCount(save)
        }
    }

    init {
        setWillNotDraw(false)
        val labelPaint = Paint().apply {
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics)
        }
        minimumHeight = max(dp(72), dp(48) + ceil(labelPaint.fontSpacing).toInt())
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(22).toFloat()
            setStroke(dp(1), Color.rgb(225, 229, 232))
        }
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        addView(makeRow(false), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        selectedArtwork.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        selectedArtwork.isClickable = false
        selectedArtwork.isFocusable = false
        selectedArtwork.addView(makeRow(true), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(selectedArtwork, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        updateSelectedState()
    }

    /** Updates selection without invoking [onSelect]. */
    fun select(index: Int, animate: Boolean = true) {
        require(index in labels.indices) { "Unknown navigation index: $index" }
        if (selectedIndex == index && indicatorAnimator != null && animate) return
        selectedIndex = index
        updateSelectedState()
        indicatorAnimator?.cancel()
        indicatorAnimator = null
        if (width <= 0) {
            indicatorReady = false
            return
        }
        val destination = targetLeft(index)
        if (!animate || !indicatorReady || !isAttachedToWindow || !ValueAnimator.areAnimatorsEnabled()
            || kotlin.math.abs(destination - indicatorLeft) < 0.5f
        ) {
            indicatorLeft = destination
            indicatorReady = true
            refreshIndicator()
            return
        }
        indicatorAnimator = ValueAnimator.ofFloat(indicatorLeft, destination).apply {
            duration = 420L
            interpolator = DecelerateInterpolator(1.5f)
            addUpdateListener {
                indicatorLeft = it.animatedValue as Float
                refreshIndicator()
            }
            start()
        }
    }

    /** Stops motion and leaves a complete, selected state for backgrounding or reuse. */
    fun dispose() {
        indicatorAnimator?.cancel()
        indicatorAnimator = null
        if (width > 0) {
            indicatorLeft = targetLeft(selectedIndex)
            indicatorReady = true
            refreshIndicator()
        }
    }

    override fun onDetachedFromWindow() {
        dispose()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Rotation, insets, and font-size changes must not retain an old pixel target.
        dispose()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!indicator.isEmpty) canvas.drawRoundRect(indicator, dp(18).toFloat(), dp(18).toFloat(), indicatorPaint)
    }

    @Suppress("DEPRECATION") // The obtain APIs retain Android 8 compatibility.
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.collectionInfo = AccessibilityNodeInfo.CollectionInfo.obtain(
            1, labels.size, false, AccessibilityNodeInfo.CollectionInfo.SELECTION_MODE_SINGLE,
        )
    }

    private fun makeRow(white: Boolean) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(inset.toInt(), inset.toInt(), inset.toInt(), inset.toInt())
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        labels.forEachIndexed { index, label ->
            val item = FrameLayout(context).apply {
                minimumHeight = dp(48)
                if (white) {
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                } else {
                    id = ids[index]
                    isClickable = true
                    isFocusable = true
                    contentDescription = context.getString(R.string.nav_description, context.getString(label), index + 1, labels.size)
                    foreground = RippleDrawable(
                        ColorStateList.valueOf(0x1F5F6C7B), null,
                        GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(18).toFloat() },
                    )
                    accessibilityDelegate = object : AccessibilityDelegate() {
                        @Suppress("DEPRECATION") // The builder alternative requires Android 13.
                        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                            super.onInitializeAccessibilityNodeInfo(host, info)
                            info.className = "android.widget.Button"
                            info.collectionItemInfo = AccessibilityNodeInfo.CollectionItemInfo.obtain(
                                0, 1, index, 1, false, host.isSelected,
                            )
                        }
                    }
                    setOnClickListener { onSelect(index) }
                    items += this
                }
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(dp(2), dp(8), dp(2), dp(8))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    addView(ImageView(context).apply {
                        setImageResource(icons[index])
                        imageTintList = ColorStateList.valueOf(if (white) Color.WHITE else muted)
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }, LinearLayout.LayoutParams(dp(23), dp(23)))
                    addView(TextView(context).apply {
                        text = context.getString(label)
                        textSize = 12f
                        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                        setTextColor(if (white) Color.WHITE else muted)
                        gravity = Gravity.CENTER
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        includeFontPadding = false
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(5)
                    })
                }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            }
            addView(item, LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }
    }

    private fun updateSelectedState() {
        items.forEachIndexed { index, item ->
            item.isSelected = index == selectedIndex
            item.isActivated = index == selectedIndex
        }
    }

    private fun targetLeft(index: Int): Float {
        val slot = (width - 2 * inset).coerceAtLeast(0f) / labels.size
        val visualIndex = if (layoutDirection == View.LAYOUT_DIRECTION_RTL) labels.lastIndex - index else index
        return inset + visualIndex * slot
    }

    private fun refreshIndicator() {
        val slot = (width - 2 * inset).coerceAtLeast(0f) / labels.size
        indicator.set(indicatorLeft, inset, indicatorLeft + slot, (height - inset).coerceAtLeast(inset))
        invalidate()
        selectedArtwork.invalidate()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}
