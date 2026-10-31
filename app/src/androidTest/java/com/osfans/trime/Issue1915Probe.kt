// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.Region
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.inspector.WindowInspector
import android.widget.EditText
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.children
import com.osfans.trime.core.RimeApi
import com.osfans.trime.core.data.DataManager
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.data.theme.ThemeYaml
import com.osfans.trime.ime.bar.ui.ToolButton
import com.osfans.trime.ime.core.InputView
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.KeyboardView
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ui.main.EditorProbeActivity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import org.kodein.di.direct
import org.kodein.di.instance
import splitties.dimensions.dp
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** #1915 draft: only run on the dedicated empty API 36 emulator. */
internal object Issue1915Probe {
    private const val PATCH = "patch:\n  style/keyboard_height: 280\n"
    private const val NAME = "issue1915-original-toolbar"

    fun run(instrumentation: Instrumentation) {
        val result = Bundle()
        var failure: Throwable? = null
        var host: Activity? = null
        var session: RimeSession? = null
        var targetService: TrimeInputMethodService? = null
        var originalAutomationFlags: Int? = null
        var visibleKeyboardBounds: Rect? = null
        var custom: File? = null
        var artifact: File? = null
        var backup: ByteArray? = null
        var backupFile: File? = null
        var capturedNative = false
        var artifactExisted = false
        var deploymentStarted = false
        var ownsCustom = false
        var originalTheme: String? = null
        var originalThemePresent = false
        var originalThemeRaw: String? = null
        var originalSchema = ""
        var originalAscii = false
        var originalFullShape = false
        val keyboardPrefs = AppPrefs.defaultInstance().keyboard
        val prefs = keyboardPrefs.keyboardHeight.sharedPreferences
        val originalHeightPresent = prefs.contains(AppPrefs.Keyboard.KEYBOARD_HEIGHT)
        val originalHeightRaw = prefs.getInt(AppPrefs.Keyboard.KEYBOARD_HEIGHT, 0)
        val originalManualPresent = prefs.contains(AppPrefs.Keyboard.MANUAL_LANGUAGE_MODE)
        val originalManualRaw = prefs.getString(AppPrefs.Keyboard.MANUAL_LANGUAGE_MODE, null)
        fun <T> main(block: () -> T): T {
            if (Looper.myLooper() == Looper.getMainLooper()) return block()
            var outcome: Result<T>? = null
            instrumentation.runOnMainSync { outcome = runCatching(block) }
            return checkNotNull(outcome).getOrThrow()
        }
        fun <T> api(block: suspend RimeApi.() -> T): T = runBlocking {
            withTimeout(30_000) { checkNotNull(session).runOnReady(block) }
        }
        fun descendants(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) view.children.forEach { yieldAll(descendants(it)) }
        }
        fun input(): InputView? = WindowInspector.getGlobalWindowViews().asSequence()
            .flatMap(::descendants).filterIsInstance<InputView>().firstOrNull { it.isShown }
        fun await(label: String, predicate: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 30_000
            while (SystemClock.uptimeMillis() < deadline) {
                if (main(predicate)) return
                SystemClock.sleep(50)
            }
            error("Timed out: $label")
        }
        fun phase(text: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "PASS: $text\n") })
        fun screenshot(label: String) {
            check(label.matches(Regex("[a-z0-9_]+")))
            val root = main { checkNotNull(input()).rootView }
            val (bitmap, rendering) = RenderedScreenshotBarrier.capture(instrumentation, root) {
                check(root.isAttachedToWindow && root.isShown && input()?.rootView === root)
            }
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "RENDER: $label $rendering\n") })
            try {
                val bounds = checkNotNull(visibleKeyboardBounds)
                check(bounds.left >= 0 && bounds.top >= 0 && bounds.right <= bitmap.width && bounds.bottom <= bitmap.height)
                val colors = mutableSetOf<Int>()
                for (y in bounds.top until bounds.bottom step 6) {
                    for (x in bounds.left until bounds.right step 6) colors.add(bitmap.getPixel(x, y) and 0x00f0f0f0)
                }
                check(colors.size >= 3) { "Rendered screen does not contain a visible keyboard: $label bounds=$bounds" }
                val file = File.createTempFile("issue1915-$label-", ".png", instrumentation.targetContext.cacheDir)
                file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                instrumentation.sendStatus(0, Bundle().apply { putString("stream", "EVIDENCE: $label ${file.absolutePath}\n") })
            } finally {
                bitmap.recycle()
            }
        }
        fun tap(x: Int, y: Int) {
            val now = SystemClock.uptimeMillis()
            val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat(), 0)
            val up = MotionEvent.obtain(now, now + 35, MotionEvent.ACTION_UP, x.toFloat(), y.toFloat(), 0)
            down.source = InputDevice.SOURCE_TOUCHSCREEN
            up.source = InputDevice.SOURCE_TOUCHSCREEN
            try {
                check(instrumentation.uiAutomation.injectInputEvent(down, true)) { "Touch DOWN rejected" }
                check(instrumentation.uiAutomation.injectInputEvent(up, true)) { "Touch UP rejected" }
            } finally {
                down.recycle()
                up.recycle()
            }
        }
        try {
            instrumentation.waitForIdleSync()
            check(Build.VERSION.SDK_INT == 36 && Build.HARDWARE in setOf("ranchu", "goldfish")) { "Dedicated API 36 emulator required" }
            val marker = File(checkNotNull(instrumentation.targetContext.getExternalFilesDir(null)), "issue1915-dedicated-v1")
            check(marker.isFile && marker.length() == 0L) { "Dedicated issue1915 marker required" }
            val automation = instrumentation.uiAutomation
            originalAutomationFlags = automation.serviceInfo.flags
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            }
            val user = DataManager.userDataDir.canonicalFile
            val staging = DataManager.stagingDir.canonicalFile
            custom = File(user, "tongwenfeng.trime.custom.yaml")
            artifact = File(staging, "tongwenfeng.trime.yaml")
            check(checkNotNull(custom).absoluteFile.parentFile == user && !checkNotNull(custom).exists() && !Files.isSymbolicLink(checkNotNull(custom).toPath())) {
                "Original custom file exists or is a symbolic link; refusing to overwrite"
            }
            check(checkNotNull(artifact).absoluteFile.parentFile == staging && !Files.isSymbolicLink(checkNotNull(artifact).toPath()))
            artifactExisted = checkNotNull(artifact).exists()
            if (artifactExisted) {
                check(checkNotNull(artifact).isFile && checkNotNull(artifact).length() <= 4L * 1024 * 1024)
                backup = checkNotNull(artifact).readBytes()
                backupFile = File.createTempFile("issue1915-original-artifact-", ".yaml", instrumentation.targetContext.cacheDir)
                checkNotNull(backupFile).writeBytes(checkNotNull(backup))
                check(checkNotNull(backupFile).readBytes().contentEquals(checkNotNull(backup)))
            }
            val themePref = ThemeManager.prefs.selectedTheme
            originalThemePresent = themePref.sharedPreferences.contains(themePref.key)
            originalThemeRaw = themePref.sharedPreferences.getString(themePref.key, null)
            originalTheme = themePref.getValue()
            session = main { RimeDaemon.createSession(NAME) }
            api {
                originalSchema = selectedSchemaId()
                originalAscii = getRuntimeOption("ascii_mode")
                originalFullShape = getRuntimeOption("full_shape")
            }
            capturedNative = true
            val recovery = File.createTempFile("issue1915-recovery-", ".json", instrumentation.targetContext.cacheDir)
            recovery.writeText(org.json.JSONObject().apply {
                put("custom_was_absent", true)
                put("artifact_existed", artifactExisted)
                put("artifact_path", checkNotNull(artifact).absolutePath)
                put("artifact_backup", backupFile?.absolutePath ?: "")
                put("theme_present", originalThemePresent)
                put("theme_raw", originalThemeRaw ?: org.json.JSONObject.NULL)
                put("theme_value", originalTheme)
                put("height_present", originalHeightPresent)
                put("height_raw", originalHeightRaw)
                put("manual_present", originalManualPresent)
                put("manual_raw", originalManualRaw ?: org.json.JSONObject.NULL)
                put("schema", originalSchema)
                put("ascii", originalAscii)
                put("full_shape", originalFullShape)
            }.toString())
            result.putString("recovery_record", recovery.absolutePath)
            check(checkNotNull(custom).createNewFile()) { "Custom fixture path appeared concurrently" }
            ownsCustom = true
            checkNotNull(custom).writeText(PATCH)
            main { keyboardPrefs.keyboardHeight.setValue(0) }
            runBlocking { ThemeManager.init(instrumentation.targetContext.resources.configuration) }
            deploymentStarted = true
            check(api { deployConfigFile("tongwenfeng.trime", "config_version") }) { "Native theme deployment failed" }
            val deployed = ThemeYaml.parser.decodeFromString<Theme>(checkNotNull(artifact).readText())
            check(deployed.style.keyboardHeight == 280) { "Original style/keyboard_height patch was not deployed" }
            check(runBlocking { ThemeManager.selectTheme("tongwenfeng.trime") } == "tongwenfeng.trime")
            check(ThemeManager.activeTheme.style.keyboardHeight == 280)
            api { clearComposition(); check(selectSchema("luna_pinyin")); setRuntimeOption("ascii_mode", false) }
            phase("original tongwenfeng custom patch deployed at 280dp")
            val activity = instrumentation.startActivitySync(
                Intent(instrumentation.targetContext, EditorProbeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            host = activity
            val field = main {
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                EditText(activity).apply {
                    id = View.generateViewId()
                    inputType = InputType.TYPE_CLASS_TEXT
                    imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
                    activity.setContentView(this)
                    requestFocus()
                }
            }
            val manager = activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager
            await("editor focus and window focus before IME request") { field.hasFocus() && field.hasWindowFocus() }
            main { manager.showSoftInput(field, 0) }
            await("real IME editor and 280dp keyboard") {
                val root = input() ?: return@await false
                val service = root.di.direct.instance<TrimeInputMethodService>()
                val model = root.di.direct.instance<KeyboardWindow>().currentKeyboard
                val views = descendants(root).filterIsInstance<KeyboardView>().filter { it.isShown }.toList()
                service.currentInputEditorInfo?.fieldId == field.id && field.hasFocus() &&
                    model.keyboardHeight == root.context.dp(280) && views.size == 1 && views.single().height == model.keyboardHeight
            }
            // A laid-out IME View can exist before WindowManager actually shows it.
            // Require platform IME visibility, a real accessibility IME window and stable
            // keyboard node bounds before taking a rendered screenshot or injecting touches.
            var lastBounds: Rect? = null
            var stableSince = 0L
            var stableBounds: Rect? = null
            val visibilityDeadline = SystemClock.uptimeMillis() + 45_000
            while (SystemClock.uptimeMillis() < visibilityDeadline) {
                val visible = main {
                    field.hasFocus() && field.hasWindowFocus() &&
                        ViewCompat.getRootWindowInsets(field)?.isVisible(WindowInsetsCompat.Type.ime()) == true
                }
                val bounds = if (visible) automation.windows.asSequence()
                    .filter { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                    .mapNotNull { window ->
                        window.root?.findAccessibilityNodeInfosByViewId(
                            instrumentation.targetContext.resources.getResourceName(com.osfans.trime.R.id.keyboard_view),
                        )?.firstOrNull { it.isVisibleToUser }?.let { node -> Rect().also(node::getBoundsInScreen) }
                    }.firstOrNull { it.width() > 100 && it.height() > 100 } else null
                val now = SystemClock.uptimeMillis()
                if (bounds == null || bounds != lastBounds) stableSince = now
                lastBounds = bounds?.let(::Rect)
                if (bounds != null && now - stableSince >= 500) { stableBounds = bounds; break }
                SystemClock.sleep(50)
            }
            val actualBounds = checkNotNull(stableBounds) { "System IME visibility or accessibility bounds did not stabilize; last=$lastBounds" }
            visibleKeyboardBounds = Rect(actualBounds)
            result.putString("keyboard_accessibility_bounds", actualBounds.toShortString())
            targetService = main { checkNotNull(input()).di.direct.instance<TrimeInputMethodService>() }
            phase("focused editor, platform-visible IME, stable accessibility bounds=$actualBounds and actual 280dp keyboard")
            screenshot("initial")

            // The target is each ToolButton's visible rectangle. Empty inter-button margins
            // are deliberately not treated as buttons. Confirm all visible button centers are
            // in the IME window region; then inject physical touches on two reversible toggles.
            fun geometry(): Map<String, Rect> = main {
                val root = checkNotNull(input())
                val service = root.di.direct.instance<TrimeInputMethodService>()
                val decor = checkNotNull(service.window.window).decorView
                val win = IntArray(2).also(decor::getLocationInWindow)
                val screen = IntArray(2).also(decor::getLocationOnScreen)
                val offsetX = screen[0] - win[0]
                val offsetY = screen[1] - win[1]
                val insets = InputMethodService.Insets().also(service::onComputeInsets)
                check(insets.touchableInsets == InputMethodService.Insets.TOUCHABLE_INSETS_REGION)
                val region = Region(insets.touchableRegion).apply { translate(offsetX, offsetY) }
                val buttons = descendants(root).filterIsInstance<ToolButton>().filter { it.isShown && it.isEnabled && it.isClickable }.toList()
                check(buttons.size >= 2) { "No visible toolbar buttons" }
                buttons.forEach { button ->
                    val visible = Rect()
                    check(button.getGlobalVisibleRect(visible))
                    val base = IntArray(2).also(button.rootView::getLocationOnScreen)
                    visible.offset(base[0], base[1])
                    check(!visible.isEmpty && region.contains(visible.centerX(), visible.centerY())) { "Button center outside IME touchable region: $visible" }
                }
                buttons.filter { it.option in setOf("ascii_mode", "full_shape") }.associate { button ->
                    val visible = Rect()
                    check(button.getGlobalVisibleRect(visible))
                    val base = IntArray(2).also(button.rootView::getLocationOnScreen)
                    visible.offset(base[0], base[1])
                    check(visible.width() >= 8 && visible.height() >= 8)
                    check(region.contains(visible.centerX(), visible.top + 2) && region.contains(visible.centerX(), visible.bottom - 3))
                    checkNotNull(button.option) to visible
                }.also { check(it.keys == setOf("ascii_mode", "full_shape")) { "Expected two original tongwenfeng toggles" } }
            }
            val failedTouches = mutableListOf<String>()
            for (option in listOf("ascii_mode", "full_shape")) {
                for (position in listOf("center", "top", "bottom")) {
                    val before = api { getRuntimeOption(option) }
                    val rect = geometry().getValue(option)
                    val y = when (position) { "top" -> rect.top + 2; "bottom" -> rect.bottom - 3; else -> rect.centerY() }
                    tap(rect.centerX(), y)
                    val deadline = SystemClock.uptimeMillis() + 5_000
                    var changed = false
                    while (SystemClock.uptimeMillis() < deadline) {
                        if (api { getRuntimeOption(option) } != before) { changed = true; break }
                        SystemClock.sleep(50)
                    }
                    check(main { field.text.isEmpty() }) { "Toolbar touch reached editor" }
                    if (changed) {
                        phase("actual $option $position touch toggled option; bounds=$rect")
                    } else {
                        val detail = "$option $position did not toggle; bounds=$rect, point=${rect.centerX()},$y"
                        failedTouches += detail
                        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "FAIL: $detail\n") })
                    }
                    screenshot("${option}_${position}")
                }
            }
            result.putInt("actual_touch_checks", 6)
            result.putString("failed_touch_checks", failedTouches.joinToString("; "))
            check(failedTouches.isEmpty()) { "Actual toolbar touches failed: $failedTouches" }
        } catch (error: Throwable) {
            failure = error
        } finally {
            // Run each restoration even if a previous one fails; retain recovery files on disk.
            fun restore(block: () -> Unit) {
                try { block() } catch (error: Throwable) {
                    val previous = failure
                    if (previous == null) failure = error else previous.addSuppressed(error)
                }
            }
            restore {
                targetService?.let { service ->
                    val barrier = CompletableDeferred<Unit>()
                    main { service.postRimeJob { barrier.complete(Unit) } }
                    runBlocking { withTimeout(15_000) { barrier.await() } }
                    instrumentation.waitForIdleSync()
                }
            }
            restore { main { host?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED; host?.finish() } }
            restore {
                if (ownsCustom) {
                    val file = checkNotNull(custom)
                    check(!Files.isSymbolicLink(file.toPath()) && file.isFile && file.readText() == PATCH && file.delete()) {
                        "Could not safely remove owned custom fixture"
                    }
                }
            }
            restore {
                if (deploymentStarted) {
                    val destination = checkNotNull(artifact)
                    check(!Files.isSymbolicLink(destination.toPath()))
                    if (artifactExisted) {
                        val saved = checkNotNull(backupFile).readBytes()
                        check(saved.contentEquals(checkNotNull(backup))) { "Recovery copy changed" }
                        val temporary = File.createTempFile("issue1915-restore-", ".yaml", destination.parentFile)
                        temporary.writeBytes(saved)
                        Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                        check(destination.readBytes().contentEquals(saved))
                    } else if (destination.exists()) {
                        check(destination.isFile && destination.delete())
                    }
                }
            }
            restore { originalTheme?.let { runBlocking { check(ThemeManager.selectTheme(it, forceReload = true) == it) } } }
            restore {
                if (capturedNative && session != null && originalSchema.isNotEmpty()) {
                    api { clearComposition(); check(selectSchema(originalSchema)); setRuntimeOption("ascii_mode", originalAscii); setRuntimeOption("full_shape", originalFullShape) }
                }
            }
            restore {
                instrumentation.waitForIdleSync()
                main {
                    val edit = prefs.edit()
                    if (originalHeightPresent) edit.putInt(AppPrefs.Keyboard.KEYBOARD_HEIGHT, originalHeightRaw) else edit.remove(AppPrefs.Keyboard.KEYBOARD_HEIGHT)
                    if (originalManualPresent) edit.putString(AppPrefs.Keyboard.MANUAL_LANGUAGE_MODE, checkNotNull(originalManualRaw)) else edit.remove(AppPrefs.Keyboard.MANUAL_LANGUAGE_MODE)
                    check(edit.commit())
                    targetService?.keyboardLanguage?.reloadRemembered(keyboardPrefs.manualLanguageMode.getValue())
                }
            }
            restore {
                if (originalTheme != null) {
                    val selected = ThemeManager.prefs.selectedTheme
                    val edit = selected.sharedPreferences.edit()
                    if (originalThemePresent) edit.putString(selected.key, checkNotNull(originalThemeRaw)) else edit.remove(selected.key)
                    check(edit.commit())
                }
            }
            restore { if (session != null) main { RimeDaemon.destroySession(NAME) } }
            restore {
                originalAutomationFlags?.let { original ->
                    instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply { flags = original }
                }
            }
        }
        result.putBoolean("passed", failure == null)
        result.putString("stream", failure?.stackTraceToString() ?: "PASS: original 280dp theme; six real toolbar toggle touches and complete restoration\n")
        instrumentation.finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }
}
