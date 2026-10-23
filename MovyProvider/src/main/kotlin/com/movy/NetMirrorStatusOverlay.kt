package com.movy

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.lagradost.cloudstream3.CommonActivity
import java.lang.ref.WeakReference

class NetMirrorStatusOverlay private constructor(
    private val app: Application
) : Application.ActivityLifecycleCallbacks {

    private val handler = Handler(Looper.getMainLooper())
    private var activityRef: WeakReference<Activity>? = null
    private var session: Any? = null
    private var button: TextView? = null
    private var renderedStatus: Status? = null

    private enum class Status {
        LOGIN,
        VERIFY,
        READY
    }

    private val watchRunnable = object : Runnable {
        override fun run() {
            val activeSession = session ?: return
            val activity = activityRef?.get()
            if (activity == null || activity.isFinishing || activity.isDestroyed) return

            val homeRoot = activity.findViewById<ViewGroup>(
                activity.resources.getIdentifier("home_root", "id", activity.packageName)
            )

            if (homeRoot != null && homeRoot.isShown) {
                ensureButton(homeRoot)
                updateButton()
            } else {
                hideButton()
            }

            handler.postAtTime(
                this,
                activeSession,
                SystemClock.uptimeMillis() + WATCH_INTERVAL_MS
            )
        }
    }

    private fun ensureButton(parent: ViewGroup) {
        if (button?.parent === parent) return
        hideButton()

        val context = parent.context
        val view = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(context, 12), dp(context, 7), dp(context, 12), dp(context, 7))
            elevation = dp(context, 6).toFloat()
            isClickable = true
            isFocusable = true
            setOnClickListener {
                NetMirrorSettings.openManageDialog(context)
            }
        }
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END
        ).apply {
            bottomMargin = dp(context, 84)
            marginEnd = dp(context, 16)
        }
        parent.addView(view, params)
        view.bringToFront()
        button = view
        renderedStatus = null
    }

    private fun updateButton() {
        val view = button ?: return
        val status = currentStatus()
        if (status == renderedStatus) return
        renderedStatus = status
        val context = view.context
        view.text = when (status) {
            Status.LOGIN -> "NetMirror: Login"
            Status.VERIFY -> "NetMirror: Verify"
            Status.READY -> "NetMirror: Ready"
        }
        view.setTextColor(
            when (status) {
                Status.VERIFY -> Color.rgb(26, 26, 26)
                else -> Color.WHITE
            }
        )
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, 24).toFloat()
            setColor(
                when (status) {
                    Status.LOGIN -> Color.rgb(179, 38, 30)
                    Status.VERIFY -> Color.rgb(249, 168, 37)
                    Status.READY -> Color.rgb(27, 94, 32)
                }
            )
        }
    }

    private fun currentStatus(): Status {
        val cookies = NetMirrorStore.cookies()
        val token = cookies["user_token"]
        val hash = cookies["t_hash_t"]
        return when {
            token.isNullOrBlank() -> Status.LOGIN
            hash?.contains("::ni::") == true -> Status.VERIFY
            else -> Status.READY
        }
    }

    private fun hideButton() {
        val view = button ?: return
        (view.parent as? ViewGroup)?.removeView(view)
        button = null
        renderedStatus = null
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private fun attach(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (activityRef?.get() === activity && session != null) return
        detach()
        activityRef = WeakReference(activity)
        val activeSession = Any()
        session = activeSession
        handler.postAtTime(watchRunnable, activeSession, SystemClock.uptimeMillis())
    }

    override fun onActivityResumed(activity: Activity) {
        attach(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        if (activityRef?.get() === activity) detach()
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (activityRef?.get() === activity) detach()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    private fun detach() {
        session = null
        activityRef = null
        handler.removeCallbacks(watchRunnable)
        hideButton()
    }

    companion object {
        private const val WATCH_INTERVAL_MS = 1000L

        @Volatile
        private var instance: NetMirrorStatusOverlay? = null

        fun install(context: Context) {
            val application = context.applicationContext as? Application ?: return
            if (instance != null) return
            synchronized(NetMirrorStatusOverlay::class.java) {
                if (instance != null) return
                val overlay = NetMirrorStatusOverlay(application)
                application.registerActivityLifecycleCallbacks(overlay)
                instance = overlay
                val current = context.findActivity()
                    ?: runCatching { CommonActivity.activity }.getOrNull()
                if (current != null) {
                    overlay.handler.post { overlay.attach(current) }
                }
            }
        }

        fun release() {
            val current = synchronized(NetMirrorStatusOverlay::class.java) {
                instance.also { instance = null }
            } ?: return
            current.detach()
            current.app.unregisterActivityLifecycleCallbacks(current)
        }
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}
