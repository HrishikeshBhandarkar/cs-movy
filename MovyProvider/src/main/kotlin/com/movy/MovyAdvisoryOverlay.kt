package com.movy

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.lagradost.cloudstream3.ui.player.PlayerView
import java.lang.ref.WeakReference

class MovyAdvisoryOverlay private constructor(
    private val app: Application
) : Application.ActivityLifecycleCallbacks {

    private val handler = Handler(Looper.getMainLooper())
    private var activityRef: WeakReference<Activity>? = null
    private var card: View? = null
    private var overlayViews: OverlayViews? = null
    private var session: Any? = null

    private data class OverlayViews(
        val root: View,
        val ribbon: View,
        val copy: View,
        val rating: TextView,
        val reasons: TextView
    )

    override fun onActivityResumed(activity: Activity) {
        if (activityRef?.get() === activity && card?.parent != null) return
        detach()
        activityRef = WeakReference(activity)
        val newSession = Any()
        session = newSession
        searchForPlayer(activity, newSession, 0)
    }

    private fun searchForPlayer(activity: Activity, activeSession: Any, attempt: Int) {
        if (session !== activeSession) return
        if (activity.isFinishing || activity.isDestroyed) return

        val player = findPlayerView(activity.window.decorView)
        if (player != null) {
            attachToPlayer(player, activeSession)
            return
        }

        if (attempt >= MAX_POLL_ATTEMPTS) return
        handler.postAtTime(
            Runnable { searchForPlayer(activity, activeSession, attempt + 1) },
            activeSession,
            SystemClock.uptimeMillis() + POLL_INTERVAL_MS
        )
    }

    private fun findPlayerView(view: View): PlayerView? {
        if (view is PlayerView && view.isAttachedToWindow) return view
        if (view is ViewGroup) {
            for (index in view.childCount - 1 downTo 0) {
                val found = findPlayerView(view.getChildAt(index))
                if (found != null) return found
            }
        }
        return null
    }

    private fun attachToPlayer(player: PlayerView, activeSession: Any) {
        if (session !== activeSession) return
        if (card?.parent === player) return

        removeExistingCard()

        val overlay = buildOverlay(player.context)
        val root = overlay.root
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START
        ).apply {
            marginStart = dp(player.context, START_INSET_DP)
            topMargin = dp(player.context, TOP_INSET_DP)
        }

        player.addView(root, params)
        card = root
        overlayViews = overlay
        Log.d(TAG, "Attached demo advisory overlay")

        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit

            override fun onViewDetachedFromWindow(v: View) {
                if (card === v) cleanupCard()
            }
        })

        var started = false
        root.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                v: View,
                left: Int,
                top: Int,
                right: Int,
                bottom: Int,
                oldLeft: Int,
                oldTop: Int,
                oldRight: Int,
                oldBottom: Int
            ) {
                if (started || bottom - top <= 0) return
                started = true
                v.removeOnLayoutChangeListener(this)
                syncRibbonHeight(overlay)
                v.post { startSequence(overlay, activeSession) }
            }
        })
    }

    private fun syncRibbonHeight(overlay: OverlayViews) {
        if (overlay.copy.height <= 0) return
        val height = overlay.copy.height.coerceAtLeast(dp(overlay.copy.context, RIBBON_MIN_HEIGHT_DP))
        val params = overlay.ribbon.layoutParams as? LinearLayout.LayoutParams ?: return
        if (params.height == height) return
        params.height = height
        overlay.ribbon.layoutParams = params
    }

    private fun ribbonHeight(overlay: OverlayViews): Float =
        maxOf(
            overlay.ribbon.height,
            overlay.copy.height,
            dp(overlay.copy.context, RIBBON_MIN_HEIGHT_DP)
        ).toFloat()

    private fun startSequence(overlay: OverlayViews, activeSession: Any) {
        val root = overlay.root
        if (!isActive(root, activeSession)) return
        syncRibbonHeight(overlay)
        if (overlay.ribbon.height <= 0 || overlay.rating.width <= 0 || overlay.reasons.width <= 0) {
            handler.postAtTime(
                Runnable { startSequence(overlay, activeSession) },
                activeSession,
                SystemClock.uptimeMillis() + 16L
            )
            return
        }

        handler.postAtTime(
            Runnable {
                if (!isActive(root, activeSession)) return@Runnable
                enter(overlay)
                handler.postAtTime(
                    Runnable {
                        if (!isActive(root, activeSession)) return@Runnable
                        exit(overlay)
                        handler.postAtTime(
                            Runnable {
                                if (isActive(root, activeSession)) removeCard(root)
                            },
                            activeSession,
                            SystemClock.uptimeMillis() + (CYCLE_MS - EXIT_AT_MS)
                        )
                    },
                    activeSession,
                    SystemClock.uptimeMillis() + EXIT_AT_MS
                )
            },
            activeSession,
            SystemClock.uptimeMillis() + DELAY_MS
        )
    }

    private fun enter(overlay: OverlayViews) {
        overlay.ribbon.animate().cancel()
        overlay.rating.animate().cancel()
        overlay.reasons.animate().cancel()

        overlay.ribbon.pivotY = ribbonHeight(overlay)
        overlay.ribbon.scaleY = 0f
        overlay.ribbon.alpha = 0f

        overlay.rating.alpha = 1f
        overlay.reasons.alpha = 1f
        overlay.rating.translationX = -overlay.rating.width * MASK_TRAVEL
        overlay.reasons.translationX = -overlay.reasons.width * MASK_TRAVEL

        overlay.ribbon.animate()
            .scaleY(1f)
            .alpha(1f)
            .setDuration(BAR_IN_MS)
            .setInterpolator(IN_EASE)
            .start()

        overlay.rating.animate()
            .translationX(0f)
            .setStartDelay(RATING_IN_DELAY_MS)
            .setDuration(TEXT_SLIDE_MS)
            .setInterpolator(IN_EASE)
            .start()

        overlay.reasons.animate()
            .translationX(0f)
            .setStartDelay(DESCRIPTORS_IN_DELAY_MS)
            .setDuration(TEXT_SLIDE_MS)
            .setInterpolator(IN_EASE)
            .start()
    }

    private fun exit(overlay: OverlayViews) {
        overlay.ribbon.animate().cancel()
        overlay.rating.animate().cancel()
        overlay.reasons.animate().cancel()

        overlay.ribbon.pivotY = ribbonHeight(overlay)

        overlay.descriptorsExit()
        overlay.rating.animate()
            .translationX(-overlay.rating.width * MASK_TRAVEL)
            .setStartDelay(RATING_OUT_DELAY_MS)
            .setDuration(TEXT_RETRACT_MS)
            .setInterpolator(OUT_EASE)
            .withEndAction { overlay.rating.alpha = 0f }
            .start()

        overlay.ribbon.animate()
            .scaleY(0f)
            .alpha(0f)
            .setStartDelay(BAR_OUT_DELAY_MS)
            .setDuration(BAR_OUT_MS)
            .setInterpolator(OUT_EASE)
            .start()
    }

    private fun OverlayViews.descriptorsExit() {
        reasons.animate()
            .translationX(-reasons.width * MASK_TRAVEL)
            .setDuration(TEXT_RETRACT_MS)
            .setInterpolator(OUT_EASE)
            .withEndAction { reasons.alpha = 0f }
            .start()
    }

    private fun isActive(view: View, activeSession: Any): Boolean =
        session === activeSession &&
            card === view &&
            view.parent != null &&
            view.isAttachedToWindow

    private fun removeCard(view: View) {
        if (card !== view) return
        cancelAnimations()
        (view.parent as? ViewGroup)?.removeView(view)
        card = null
        overlayViews = null
    }

    private fun removeExistingCard() {
        val current = card ?: return
        card = null
        cancelAnimations()
        (current.parent as? ViewGroup)?.removeView(current)
        overlayViews = null
    }

    private fun cancelAnimations() {
        overlayViews?.let {
            it.ribbon.animate().cancel()
            it.rating.animate().cancel()
            it.reasons.animate().cancel()
        }
    }

    private fun cleanupCard() {
        val activeSession = session
        if (activeSession != null) handler.removeCallbacksAndMessages(activeSession)
        session = null
        removeExistingCard()
    }

    private fun detach() {
        val activeSession = session
        if (activeSession != null) handler.removeCallbacksAndMessages(activeSession)
        session = null
        activityRef = null
        removeExistingCard()
    }

    private fun buildOverlay(context: Context): OverlayViews {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }

        val ribbon = View(context).apply {
            setBackgroundColor(RIBBON_COLOR)
        }

        val copy = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = true
            clipToPadding = false
            setPadding(dp(context, TEXT_GAP_DP), 0, dp(context, 4), 0)
            isClickable = false
            isFocusable = false
        }

        val rating = TextView(context).apply {
            text = DEMO_RATING
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            includeFontPadding = false
            isSingleLine = true
            letterSpacing = 0.04f
            setShadowLayer(4f, 0f, 2f, Color.argb(230, 0, 0, 0))
        }

        val reasons = TextView(context).apply {
            text = DEMO_TEXT
            setTextColor(REASON_COLOR)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            includeFontPadding = false
            isSingleLine = true
            maxWidth = (context.resources.displayMetrics.widthPixels - dp(context, 96))
                .coerceAtLeast(dp(context, 180))
            setShadowLayer(3f, 0f, 1f, Color.argb(220, 0, 0, 0))
        }

        copy.addView(rating, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        copy.addView(reasons, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(context, 3)
        })

        root.addView(ribbon, LinearLayout.LayoutParams(dp(context, RIBBON_WIDTH_DP), 0))
        root.addView(copy, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        root.contentDescription = "Content advisory: $DEMO_RATING, $DEMO_TEXT"
        return OverlayViews(root, ribbon, copy, rating, reasons)
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) {
        if (activityRef?.get() === activity) detach()
    }

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) {
        if (activityRef?.get() === activity) detach()
    }

    companion object {
        private const val TAG = "MovyAdvisoryOverlay"
        private const val DELAY_MS = 0L
        private const val EXIT_AT_MS = 4_500L
        private const val CYCLE_MS = 5_220L
        private const val BAR_IN_MS = 350L
        private const val TEXT_SLIDE_MS = 460L
        private const val RATING_IN_DELAY_MS = 200L
        private const val DESCRIPTORS_IN_DELAY_MS = 260L
        private const val TEXT_RETRACT_MS = 390L
        private const val RATING_OUT_DELAY_MS = 60L
        private const val BAR_OUT_DELAY_MS = 380L
        private const val BAR_OUT_MS = 340L
        private const val RIBBON_WIDTH_DP = 4
        private const val RIBBON_MIN_HEIGHT_DP = 44
        private const val START_INSET_DP = 48
        private const val TOP_INSET_DP = 42
        private const val TEXT_GAP_DP = 14
        private const val MASK_TRAVEL = 1.08f
        private const val POLL_INTERVAL_MS = 250L
        private const val MAX_POLL_ATTEMPTS = 40
        private const val DEMO_RATING = "RATED A"
        private const val DEMO_TEXT = "self-harm, sex, violence, substances, suicide, tobacco use"
        private val RIBBON_COLOR = Color.parseColor("#E50914")
        private val REASON_COLOR = Color.rgb(179, 179, 179)
        private val IN_EASE = PathInterpolator(0.16f, 1f, 0.3f, 1f)
        private val OUT_EASE = PathInterpolator(0.65f, 0f, 0.35f, 1f)

        @Volatile
        private var instance: MovyAdvisoryOverlay? = null

        fun install(context: Context) {
            val application = context.applicationContext as? Application ?: return
            if (instance != null) return
            synchronized(MovyAdvisoryOverlay::class.java) {
                if (instance != null) return
                val overlay = MovyAdvisoryOverlay(application)
                application.registerActivityLifecycleCallbacks(overlay)
                instance = overlay
            }
        }

        fun release() {
            val current = synchronized(MovyAdvisoryOverlay::class.java) {
                instance.also { instance = null }
            } ?: return
            current.detach()
            current.app.unregisterActivityLifecycleCallbacks(current)
        }
    }
}

