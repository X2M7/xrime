/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime

import android.app.Instrumentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.os.SystemClock
import android.view.inputmethod.InputMethodManager
import android.view.inspector.WindowInspector
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.room.Room
import androidx.viewpager2.widget.ViewPager2
import com.osfans.trime.data.db.AdmittedClipboardImage
import com.osfans.trime.data.db.ClipboardImageRepository
import com.osfans.trime.data.db.Database
import com.osfans.trime.data.db.ImageCaptureAuthorization
import com.osfans.trime.data.db.ImageStagingBudget
import com.osfans.trime.data.db.PrivateImageByteStore
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.ime.clipboard.ClipboardWindow
import com.osfans.trime.ime.clipboard.ClipboardLayout
import com.osfans.trime.ime.clipboard.ClipboardImageAdapter
import com.osfans.trime.ime.core.InputView
import com.osfans.trime.ime.core.PrivacyModeState
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ime.window.BoardWindowManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.kodein.di.direct
import org.kodein.di.instance
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/** Real page and editor lifecycle with an isolated database and private image directory. */
internal object ClipboardImageUiProbe {
    suspend fun verify(instrumentation: Instrumentation, service: TrimeInputMethodService, editor: EditText, input: () -> InputView?) {
        fun <T> main(block: () -> T): T {
            var result: Result<T>? = null
            instrumentation.runOnMainSync { result = runCatching(block) }
            return checkNotNull(result).getOrThrow()
        }
        fun descendants(root: View): Sequence<View> = sequence {
            yield(root)
            if (root is ViewGroup) for (i in 0 until root.childCount) yieldAll(descendants(root.getChildAt(i)))
        }
        var diagnosticWindow: ClipboardWindow? = null
        fun imageDiagnostic(): String {
            val root = input()
            val window = diagnosticWindow
            val adapter = window?.let {
                ClipboardWindow::class.java.getDeclaredField("imageAdapter").apply { isAccessible = true }.get(it) as? ClipboardImageAdapter
            }
            fun field(name: String): Any? = adapter?.let {
                ClipboardImageAdapter::class.java.getDeclaredField(name).apply { isAccessible = true }.get(it)
            }
            val lists = root?.let { descendants(it).filterIsInstance<androidx.recyclerview.widget.RecyclerView>().filter { list ->
                list.isShown && list.adapter === adapter
            }.map { list ->
                val holders = (0 until list.childCount).mapNotNull { index ->
                    val holder = list.getChildViewHolder(list.getChildAt(index)) as? ClipboardImageAdapter.Holder ?: return@mapNotNull null
                    "position=${holder.bindingAdapterPosition},jobActive=${holder.imageJob?.isActive}," +
                        "jobDone=${holder.imageJob?.isCompleted},jobCancelled=${holder.imageJob?.isCancelled}," +
                        "bitmap=${holder.image.drawable != null},failed=${holder.label.text == service.getString(R.string.clipboard_image_load_failed)}"
                }
                "count=${list.adapter?.itemCount},holders=$holders"
            }.toList() }
            val nativeBound = TrimeInputMethodService::class.java.getDeclaredField("nativeBoundEditorToken").apply { isAccessible = true }.getLong(service)
            return "attached=${window?.let { root?.di?.direct?.instance<BoardWindowManager>()?.isAttached(it) }}," +
                "nativeBound=$nativeBound,adapterGeneration=${field("generation")},adapterEpoch=${field("privacyEpoch")}," +
                "adapterOwner=${field("editorToken")},imageLists=$lists"
        }
        suspend fun until(label: String, condition: () -> Boolean) {
            try {
                withTimeout(20_000) { while (!main(condition)) delay(25) }
            } catch (failure: TimeoutCancellationException) {
                val details = main {
                    val root = input()
                    val pages = root?.let { descendants(it).filterIsInstance<ViewPager2>().filter { pager -> pager.isShown }
                        .map { pager -> "page=${pager.currentItem},pages=${pager.adapter?.itemCount}" }.toList() }
                    val lists = root?.let { descendants(it).filterIsInstance<androidx.recyclerview.widget.RecyclerView>()
                        .filter { list -> list.isShown }.map { list -> list.adapter?.itemCount }.toList() }
                    val unavailable = root?.let { descendants(it).filterIsInstance<TextView>().any { view ->
                        view.isShown && view.text.toString() == service.getString(R.string.clipboard_images_unavailable)
                    } }
                    "field=${service.currentInputEditorInfo?.fieldId},expected=${editor.id},owner=${service.editorToken}, " +
                        "private=${service.isPrivacyModeActive},ordinaryEpoch=${PrivacyModeState.observeOrdinaryPersistence()}, " +
                        "inputShown=${root?.isShown},pages=$pages,lists=$lists,unavailable=$unavailable,${imageDiagnostic()}"
                }
                throw IllegalStateException("Timed out waiting for $label: $details", failure)
            }
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "PASS: $label\n") })
        }
        val name = "image-ui-${UUID.randomUUID()}.db"
        val directory = File(service.cacheDir, "image-ui-${UUID.randomUUID()}")
        check(directory.mkdir())
        val database = Room.databaseBuilder(service, Database::class.java, name).build()
        val repository = ClipboardImageRepository(database, PrivateImageByteStore(directory))
        val privacy = AppPrefs.defaultInstance().general.manualPrivacyMode
        val previousPrivacy = privacy.getValue()
        val imm = editor.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        var secondary: EditText? = null
        try {
            val bitmap = Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888)
            val encoded = ByteArrayOutputStream()
            try { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, encoded)) } finally { bitmap.recycle() }
            val authorization = checkNotNull(ImageCaptureAuthorization.acquire { true })
            val permit = authorization.acquirePersistencePermit()
            val saved = try {
                val store = PrivateImageByteStore(directory)
                val staged = store.stage(ByteArrayInputStream(encoded.toByteArray()), authorization::requireAllowed)
                repository.save(staged, AdmittedClipboardImage(Uri.parse("content://fixture/image"), setOf("image/png"), authorization), authorization, permit)
            } finally { PrivacyModeState.releasePersistencePermit(permit) }
            fun showImagePage(): ClipboardWindow = main {
                val root = checkNotNull(input())
                val window = ClipboardWindow(root.di, 2, repository)
                diagnosticWindow = window
                root.di.direct.instance<BoardWindowManager>().attachWindow(window)
                window
            }
            fun page(): ViewPager2? = input()?.let { descendants(it).filterIsInstance<ViewPager2>().firstOrNull { pager -> pager.isShown } }
            fun visibleImage(): ImageView? = input()?.let { root ->
                descendants(root).filterIsInstance<ImageView>().firstOrNull { it.isShown && it.drawable is BitmapDrawable }
            }
            fun activeManager() = checkNotNull(input()).di.direct.instance<BoardWindowManager>()
            val heldLock = ImageStagingBudget.acquireExclusive(directory.canonicalFile)
            var window: ClipboardWindow
            try {
                window = showImagePage()
                until("busy image offers retry") {
                    input()?.let { root -> descendants(root).filterIsInstance<TextView>().any {
                        it.isShown && it.text.toString() == service.getString(R.string.clipboard_image_load_failed)
                    } } == true
                }
            } finally { heldLock.close() }
            main {
                val label = descendants(checkNotNull(input())).filterIsInstance<TextView>().first {
                    it.isShown && it.text.toString() == service.getString(R.string.clipboard_image_load_failed)
                }
                check((label.parent as View).performClick())
            }
            until("image page displays ready private thumbnail") { activeManager().isAttached(window) && page()?.currentItem == 2 && visibleImage() != null }
            main {
                val row = checkNotNull(visibleImage()).parent as ViewGroup
                check(row.hasOnClickListeners() && (row.getChildAt(1) as TextView).text.toString() != service.getString(R.string.clipboard_image_load_failed)) {
                    "Successful image binding retained the retry state"
                }
            }
            main {
                val layout = ClipboardWindow::class.java.getDeclaredField("clipboardLayout").apply { isAccessible = true }.get(window) as ClipboardLayout
                check(layout.viewPager.adapter?.itemCount == 3)
                check(layout.titleUi.deleteAllButton.visibility == View.GONE) { "Image page routed delete-all to a different category" }
                check(layout.titleUi.returnAfterPasteButton.visibility == View.GONE) { "Image page exposes a paste-only setting" }
            }
            val oldImage = main { checkNotNull(visibleImage()) }
            val previousToken = main { service.editorToken }
            main { imm.restartInput(editor) }
            until("ordinary editor restart clears image page") { service.editorToken != previousToken && oldImage.drawable == null && page()?.currentItem != 2 }
            window = showImagePage()
            until("image page reloads after editor restart") { activeManager().isAttached(window) && visibleImage() != null }
            val privateImage = main { checkNotNull(visibleImage()) }
            main { privacy.setValue(true) }
            until("privacy transition removes image thumbnail") { service.isPrivacyModeActive && privateImage.drawable == null && !activeManager().isAttached(window) }
            main { privacy.setValue(previousPrivacy) }
            until("ordinary editor restored after privacy") { !service.isPrivacyModeActive }
            window = showImagePage()
            until("image page reloads after privacy") { activeManager().isAttached(window) && visibleImage() != null }
            val hiddenImage = main { checkNotNull(visibleImage()) }
            main { imm.hideSoftInputFromWindow(editor.windowToken, 0) }
            until("hiding input clears image thumbnail") { hiddenImage.drawable == null }
            main { imm.showSoftInput(editor, 0) }
            until("editor returns after hide") { input()?.isShown == true && service.currentInputEditorInfo?.fieldId == editor.id }
            window = showImagePage()
            until("image page reloads before focus change") { activeManager().isAttached(window) && visibleImage() != null }
            val switchedImage = main { checkNotNull(visibleImage()) }
            val parent = main { editor.parent as ViewGroup }
            val secondEditor = main {
                EditText(editor.context).apply {
                    id = View.generateViewId()
                    parent.addView(this, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 72))
                    requestFocus()
                    imm.showSoftInput(this, 0)
                }
            }
            secondary = secondEditor
            until("new editor clears prior image page") {
                service.currentInputEditorInfo?.fieldId == secondEditor.id && switchedImage.drawable == null && page()?.currentItem != 2
            }
            main {
                (secondEditor.parent as? ViewGroup)?.removeView(secondEditor)
                secondary = null
                editor.requestFocus()
                imm.showSoftInput(editor, 0)
            }
            until("original editor returns for image actions") { service.currentInputEditorInfo?.fieldId == editor.id && input()?.isShown == true }
            val sentinel = main { editor.text.toString() }
            window = showImagePage()
            try {
                until("image page reloads before action checks") { activeManager().isAttached(window) && visibleImage() != null }
            } catch (failure: IllegalStateException) {
                val details = main {
                    val root = input()
                    val pages = root?.let { descendants(it).filterIsInstance<ViewPager2>().filter { pager -> pager.isShown }.map { pager ->
                        "page=${pager.currentItem},pages=${pager.adapter?.itemCount}"
                    }.toList() }
                    val lists = root?.let { descendants(it).filterIsInstance<androidx.recyclerview.widget.RecyclerView>().filter { list -> list.isShown }
                        .map { list -> list.adapter?.itemCount }.toList() }
                    val unavailable = root?.let { descendants(it).filterIsInstance<TextView>().any { view ->
                        view.isShown && view.text.toString() == service.getString(R.string.clipboard_images_unavailable)
                    } }
                    val adapter = ClipboardWindow::class.java.getDeclaredField("imageAdapter").apply { isAccessible = true }.get(window) as? ClipboardImageAdapter
                    fun adapterField(name: String): Any? = adapter?.let {
                        ClipboardImageAdapter::class.java.getDeclaredField(name).apply { isAccessible = true }.get(it)
                    }
                    val holders = root?.let { descendants(it).filterIsInstance<androidx.recyclerview.widget.RecyclerView>().filter { list ->
                        list.isShown && list.adapter === adapter
                    }.flatMap { list -> (0 until list.childCount).asSequence().mapNotNull { index ->
                        val holder = list.getChildViewHolder(list.getChildAt(index)) as? ClipboardImageAdapter.Holder ?: return@mapNotNull null
                        "position=${holder.bindingAdapterPosition},jobActive=${holder.imageJob?.isActive}," +
                            "jobDone=${holder.imageJob?.isCompleted},jobCancelled=${holder.imageJob?.isCancelled}," +
                            "bitmap=${holder.image.drawable != null},failed=${holder.label.text == service.getString(R.string.clipboard_image_load_failed)}"
                    } }.toList() }
                    val nativeBound = TrimeInputMethodService::class.java.getDeclaredField("nativeBoundEditorToken").apply { isAccessible = true }.getLong(service)
                    "attached=${root?.di?.direct?.instance<BoardWindowManager>()?.isAttached(window)}, " +
                        "field=${service.currentInputEditorInfo?.fieldId},expected=${editor.id},owner=${service.editorToken}, " +
                        "private=${service.isPrivacyModeActive},ordinaryEpoch=${PrivacyModeState.observeOrdinaryPersistence()}, " +
                        "inputShown=${root?.isShown},$pages,lists=$lists,unavailable=$unavailable,nativeBound=$nativeBound," +
                        "adapterGeneration=${adapterField("generation")},adapterEpoch=${adapterField("privacyEpoch")}," +
                        "adapterOwner=${adapterField("editorToken")},holders=$holders"
                }
                throw IllegalStateException("Image reload timed out: $details", failure)
            }
            repeat(10) {
                val shown = main { checkNotNull(visibleImage()) }
                main { checkNotNull(page()).setCurrentItem(0, false) }
                until("image page releases thumbnail on tab change") { shown.drawable == null && page()?.currentItem == 0 }
                main { checkNotNull(page()).setCurrentItem(2, false) }
                until("image page survives repeated tab changes") { page()?.currentItem == 2 && visibleImage() != null }
            }
            if (Build.VERSION.SDK_INT >= 29) {
                fun menuText(text: String): TextView? = WindowInspector.getGlobalWindowViews().asSequence().flatMap(::descendants)
                    .filterIsInstance<TextView>().firstOrNull { it.isShown && it.text.toString() == text }
                fun menuRow(text: String): View {
                    var row: View = checkNotNull(menuText(text))
                    while (!row.isClickable) row = row.parent as? View ?: error("Image menu item has no clickable row")
                    return row
                }
                fun openMenu() = main { check((checkNotNull(visibleImage()).parent as View).performLongClick()) }
                fun tapMenu(text: String) {
                    val point = main {
                        val view = checkNotNull(menuText(text))
                        val location = IntArray(2).also(view::getLocationOnScreen)
                        (location[0] + view.width / 2f) to (location[1] + view.height / 2f)
                    }
                    val now = SystemClock.uptimeMillis()
                    for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                        val event = MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, point.first, point.second, 0)
                        try { instrumentation.sendPointerSync(event) } finally { event.recycle() }
                    }
                }
                openMenu()
                until("image pin action appears") { menuText(service.getString(R.string.simple_key_pin)) != null }
                tapMenu(service.getString(R.string.simple_key_pin))
                withTimeout(20_000) { while (database.clipboardImagePayloadDao().get(saved.id)?.pinned != true) delay(25) }
                openMenu()
                until("image delete action appears") { menuText(service.getString(R.string.delete)) != null }
                val staleDelete = main { menuRow(service.getString(R.string.delete)) }
                val tokenBeforeRestart = main { service.editorToken }
                main { imm.restartInput(editor) }
                until("image popup invalidated on editor restart") { service.editorToken != tokenBeforeRestart && !staleDelete.isShown }
                main { staleDelete.performClick() }
                delay(250)
                check(database.clipboardImagePayloadDao().get(saved.id) != null) { "Stale image action deleted a record" }
                window = showImagePage()
                until("image page reloads for deletion") { activeManager().isAttached(window) && visibleImage() != null }
                openMenu()
                until("current image delete action appears") { menuText(service.getString(R.string.delete)) != null }
                tapMenu(service.getString(R.string.delete))
                withTimeout(20_000) { while (database.clipboardImagePayloadDao().get(saved.id) != null) delay(25) }
                check(main { editor.text.toString() == sentinel }) { "Image actions changed the editor text" }
                instrumentation.sendStatus(0, Bundle().apply { putString("stream", "PASS: image pin, stale menu rejection, deletion and editor isolation\n") })
            }
        } finally {
            main {
                privacy.setValue(previousPrivacy)
                secondary?.let { (it.parent as? ViewGroup)?.removeView(it) }
                editor.requestFocus()
                imm.showSoftInput(editor, 0)
                input()?.di?.direct?.instance<BoardWindowManager>()?.attachWindow(KeyboardWindow)
            }
            until("original editor restored after image page probe") {
                service.currentInputEditorInfo?.fieldId == editor.id && input()?.isShown == true && !service.isPrivacyModeActive
            }
            database.close()
            service.deleteDatabase(name)
            check(directory.deleteRecursively())
        }
    }
}
