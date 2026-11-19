package com.bsecurity.app

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** A short local presentation, independent of network access and application initialization. */
class BrandedLaunchView(context: Context) : FrameLayout(context) {
    private val handler = Handler(Looper.getMainLooper())
    private val introduction = LinearLayout(context)
    private val loading = LoadingLine(context)
    private var entry: AnimatorSet? = null
    private var exit: ObjectAnimator? = null
    private var completion: (() -> Unit)? = null
    private var started = false
    private var finished = false
    private var disposed = false
    private val beginExit = Runnable { fadeOut() }
    private val finish = Runnable { finishImmediately() }

    init {
        id = R.id.splash_screen
        setBackgroundColor(Color.rgb(244, 244, 242))
        isClickable = true
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES

        introduction.orientation = LinearLayout.VERTICAL
        introduction.gravity = Gravity.CENTER
        introduction.setPadding(dp(24), dp(24), dp(24), dp(24))
        addView(introduction, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        introduction.addView(ImageView(context).apply {
            setImageResource(R.drawable.bsoftware_mark)
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(128), dp(128)).apply { bottomMargin = dp(2) })

        introduction.addView(TextView(context).apply {
            id = R.id.splash_brand
            text = context.getString(R.string.splash_brand)
            textSize = 38f
            setTextColor(Color.rgb(17, 17, 17))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = -.045f
            gravity = Gravity.CENTER
            includeFontPadding = false
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        introduction.addView(TextView(context).apply {
            text = context.getString(R.string.splash_product)
            textSize = 13f
            setTextColor(Color.rgb(95, 108, 123))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = .1f
            gravity = Gravity.CENTER
            includeFontPadding = false
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
            bottomMargin = dp(36)
        })

        loading.id = R.id.splash_loading
        loading.contentDescription = context.getString(R.string.splash_loading)
        introduction.addView(loading, LinearLayout.LayoutParams(dp(144), dp(3)))

        introduction.addView(TextView(context).apply {
            text = context.getString(R.string.splash_loading)
            textSize = 11f
            setTextColor(Color.rgb(95, 108, 123))
            gravity = Gravity.CENTER
            includeFontPadding = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(12)
        })
    }

    /** Call once, on the main thread, after attaching the view. */
    fun start(onFinished: () -> Unit) {
        if (started || disposed) return
        started = true
        completion = onFinished
        if (finished || !ValueAnimator.areAnimatorsEnabled()) {
            finishImmediately()
            return
        }

        introduction.alpha = 0f
        introduction.translationY = dp(12).toFloat()
        entry = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(introduction, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(introduction, View.TRANSLATION_Y, dp(12).toFloat(), 0f)
            )
            duration = 700L
            interpolator = DecelerateInterpolator()
            start()
        }
        loading.start()
        handler.postDelayed(beginExit, 2400L)
        // Completion also has a clock deadline: cancellation cannot strand the launch screen.
        handler.postDelayed(finish, 2900L)
    }

    /** Stops the presentation and delivers its completion at most once. */
    fun finishImmediately() {
        if (disposed) return
        finished = true
        stopAnimations()
        val action = completion
        completion = null
        action?.invoke()
    }

    /** Releases the view during activity destruction, without invoking application callbacks. */
    fun dispose() {
        if (disposed) return
        disposed = true
        finished = true
        completion = null
        stopAnimations()
    }

    override fun onDetachedFromWindow() {
        dispose()
        super.onDetachedFromWindow()
    }

    private fun fadeOut() {
        if (finished || disposed) return
        if (!ValueAnimator.areAnimatorsEnabled()) {
            finishImmediately()
            return
        }
        exit = ObjectAnimator.ofFloat(this, View.ALPHA, alpha, 0f).apply {
            duration = 400L
            start()
        }
        handler.removeCallbacks(finish)
        handler.postDelayed(finish, 400L)
    }

    private fun stopAnimations() {
        handler.removeCallbacks(beginExit)
        handler.removeCallbacks(finish)
        entry?.cancel()
        exit?.cancel()
        entry = null
        exit = null
        loading.stop()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()

    private class LoadingLine(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var progress = 0f
        private var animator: ValueAnimator? = null

        fun start() {
            stop()
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1800L
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener {
                    progress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        fun stop() {
            animator?.cancel()
            animator?.removeAllUpdateListeners()
            animator = null
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val radius = height / 2f
            paint.color = Color.rgb(218, 223, 224)
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, paint)
            val length = width * .36f
            val left = (width + length) * progress - length
            canvas.save()
            canvas.clipRect(0, 0, width, height)
            paint.color = Color.rgb(21, 32, 43)
            canvas.drawRoundRect(left, 0f, left + length, height.toFloat(), radius, radius, paint)
            canvas.restore()
        }
    }
}
