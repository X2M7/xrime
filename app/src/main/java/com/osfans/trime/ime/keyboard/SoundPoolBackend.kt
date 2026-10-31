/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.keyboard

import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.view.KeyEvent
import com.osfans.trime.data.soundeffect.SoundFileAccess
import java.io.File
import java.io.IOException
import java.util.Collections
import java.util.IdentityHashMap
import timber.log.Timber

/** All methods except [failed] run serially on the audio worker. */
internal class SoundPoolBackend(
    private val audioManager: AudioManager,
    private val showFailure: () -> Unit,
) : SoundDemandCoordinator.Backend<SoundPool> {
    // Accessed only by the serial audio worker; retained until successful release.
    private val listenerFailures = Collections.newSetFromMap(IdentityHashMap<SoundPool, Boolean>())
    override fun create(callback: SoundDemandCoordinator.Completion<SoundPool>): SoundPool =
        SoundPool.Builder().setMaxStreams(3).setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        ).build().also { pool ->
            try {
                pool.setOnLoadCompleteListener { loaded, id, status -> callback.loaded(loaded, id, status == 0) }
            } catch (_: RuntimeException) {
                // Keep the pool owned by the coordinator so a later retirement can retry release.
                listenerFailures.add(pool)
            } catch (failure: Throwable) {
                try {
                    pool.release()
                } catch (cleanup: Throwable) {
                    if (cleanup !== failure) failure.addSuppressed(cleanup)
                }
                throw failure
            }
        }

    override fun load(pool: SoundPool, catalog: SoundDemandCoordinator.Catalog, file: String): Int {
        if (pool in listenerFailures) return 0
        val descriptor = SoundFileAccess.open(File(catalog.root), catalog.folder, file) ?: return 0
        return descriptor.use {
            val size = it.statSize
            if (size <= 0 || size > 16L * 1024 * 1024) return@use 0
            pool.load(it.fileDescriptor, 0, size, 1)
        }
    }

    override fun play(pool: SoundPool, id: Int, gain: Float, requestedAt: Long): Boolean =
        pool.play(id, gain, gain, 0, 0, 1f) != 0

    override fun fallback(keyCode: Int, volume: Float, requestedAt: Long) {
        val effect = when (keyCode) {
            KeyEvent.KEYCODE_SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
            KeyEvent.KEYCODE_DEL -> AudioManager.FX_KEYPRESS_DELETE
            KeyEvent.KEYCODE_ENTER -> AudioManager.FX_KEYPRESS_RETURN
            else -> AudioManager.FX_KEYPRESS_STANDARD
        }
        audioManager.playSoundEffect(effect, volume)
    }

    override fun unload(pool: SoundPool, id: Int) {
        if (!pool.unload(id)) throw IOException("SoundPool rejected sample unload")
    }

    override fun release(pool: SoundPool) {
        pool.release()
        listenerFailures.remove(pool)
    }
    override fun failed(reason: SoundDemandCoordinator.Failure, visible: Boolean) {
        Timber.w("Key sound failure stage=%s", reason.name)
        if (visible) showFailure()
    }
}
