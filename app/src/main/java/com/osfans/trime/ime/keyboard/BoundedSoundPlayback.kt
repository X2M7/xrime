/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.keyboard

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.Keep
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.prefs.PreferenceDelegate
import com.osfans.trime.data.soundeffect.SoundEffectManager
import com.osfans.trime.data.soundeffect.SoundEffect
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Main-thread owner; bounded workers survive service recreation to finish cleanup. */
internal class BoundedSoundPlayback(
    private val backend: SoundPoolBackend,
    private val onEffectInstalled: () -> Unit,
) {
    private val prefs = AppPrefs.defaultInstance().keyboard
    private val handler = Handler(Looper.getMainLooper())
    private val audioWorker = Executors.newSingleThreadExecutor { task -> Thread(task, "xrime-sound").apply { isDaemon = true } }
    private val configWorker = Executors.newSingleThreadExecutor { task -> Thread(task, "xrime-sound-config").apply { isDaemon = true } }
    private val owner = object : SoundDemandCoordinator.Owner {
        override fun post(task: Runnable) { handler.post(task) }
        override fun postDelayed(task: Runnable, millis: Long) { handler.postDelayed(task, millis) }
        override fun cancelDelayed(task: Runnable) { handler.removeCallbacks(task) }
        override fun uptimeMillis(): Long = SystemClock.uptimeMillis()
        override fun isOwner(): Boolean = Looper.myLooper() == Looper.getMainLooper()
    }
    private val coordinator = SoundDemandCoordinator(owner, audioWorker, backend)
    private data class Request(val selection: String, val revision: Long)
    private data class Resolved(val snapshot: SoundEffectManager.SelectionSnapshot, val catalog: SoundDemandCoordinator.Catalog)
    private val loader = LatestSoundConfigLoader(
        object : LatestSoundConfigLoader.Owner {
            override fun isOwner(): Boolean = owner.isOwner()
            override fun post(task: Runnable) = owner.post(task)
        },
        configWorker,
        LatestSoundConfigLoader.Load<Request, Resolved> { request ->
            val snapshot = SoundEffectManager.readSelection(request.selection)
            val effect = snapshot.effect
            Resolved(snapshot, SoundDemandCoordinator.Catalog(snapshot.root.absolutePath, effect?.folder ?: "", effect?.sound ?: emptyList()))
        },
        LatestSoundConfigLoader.Publish<Request, Resolved> { request, resolved, error ->
            if (attached && request.revision == coordinator.revision() && prefs.soundOnKeyPress.getValue() &&
                prefs.useCustomSoundEffect.getValue() && prefs.customSoundEffect.getValue() == request.selection
            ) {
                if (error == null && resolved?.snapshot?.effect != null && SoundEffectManager.installSelection(resolved.snapshot) &&
                    coordinator.configure(resolved.catalog, true, request.revision)
                ) {
                    installedEffect = resolved.snapshot.effect
                    onEffectInstalled()
                } else if (request.revision == coordinator.revision()) {
                    val reason = if (error != null) SoundDemandCoordinator.Failure.CONFIG_READ else SoundDemandCoordinator.Failure.CONFIG_INVALID
                    backend.failed(reason, true)
                }
            }
        },
    )
    private val empty = SoundDemandCoordinator.Catalog(Collections.emptyList())
    private var attached = false
    private var appliedRevision = -1L
    private val refreshScheduled = AtomicBoolean()
    var installedEffect: SoundEffect? = null
        private set

    @Keep private val soundListener = PreferenceDelegate.OnChangeListener<Boolean> { _, _ -> changed() }
    @Keep private val customListener = PreferenceDelegate.OnChangeListener<Boolean> { _, _ -> changed() }
    @Keep private val nameListener = PreferenceDelegate.OnChangeListener<String> { _, _ -> changed() }
    @Keep private val volumeListener = PreferenceDelegate.OnChangeListener<Int> { _, _ -> changed() }

    /** Revocation happens on the notifying thread, prior to scheduling any owner work. */
    private fun changed() {
        coordinator.revokeNow()
        if (refreshScheduled.compareAndSet(false, true)) {
            if (!handler.post {
                    refreshScheduled.set(false)
                    refresh(coordinator.revision())
                }
            ) refreshScheduled.set(false)
        }
    }
    fun init() {
        check(owner.isOwner())
        if (attached) return
        attached = true
        prefs.soundOnKeyPress.registerOnChangeListener(soundListener)
        prefs.useCustomSoundEffect.registerOnChangeListener(customListener)
        prefs.customSoundEffect.registerOnChangeListener(nameListener)
        prefs.soundVolume.registerOnChangeListener(volumeListener)
        coordinator.resumeInput(coordinator.revision())
        refresh(coordinator.revokeNow())
    }
    fun reload() { check(owner.isOwner()); refresh(coordinator.revokeNow()) }
    private fun refresh(revision: Long) {
        check(owner.isOwner())
        if (!attached || revision == appliedRevision || revision != coordinator.revision()) return
        loader.invalidate()
        installedEffect = null
        val on = prefs.soundOnKeyPress.getValue()
        if (!coordinator.configure(empty, on, revision)) return
        appliedRevision = revision
        if (on && prefs.useCustomSoundEffect.getValue()) {
            val selected = prefs.customSoundEffect.getValue()
            if (selected.isNotEmpty()) loader.request(Request(selected, revision))
        }
    }
    fun startInput() { check(owner.isOwner()); coordinator.resumeInput(coordinator.revision()) }
    fun finishInput() { check(owner.isOwner()); coordinator.pauseInput() }
    fun press(index: Int, gain: Float, keyCode: Int, systemVolume: Float) {
        check(owner.isOwner())
        coordinator.press(if (installedEffect != null) index else -1, gain, keyCode, systemVolume)
    }
    fun destroy() {
        check(owner.isOwner())
        if (!attached) return
        attached = false
        coordinator.revokeNow()
        prefs.soundOnKeyPress.unregisterOnChangeListener(soundListener)
        prefs.useCustomSoundEffect.unregisterOnChangeListener(customListener)
        prefs.customSoundEffect.unregisterOnChangeListener(nameListener)
        prefs.soundVolume.unregisterOnChangeListener(volumeListener)
        loader.invalidate()
        installedEffect = null
        coordinator.close()
    }
}
