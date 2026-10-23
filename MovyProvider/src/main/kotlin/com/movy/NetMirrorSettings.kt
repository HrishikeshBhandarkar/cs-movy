package com.movy

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.app.Dialog
import android.content.Context
import android.content.ClipboardManager
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.view.Window
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.lagradost.cloudstream3.CommonActivity
import java.lang.ref.WeakReference

object NetMirrorSettings {
    private const val VERIFY_URL = "https://net77.cc/verify.php"
    private const val LOGIN_URL = "https://net77.cc/login"
    private const val MOBILE_HOME = "https://net77.cc/mobile/home?app=1"
    private const val MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 5 Build/TQ3A.230901.001; wv) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/144.0.7559.132 " +
            "Safari/537.36 /OS.Gatu v3.0"

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var currentActivity: WeakReference<Activity>? = null

    @Volatile
    private var installed = false

    @Volatile
    private var loginShowing = false

    @Volatile
    private var pendingLogin: String? = null

    fun init(context: Context) {
        if (installed) return
        val application = context.applicationContext as? Application ?: return
        synchronized(this) {
            if (installed) return
            installed = true
            application.registerActivityLifecycleCallbacks(
                object : Application.ActivityLifecycleCallbacks {
                    override fun onActivityResumed(activity: Activity) {
                        currentActivity = WeakReference(activity)
                        val reason = pendingLogin
                        if (reason != null) {
                            pendingLogin = null
                            mainHandler.post { showLogin(reason) }
                        }
                    }

                    override fun onActivityDestroyed(activity: Activity) {
                        if (currentActivity?.get() === activity) {
                            currentActivity = null
                            loginShowing = false
                        }
                    }

                    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                    override fun onActivityStarted(activity: Activity) = Unit
                    override fun onActivityPaused(activity: Activity) = Unit
                    override fun onActivityStopped(activity: Activity) = Unit
                    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                }
            )
        }
    }

    fun openManageDialog(context: Context) {
        val activity = resolveActivity(context) ?: return
        runOnUi {
            if (activity.isFinishing || activity.isDestroyed) return@runOnUi
            showManageDialog(activity)
        }
    }

    private fun showManageDialog(activity: Activity) {
        val currentToken = NetMirrorStore.userToken()
        val hasToken = !currentToken.isNullOrBlank()

        val density = activity.resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val smallPad = (8 * density).toInt()

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        val statusText = TextView(activity).apply {
            text = if (hasToken) {
                "Status: Active (Cookie present; expires in 24h)"
            } else {
                "Status: Missing or Expired"
            }
            setTextColor(if (hasToken) Color.rgb(46, 125, 50) else Color.rgb(198, 40, 40))
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, smallPad)
        }
        root.addView(statusText)

        val descText = TextView(activity).apply {
            text = "NetMirror cookies expire every 24 hours. You can replace or paste your fresh user_token / cookie header below at any time."
            setTextColor(Color.DKGRAY)
            textSize = 12f
            setPadding(0, 0, 0, smallPad)
        }
        root.addView(descText)

        val input = EditText(activity).apply {
            hint = "user_token=... or raw token value"
            if (hasToken) {
                setText(currentToken)
            }
            minLines = 3
            textSize = 13f
            setBackgroundResource(android.R.drawable.edit_text)
            setPadding(smallPad * 2, smallPad * 2, smallPad * 2, smallPad * 2)
        }
        root.addView(input)

        val btnRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, smallPad, 0, 0)
        }

        val pasteClipBtn = Button(activity).apply {
            text = "Paste from Clipboard"
            setOnClickListener {
                val clipMgr = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val clipText = clipMgr?.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
                if (!clipText.isNullOrBlank()) {
                    input.setText(clipText)
                    Toast.makeText(activity, "Pasted from clipboard", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(activity, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                }
            }
        }
        btnRow.addView(pasteClipBtn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(btnRow)

        AlertDialog.Builder(activity)
            .setTitle("NetMirror Cookie Manager")
            .setView(root)
            .setPositiveButton("Save & Replace") { _, _ ->
                val text = input.text?.toString().orEmpty()
                NetMirrorStore.saveUserToken(text)
                val saved = NetMirrorStore.userToken()
                Toast.makeText(
                    activity,
                    if (saved == null) "Token was empty" else "NetMirror cookie saved & replaced!",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNeutralButton("Webview Login") { _, _ ->
                loginShowing = true
                openLogin(activity, null)
            }
            .setNegativeButton("Clear") { _, _ ->
                NetMirrorStore.clear()
                Toast.makeText(activity, "NetMirror session cleared", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    fun show(context: Context) {
        val activity = resolveActivity(context) ?: return
        runOnUi {
            if (activity.isFinishing || activity.isDestroyed) return@runOnUi
            showMenu(activity)
        }
    }

    fun showLogin(reason: String? = null) {
        val activity = resolveActivity()
        if (activity == null) {
            pendingLogin = reason ?: "Log in to NetMirror to unlock playback."
            return
        }
        runOnUi {
            if (loginShowing) return@runOnUi
            if (activity.isFinishing || activity.isDestroyed) {
                pendingLogin = reason ?: "Log in to NetMirror to unlock playback."
                return@runOnUi
            }
            loginShowing = true
            openLogin(activity, reason)
        }
    }

    private fun showMenu(activity: Activity) {
        val hasToken = NetMirrorStore.userToken() != null
        val options = arrayOf(
            "Login to NetMirror",
            "Paste user_token",
            "Clear saved session"
        )
        AlertDialog.Builder(activity)
            .setTitle("NetMirror")
            .setMessage(
                if (hasToken) {
                    "A session is saved. Use Login if playback asks for fresh credentials."
                } else {
                    "No user_token saved yet. Log in and save, or paste a token."
                }
            )
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        loginShowing = true
                        openLogin(activity, null)
                    }
                    1 -> pasteToken(activity)
                    2 -> {
                        NetMirrorStore.clear()
                        Toast.makeText(activity, "NetMirror session cleared", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun resolveActivity(context: Context? = null): Activity? {
        val direct = context?.findActivity()
        if (direct != null && !direct.isFinishing && !direct.isDestroyed) return direct
        val common = runCatching { CommonActivity.activity }.getOrNull()
        if (common != null && !common.isFinishing && !common.isDestroyed) return common
        return currentActivity?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
    }

    private fun runOnUi(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private fun openLogin(activity: Activity, reason: String?) {
        var dialog: Dialog? = null
        try {
            openLoginDialog(activity, reason) { created -> dialog = created }
        } catch (error: Throwable) {
            loginShowing = false
            runCatching { dialog?.dismiss() }
            Toast.makeText(
                activity,
                "Login screen failed to open. Use Paste user_token instead.",
                Toast.LENGTH_LONG
            ).show()
            pasteToken(activity)
        }
    }

    private fun openLoginDialog(activity: Activity, reason: String?, onCreated: (Dialog) -> Unit) {
        val dialog = Dialog(activity).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setCanceledOnTouchOutside(false)
        }
        onCreated(dialog)

        val density = activity.resources.displayMetrics.density
        val padding = (12 * density).toInt()

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(Color.rgb(24, 24, 24))
        }

        val title = TextView(activity).apply {
            text = "NetMirror login"
            setTextColor(Color.WHITE)
            textSize = 16f
        }

        val pasteButton = Button(activity).apply {
            text = "Paste"
            setOnClickListener {
                dialog.dismiss()
                pasteToken(activity)
            }
        }

        val saveButton = Button(activity).apply {
            text = "Save & close"
            setOnClickListener {
                val unlocked = captureCookies(activity, notify = false)
                CookieManager.getInstance().flush()
                dialog.dismiss()
                Toast.makeText(
                    activity,
                    if (unlocked) "NetMirror session saved" else
                        "No user_token yet. Log in or play one ad, then save again.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        bar.addView(
            title,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        bar.addView(pasteButton)
        bar.addView(saveButton)

        val webView = WebView(activity).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.userAgentString = MOBILE_UA
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val url = request?.url?.toString().orEmpty()
                    if (isNetMirrorHome(url)) {
                        view?.loadUrl(MOBILE_HOME)
                        return true
                    }
                    return false
                }

                @Deprecated("Deprecated in Java")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                    if (isNetMirrorHome(url)) {
                        view?.loadUrl(MOBILE_HOME)
                        return true
                    }
                    return false
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    if (isNetMirrorHome(url)) {
                        view?.loadUrl(MOBILE_HOME)
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    captureCookies(activity, notify = false)
                }
            }
        }

        val loginButton = Button(activity).apply {
            text = "Login"
            setOnClickListener { webView.loadUrl(LOGIN_URL) }
        }
        bar.addView(loginButton)

        root.addView(bar)
        if (!reason.isNullOrBlank()) {
            root.addView(
                TextView(activity).apply {
                    text = reason
                    setTextColor(Color.rgb(60, 45, 0))
                    setBackgroundColor(Color.rgb(255, 245, 204))
                    setPadding(padding, padding, padding, padding)
                    textSize = 13f
                }
            )
        }
        root.addView(
            webView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        dialog.setContentView(root)
        dialog.setOnDismissListener {
            loginShowing = false
            captureCookies(activity, notify = false)
            CookieManager.getInstance().flush()
            webView.destroy()
        }
        dialog.show()
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        webView.loadUrl(VERIFY_URL)
    }

    private fun isNetMirrorHome(url: String?): Boolean =
        url?.startsWith("https://net77.cc/home") == true ||
            url?.startsWith("https://net52.cc/home") == true

    private fun captureCookies(context: Context, notify: Boolean = true): Boolean {
        val manager = CookieManager.getInstance()
        val values = mutableMapOf<String, String>()
        listOf(
            "https://net77.cc",
            "https://net52.cc"
        ).forEach { url ->
            val header = manager.getCookie(url) ?: return@forEach
            header.split(";").forEach { part ->
                val index = part.indexOf('=')
                if (index > 0) {
                    val key = part.substring(0, index).trim()
                    val value = part.substring(index + 1).trim()
                    if (key.isNotBlank() && value.isNotBlank()) values[key] = value
                }
            }
        }
        if (values.isNotEmpty()) {
            NetMirrorStore.update(values)
        }
        val unlocked = NetMirrorStore.userToken() != null
        if (notify && !unlocked) {
            Toast.makeText(
                context,
                "No user_token yet. Log in or play one ad, then save again.",
                Toast.LENGTH_LONG
            ).show()
        }
        return unlocked
    }

    private fun pasteToken(activity: Activity) {
        val input = EditText(activity).apply {
            hint = "user_token=... or the raw token value"
            setSingleLine(false)
            minLines = 2
            setPadding(16, 16, 16, 16)
        }
        AlertDialog.Builder(activity)
            .setTitle("NetMirror user_token")
            .setMessage("Paste the user_token cookie from your logged-in NetMirror browser session.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                NetMirrorStore.saveUserToken(input.text?.toString().orEmpty())
                val saved = NetMirrorStore.userToken()
                Toast.makeText(
                    activity,
                    if (saved == null) "Token was empty" else "Token saved",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun Context.findActivity(): Activity? {
        var current: Context = this
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return current as? Activity
    }
}
