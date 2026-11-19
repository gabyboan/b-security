package com.bsecurity.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Motion only represents an active reading. Counts come from the work, never a clock. */
class SecurityCheckView(
    context: Context,
    title: String,
    status: String,
    deviceCheck: Boolean = false,
) : LinearLayout(context) {
    private val ink = Color.rgb(21, 32, 43)
    private val muted = Color.rgb(95, 108, 123)
    private val radar = ReadingRadar(context)
    private val titleText = TextView(context)
    private val count = TextView(context)
    private val progress = ReadingProgress(context)
    val statusText = TextView(context)

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(20), dp(24), dp(20), dp(24))
        background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(22).toFloat()
            setStroke(dp(1), Color.rgb(216, 224, 232))
        }
        if (deviceCheck) id = R.id.security_check_progress

        addView(FrameLayout(context).apply {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            addView(radar, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            addView(ImageView(context).apply {
                setImageResource(R.drawable.bsoftware_mark)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }, FrameLayout.LayoutParams(dp(70), dp(70), Gravity.CENTER))
        }, LayoutParams(dp(156), dp(156)))

        addView(titleText.apply {
            text = title
            textSize = 19f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setTextColor(ink)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })

        statusText.apply {
            if (deviceCheck) id = R.id.security_check_status
            text = status
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(muted)
            setLineSpacing(dp(3).toFloat(), 1f)
            accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        }
        addView(statusText, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        count.apply {
            if (deviceCheck) id = R.id.security_check_count
            text = context.getString(R.string.security_check_reading)
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(ink)
            gravity = Gravity.CENTER
        }
        addView(count, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(24) })
        progress.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(progress, LayoutParams(LayoutParams.MATCH_PARENT, dp(4)).apply { topMargin = dp(9) })
        addView(TextView(context).apply {
            text = context.getString(R.string.security_check_result_detail)
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(muted)
            setLineSpacing(dp(2).toFloat(), 1f)
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
    }

    fun setProgress(completed: Int, total: Int) {
        if (total <= 0) {
            count.text = context.getString(R.string.security_check_reading)
            progress.ratio = 0f
            return
        }
        val done = completed.coerceIn(0, total)
        count.text = context.getString(R.string.security_check_count, done, total)
        progress.ratio = done.toFloat() / total
    }

    fun setActive(active: Boolean) { radar.setActive(active) }

    /** The outgoing card can finish its visual transition after the work has ended. */
    fun complete(success: Boolean, detail: String? = null) {
        radar.setActive(false)
        titleText.text = context.getString(if (success) R.string.security_check_complete else R.string.security_check_failed)
        statusText.text = detail ?: context.getString(R.string.security_check_ready)
    }

    fun dispose() { radar.setActive(false) }

    override fun onDetachedFromWindow() {
        dispose()
        super.onDetachedFromWindow()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()

    private class ReadingProgress(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        var ratio = 0f
            set(value) { field = value.coerceIn(0f, 1f); invalidate() }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val radius = height / 2f
            paint.color = Color.rgb(230, 234, 235)
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, paint)
            paint.color = Color.rgb(21, 32, 43)
            if (ratio > 0f) canvas.drawRoundRect(0f, 0f, width * ratio, height.toFloat(), radius, radius, paint)
        }
    }

    private class ReadingRadar(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bounds = RectF()
        private var phase = 0f
        private var active = false
        private var motion: ValueAnimator? = null

        fun setActive(value: Boolean) {
            active = value
            updateMotion()
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            updateMotion()
        }

        override fun onDetachedFromWindow() {
            stopMotion()
            super.onDetachedFromWindow()
        }

        override fun onWindowVisibilityChanged(visibility: Int) {
            super.onWindowVisibilityChanged(visibility)
            updateMotion()
        }

        private fun updateMotion() {
            if (!active || !isAttachedToWindow || windowVisibility != VISIBLE || !ValueAnimator.areAnimatorsEnabled()) {
                stopMotion()
                return
            }
            if (motion != null) return
            motion = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 2200L
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    phase = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        private fun stopMotion() {
            motion?.cancel()
            motion?.removeAllUpdateListeners()
            motion = null
            phase = 0f
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val radius = minOf(width, height) * .43f
            val density = resources.displayMetrics.density
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(244, 246, 245)
            canvas.drawCircle(cx, cy, radius - 12 * density, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = 1 * density
            paint.color = Color.rgb(228, 233, 232)
            canvas.drawCircle(cx, cy, radius, paint)
            canvas.drawCircle(cx, cy, radius - 7 * density, paint)

            bounds.set(cx - radius, cy - radius, cx + radius, cy + radius)
            paint.strokeWidth = 3 * density
            paint.color = Color.rgb(21, 32, 43)
            canvas.drawArc(bounds, phase * 360f - 90f, 76f, false, paint)
            paint.strokeWidth = 2 * density
            paint.color = Color.rgb(167, 181, 176)
            canvas.drawArc(bounds, phase * 360f + 80f, 35f, false, paint)

            val pulse = if (motion == null) .5f else phase
            paint.strokeWidth = 1 * density
            paint.color = Color.argb(((1f - pulse) * 48).toInt(), 21, 32, 43)
            canvas.drawCircle(cx, cy, radius + pulse * 9 * density, paint)
        }
    }
}
