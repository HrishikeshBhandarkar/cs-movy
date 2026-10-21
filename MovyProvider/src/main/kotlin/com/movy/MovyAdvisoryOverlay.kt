package com.movy

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
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
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Netflix-style content advisory overlay for the CloudStream player.
 *
 * Sequence:
 *  1. Main Content Advisory:
 *     - Dynamic accent ribbon scales in vertically
 *     - Rating ("RATED <badge>") and content reasons slide out of the mask
 *     - Holds, then retracts into the mask and collapses
 *  2. Statutory Anti-Tobacco Advisory (if item contains smoking/tobacco depictions):
 *     - Separate entity shown after main advisory closes
 *     - Clean single-line warning: "Consumption of tobacco products is injurious to health"
 *     - If interrupted, only the currently active advisory entity is reshowed
 *
 * Features real rating and descriptor fetching via TMDB and OMDB APIs with smart fallback.
 */
class MovyAdvisoryOverlay private constructor(
    private val app: Application
) : Application.ActivityLifecycleCallbacks {

    private val handler = Handler(Looper.getMainLooper())
    private var activityRef: WeakReference<Activity>? = null
    private var card: View? = null
    private var overlayViews: OverlayViews? = null
    private var session: Any? = null
    private var playerRef: WeakReference<ViewGroup>? = null

    private var mainState = AdvisoryState.NOT_SHOWN
    private var tobaccoState = AdvisoryState.NOT_SHOWN
    private var hasTobacco = true
    private var lastResolvedTitle: String? = null

    private var controlsVisible = false
    private var playbackRunning = false
    private var playbackAnchorMs = 0L
    private var lastPosition = -1L
    private var lastPositionAt = 0L
    private var playerFragment: Any? = null
    private var playerFragmentAt = 0L
    private var lastHeartbeatMs = 0L

    private var enterSet: AnimatorSet? = null
    private var exitSet: AnimatorSet? = null
    private var sequenceToken: Any? = null

    private var tobaccoEnterSet: AnimatorSet? = null
    private var tobaccoExitSet: AnimatorSet? = null
    private var tobaccoToken: Any? = null

    private enum class AdvisoryState {
        NOT_SHOWN,
        SHOWING,
        DONE,
        INTERRUPTED
    }

    private data class OverlayViews(
        val root: FrameLayout,
        val mainContainer: LinearLayout,
        val mainRibbon: RibbonView,
        val mainCopy: LinearLayout,
        val ratingRow: LinearLayout,
        val prefix: TextView,
        val rating: TextView,
        val reasons: TextView,
        val tobaccoContainer: LinearLayout,
        val tobaccoRibbon: RibbonView,
        val tobaccoCopy: LinearLayout,
        val tobaccoText: TextView,
        val vignette: View
    )

    private val watchRunnable = object : Runnable {
        override fun run() {
            val activeSession = session ?: return
            val activity = activityRef?.get()
            if (activity == null || activity.isFinishing || activity.isDestroyed) return

            val attached = playerRef?.get()
            if (attached != null && attached.isAttachedToWindow && attached.parent != null) {
                handler.postAtTime(this, activeSession, SystemClock.uptimeMillis() + WATCH_INTERVAL_MS)
                return
            }
            removeExistingCard()

            resolvePlayerFragment(activity)

            val fromFragment = playerViewFromFragment(playerFragment)
            val player = fromFragment ?: findPlayerView(activity.window.decorView)
            if (player != null) attachToPlayer(player, activeSession)

            val now = SystemClock.uptimeMillis()
            if (now - lastHeartbeatMs > HEARTBEAT_MS) {
                lastHeartbeatMs = now
                Log.i(
                    TAG,
                    "watch activity=${activity.javaClass.simpleName} " +
                        "fragment=${playerFragment?.javaClass?.simpleName} " +
                        "fromFragment=${fromFragment != null} found=${player != null} " +
                        "card=${card?.parent != null}"
                )
            }

            handler.postAtTime(this, activeSession, SystemClock.uptimeMillis() + WATCH_INTERVAL_MS)
        }
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            val activeSession = session ?: return
            val player = playerRef?.get()
            if (session !== activeSession || player == null || !player.isAttachedToWindow) return
            tick(player)
            handler.postAtTime(this, activeSession, SystemClock.uptimeMillis() + POLL_INTERVAL_MS)
        }
    }

    override fun onActivityResumed(activity: Activity) {
        Log.i(TAG, "onActivityResumed ${activity.javaClass.simpleName}")
        if (activityRef?.get() === activity && session != null) return
        detach()
        activityRef = WeakReference(activity)
        val newSession = Any()
        session = newSession
        handler.postAtTime(watchRunnable, newSession, SystemClock.uptimeMillis())
    }

    private fun findPlayerView(view: View): ViewGroup? {
        findMedia3PlayerView(view)?.let { return it }
        return findCloudStreamPlayerView(view)
    }

    private fun findCloudStreamPlayerView(view: View): ViewGroup? {
        if (view is ViewGroup && view.isAttachedToWindow && isPlayerView(view, CLOUDSTREAM_PLAYER)) {
            return view
        }
        if (view is ViewGroup) {
            for (index in view.childCount - 1 downTo 0) {
                val found = findCloudStreamPlayerView(view.getChildAt(index))
                if (found != null) return found
            }
        }
        return null
    }

    private fun findMedia3PlayerView(view: View): ViewGroup? {
        if (view is ViewGroup && view.isAttachedToWindow && isPlayerView(view, MEDIA3_PLAYER)) {
            return view
        }
        if (view is ViewGroup) {
            for (index in view.childCount - 1 downTo 0) {
                val found = findMedia3PlayerView(view.getChildAt(index))
                if (found != null) return found
            }
        }
        return null
    }

    private fun isPlayerView(view: View, className: String): Boolean {
        var type: Class<*>? = view.javaClass
        while (type != null) {
            if (type.name == className) return true
            type = type.superclass
        }
        return false
    }

    private fun attachToPlayer(player: ViewGroup, activeSession: Any) {
        if (session !== activeSession) return
        if (card?.parent === player) return

        removeExistingCard()

        val overlay = buildOverlay(player.context, player)
        val root = overlay.root
        val widthDp = playerWidthDp(player)
        val compact = widthDp <= COMPACT_WIDTH_DP
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START
        ).apply {
            marginStart = dp(player.context, if (compact) START_INSET_COMPACT_DP else START_INSET_DP)
            topMargin = dp(player.context, if (compact) TOP_INSET_COMPACT_DP else TOP_INSET_DP)
        }

        val vignetteParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START
        )

        overlay.vignette.elevation = 998f
        overlay.vignette.translationZ = 998f
        root.elevation = 999f
        root.translationZ = 999f

        player.addView(overlay.vignette, vignetteParams)
        player.addView(root, params)
        overlay.vignette.bringToFront()
        root.bringToFront()

        card = root
        overlayViews = overlay
        playerRef = WeakReference(player)

        val accentColor = getAccentColor(player.context)
        overlay.mainRibbon.setColor(accentColor)
        overlay.tobaccoRibbon.setColor(accentColor)

        Log.i(TAG, "attached to player ${player.javaClass.name} with accentColor=#${Integer.toHexString(accentColor)}")

        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit

            override fun onViewDetachedFromWindow(v: View) {
                if (card === v) cleanupCard()
            }
        })

        handler.postAtTime(pollRunnable, activeSession, SystemClock.uptimeMillis())

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
                if (bottom - top <= 0) return
                v.post {
                    syncRibbonHeight(overlay)
                    syncTobaccoRibbonHeight(overlay)
                    sizeVignette(overlay, player)
                }
            }
        })
    }

    private fun syncRibbonHeight(overlay: OverlayViews) {
        val height = overlay.mainCopy.height
        if (height <= 0) return
        val params = overlay.mainRibbon.layoutParams as? LinearLayout.LayoutParams ?: return
        if (params.height == height) return
        params.height = height
        overlay.mainRibbon.layoutParams = params
    }

    private fun syncTobaccoRibbonHeight(overlay: OverlayViews) {
        val height = overlay.tobaccoCopy.height
        if (height <= 0) return
        val params = overlay.tobaccoRibbon.layoutParams as? LinearLayout.LayoutParams ?: return
        if (params.height == height) return
        params.height = height
        overlay.tobaccoRibbon.layoutParams = params
    }

    private fun sizeVignette(overlay: OverlayViews, player: ViewGroup) {
        val context = overlay.vignette.context
        val playerWidth = player.width
        val playerHeight = player.height
        if (playerWidth <= 0 || playerHeight <= 0) return

        var width = (playerWidth * VIGNETTE_WIDTH_RATIO).toInt()
        var height = (width * VIGNETTE_ASPECT).toInt()

        val activeContentWidth = maxOf(overlay.mainContainer.width, overlay.tobaccoContainer.width)
        val needed = activeContentWidth + dp(context, 48)
        if (width < needed) {
            val scale = needed.toFloat() / width
            width = needed
            height = (height * scale).toInt()
        }
        val maxHeight = (playerHeight * VIGNETTE_MAX_HEIGHT_RATIO).toInt()
        if (height > maxHeight && height > 0) {
            val scale = maxHeight.toFloat() / height
            width = (width * scale).toInt()
            height = maxHeight
        }

        val params = overlay.vignette.layoutParams as? FrameLayout.LayoutParams ?: return
        if (params.width == width && params.height == height) return
        params.width = width
        params.height = height
        overlay.vignette.layoutParams = params
    }

    private fun ribbonHeight(overlay: OverlayViews): Float {
        val h = maxOf(overlay.mainRibbon.height, overlay.mainCopy.height)
        return if (h > 0) h.toFloat() else dp(overlay.mainCopy.context, 40).toFloat()
    }

    private fun maskTravel(overlay: OverlayViews): Float {
        val w = maxOf(overlay.ratingRow.width, overlay.reasons.width)
        val width = if (w > 0) w.toFloat() else dp(overlay.mainCopy.context, 280).toFloat()
        return MASK_TRAVEL * width
    }

    private fun tobaccoRibbonHeight(overlay: OverlayViews): Float {
        val h = maxOf(overlay.tobaccoRibbon.height, overlay.tobaccoCopy.height)
        return if (h > 0) h.toFloat() else dp(overlay.tobaccoCopy.context, 24).toFloat()
    }

    private fun tobaccoMaskTravel(overlay: OverlayViews): Float {
        val w = overlay.tobaccoText.width
        val width = if (w > 0) w.toFloat() else dp(overlay.tobaccoCopy.context, 320).toFloat()
        return MASK_TRAVEL * width
    }

    // ---------------------------------------------------------------------
    // Playback / controls polling & State Machine
    // ---------------------------------------------------------------------

    private fun tick(player: ViewGroup) {
        val now = SystemClock.uptimeMillis()

        resolvePlayerFragment(activityRef?.get())
        val fragment = playerFragment

        val running = isPlaybackRunning(player, fragment)
        if (running != playbackRunning) {
            playbackRunning = running
            if (running) playbackAnchorMs = now
            Log.i(TAG, "playback running changed: $running (anchor=$playbackAnchorMs)")
        }

        // Reset state on seek back to start or new video
        val currentPos = playerPosition(player)
        if (currentPos != null && currentPos < 5000L && lastPosition > 15000L) {
            Log.i(TAG, "playback restarted: resetting advisory states")
            mainState = AdvisoryState.NOT_SHOWN
            tobaccoState = AdvisoryState.NOT_SHOWN
        }

        val uiVisible = isPlayerUiVisible(fragment, player)
        if (uiVisible) {
            if (!controlsVisible) {
                controlsVisible = true
                if (mainState == AdvisoryState.SHOWING) {
                    stopAnimations()
                    hideAdvisoryViews()
                    mainState = AdvisoryState.INTERRUPTED
                    Log.i(TAG, "player controls shown, main advisory interrupted")
                }
                if (tobaccoState == AdvisoryState.SHOWING) {
                    stopTobaccoAnimations()
                    hideTobaccoViews()
                    tobaccoState = AdvisoryState.INTERRUPTED
                    Log.i(TAG, "player controls shown, tobacco advisory interrupted")
                }
            }
            return
        }

        if (controlsVisible) {
            controlsVisible = false
            Log.i(TAG, "player controls hidden, advisory execution can proceed")
        }

        // Step 1: Main Advisory plays first
        if (mainState != AdvisoryState.DONE) {
            if (mainState == AdvisoryState.NOT_SHOWN || mainState == AdvisoryState.INTERRUPTED) {
                if (canStart(now)) {
                    startSequence()
                }
            }
        } else if (hasTobacco && tobaccoState != AdvisoryState.DONE) {
            // Step 2: Tobacco Advisory plays second as separate entity!
            // If interrupted, ONLY this reshows!
            if (tobaccoState == AdvisoryState.NOT_SHOWN || tobaccoState == AdvisoryState.INTERRUPTED) {
                if (canStart(now)) {
                    startTobaccoSequence()
                }
            }
        }

        if (now - lastHeartbeatMs > HEARTBEAT_MS) {
            lastHeartbeatMs = now
            Log.i(
                TAG,
                "heartbeat status=${playerStatusName(fragment)} exoPlaying=${exoPlayerIsPlaying(player)} " +
                    "pos=$currentPos uiShowing=$uiVisible mainState=$mainState " +
                    "tobaccoState=$tobaccoState hasTobacco=$hasTobacco running=$playbackRunning canStart=${canStart(now)}"
            )
        }
    }

    private fun canStart(now: Long): Boolean =
        playbackRunning && (now - playbackAnchorMs) >= START_DELAY_MS

    private fun isPlaybackRunning(player: ViewGroup, fragment: Any?): Boolean {
        val status = playerStatusName(fragment)
        if (status == "IsPlaying") return true
        if (status in listOf("IsBuffering", "IsPaused", "IsEnded")) return false
        if (fragmentIsPlaying(fragment)) return true
        if (exoPlayerIsPlaying(player)) return true
        return positionIsAdvancing(player)
    }

    private fun fragmentIsPlaying(fragment: Any?): Boolean {
        if (fragment == null) return false
        return try {
            val cs3Player = invokeDeclared(fragment, "getPlayer") ?: return false
            val method = cs3Player.javaClass.getMethod("getIsPlaying").apply { isAccessible = true }
            (method.invoke(cs3Player) as? Boolean) ?: false
        } catch (t: Throwable) {
            false
        }
    }

    private fun getMedia3Player(player: ViewGroup): Any? = try {
        player.javaClass.getMethod("getPlayer").invoke(player)
    } catch (t: Throwable) {
        null
    }

    private fun exoPlayerIsPlaying(player: ViewGroup): Boolean {
        val exo = getMedia3Player(player) ?: return false
        try {
            val playerClass = Class.forName("androidx.media3.common.Player")
            val isPlaying = playerClass.getMethod("isPlaying").invoke(exo) as? Boolean
            if (isPlaying != null) return isPlaying
        } catch (t: Throwable) { }
        try {
            val playerClass = Class.forName("androidx.media3.common.Player")
            val pwr = playerClass.getMethod("getPlayWhenReady").invoke(exo) as? Boolean ?: false
            val pState = playerClass.getMethod("getPlaybackState").invoke(exo) as? Int ?: 0
            if (pwr && pState == 3) return true
        } catch (t: Throwable) { }
        try {
            val playerClass = Class.forName("com.google.android.exoplayer2.Player")
            val isPlaying = playerClass.getMethod("isPlaying").invoke(exo) as? Boolean
            if (isPlaying != null) return isPlaying
        } catch (t: Throwable) { }
        try {
            val method = exo.javaClass.getMethod("isPlaying").apply { isAccessible = true }
            return (method.invoke(exo) as? Boolean) ?: false
        } catch (t: Throwable) {
            return false
        }
    }

    private fun positionIsAdvancing(player: ViewGroup): Boolean {
        val position = playerPosition(player) ?: return false
        val now = SystemClock.uptimeMillis()
        val previous = lastPosition
        val previousAt = lastPositionAt
        lastPosition = position
        lastPositionAt = now
        if (previous < 0L) return false
        if (now - previousAt > POSITION_STALE_MS) return false
        return position > previous
    }

    private fun playerPosition(player: ViewGroup): Long? {
        val exo = getMedia3Player(player) ?: return null
        try {
            val playerClass = Class.forName("androidx.media3.common.Player")
            return playerClass.getMethod("getCurrentPosition").invoke(exo) as? Long
        } catch (t: Throwable) { }
        try {
            val playerClass = Class.forName("com.google.android.exoplayer2.Player")
            return playerClass.getMethod("getCurrentPosition").invoke(exo) as? Long
        } catch (t: Throwable) { }
        try {
            val method = exo.javaClass.getMethod("getCurrentPosition").apply { isAccessible = true }
            return method.invoke(exo) as? Long
        } catch (t: Throwable) {
            return null
        }
    }

    private fun resolvePlayerFragment(activity: Activity?) {
        val now = SystemClock.uptimeMillis()
        if (playerFragment != null && now - playerFragmentAt < FRAGMENT_CACHE_MS) return

        playerFragment = null
        if (activity != null) {
            val fragments = getFragmentsFromActivity(activity)
            val candidates = findPlayerFragments(fragments)
            for (candidate in candidates) {
                val exo = playerViewFromFragment(candidate)
                if (exo != null && exo.isAttachedToWindow) {
                    playerFragment = candidate
                    break
                }
            }
            if (playerFragment == null && candidates.isNotEmpty()) {
                playerFragment = candidates.first()
            }
        }
        playerFragmentAt = now
    }

    private fun getFragmentsFromActivity(activity: Activity): List<*>? {
        return try {
            val getFmMethod = activity.javaClass.getMethod("getSupportFragmentManager").apply { isAccessible = true }
            val manager = getFmMethod.invoke(activity) ?: return null
            val fmClass = Class.forName("androidx.fragment.app.FragmentManager")
            val getFragmentsMethod = fmClass.getMethod("getFragments").apply { isAccessible = true }
            getFragmentsMethod.invoke(manager) as? List<*>
        } catch (t: Throwable) {
            null
        }
    }

    private fun findPlayerFragments(fragments: List<*>?): List<Any> {
        if (fragments == null) return emptyList()
        val found = mutableListOf<Any>()
        for (candidate in fragments) {
            if (candidate == null) continue
            val className = candidate.javaClass.name
            if (className.contains("Player", ignoreCase = true) ||
                declaresMethod(candidate.javaClass, "isUIShowing") ||
                declaresMethod(candidate.javaClass, "getCurrentPlayerStatus")
            ) {
                found.add(candidate)
            }
            val childFragments = getChildFragments(candidate)
            if (childFragments != null) found.addAll(findPlayerFragments(childFragments))
        }
        return found
    }

    private fun getChildFragments(fragment: Any): List<*>? {
        return try {
            val getChildFmMethod = fragment.javaClass.getMethod("getChildFragmentManager").apply { isAccessible = true }
            val childManager = getChildFmMethod.invoke(fragment) ?: return null
            val fmClass = Class.forName("androidx.fragment.app.FragmentManager")
            val getFragmentsMethod = fmClass.getMethod("getFragments").apply { isAccessible = true }
            getFragmentsMethod.invoke(childManager) as? List<*>
        } catch (t: Throwable) {
            null
        }
    }

    private fun playerViewFromFragment(fragment: Any?): ViewGroup? {
        if (fragment == null) return null
        val exo = invokeDeclared(fragment, "getPlayerView")
            ?: (invokeDeclared(fragment, "getPlayerHostView")?.let { host ->
                invokeDeclared(host, "getExoPlayerView")
            })
        return exo as? ViewGroup
    }

    private fun declaresMethod(type: Class<*>, name: String): Boolean {
        var current: Class<*>? = type
        while (current != null) {
            try {
                current.getDeclaredMethod(name)
                return true
            } catch (t: NoSuchMethodException) {
                current = current.superclass
            }
        }
        return false
    }

    private fun invokeDeclared(target: Any, name: String): Any? = try {
        var current: Class<*>? = target.javaClass
        var method: java.lang.reflect.Method? = null
        while (current != null && method == null) {
            try {
                method = current.getDeclaredMethod(name)
            } catch (t: NoSuchMethodException) {
                current = current.superclass
            }
        }
        method?.apply { isAccessible = true }?.invoke(target)
    } catch (t: Throwable) {
        null
    }

    private fun playerStatusName(fragment: Any?): String? {
        if (fragment == null) return null
        return try {
            val status = invokeDeclared(fragment, "getCurrentPlayerStatus")
            (status as? Enum<*>)?.name ?: status?.toString()
        } catch (t: Throwable) {
            null
        }
    }

    private fun isPlayerUiVisible(fragment: Any?, player: ViewGroup?): Boolean {
        if (fragment != null) {
            try {
                val isShowing = fragment.javaClass.getMethod("isUIShowing").invoke(fragment) as? Boolean
                if (isShowing != null) return isShowing
            } catch (t: Throwable) { }
        }
        if (player != null) {
            try {
                val res = player.resources
                val pkg = player.context.packageName
                val centerId = res.getIdentifier("player_center_menu", "id", pkg)
                val pipId = res.getIdentifier("piphide", "id", pkg)
                val barId = res.getIdentifier("bottom_player_bar", "id", pkg)
                val centerMenu = if (centerId != 0) player.findViewById<View?>(centerId) else null
                val piphide = if (pipId != 0) player.findViewById<View?>(pipId) else null
                val bottomBar = if (barId != 0) player.findViewById<View?>(barId) else null
                if (centerMenu?.visibility == View.VISIBLE || piphide?.visibility == View.VISIBLE || bottomBar?.visibility == View.VISIBLE) {
                    return true
                }
            } catch (t: Throwable) { }
        }
        return false
    }

    // ---------------------------------------------------------------------
    // Dynamic Accent Color
    // ---------------------------------------------------------------------

    private fun getAccentColor(context: Context): Int {
        val act = activityRef?.get()
        val theme = act?.theme ?: context.theme
        val typedValue = TypedValue()

        val primaryAttr = context.resources.getIdentifier("colorPrimary", "attr", context.packageName)
        if (primaryAttr != 0 && theme.resolveAttribute(primaryAttr, typedValue, true)) {
            if (typedValue.type >= TypedValue.TYPE_FIRST_COLOR_INT && typedValue.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                if (typedValue.data != 0 && typedValue.data != Color.BLACK) return typedValue.data
            }
        }

        val accentAttr = context.resources.getIdentifier("colorAccent", "attr", context.packageName)
        if (accentAttr != 0 && theme.resolveAttribute(accentAttr, typedValue, true)) {
            if (typedValue.type >= TypedValue.TYPE_FIRST_COLOR_INT && typedValue.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                if (typedValue.data != 0 && typedValue.data != Color.BLACK) return typedValue.data
            }
        }

        if (theme.resolveAttribute(android.R.attr.colorPrimary, typedValue, true)) {
            if (typedValue.type >= TypedValue.TYPE_FIRST_COLOR_INT && typedValue.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                if (typedValue.data != 0 && typedValue.data != Color.BLACK) return typedValue.data
            }
        }

        if (theme.resolveAttribute(android.R.attr.colorAccent, typedValue, true)) {
            if (typedValue.type >= TypedValue.TYPE_FIRST_COLOR_INT && typedValue.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                if (typedValue.data != 0 && typedValue.data != Color.BLACK) return typedValue.data
            }
        }

        try {
            val prefs = android.preference.PreferenceManager.getDefaultSharedPreferences(context)
            val keyId = context.resources.getIdentifier("primary_color_key", "string", context.packageName)
            val key = if (keyId != 0) context.getString(keyId) else "primary_color_key"
            val colorName = prefs.getString(key, null)
            if (colorName != null) {
                mapColorNameToColor(colorName)?.let { return it }
            }
        } catch (t: Throwable) { }

        return DEFAULT_RIBBON_COLOR
    }

    private fun mapColorNameToColor(name: String): Int? = when (name) {
        "Blue" -> Color.parseColor("#5664B7")
        "Red" -> Color.parseColor("#D50000")
        "Purple" -> Color.parseColor("#6200EA")
        "Green" -> Color.parseColor("#00BFA5")
        "GreenApple" -> Color.parseColor("#48E484")
        "Banana" -> Color.parseColor("#E4D448")
        "Party" -> Color.parseColor("#EA596E")
        "Pink" -> Color.parseColor("#FF1493")
        "CarnationPink" -> Color.parseColor("#BD5DA5")
        "DarkGreen" -> Color.parseColor("#004500")
        "Maroon" -> Color.parseColor("#451010")
        "NavyBlue" -> Color.parseColor("#000080")
        "Grey" -> Color.parseColor("#515151")
        "White" -> Color.parseColor("#FFFFFF")
        "Brown" -> Color.parseColor("#622C00")
        "Orange" -> Color.parseColor("#CE8500")
        "DandelionYellow" -> Color.parseColor("#F5BB00")
        "CoolBlue" -> Color.parseColor("#408CAC")
        "Lavender" -> Color.parseColor("#6F55AF")
        "Normal" -> Color.parseColor("#E50914")
        else -> null
    }

    // ---------------------------------------------------------------------
    // Metadata & Real Ratings API
    // ---------------------------------------------------------------------

    private fun updateAdvisoryContent(overlay: OverlayViews, fragment: Any?) {
        if (fragment == null) return
        try {
            val viewModel = invokeDeclared(fragment, "getViewModel") ?: return
            val state = invokeDeclared(viewModel, "getState") ?: return
            val genState = invokeDeclared(state, "getGeneratorState") ?: return
            val response = invokeDeclared(genState, "getResponse") ?: return

            val title = (invokeDeclared(response, "getName") as? String)?.trim().orEmpty()
            val year = invokeDeclared(response, "getYear") as? Int
            val typeStr = invokeDeclared(response, "getType")?.toString().orEmpty()
            val isTv = typeStr.contains("Tv", ignoreCase = true) || typeStr.contains("Series", ignoreCase = true)
            val contentRating = (invokeDeclared(response, "getContentRating") as? String)?.trim()
            val tags = invokeDeclared(response, "getTags") as? List<*>
            val plot = (invokeDeclared(response, "getPlot") as? String).orEmpty()

            // 1. Initial immediate local resolution from LoadResponse
            var ratingBadge: String? = null
            var descriptors: String? = null

            if (!contentRating.isNullOrBlank()) {
                val delimiters = Regex("[,|:•–-]")
                if (contentRating.contains(delimiters)) {
                    val parts = contentRating.split(delimiters, limit = 2).map { it.trim() }
                    ratingBadge = parts.getOrNull(0)
                    descriptors = parts.getOrNull(1)
                } else {
                    ratingBadge = contentRating
                }
            }

            if (ratingBadge.isNullOrBlank() && !tags.isNullOrEmpty()) {
                val ratingRegex = Regex("^(TV-MA|TV-14|TV-PG|TV-G|TV-Y7|TV-Y|18\\+|16\\+|13\\+|12\\+|7\\+|R|PG-13|PG|G|NC-17|U/A.*|U|A)$", RegexOption.IGNORE_CASE)
                val matchedTag = tags.firstOrNull { it?.toString()?.trim()?.matches(ratingRegex) == true }
                if (matchedTag != null) ratingBadge = matchedTag.toString().trim()
            }

            if (descriptors.isNullOrBlank() && !tags.isNullOrEmpty()) {
                val filteredTags = tags.mapNotNull { it?.toString()?.trim() }
                    .filterNot { it.equals(ratingBadge, ignoreCase = true) }
                    .take(4)
                if (filteredTags.isNotEmpty()) {
                    descriptors = filteredTags.joinToString(", ") { it.lowercase() }
                }
            }

            // Check if tobacco is present in local data
            val textToScan = (descriptors.orEmpty() + " " + tags?.joinToString() + " " + plot).lowercase()
            hasTobacco = textToScan.contains("tobacco") || textToScan.contains("smoking") ||
                textToScan.contains("cigarette") || textToScan.contains("cigar") ||
                textToScan.contains("nicotine") || textToScan.contains("substance")

            if (!ratingBadge.isNullOrBlank()) {
                applyMainText(overlay, ratingBadge, descriptors)
            }

            // 2. Query Real Ratings API (TMDB / OMDB) in background if title is new
            if (title.isNotBlank() && title != lastResolvedTitle) {
                lastResolvedTitle = title
                MetadataRatingFetcher.fetch(title, year, isTv) { result ->
                    Log.i(TAG, "API Rating resolved: badge=${result.ratingBadge}, desc=${result.descriptors}, tobacco=${result.hasTobacco}")
                    applyMainText(overlay, result.ratingBadge, result.descriptors)
                    if (result.hasTobacco) hasTobacco = true
                    syncRibbonHeight(overlay)
                }
            }
        } catch (t: Throwable) {
            Log.d(TAG, "Could not extract metadata from player: ${t.message}")
        }
    }

    private fun applyMainText(overlay: OverlayViews, ratingBadge: String, descriptors: String?) {
        val cleanBadge = ratingBadge.removePrefix("RATED").removePrefix("Rated").removePrefix("rated").trim()
        overlay.prefix.text = DEMO_PREFIX
        overlay.rating.text = cleanBadge.uppercase()
        overlay.reasons.text = descriptors ?: ""
        overlay.reasons.visibility = if (descriptors.isNullOrBlank()) View.GONE else View.VISIBLE
        overlay.mainContainer.contentDescription = "Content advisory: RATED $cleanBadge, $descriptors"
    }

    // ---------------------------------------------------------------------
    // Main Content Advisory Choreography
    // ---------------------------------------------------------------------

    private fun startSequence() {
        val overlay = overlayViews ?: return
        val player = playerRef?.get()
        stopAnimations()

        if (player != null) {
            val accentColor = getAccentColor(player.context)
            overlay.mainRibbon.setColor(accentColor)
        }

        updateAdvisoryContent(overlay, playerFragment)
        overlay.tobaccoContainer.visibility = View.GONE
        overlay.mainContainer.visibility = View.VISIBLE
        syncRibbonHeight(overlay)
        prepareViews(overlay)

        overlay.vignette.elevation = 998f
        overlay.vignette.translationZ = 998f
        overlay.root.elevation = 999f
        overlay.root.translationZ = 999f
        overlay.vignette.bringToFront()
        overlay.root.bringToFront()

        mainState = AdvisoryState.SHOWING
        val token = Any()
        sequenceToken = token

        Log.i(TAG, "main advisory choreography started! state=$mainState")

        enterSet = buildEnterAnimators(overlay).also { it.start() }

        handler.postAtTime(
            Runnable {
                if (mainState != AdvisoryState.SHOWING) return@Runnable
                startExit(overlay)
            },
            token,
            SystemClock.uptimeMillis() + EXIT_START_MS
        )
    }

    private fun startExit(overlay: OverlayViews) {
        exitSet = buildExitAnimators(overlay).also { it.start() }

        handler.postAtTime(
            Runnable {
                if (mainState != AdvisoryState.SHOWING) return@Runnable
                hideAdvisoryViews()
                mainState = AdvisoryState.DONE
                Log.i(TAG, "main advisory choreography completed (state=DONE)")

                // After main advisory closes, if tobacco depiction is present, show statutory warning!
                if (hasTobacco && tobaccoState == AdvisoryState.NOT_SHOWN) {
                    handler.postDelayed({
                        val p = playerRef?.get()
                        if (p != null && isPlaybackRunning(p, playerFragment) && !controlsVisible) {
                            startTobaccoSequence()
                        }
                    }, TOBACCO_GAP_AFTER_MAIN_MS)
                }
            },
            sequenceToken,
            SystemClock.uptimeMillis() + EXIT_TOTAL_MS
        )
    }

    private fun prepareViews(overlay: OverlayViews) {
        val travel = maskTravel(overlay)
        val h = ribbonHeight(overlay)

        overlay.mainRibbon.pivotY = h / 2f
        overlay.mainRibbon.scaleY = 0f
        overlay.mainRibbon.alpha = 0f

        overlay.ratingRow.translationX = -travel
        overlay.ratingRow.alpha = 0f

        overlay.reasons.translationX = -travel
        overlay.reasons.alpha = 0f

        overlay.vignette.alpha = 0f
    }

    private fun hideAdvisoryViews() {
        val overlay = overlayViews ?: return
        overlay.mainRibbon.alpha = 0f
        overlay.mainRibbon.scaleY = 0f
        overlay.ratingRow.alpha = 0f
        overlay.reasons.alpha = 0f
        overlay.vignette.alpha = 0f
        overlay.mainContainer.visibility = View.GONE
    }

    private fun buildEnterAnimators(overlay: OverlayViews): AnimatorSet {
        val travel = maskTravel(overlay)
        val set = AnimatorSet()

        val barScale = ObjectAnimator.ofFloat(overlay.mainRibbon, View.SCALE_Y, 0f, 1f).apply {
            duration = BAR_IN_MS
            interpolator = IN_EASE
        }
        val barFade = ObjectAnimator.ofFloat(overlay.mainRibbon, View.ALPHA, 0f, 1f).apply {
            duration = BAR_FADE_IN_MS
            interpolator = LINEAR
        }

        val ratingSlide = ObjectAnimator.ofFloat(overlay.ratingRow, View.TRANSLATION_X, -travel, 0f).apply {
            duration = TEXT_SLIDE_MS
            interpolator = IN_EASE
            startDelay = RATING_IN_DELAY_MS
        }
        val ratingFade = ObjectAnimator.ofFloat(overlay.ratingRow, View.ALPHA, 0f, 1f).apply {
            duration = TEXT_FADE_IN_MS
            interpolator = LINEAR
            startDelay = RATING_IN_DELAY_MS
        }
        val descriptorsSlide = ObjectAnimator.ofFloat(overlay.reasons, View.TRANSLATION_X, -travel, 0f).apply {
            duration = TEXT_SLIDE_MS
            interpolator = IN_EASE
            startDelay = DESCRIPTORS_IN_DELAY_MS
        }
        val descriptorsFade = ObjectAnimator.ofFloat(overlay.reasons, View.ALPHA, 0f, 1f).apply {
            duration = TEXT_FADE_IN_MS
            interpolator = LINEAR
            startDelay = DESCRIPTORS_IN_DELAY_MS
        }

        val vignetteFade = ObjectAnimator.ofFloat(overlay.vignette, View.ALPHA, 0f, 1f).apply {
            duration = VIGNETTE_FADE_MS
            interpolator = EASE
        }

        set.playTogether(
            barScale,
            barFade,
            ratingSlide,
            ratingFade,
            descriptorsSlide,
            descriptorsFade,
            vignetteFade
        )
        return set
    }

    private fun buildExitAnimators(overlay: OverlayViews): AnimatorSet {
        val travel = maskTravel(overlay)
        val set = AnimatorSet()

        val descriptorsSlide = ObjectAnimator.ofFloat(overlay.reasons, View.TRANSLATION_X, 0f, -travel).apply {
            duration = TEXT_RETRACT_MS
            interpolator = OUT_EASE
        }
        val descriptorsFade = ObjectAnimator.ofFloat(overlay.reasons, View.ALPHA, 1f, 0f).apply {
            duration = TEXT_FADE_OUT_MS
            interpolator = LINEAR
            startDelay = TEXT_RETRACT_MS - TEXT_FADE_OUT_MS
        }

        val ratingSlide = ObjectAnimator.ofFloat(overlay.ratingRow, View.TRANSLATION_X, 0f, -travel).apply {
            duration = TEXT_RETRACT_MS
            interpolator = OUT_EASE
            startDelay = RATING_OUT_DELAY_MS
        }
        val ratingFade = ObjectAnimator.ofFloat(overlay.ratingRow, View.ALPHA, 1f, 0f).apply {
            duration = TEXT_FADE_OUT_MS
            interpolator = LINEAR
            startDelay = RATING_OUT_DELAY_MS + (TEXT_RETRACT_MS - TEXT_FADE_OUT_MS)
        }

        val barCollapse = ObjectAnimator.ofFloat(overlay.mainRibbon, View.SCALE_Y, 1f, 0f).apply {
            duration = BAR_OUT_MS
            interpolator = OUT_EASE
            startDelay = BAR_OUT_DELAY_MS
        }
        val barFade = ObjectAnimator.ofFloat(overlay.mainRibbon, View.ALPHA, 1f, 0f).apply {
            duration = BAR_FADE_OUT_MS
            interpolator = LINEAR
            startDelay = BAR_OUT_DELAY_MS + (BAR_OUT_MS - BAR_FADE_OUT_MS)
        }

        val vignetteFade = ObjectAnimator.ofFloat(overlay.vignette, View.ALPHA, 1f, 0f).apply {
            duration = VIGNETTE_FADE_MS
            interpolator = EASE
            startDelay = VIGNETTE_OUT_DELAY_MS
        }

        set.playTogether(
            descriptorsSlide,
            descriptorsFade,
            ratingSlide,
            ratingFade,
            barCollapse,
            barFade,
            vignetteFade
        )
        return set
    }

    private fun stopAnimations() {
        enterSet?.cancel()
        enterSet = null
        exitSet?.cancel()
        exitSet = null
        sequenceToken?.let { handler.removeCallbacksAndMessages(it) }
        sequenceToken = null
    }

    // ---------------------------------------------------------------------
    // Statutory Anti-Tobacco Advisory Choreography (Separate Entity)
    // ---------------------------------------------------------------------

    private fun startTobaccoSequence() {
        val overlay = overlayViews ?: return
        val player = playerRef?.get()
        stopTobaccoAnimations()

        if (player != null) {
            val accentColor = getAccentColor(player.context)
            overlay.tobaccoRibbon.setColor(accentColor)
        }

        overlay.mainContainer.visibility = View.GONE
        overlay.tobaccoContainer.visibility = View.VISIBLE
        syncTobaccoRibbonHeight(overlay)
        prepareTobaccoViews(overlay)

        overlay.vignette.elevation = 998f
        overlay.vignette.translationZ = 998f
        overlay.root.elevation = 999f
        overlay.root.translationZ = 999f
        overlay.vignette.bringToFront()
        overlay.root.bringToFront()

        tobaccoState = AdvisoryState.SHOWING
        val token = Any()
        tobaccoToken = token

        Log.i(TAG, "tobacco advisory sequence started! state=$tobaccoState")

        tobaccoEnterSet = buildTobaccoEnterAnimators(overlay).also { it.start() }

        handler.postAtTime(
            Runnable {
                if (tobaccoState != AdvisoryState.SHOWING) return@Runnable
                startTobaccoExit(overlay)
            },
            token,
            SystemClock.uptimeMillis() + TOBACCO_EXIT_START_MS
        )
    }

    private fun startTobaccoExit(overlay: OverlayViews) {
        tobaccoExitSet = buildTobaccoExitAnimators(overlay).also { it.start() }

        handler.postAtTime(
            Runnable {
                if (tobaccoState != AdvisoryState.SHOWING) return@Runnable
                hideTobaccoViews()
                tobaccoState = AdvisoryState.DONE
                Log.i(TAG, "tobacco advisory completed (state=DONE)")
            },
            tobaccoToken,
            SystemClock.uptimeMillis() + TOBACCO_EXIT_TOTAL_MS
        )
    }

    private fun prepareTobaccoViews(overlay: OverlayViews) {
        val travel = tobaccoMaskTravel(overlay)
        val h = tobaccoRibbonHeight(overlay)

        overlay.tobaccoRibbon.pivotY = h / 2f
        overlay.tobaccoRibbon.scaleY = 0f
        overlay.tobaccoRibbon.alpha = 0f

        overlay.tobaccoText.translationX = -travel
        overlay.tobaccoText.alpha = 0f

        overlay.vignette.alpha = 0f
    }

    private fun hideTobaccoViews() {
        val overlay = overlayViews ?: return
        overlay.tobaccoRibbon.alpha = 0f
        overlay.tobaccoRibbon.scaleY = 0f
        overlay.tobaccoText.alpha = 0f
        overlay.vignette.alpha = 0f
        overlay.tobaccoContainer.visibility = View.GONE
    }

    private fun buildTobaccoEnterAnimators(overlay: OverlayViews): AnimatorSet {
        val travel = tobaccoMaskTravel(overlay)
        val set = AnimatorSet()

        val barScale = ObjectAnimator.ofFloat(overlay.tobaccoRibbon, View.SCALE_Y, 0f, 1f).apply {
            duration = BAR_IN_MS
            interpolator = IN_EASE
        }
        val barFade = ObjectAnimator.ofFloat(overlay.tobaccoRibbon, View.ALPHA, 0f, 1f).apply {
            duration = BAR_FADE_IN_MS
            interpolator = LINEAR
        }

        val textSlide = ObjectAnimator.ofFloat(overlay.tobaccoText, View.TRANSLATION_X, -travel, 0f).apply {
            duration = TEXT_SLIDE_MS
            interpolator = IN_EASE
            startDelay = RATING_IN_DELAY_MS
        }
        val textFade = ObjectAnimator.ofFloat(overlay.tobaccoText, View.ALPHA, 0f, 1f).apply {
            duration = TEXT_FADE_IN_MS
            interpolator = LINEAR
            startDelay = RATING_IN_DELAY_MS
        }

        val vignetteFade = ObjectAnimator.ofFloat(overlay.vignette, View.ALPHA, 0f, 1f).apply {
            duration = VIGNETTE_FADE_MS
            interpolator = EASE
        }

        set.playTogether(barScale, barFade, textSlide, textFade, vignetteFade)
        return set
    }

    private fun buildTobaccoExitAnimators(overlay: OverlayViews): AnimatorSet {
        val travel = tobaccoMaskTravel(overlay)
        val set = AnimatorSet()

        val textSlide = ObjectAnimator.ofFloat(overlay.tobaccoText, View.TRANSLATION_X, 0f, -travel).apply {
            duration = TEXT_RETRACT_MS
            interpolator = OUT_EASE
        }
        val textFade = ObjectAnimator.ofFloat(overlay.tobaccoText, View.ALPHA, 1f, 0f).apply {
            duration = TEXT_FADE_OUT_MS
            interpolator = LINEAR
            startDelay = TEXT_RETRACT_MS - TEXT_FADE_OUT_MS
        }

        val barCollapse = ObjectAnimator.ofFloat(overlay.tobaccoRibbon, View.SCALE_Y, 1f, 0f).apply {
            duration = BAR_OUT_MS
            interpolator = OUT_EASE
            startDelay = BAR_OUT_DELAY_MS
        }
        val barFade = ObjectAnimator.ofFloat(overlay.tobaccoRibbon, View.ALPHA, 1f, 0f).apply {
            duration = BAR_FADE_OUT_MS
            interpolator = LINEAR
            startDelay = BAR_OUT_DELAY_MS + (BAR_OUT_MS - BAR_FADE_OUT_MS)
        }

        val vignetteFade = ObjectAnimator.ofFloat(overlay.vignette, View.ALPHA, 1f, 0f).apply {
            duration = VIGNETTE_FADE_MS
            interpolator = EASE
            startDelay = VIGNETTE_OUT_DELAY_MS
        }

        set.playTogether(textSlide, textFade, barCollapse, barFade, vignetteFade)
        return set
    }

    private fun stopTobaccoAnimations() {
        tobaccoEnterSet?.cancel()
        tobaccoEnterSet = null
        tobaccoExitSet?.cancel()
        tobaccoExitSet = null
        tobaccoToken?.let { handler.removeCallbacksAndMessages(it) }
        tobaccoToken = null
    }

    private fun removeExistingCard() {
        val current = card ?: return
        val vignette = overlayViews?.vignette
        stopAnimations()
        stopTobaccoAnimations()
        card = null
        overlayViews = null
        (current.parent as? ViewGroup)?.removeView(current)
        (vignette?.parent as? ViewGroup)?.removeView(vignette)
    }

    private fun cleanupCard() {
        Log.i(TAG, "cleanupCard: overlay detached from window")
        stopAnimations()
        stopTobaccoAnimations()
        card = null
        overlayViews = null
        playerRef = null
        playerFragment = null
        mainState = AdvisoryState.NOT_SHOWN
        tobaccoState = AdvisoryState.NOT_SHOWN
        controlsVisible = false
        playbackRunning = false
        lastPosition = -1L
    }

    private fun detach() {
        val activeSession = session
        if (activeSession != null) handler.removeCallbacksAndMessages(activeSession)
        session = null
        activityRef = null
        playerRef = null
        playerFragment = null
        mainState = AdvisoryState.NOT_SHOWN
        tobaccoState = AdvisoryState.NOT_SHOWN
        controlsVisible = false
        playbackRunning = false
        lastPosition = -1L
        removeExistingCard()
    }

    // ---------------------------------------------------------------------
    // View Construction - Restored original placement & text sizing
    // ---------------------------------------------------------------------

    private fun buildOverlay(context: Context, player: ViewGroup): OverlayViews {
        val widthDp = playerWidthDp(player)
        val compact = widthDp <= COMPACT_WIDTH_DP
        val ratingTextSp = clampSp(RATING_TEXT_MIN_SP, RATING_TEXT_MAX_SP, RATING_TEXT_VW, widthDp)
        val reasonTextSp = clampSp(REASON_TEXT_MIN_SP, REASON_TEXT_MAX_SP, REASON_TEXT_VW, widthDp)
        val tobaccoTextSp = clampSp(TOBACCO_TEXT_MIN_SP, TOBACCO_TEXT_MAX_SP, TOBACCO_TEXT_VW, widthDp)
        val startInset = if (compact) START_INSET_COMPACT_DP else START_INSET_DP

        val root = FrameLayout(context).apply {
            clipChildren = false
            isClickable = false
            isFocusable = false
        }

        // 1. Main Advisory Container
        val mainContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            isClickable = false
            isFocusable = false
            setPadding(0, dp(context, 4), dp(context, 16), dp(context, 4))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }

        val accentColor = getAccentColor(player.context)
        val mainRibbon = RibbonView(
            context,
            dpF(context, RIBBON_WIDTH_DP).toFloat(),
            accentColor
        )

        val mainCopy = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = true
            clipToPadding = false
            isClickable = false
            isFocusable = false
        }

        val ratingRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = false
            isFocusable = false
        }

        val prefix = TextView(context).apply {
            text = DEMO_PREFIX
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ratingTextSp)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            includeFontPadding = false
            isSingleLine = true
            letterSpacing = 0.035f
            setShadowLayer(6f, 0f, 2f, Color.argb(230, 0, 0, 0))
        }

        val rating = TextView(context).apply {
            text = DEMO_RATING
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, ratingTextSp)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            includeFontPadding = false
            isSingleLine = true
            letterSpacing = 0.035f
            setShadowLayer(6f, 0f, 2f, Color.argb(230, 0, 0, 0))
        }

        val reasons = TextView(context).apply {
            text = DEMO_TEXT
            setTextColor(REASON_COLOR)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, reasonTextSp)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            includeFontPadding = false
            isSingleLine = true
            letterSpacing = 0.015f
            setShadowLayer(5f, 0f, 1f, Color.argb(235, 0, 0, 0))
            maxWidth = (playerWidthPx(player) -
                dp(context, startInset + RIBBON_WIDTH_DP.toInt() + MASK_GAP_DP + 16 + 24))
                .coerceAtLeast(dp(context, 180))
        }

        ratingRow.addView(prefix, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        ratingRow.addView(rating, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            marginStart = dp(context, RATING_GAP_DP)
        })

        mainCopy.addView(ratingRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            marginEnd = dp(context, MASK_TRAILING_DP)
        })
        mainCopy.addView(reasons, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            marginEnd = dp(context, MASK_TRAILING_DP)
            topMargin = dp(context, ROW_GAP_DP)
        })

        mainContainer.addView(mainRibbon, LinearLayout.LayoutParams(
            dpF(context, RIBBON_WIDTH_DP),
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        mainContainer.addView(mainCopy, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            marginStart = dp(context, MASK_GAP_DP)
        })

        mainContainer.contentDescription = "Content advisory: $DEMO_PREFIX $DEMO_RATING, $DEMO_TEXT"

        // 2. Statutory Anti-Tobacco Advisory Container (Separate Entity)
        val tobaccoContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            isClickable = false
            isFocusable = false
            setPadding(0, dp(context, 4), dp(context, 16), dp(context, 4))
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }

        val tobaccoRibbon = RibbonView(
            context,
            dpF(context, RIBBON_WIDTH_DP).toFloat(),
            accentColor
        )

        val tobaccoCopy = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = true
            clipToPadding = false
            isClickable = false
            isFocusable = false
        }

        val tobaccoText = TextView(context).apply {
            text = TOBACCO_WARNING_TEXT
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, tobaccoTextSp)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            includeFontPadding = false
            isSingleLine = true
            letterSpacing = 0.02f
            setShadowLayer(5f, 0f, 1.5f, Color.argb(230, 0, 0, 0))
            maxWidth = (playerWidthPx(player) -
                dp(context, startInset + RIBBON_WIDTH_DP.toInt() + MASK_GAP_DP + 16 + 24))
                .coerceAtLeast(dp(context, 180))
        }

        tobaccoCopy.addView(tobaccoText, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            marginEnd = dp(context, MASK_TRAILING_DP)
        })

        tobaccoContainer.addView(tobaccoRibbon, LinearLayout.LayoutParams(
            dpF(context, RIBBON_WIDTH_DP),
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        tobaccoContainer.addView(tobaccoCopy, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            marginStart = dp(context, MASK_GAP_DP)
        })

        tobaccoContainer.contentDescription = "Content advisory: $TOBACCO_WARNING_TEXT"

        // Add both containers to the common root
        root.addView(mainContainer, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ))
        root.addView(tobaccoContainer, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ))

        // Hidden until sequences start
        mainRibbon.alpha = 0f
        ratingRow.alpha = 0f
        reasons.alpha = 0f

        tobaccoRibbon.alpha = 0f
        tobaccoText.alpha = 0f

        val vignette = CornerVignetteView(context).apply { alpha = 0f }

        return OverlayViews(
            root = root,
            mainContainer = mainContainer,
            mainRibbon = mainRibbon,
            mainCopy = mainCopy,
            ratingRow = ratingRow,
            prefix = prefix,
            rating = rating,
            reasons = reasons,
            tobaccoContainer = tobaccoContainer,
            tobaccoRibbon = tobaccoRibbon,
            tobaccoCopy = tobaccoCopy,
            tobaccoText = tobaccoText,
            vignette = vignette
        )
    }

    /**
     * Sleek, slender accent bar dynamically colored with CloudStream's theme accent color.
     */
    private class RibbonView(
        context: Context,
        private val barWidth: Float,
        initialColor: Int
    ) : View(context) {

        private var barColor: Int = initialColor
        private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = barColor
            style = Paint.Style.FILL
        }
        private val cornerRadius = dpAt(context, 1).toFloat()

        fun setColor(color: Int) {
            if (barColor != color) {
                barColor = color
                barPaint.color = color
                invalidate()
            }
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            canvas.drawRoundRect(
                0f,
                0f,
                w,
                h,
                cornerRadius,
                cornerRadius,
                barPaint
            )
        }
    }

    /** Radial gradient that subtly darkens the top-left corner for high text contrast. */
    private class CornerVignetteView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var shader: RadialGradient? = null

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (w <= 0 || h <= 0) return
            val gradient = RadialGradient(
                0f,
                0f,
                1f,
                intArrayOf(
                    Color.argb(180, 0, 0, 0),
                    Color.argb(100, 0, 0, 0),
                    Color.argb(25, 0, 0, 0),
                    Color.argb(0, 0, 0, 0)
                ),
                floatArrayOf(0f, 0.40f, 0.70f, 1f),
                Shader.TileMode.CLAMP
            )
            val local = Matrix()
            local.setScale(w.toFloat(), h.toFloat())
            gradient.setLocalMatrix(local)
            shader = gradient
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return

            paint.shader = shader
            canvas.drawRect(0f, 0f, w, h, paint)
        }
    }

    private fun dp(context: Context, value: Int): Int = dpAt(context, value)
    private fun dpF(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    private fun playerWidthDp(player: ViewGroup): Float {
        val metrics = player.resources.displayMetrics
        val widthPx = if (player.width > 0) player.width else metrics.widthPixels
        return widthPx / metrics.density
    }

    private fun playerWidthPx(player: ViewGroup): Int {
        val metrics = player.resources.displayMetrics
        return if (player.width > 0) player.width else metrics.widthPixels
    }

    private fun clampSp(minSp: Float, maxSp: Float, viewportRatio: Float, widthDp: Float): Float =
        minOf(maxSp, maxOf(minSp, widthDp * viewportRatio))

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

    // ---------------------------------------------------------------------
    // Metadata / Rating API Fetcher (TMDB & OMDB)
    // ---------------------------------------------------------------------

    private object MetadataRatingFetcher {
        private const val TMDB_API_KEY = "e6333b32409e02a4a6eba6fb7ff866bb"
        private const val OMDB_API_KEY = "b9a5e69d"
        private val cache = ConcurrentHashMap<String, MetadataResult>()

        data class MetadataResult(
            val ratingBadge: String,
            val descriptors: String,
            val hasTobacco: Boolean
        )

        fun fetch(
            rawTitle: String,
            year: Int?,
            isTv: Boolean,
            onResult: (MetadataResult) -> Unit
        ) {
            val clean = cleanTitle(rawTitle)
            if (clean.isBlank()) return
            val key = "$clean:${year ?: 0}:$isTv".lowercase()
            cache[key]?.let {
                onResult(it)
                return
            }

            Thread {
                try {
                    val result = queryTmdb(clean, year, isTv) ?: queryOmdb(clean, year)
                    if (result != null) {
                        cache[key] = result
                        Handler(Looper.getMainLooper()).post { onResult(result) }
                    }
                } catch (t: Throwable) {
                    Log.d(TAG, "Rating fetch error: ${t.message}")
                }
            }.start()
        }

        private fun cleanTitle(title: String): String = title
            .replace(Regex("""\b(Season|S)\s*\d+.*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\b(Episode|EP)\s*\d+.*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\b(1080p|720p|2160p|4k|hdr|web-dl|bluray|x264|x265|hevc)\b.*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\((19|20)\d{2}\)"""), "")
            .trim()

        private fun queryTmdb(title: String, year: Int?, isTv: Boolean): MetadataResult? {
            val encoded = URLEncoder.encode(title, "UTF-8")
            val searchUrl = "https://api.tmdb.org/3/search/multi?api_key=$TMDB_API_KEY&query=$encoded${if (year != null) "&year=$year" else ""}"
            val jsonStr = httpGet(searchUrl) ?: return null
            val root = JSONObject(jsonStr)
            val results = root.optJSONArray("results") ?: return null
            if (results.length() == 0) return null
            val first = results.optJSONObject(0) ?: return null
            val id = first.optInt("id", 0)
            if (id == 0) return null
            val mediaType = first.optString("media_type").ifBlank { if (isTv) "tv" else "movie" }
            val overview = first.optString("overview", "").lowercase()

            var ratingBadge: String? = null
            var hasTobacco = overview.contains("smoking") || overview.contains("tobacco") ||
                overview.contains("cigarette") || overview.contains("cigar")

            val keywordsList = mutableListOf<String>()

            if (mediaType == "tv") {
                val crJson = httpGet("https://api.tmdb.org/3/tv/$id/content_ratings?api_key=$TMDB_API_KEY")
                if (crJson != null) {
                    val crObj = JSONObject(crJson)
                    val crResults = crObj.optJSONArray("results")
                    if (crResults != null) {
                        var inRating: String? = null
                        var usRating: String? = null
                        var gbRating: String? = null
                        var anyRating: String? = null
                        for (i in 0 until crResults.length()) {
                            val r = crResults.optJSONObject(i) ?: continue
                            val country = r.optString("iso_3166_1")
                            val rating = r.optString("rating").trim()
                            if (rating.isBlank()) continue
                            if (country == "IN") inRating = rating
                            if (country == "US") usRating = rating
                            if (country == "GB") gbRating = rating
                            if (anyRating == null) anyRating = rating
                        }
                        ratingBadge = inRating ?: usRating ?: gbRating ?: anyRating
                    }
                }
                val kwJson = httpGet("https://api.tmdb.org/3/tv/$id/keywords?api_key=$TMDB_API_KEY")
                if (kwJson != null) {
                    val kwObj = JSONObject(kwJson)
                    val kwArr = kwObj.optJSONArray("results")
                    if (kwArr != null) {
                        for (i in 0 until kwArr.length()) {
                            val kw = kwArr.optJSONObject(i)?.optString("name")?.lowercase() ?: continue
                            keywordsList.add(kw)
                        }
                    }
                }
            } else {
                val relJson = httpGet("https://api.tmdb.org/3/movie/$id/release_dates?api_key=$TMDB_API_KEY")
                if (relJson != null) {
                    val relObj = JSONObject(relJson)
                    val relResults = relObj.optJSONArray("results")
                    if (relResults != null) {
                        var inCert: String? = null
                        var usCert: String? = null
                        var gbCert: String? = null
                        var anyCert: String? = null
                        for (i in 0 until relResults.length()) {
                            val r = relResults.optJSONObject(i) ?: continue
                            val country = r.optString("iso_3166_1")
                            val dates = r.optJSONArray("release_dates") ?: continue
                            for (j in 0 until dates.length()) {
                                val d = dates.optJSONObject(j) ?: continue
                                val cert = d.optString("certification").trim()
                                if (cert.isBlank()) continue
                                if (country == "IN" && inCert == null) inCert = cert
                                if (country == "US" && usCert == null) usCert = cert
                                if (country == "GB" && gbCert == null) gbCert = cert
                                if (anyCert == null) anyCert = cert
                            }
                        }
                        ratingBadge = inCert ?: usCert ?: gbCert ?: anyCert
                    }
                }
                val kwJson = httpGet("https://api.tmdb.org/3/movie/$id/keywords?api_key=$TMDB_API_KEY")
                if (kwJson != null) {
                    val kwObj = JSONObject(kwJson)
                    val kwArr = kwObj.optJSONArray("keywords")
                    if (kwArr != null) {
                        for (i in 0 until kwArr.length()) {
                            val kw = kwArr.optJSONObject(i)?.optString("name")?.lowercase() ?: continue
                            keywordsList.add(kw)
                        }
                    }
                }
            }

            for (k in keywordsList) {
                if (k.contains("smoking") || k.contains("tobacco") || k.contains("cigarette") || k.contains("cigar")) {
                    hasTobacco = true
                }
            }

            if (ratingBadge.isNullOrBlank()) return null
            val descriptors = mapKeywordsToDescriptors(keywordsList, ratingBadge)
            return MetadataResult(ratingBadge, descriptors, hasTobacco)
        }

        private fun queryOmdb(title: String, year: Int?): MetadataResult? {
            val encoded = URLEncoder.encode(title, "UTF-8")
            val url = "http://www.omdbapi.com/?t=$encoded${if (year != null) "&y=$year" else ""}&apikey=$OMDB_API_KEY"
            val jsonStr = httpGet(url) ?: return null
            val obj = JSONObject(jsonStr)
            if (obj.optString("Response") != "True") return null
            val rated = obj.optString("Rated").trim()
            if (rated.isBlank() || rated == "N/A" || rated.equals("Not Rated", ignoreCase = true)) return null

            val genre = obj.optString("Genre", "")
            val plot = obj.optString("Plot", "").lowercase()
            val hasTobacco = plot.contains("smoking") || plot.contains("tobacco") ||
                plot.contains("cigarette") || plot.contains("cigar")

            val descriptors = mapGenreToDescriptors(genre, rated)
            return MetadataResult(rated, descriptors, hasTobacco)
        }

        private fun mapKeywordsToDescriptors(keywords: List<String>, rating: String): String {
            val found = linkedSetOf<String>()
            for (kw in keywords) {
                when {
                    kw.contains("violence") || kw.contains("war") || kw.contains("fight") ||
                        kw.contains("murder") || kw.contains("gangster") || kw.contains("kill") ->
                        found.add("violence")
                    kw.contains("horror") || kw.contains("fear") || kw.contains("monster") ||
                        kw.contains("terror") || kw.contains("suspense") || kw.contains("supernatural") ->
                        found.add("fear")
                    kw.contains("drug") || kw.contains("substance") || kw.contains("alcohol") ||
                        kw.contains("drinking") || kw.contains("cocaine") ->
                        found.add("substances")
                    kw.contains("tobacco") || kw.contains("smoking") || kw.contains("cigarette") || kw.contains("cigar") ->
                        found.add("tobacco use")
                    kw.contains("profanity") || kw.contains("swearing") || kw.contains("language") ->
                        found.add("coarse language")
                    kw.contains("sex") || kw.contains("nudity") || kw.contains("erotic") ->
                        found.add("sex, nudity")
                    kw.contains("suicide") || kw.contains("self-harm") ->
                        found.add("suicide, self-harm")
                    kw.contains("gore") || kw.contains("bloody") ->
                        found.add("gore")
                }
            }

            if (found.size < 2) {
                when {
                    rating.contains("MA") || rating.contains("18") || rating.contains("R") || rating == "A" -> {
                        found.add("violence")
                        found.add("substances")
                        found.add("coarse language")
                    }
                    rating.contains("14") || rating.contains("16") || rating.contains("PG-13") -> {
                        found.add("fear")
                        found.add("language")
                        found.add("violence")
                    }
                    else -> {
                        found.add("mild violence")
                        found.add("fear")
                    }
                }
            }
            return found.take(4).joinToString(", ")
        }

        private fun mapGenreToDescriptors(genre: String, rating: String): String {
            val found = linkedSetOf<String>()
            val g = genre.lowercase()
            if (g.contains("action") || g.contains("crime") || g.contains("war")) found.add("violence")
            if (g.contains("horror") || g.contains("thriller") || g.contains("mystery")) found.add("fear")
            if (g.contains("crime") || g.contains("drama")) found.add("substances")
            if (g.contains("comedy") || g.contains("drama")) found.add("language")
            if (found.isEmpty()) {
                if (rating.contains("18") || rating.contains("MA") || rating == "R" || rating == "A") {
                    found.add("violence")
                    found.add("substances")
                    found.add("coarse language")
                } else {
                    found.add("fear")
                    found.add("mild violence")
                }
            }
            return found.take(4).joinToString(", ")
        }

        private fun httpGet(urlStr: String): String? {
            return try {
                val url = URL(urlStr)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                if (conn.responseCode in 200..299) {
                    BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                } else null
            } catch (t: Throwable) {
                null
            }
        }
    }

    companion object {
        private const val TAG = "MovyAdvisory"
        private const val CLOUDSTREAM_PLAYER = "com.lagradost.cloudstream3.ui.player.PlayerView"
        private const val MEDIA3_PLAYER = "androidx.media3.ui.PlayerView"

        private const val ANIM_SCALE = 2.5f

        private const val BASE_BAR_IN_MS = 350L
        private const val BASE_TEXT_SLIDE_MS = 460L
        private const val BASE_RATING_IN_DELAY_MS = 200L
        private const val BASE_DESCRIPTORS_IN_DELAY_MS = 260L
        private const val BASE_TEXT_RETRACT_MS = 390L
        private const val BASE_RATING_OUT_DELAY_MS = 60L
        private const val BASE_BAR_OUT_DELAY_MS = 380L
        private const val BASE_BAR_OUT_MS = 340L
        private const val BASE_VIGNETTE_FADE_MS = 450L
        private const val BASE_VIGNETTE_OUT_DELAY_MS = 340L

        private val BAR_IN_MS = (BASE_BAR_IN_MS * ANIM_SCALE).toLong()
        private val TEXT_SLIDE_MS = (BASE_TEXT_SLIDE_MS * ANIM_SCALE).toLong()
        private val RATING_IN_DELAY_MS = (BASE_RATING_IN_DELAY_MS * ANIM_SCALE).toLong()
        private val DESCRIPTORS_IN_DELAY_MS = (BASE_DESCRIPTORS_IN_DELAY_MS * ANIM_SCALE).toLong()
        private val TEXT_RETRACT_MS = (BASE_TEXT_RETRACT_MS * ANIM_SCALE).toLong()
        private val RATING_OUT_DELAY_MS = (BASE_RATING_OUT_DELAY_MS * ANIM_SCALE).toLong()
        private val BAR_OUT_DELAY_MS = (BASE_BAR_OUT_DELAY_MS * ANIM_SCALE).toLong()
        private val BAR_OUT_MS = (BASE_BAR_OUT_MS * ANIM_SCALE).toLong()
        private val VIGNETTE_FADE_MS = (BASE_VIGNETTE_FADE_MS * ANIM_SCALE).toLong()
        private val VIGNETTE_OUT_DELAY_MS = (BASE_VIGNETTE_OUT_DELAY_MS * ANIM_SCALE).toLong()

        private val BAR_FADE_IN_MS = (BAR_IN_MS * 0.25f).toLong()
        private val TEXT_FADE_IN_MS = (TEXT_SLIDE_MS * 0.20f).toLong()
        private val TEXT_FADE_OUT_MS = (TEXT_RETRACT_MS * 0.20f).toLong()
        private val BAR_FADE_OUT_MS = (BAR_OUT_MS * 0.25f).toLong()

        private val IN_COMPLETE_MS = DESCRIPTORS_IN_DELAY_MS + TEXT_SLIDE_MS
        private val EXIT_START_MS = IN_COMPLETE_MS + HOLD_MS
        private val EXIT_TOTAL_MS = VIGNETTE_OUT_DELAY_MS + VIGNETTE_FADE_MS

        private const val HOLD_MS = 3_000L
        private const val START_DELAY_MS = 1_500L
        private const val TOBACCO_GAP_AFTER_MAIN_MS = 600L

        private val TOBACCO_HOLD_MS = 3_500L
        private val TOBACCO_IN_COMPLETE_MS = RATING_IN_DELAY_MS + TEXT_SLIDE_MS
        private val TOBACCO_EXIT_START_MS = TOBACCO_IN_COMPLETE_MS + TOBACCO_HOLD_MS
        private val TOBACCO_EXIT_TOTAL_MS = VIGNETTE_OUT_DELAY_MS + VIGNETTE_FADE_MS

        // Placement (restored to original values per user request):
        private const val START_INSET_DP = 48
        private const val TOP_INSET_DP = 42
        private const val START_INSET_COMPACT_DP = 20
        private const val TOP_INSET_COMPACT_DP = 24
        private const val COMPACT_WIDTH_DP = 768f

        // Slender Ribbon UI styling:
        private const val RIBBON_WIDTH_DP = 2.8f
        private const val MASK_GAP_DP = 8
        private const val MASK_TRAILING_DP = 4
        private const val ROW_GAP_DP = 3
        private const val RATING_GAP_DP = 7
        private const val MASK_TRAVEL = 1.08f

        // Typography (restored to original values per user request):
        private const val RATING_TEXT_MIN_SP = 17.6f
        private const val RATING_TEXT_MAX_SP = 23.2f
        private const val RATING_TEXT_VW = 0.0155f
        private const val REASON_TEXT_MIN_SP = 14.08f
        private const val REASON_TEXT_MAX_SP = 16.8f
        private const val REASON_TEXT_VW = 0.0115f

        // Statutory Anti-Tobacco Warning:
        private const val TOBACCO_TEXT_MIN_SP = 14.08f
        private const val TOBACCO_TEXT_MAX_SP = 16.8f
        private const val TOBACCO_TEXT_VW = 0.0115f
        private const val TOBACCO_WARNING_TEXT = "Consumption of tobacco products is injurious to health"

        private const val VIGNETTE_WIDTH_RATIO = 0.72f
        private const val VIGNETTE_ASPECT = 320f / 680f
        private const val VIGNETTE_MAX_HEIGHT_RATIO = 0.55f
        private const val POLL_INTERVAL_MS = 100L
        private const val WATCH_INTERVAL_MS = 500L
        private const val FRAGMENT_CACHE_MS = 2_000L
        private const val HEARTBEAT_MS = 2_000L
        private const val POSITION_STALE_MS = 2_000L

        private const val DEMO_PREFIX = "RATED"
        private const val DEMO_RATING = "A"
        private const val DEMO_TEXT = "self-harm, sex, violence, substances, suicide, tobacco use"

        private val DEFAULT_RIBBON_COLOR = Color.parseColor("#E50914")
        private val REASON_COLOR = Color.parseColor("#E2E2E2")

        private val IN_EASE = PathInterpolator(0.16f, 1f, 0.3f, 1f)
        private val OUT_EASE = PathInterpolator(0.65f, 0f, 0.35f, 1f)
        private val EASE = PathInterpolator(0.25f, 0.1f, 0.25f, 1f)
        private val LINEAR = LinearInterpolator()

        @Volatile
        private var instance: MovyAdvisoryOverlay? = null

        private fun dpAt(context: Context, value: Int): Int =
            (value * context.resources.displayMetrics.density + 0.5f).toInt()

        private fun findActiveActivity(): Activity? {
            return try {
                com.lagradost.cloudstream3.CommonActivity.activity
            } catch (t: Throwable) {
                null
            } ?: try {
                val activityThreadClass = Class.forName("android.app.ActivityThread")
                val activityThread = activityThreadClass.getMethod("currentActivityThread").invoke(null)
                val activitiesField = activityThreadClass.getDeclaredField("mActivities").apply { isAccessible = true }
                val activities = activitiesField.get(activityThread) as? Map<*, *>
                activities?.values?.mapNotNull { record ->
                    val activityField = record?.javaClass?.getDeclaredField("activity")?.apply { isAccessible = true }
                    activityField?.get(record) as? Activity
                }?.firstOrNull { !it.isFinishing && !it.isDestroyed }
            } catch (t: Throwable) {
                null
            }
        }

        fun install(context: Context) {
            val application = context.applicationContext as? Application ?: return
            if (instance != null) return
            synchronized(MovyAdvisoryOverlay::class.java) {
                if (instance != null) return
                val overlay = MovyAdvisoryOverlay(application)
                application.registerActivityLifecycleCallbacks(overlay)
                instance = overlay

                val currentActivity = findActiveActivity() ?: (context as? Activity)
                if (currentActivity != null && !currentActivity.isFinishing && !currentActivity.isDestroyed) {
                    overlay.onActivityResumed(currentActivity)
                }
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
