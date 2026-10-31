/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.clipboard

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.view.View
import android.widget.PopupMenu
import androidx.lifecycle.lifecycleScope
import androidx.annotation.Keep
import androidx.paging.LoadState
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.viewpager2.widget.ViewPager2
import com.osfans.trime.R
import com.osfans.trime.data.db.ClipboardHelper
import com.osfans.trime.data.db.ClipboardImageHistory
import com.osfans.trime.data.db.ClipboardImageRepository
import com.osfans.trime.data.db.CollectionHelper
import com.osfans.trime.data.db.DatabaseBean
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.prefs.PreferenceDelegate
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.ime.core.InputTabLayout
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ime.segments.SegmentsWindow
import com.osfans.trime.ime.window.BoardWindow
import com.osfans.trime.ime.window.BoardWindowManager
import com.osfans.trime.ui.main.ClipEditActivity
import com.osfans.trime.util.AppUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import org.kodein.di.DI
import org.kodein.di.instance
import splitties.views.recyclerview.verticalLayoutManager

class ClipboardWindow private constructor(
    di: DI,
    private val initialTab: Int,
    private val search: ClipboardSearchResults?,
    private val imageRepositoryOverride: ClipboardImageRepository? = null,
) : BoardWindow.BarBoardWindow(di) {
    constructor(di: DI, initialTab: Int = 0) : this(di, initialTab, null)
    internal constructor(di: DI, search: ClipboardSearchResults) : this(di, 0, search)
    internal constructor(di: DI, initialTab: Int, repository: ClipboardImageRepository) : this(di, initialTab, null, repository)

    private val service: TrimeInputMethodService by instance()
    private val windowManager: BoardWindowManager by instance()
    private val keyboardWindow: KeyboardWindow by instance()
    private val scope: ThemeScope by instance()
    private val theme: Theme get() = scope.theme

    private lateinit var clipboardLayout: ClipboardLayout
    private lateinit var clipboardPagesAdapter: ClipboardPagesAdapter
    private var wasAttached = false
    @Volatile private var attachedForImages = false
    @Volatile private var attachedEditorToken = 0L
    @Volatile private var attachmentGeneration = 0L
    private var viewGeneration = 0L

    private fun isCurrentWindow(owner: Long = attachedEditorToken): Boolean =
        attachedForImages && windowManager.isAttached(this) && service.isCurrentEditor(owner) && owner == attachedEditorToken

    private fun runForCurrentWindow(action: suspend () -> Unit) {
        val owner = attachedEditorToken
        val generation = attachmentGeneration
        if (isCurrentWindow(owner)) service.lifecycleScope.launch {
            if (generation == attachmentGeneration && isCurrentWindow(owner)) action()
        }
    }

    private val prefs = AppPrefs.defaultInstance().clipboard
    private val clipboardReturnAfterPaste by prefs.clipboardReturnAfterPaste
    @Keep
    private val returnAfterPasteListener = PreferenceDelegate.OnChangeListener<Boolean> { _, _ ->
        clipboardLayout.post {
            if (attachedForImages) refreshReturnAfterPaste()
        }
    }

    private fun refreshReturnAfterPaste() {
        clipboardLayout.titleUi.returnAfterPasteButton.apply {
            accessibilityChecked = clipboardReturnAfterPaste
            setIcon(if (clipboardReturnAfterPaste) R.drawable.ic_baseline_keyboard_24 else R.drawable.ic_keyboard_stay_24)
        }
    }

    private val clipboardBeansPager by lazy {
        Pager(PagingConfig(pageSize = 16)) { search?.let { ClipboardHelper.searchBeans(it.query) } ?: ClipboardHelper.allBeans() }
    }
    private val collectionBeansPager by lazy {
        Pager(PagingConfig(pageSize = 16)) { CollectionHelper.allBeans() }
    }
    private var clipboardBeansSubmitJob: Job? = null
    private var collectionBeansSubmitJob: Job? = null
    private var emptyStateJob: Job? = null
    private var imageSubmitJob: Job? = null
    private var imageEmptyStateJob: Job? = null
    private var imageAdapter: ClipboardImageAdapter? = null
    private var deleteAllDialog: AlertDialog? = null
    private var moreActionsMenu: PopupMenu? = null
    private var cancelPrivateCopy: (() -> Unit)? = null

    private val clipboardBeansAdapter by lazy {
        object : ClipboardAdapter(scope) {
            override fun captureActionGuard(): () -> Boolean {
                val generation = attachmentGeneration
                val owner = attachedEditorToken
                return { generation == attachmentGeneration && isCurrentWindow(owner) }
            }
            override fun onPaste(bean: DatabaseBean) {
                if (!isCurrentWindow()) return
                val repeatEvent = ClipboardHelper.repeatEvent()
                val text = bean.text ?: return
                if (search != null) {
                    service.pasteClipboardSearch(search, bean.id, text) {
                        if (isCurrentWindow()) {
                            ClipboardHelper.chooseForRepeat(repeatEvent, bean.id, text)
                        }
                        if (clipboardReturnAfterPaste) windowManager.attachWindow(KeyboardWindow)
                        else windowManager.attachWindow(ClipboardWindow(di))
                    }
                    return
                }
                if (!service.commitClipboardHistory(text)) return
                if (isCurrentWindow()) {
                    ClipboardHelper.chooseForRepeat(repeatEvent, bean.id, text)
                }
                if (clipboardReturnAfterPaste) {
                    windowManager.attachWindow(KeyboardWindow)
                }
            }

            override fun onPin(id: Int) {
                runForCurrentWindow { ClipboardHelper.pin(id) }
            }

            override fun onUnpin(id: Int) {
                runForCurrentWindow { ClipboardHelper.unpin(id) }
            }

            override fun onEdit(id: Int) {
                if (!isCurrentWindow()) return
                AppUtils.launchClipEdit(context, id, ClipEditActivity.FROM_CLIPBOARD)
            }

            override fun onShare(bean: DatabaseBean) {
                if (!isCurrentWindow()) return
                val text = bean.text ?: return
                launchTextSharing(text)
            }

            override fun onSegment(bean: DatabaseBean) {
                if (!isCurrentWindow()) return
                val text = bean.text ?: return
                windowManager.attachWindow(SegmentsWindow(di, text))
            }

            override fun onCollect(bean: DatabaseBean) {
                runForCurrentWindow { CollectionHelper.addNewBean(bean.text ?: "") }
            }

            override fun onDelete(id: Int) {
                runForCurrentWindow { ClipboardHelper.delete(id) }
            }

            override val allowItemActions: Boolean = search == null
            override val enableCollection: Boolean = true
        }
    }

    private val collectionBeansAdapter by lazy {
        object : ClipboardAdapter(scope) {
            override fun captureActionGuard(): () -> Boolean {
                val generation = attachmentGeneration
                val owner = attachedEditorToken
                return { generation == attachmentGeneration && isCurrentWindow(owner) }
            }
            override fun onPaste(bean: DatabaseBean) {
                if (!isCurrentWindow()) return
                val text = bean.text ?: return
                service.commitText(text)
                if (clipboardReturnAfterPaste) {
                    windowManager.attachWindow(KeyboardWindow)
                }
            }

            override fun onEdit(id: Int) {
                if (!isCurrentWindow()) return
                AppUtils.launchClipEdit(context, id, ClipEditActivity.FROM_COLLECTION)
            }

            override fun onShare(bean: DatabaseBean) {
                if (!isCurrentWindow()) return
                val text = bean.text ?: return
                launchTextSharing(text)
            }

            override fun onSegment(bean: DatabaseBean) {
                if (!isCurrentWindow()) return
                val text = bean.text ?: return
                windowManager.attachWindow(SegmentsWindow(di, text))
            }

            override fun onDelete(id: Int) {
                runForCurrentWindow { CollectionHelper.delete(id) }
            }

            override val enableCollection: Boolean = false
        }
    }

    private val clipboardPage by lazy {
        ClipboardPageUi(context).apply {
            emptyView.setTextColor(scope.colors.keyTextColor)
            recyclerView.apply {
                layoutManager = verticalLayoutManager()
                adapter = clipboardBeansAdapter
            }
        }
    }

    private val collectionPage by lazy {
        ClipboardPageUi(context).apply {
            recyclerView.apply {
                layoutManager = verticalLayoutManager()
                adapter = collectionBeansAdapter
            }
        }
    }

    private val imagePage by lazy {
        ClipboardPageUi(context).apply {
            emptyView.setTextColor(scope.colors.keyTextColor)
            emptyView.setText(R.string.clipboard_images_unavailable)
            recyclerView.layoutManager = verticalLayoutManager()
        }
    }

    private fun stopImages() {
        imageSubmitJob?.cancel()
        imageSubmitJob = null
        imageEmptyStateJob?.cancel()
        imageEmptyStateJob = null
        imageAdapter?.stop()
        imagePage.recyclerView.adapter = null
        imageAdapter = null
    }

    private fun startImages() {
        stopImages()
        val repository = imageRepositoryOverride ?: ClipboardImageHistory.repositoryOrNull()
        val adapter = repository?.let { imageRepository ->
            ClipboardImageAdapter(service, imageRepository, scope) { image, current, background, gate ->
                service.sendClipboardImage(imageRepository, image, current, background, gate) {
                    if (clipboardReturnAfterPaste && isCurrentWindow()) windowManager.attachWindow(KeyboardWindow)
                }
            }
        }
        if (adapter == null || !adapter.start(service.editorToken)) {
            imagePage.emptyView.setText(R.string.clipboard_images_unavailable)
            imagePage.emptyView.visibility = View.VISIBLE
            imagePage.emptyView.setOnClickListener { if (attachedForImages && clipboardLayout.viewPager.currentItem == 2) startImages() }
            return
        }
        imagePage.emptyView.visibility = View.GONE
        imageAdapter = adapter
        imagePage.recyclerView.adapter = adapter
        val activeRepository = checkNotNull(repository)
        imageSubmitJob = service.lifecycleScope.launch {
            Pager(PagingConfig(pageSize = 16)) { activeRepository.readyImages() }.flow.collectLatest { adapter.submitData(it) }
        }
        imageEmptyStateJob = service.lifecycleScope.launch {
            adapter.loadStateFlow.collectLatest { state ->
                when {
                    state.refresh is LoadState.Error -> {
                        imagePage.emptyView.setText(R.string.clipboard_images_page_failed)
                        imagePage.emptyView.visibility = View.VISIBLE
                        imagePage.emptyView.setOnClickListener {
                            if (attachedForImages && imageAdapter === adapter && clipboardLayout.viewPager.currentItem == 2) adapter.retry()
                        }
                    }
                    state.refresh is LoadState.NotLoading && adapter.itemCount == 0 -> {
                        imagePage.emptyView.setText(R.string.clipboard_images_empty)
                        imagePage.emptyView.visibility = View.VISIBLE
                        imagePage.emptyView.setOnClickListener(null)
                    }
                    else -> {
                        imagePage.emptyView.visibility = View.GONE
                        imagePage.emptyView.setOnClickListener(null)
                    }
                }
            }
        }
    }

    override fun onCreateView() = ClipboardLayout(context, scope).apply {
        val currentView = ++viewGeneration
        clipboardLayout = this
        clipboardPagesAdapter = object : ClipboardPagesAdapter() {
            override fun getItemCount(): Int = if (search == null) 3 else 1
            override fun onCreatePage(position: Int): ClipboardPageUi = when (position) {
                0 -> clipboardPage
                1 -> collectionPage
                else -> imagePage
            }
        }
        viewPager.apply {
            adapter = clipboardPagesAdapter
            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    titleUi.searchButton.visibility = if (position == 0) View.VISIBLE else View.GONE
                    titleUi.clearSearchButton.visibility = if (position == 0 && search != null) View.VISIBLE else View.GONE
                    titleUi.deleteAllButton.visibility = if (position == 2 || (position == 0 && search != null)) View.GONE else View.VISIBLE
                    titleUi.returnAfterPasteButton.visibility = if (position == 2) View.GONE else View.VISIBLE
                    titleUi.moreActionsButton.visibility = if (position == 2 || search != null) View.GONE else View.VISIBLE
                    if (attachedForImages && position == 2) startImages() else stopImages()
                }
            })
        }
        titleUi.apply {
            moreActionsButton.visibility = if (search == null) View.VISIBLE else View.GONE
            moreActionsButton.setOnClickListener { anchor ->
                if (currentView != viewGeneration || !isCurrentWindow()) return@setOnClickListener
                val owner = attachedEditorToken
                val generation = attachmentGeneration
                val menu = PopupMenu(context, anchor)
                if (theme.presetKeyboards.containsKey("xrime_text_editor")) {
                    menu.menu.add(R.string.clipboard_text_editor).setOnMenuItemClickListener {
                        if (currentView == viewGeneration && generation == attachmentGeneration && isCurrentWindow(owner) &&
                            theme.presetKeyboards.containsKey("xrime_text_editor")
                        ) {
                            windowManager.attachWindow(KeyboardWindow)
                            keyboardWindow.switchKeyboard("xrime_text_editor")
                        }
                        true
                    }
                }
                menu.menu.add(R.string.private_copy_action).setOnMenuItemClickListener {
                    if (currentView == viewGeneration && generation == attachmentGeneration && isCurrentWindow(owner)) {
                        service.copySelectionPrivately(
                            sourceCurrent = { attachedForImages && generation == attachmentGeneration && attachedEditorToken == owner },
                            registerCancellation = { cancelPrivateCopy = it },
                        )
                    }
                    true
                }
                moreActionsMenu?.dismiss()
                moreActionsMenu = menu
                menu.setOnDismissListener { if (moreActionsMenu === menu) moreActionsMenu = null }
                menu.show()
            }
            returnAfterPasteButton.setOnClickListener {
                if (currentView == viewGeneration && isCurrentWindow() &&
                    (search == null || service.isClipboardSearchResultsCurrent(search))
                ) prefs.clipboardReturnAfterPaste.setValue(!clipboardReturnAfterPaste)
            }
            searchButton.setOnClickListener {
                if (currentView == viewGeneration && isCurrentWindow() && (search == null || service.isClipboardSearchResultsCurrent(search))) {
                    service.beginClipboardSearch(search?.query.orEmpty())
                }
            }
            clearSearchButton.setOnClickListener {
                if (search != null && currentView == viewGeneration && isCurrentWindow() && service.isClipboardSearchResultsCurrent(search)) {
                    windowManager.attachWindow(ClipboardWindow(di))
                }
            }
            clearSearchButton.visibility = if (search == null) View.GONE else View.VISIBLE
            deleteAllButton.visibility = if (search == null) View.VISIBLE else View.GONE
            tabLayout.onConfigureTab(viewPager) { tabUi, position ->
                configureTab(tabUi, position)
            }
            deleteAllButton.setOnClickListener {
                if (search != null || currentView != viewGeneration || !isCurrentWindow()) return@setOnClickListener
                val currentItem = viewPager.currentItem
                when (currentItem) {
                    0 -> promptDeleteAll {
                        ClipboardHelper.deleteAll(ClipboardHelper.haveUnpinned())
                    }

                    1 -> promptDeleteAll {
                        CollectionHelper.deleteAll(CollectionHelper.haveUnpinned())
                    }
                }
            }
        }
    }

    private fun launchTextSharing(text: String) {
        val target = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val chooser = Intent.createChooser(target, null).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        service.startActivity(chooser)
    }

    private fun promptDeleteAll(action: suspend () -> Unit) {
        val owner = attachedEditorToken
        val generation = attachmentGeneration
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.delete_all)
            .setMessage(R.string.ask_to_delete_all)
            .setPositiveButton(R.string.ok) { _, _ ->
                if (generation == attachmentGeneration && isCurrentWindow(owner)) service.lifecycleScope.launch {
                    if (generation == attachmentGeneration && isCurrentWindow(owner)) action()
                }
            }.setNegativeButton(R.string.cancel, null)
            .create()
        deleteAllDialog?.dismiss()
        deleteAllDialog = dialog
        service.showDialog(dialog)
    }

    private fun configureTab(
        tabUi: InputTabLayout.TabUi,
        position: Int,
    ) {
        val label =
            when (position) {
                0 -> R.string.clipboard
                1 -> R.string.collection
                else -> R.string.clipboard_images
            }
        tabUi.label.apply {
            setText(label)
            textSize = theme.style.candidateTextSize
            setTypeface(theme.fonts.candidate, Typeface.BOLD)
            setTextColor(scope.colors.keyTextColor)
        }
    }

    override fun onAttached() {
        wasAttached = true
        attachedForImages = true
        attachedEditorToken = service.editorToken
        attachmentGeneration++
        prefs.clipboardReturnAfterPaste.registerOnChangeListener(returnAfterPasteListener)
        refreshReturnAfterPaste()
        clipboardLayout.viewPager.setCurrentItem(initialTab.coerceIn(0, clipboardPagesAdapter.itemCount - 1), false)
        if (clipboardLayout.viewPager.currentItem == 2) startImages()
        clipboardBeansSubmitJob = service.lifecycleScope.launch {
            clipboardBeansPager.flow.collect {
                clipboardBeansAdapter.submitData(it)
            }
        }
        emptyStateJob = service.lifecycleScope.launch {
            clipboardBeansAdapter.loadStateFlow.collectLatest { state ->
                clipboardPage.emptyView.visibility = if (search != null && state.refresh is LoadState.NotLoading && clipboardBeansAdapter.itemCount == 0) View.VISIBLE else View.GONE
            }
        }
        if (search == null) {
            collectionBeansSubmitJob = service.lifecycleScope.launch {
                collectionBeansPager.flow.collect {
                    collectionBeansAdapter.submitData(it)
                }
            }
        }
    }

    override fun onDetached() {
        cancelPrivateCopy?.invoke()
        cancelPrivateCopy = null
        attachedForImages = false
        attachmentGeneration++
        prefs.clipboardReturnAfterPaste.unregisterOnChangeListener(returnAfterPasteListener)
        deleteAllDialog?.dismiss()
        deleteAllDialog = null
        moreActionsMenu?.dismiss()
        moreActionsMenu = null
        stopImages()
        clipboardBeansAdapter.dismissPopupMenu()
        if (search == null) collectionBeansAdapter.dismissPopupMenu()
        clipboardBeansSubmitJob?.cancel()
        collectionBeansSubmitJob?.cancel()
        emptyStateJob?.cancel()
        search?.let(service::clipboardSearchResultsDetached)
    }

    override fun refreshColors() {
        if (!wasAttached) return
        clipboardLayout.titleUi.deleteAllButton.refreshColors()
        clipboardLayout.titleUi.searchButton.refreshColors()
        clipboardLayout.titleUi.clearSearchButton.refreshColors()
        clipboardLayout.titleUi.returnAfterPasteButton.refreshColors()
        clipboardLayout.titleUi.moreActionsButton.refreshColors()
        clipboardLayout.titleUi.tabLayout.reconfigureTabs { tabUi, position ->
            configureTab(tabUi, position)
        }
        clipboardPage.emptyView.setTextColor(scope.colors.keyTextColor)
        clipboardBeansAdapter.refreshColors()
        if (search == null) collectionBeansAdapter.refreshColors()
        if (search == null) imagePage.emptyView.setTextColor(scope.colors.keyTextColor)
    }

    override fun onInputBoundary() {
        stopImages()
        if (windowManager.isAttached(this)) windowManager.attachWindow(KeyboardWindow)
    }

    override fun onCreateBarView(): View = clipboardLayout.titleUi.root
}
