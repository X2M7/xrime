/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.DocumentsContract
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.osfans.trime.core.RimeMaintenanceMutex
import com.osfans.trime.core.data.SafDirectoryListing
import com.osfans.trime.core.data.UserDataManager
import com.osfans.trime.ui.main.EditorProbeActivity
import io.github.whiredplanck.storageaccess.StorageAccess
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Dedicated API 24 emulator only: create a new folder through the real system picker. */
class Api24SafPickerInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    private fun nodes(node: AccessibilityNodeInfo): Sequence<AccessibilityNodeInfo> = sequence {
        yield(node)
        for (i in 0 until node.childCount) node.getChild(i)?.let { yieldAll(nodes(it)) }
    }

    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? =
        uiAutomation.rootInActiveWindow?.let { nodes(it).firstOrNull(predicate) }

    private fun awaitNode(description: String, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            find(predicate)?.let { return it }
            SystemClock.sleep(100)
        }
        error("Picker did not show $description")
    }

    private fun click(node: AccessibilityNodeInfo) {
        var target: AccessibilityNodeInfo? = node
        repeat(16) {
            val current = target ?: return@repeat
            if (current.isClickable && current.isEnabled && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            target = current.parent
        }
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        check(!bounds.isEmpty) { "Picker element has no bounds" }
        val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "saf-picker-touch").apply { isDaemon = true } }
        try {
            val touch = executor.submit<Boolean> {
                val now = SystemClock.uptimeMillis()
                val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, bounds.exactCenterX(), bounds.exactCenterY(), 0)
                val up = MotionEvent.obtain(now, now + 30, MotionEvent.ACTION_UP, bounds.exactCenterX(), bounds.exactCenterY(), 0)
                down.source = InputDevice.SOURCE_TOUCHSCREEN
                up.source = InputDevice.SOURCE_TOUCHSCREEN
                try {
                    uiAutomation.injectInputEvent(down, true) && uiAutomation.injectInputEvent(up, true)
                } finally {
                    down.recycle()
                    up.recycle()
                }
            }
            check(touch.get(5, TimeUnit.SECONDS)) { "Picker touch was rejected" }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun clickText(text: String) = click(awaitNode(text) { it.text?.toString() == text && it.isEnabled })
    private fun clickDescription(text: String) = click(awaitNode(text) { it.contentDescription?.toString() == text && it.isEnabled })

    private fun inToolbar(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(16) {
            val item = current ?: return false
            if (item.viewIdResourceName == "com.android.documentsui:id/toolbar") return true
            current = item.parent
        }
        return false
    }

    override fun onStart() {
        val result = Bundle()
        var activity: Activity? = null
        var source: File? = null
        var resultCode = Activity.RESULT_CANCELED
        fun phase(name: String) = sendStatus(0, Bundle().apply { putString("stream", "API24 SAF: $name\n") })
        try {
            check(Build.VERSION.SDK_INT == 24 && Build.HARDWARE in setOf("ranchu", "goldfish")) { "API 24 emulator only" }
            val context = targetContext
            val marker = File(checkNotNull(context.getExternalFilesDir(null)), "runtime-audit-dedicated")
            check(marker.isFile && marker.length() == 0L) { "Dedicated test installation required" }
            val leaf = "trime-saf-${UUID.randomUUID()}"
            val tree = DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents", "primary:$leaf")
            phase("launching picker for $leaf")
            val monitor = addMonitor(EditorProbeActivity::class.java.name, null, false)
            val host = try {
                context.startActivity(
                    Intent(context, EditorProbeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra("api24_saf_nonce", leaf),
                )
                checkNotNull(waitForMonitorWithTimeout(monitor, 15_000)) { "Test host activity did not start" }
            } finally {
                removeMonitor(monitor)
            }
            check(host.intent.getStringExtra("api24_saf_nonce") == leaf) { "Unexpected test host activity" }
            activity = host
            val picker = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
            )
            runOnMainSync { host.startActivityForResult(picker, 4401) }
            awaitNode("DocumentsUI") { it.packageName?.toString() == "com.android.documentsui" }
            phase("picker visible; finding internal storage")
            if (find { it.text?.toString() == "Android SDK built for x86_64" && it.viewIdResourceName == "android:id/title" } == null) {
                clickDescription("Show roots")
            }
            if (find { it.text?.toString() == "Android SDK built for x86_64" && it.viewIdResourceName == "android:id/title" } == null) {
                clickDescription("More options")
                clickText("Show internal storage")
                clickDescription("Show roots")
            }
            click(awaitNode("emulator internal storage") {
                it.text?.toString() == "Android SDK built for x86_64" && it.viewIdResourceName == "android:id/title"
            })
            phase("internal storage selected; waiting for drawer")
            // The drawer animates closed after selection; a premature menu tap only dismisses it.
            awaitNode("internal storage toolbar") {
                it.text?.toString() == "Android SDK built for x86_64" && inToolbar(it) && it.isVisibleToUser
            }
            val drawerDeadline = SystemClock.uptimeMillis() + 15_000
            while (find { it.viewIdResourceName == "com.android.documentsui:id/drawer_roots" && it.isVisibleToUser } != null &&
                SystemClock.uptimeMillis() < drawerDeadline
            ) SystemClock.sleep(100)
            check(find { it.viewIdResourceName == "com.android.documentsui:id/drawer_roots" && it.isVisibleToUser } == null) {
                "Picker root drawer did not close"
            }
            clickDescription("More options")
            clickText("New folder")
            phase("creating unique directory")
            val input = awaitNode("new folder input") { it.viewIdResourceName == "android:id/text1" && it.isEditable }
            check(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, leaf)
            })) { "Cannot set unique folder name" }
            clickText("OK")
            val created = awaitNode("new folder") { it.text?.toString() == leaf }
            if (!inToolbar(created)) click(created)
            awaitNode("selected folder title") { it.text?.toString() == leaf && inToolbar(it) && it.isVisibleToUser }
            clickText("SELECT")
            phase("waiting for exact persisted grant")
            val resolver = context.contentResolver
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            val deadline = SystemClock.uptimeMillis() + 15_000
            var persisted = false
            while (!persisted && SystemClock.uptimeMillis() < deadline) {
                persisted = runCatching { resolver.takePersistableUriPermission(tree, flags); true }.getOrDefault(false)
                if (!persisted) SystemClock.sleep(100)
            }
            check(persisted && resolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission && it.isWritePermission }) {
                "Picker did not grant the expected tree"
            }
            val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            check(SafDirectoryListing.list(resolver, root).isEmpty()) { "New test folder is not empty" }
            phase("running production replacement and read-back")
            val inputFile = File.createTempFile("api24-saf-", ".txt", context.cacheDir)
            source = inputFile
            runBlocking {
                val unrelated = checkNotNull(DocumentsContract.createDocument(resolver, root, "text/plain", "unrelated.txt"))
                checkNotNull(resolver.openOutputStream(unrelated)).use { it.write("must survive".toByteArray()) }
                for (payload in listOf("first", "second\n".repeat(256))) {
                    inputFile.writeText(payload)
                    RimeMaintenanceMutex.withLock { UserDataManager.replaceExternalFileForTesting(inputFile, root, "config.yaml") }
                    val document = checkNotNull(StorageAccess.child(root, "config.yaml"))
                    check(StorageAccess.readFile(document.uri) { it.bufferedReader().readText() } == payload)
                }
                check(SafDirectoryListing.list(resolver, root).map { it.name }.toSet() == setOf("config.yaml", "unrelated.txt"))
                check(StorageAccess.readFile(unrelated) { it.bufferedReader().readText() } == "must survive")
            }
            result.putBoolean("passed", true)
            result.putString("tree", tree.toString())
            result.putString("stream", "PASS: API 24 system picker grant and production SAF replacement\n")
            resultCode = Activity.RESULT_OK
        } catch (failure: Throwable) {
            result.putBoolean("passed", false)
            result.putString("stream", "FAIL: ${failure.stackTraceToString()}\n")
        } finally {
            source?.delete()
            activity?.let { host -> runOnMainSync { host.finish() } }
            finish(resultCode, result)
        }
    }
}
