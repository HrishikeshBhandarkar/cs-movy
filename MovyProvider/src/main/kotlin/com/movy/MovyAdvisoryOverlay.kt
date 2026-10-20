package com.movy

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
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
    private var session: Any? = null

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

        val badge = buildBadge(player.context)
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START
        ).apply {
            marginStart = dp(player.context, 36)
            topMargin = dp(player.context, 36)
        }

        badge.alpha = 0f
        player.addView(badge, params)
        card = badge
        Log.d(TAG, "Attached demo advisory overlay")

        badge.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit

            override fun onViewDetachedFromWindow(v: View) {
                if (card === v) cleanupCard()
            }
        })

        var started = false
        badge.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
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
                startSequence(v, activeSession)
            }
        })
    }

    private fun startSequence(view: View, activeSession: Any) {
        if (!isActive(view, activeSession)) return

        handler.postAtTime(
            Runnable {
                if (!isActive(view, activeSession)) return@Runnable
                enter(view)
                handler.postAtTime(
                    Runnable {
                        if (!isActive(view, activeSession)) return@Runnable
                        exit(view)
                        handler.postAtTime(
                            Runnable {
                                if (isActive(view, activeSession)) removeCard(view)
                            },
                            activeSession,
                            SystemClock.uptimeMillis() + SETTLE_MS
                        )
                    },
                    activeSession,
                    SystemClock.uptimeMillis() + HOLD_MS
                )
            },
            activeSession,
            SystemClock.uptimeMillis() + DELAY_MS
        )
    }

    private fun enter(view: View) {
        view.animate().cancel()
        val height = view.height.toFloat().coerceAtLeast(1f)
        view.alpha = 0f
        view.translationY = height / 2f
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(ANIMATION_MS)
            .setInterpolator(LinearInterpolator())
            .start()
    }

    private fun exit(view: View) {
        view.animate().cancel()
        val height = view.height.toFloat().coerceAtLeast(1f)
        view.animate()
            .alpha(0f)
            .translationY(height)
            .setDuration(ANIMATION_MS)
            .setInterpolator(LinearInterpolator())
            .start()
    }

    private fun isActive(view: View, activeSession: Any): Boolean =
        session === activeSession &&
            card === view &&
            view.parent != null &&
            view.isAttachedToWindow

    private fun removeCard(view: View) {
        if (card !== view) return
        view.animate().cancel()
        (view.parent as? ViewGroup)?.removeView(view)
        card = null
    }

    private fun removeExistingCard() {
        val current = card ?: return
        card = null
        current.animate().cancel()
        (current.parent as? ViewGroup)?.removeView(current)
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

    private fun buildBadge(context: Context): View {
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 12), dp(context, 8), dp(context, 14), dp(context, 8))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(context, 6).toFloat()
                setColor(Color.argb(224, 18, 18, 18))
                setStroke(dp(context, 1), Color.argb(64, 255, 255, 255))
            }
            elevation = dp(context, 6).toFloat()
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }

        val rating = TextView(context).apply {
            text = DEMO_RATING
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(dp(context, 7), dp(context, 2), dp(context, 7), dp(context, 2))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(context, 3).toFloat()
                setColor(Color.TRANSPARENT)
                setStroke(dp(context, 1), Color.argb(220, 255, 255, 255))
            }
        }

        val message = TextView(context).apply {
            text = DEMO_TEXT
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            includeFontPadding = false
            maxLines = 2
        }

        card.addView(rating, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        card.addView(message, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            marginStart = dp(context, 10)
        })
        card.contentDescription = "Content advisory: $DEMO_RATING, $DEMO_TEXT"
        return card
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
        private const val DELAY_MS = 1_500L
        private const val HOLD_MS = 8_000L
        private const val ANIMATION_MS = 700L
        private const val SETTLE_MS = 1_400L
        private const val POLL_INTERVAL_MS = 250L
        private const val MAX_POLL_ATTEMPTS = 40
        private const val DEMO_RATING = "16"
        private const val DEMO_TEXT = "Violence, Language"

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

