/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.speech.tts.TextToSpeech
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.annotation.MainThread
import com.osfans.trime.R
import com.osfans.trime.TrimeApplication
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.ime.core.PrivacyModeState
import splitties.systemservices.audioManager
import splitties.systemservices.vibrator
import timber.log.Timber

/** 0 is the settings UI's system-default choice; Android still controls the output stream. */
internal fun customSoundGain(volume: Int): Float = if (volume == 0) 1f else volume.coerceIn(0, 100) / 100f

/**
 * Manage the key press effects, such as vibration, sound, speaking and so on.
 */
object InputFeedbackManager {
    private val keyboardPrefs = AppPrefs.defaultInstance().keyboard

    private var applicationContext: Context? = null
    private val callbackHandler by lazy { Handler(Looper.getMainLooper()) }
    private val speechRequests = FeedbackRequestDispatcher(
        isOwnerThread = { Looper.myLooper() == Looper.getMainLooper() },
        postToOwner = { request -> callbackHandler.post { request() } },
    )
    private val speech = FeedbackResource<TextToSpeech> { it.shutdown() }
    private val soundPlayback by lazy {
        BoundedSoundPlayback(
            SoundPoolBackend(audioManager) {
                applicationContext?.let { Toast.makeText(it, R.string.sound_effect_unavailable, Toast.LENGTH_LONG).show() }
            },
            onEffectInstalled = { effectPlayProgress = 0 },
        )
    }
    private data class PendingSpeech(val text: String, val onCommit: Boolean)
    private var pendingSpeech: PendingSpeech? = null
    private var speechAllowed = false
    private var speechRetryAfter = 0L

    private var effectPlayProgress = 0

    @MainThread
    fun init(context: Context = TrimeApplication.getInstance()) {
        resetSpeechRequests()
        applicationContext = context.applicationContext
        speech.init()
        soundPlayback.init()
    }

    @MainThread
    private fun resetSpeechRequests() {
        speechRequests.invalidate()
        pendingSpeech = null
        speech.ready?.stop()
    }

    @MainThread
    private fun prepareSpeech(onCommit: Boolean) {
        val context = applicationContext ?: return
        if (!speechAllowed || PrivacyModeState.active || SystemClock.uptimeMillis() < speechRetryAfter) {
            pendingSpeech = null
            return
        }
        if (!speech.canAcquire) {
            speech.destroy()
            speech.init()
        }
        if (speech.current == null) {
            @Suppress("DEPRECATION")
            val available = context.packageManager.queryIntentServices(
                Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE),
                PackageManager.MATCH_DEFAULT_ONLY,
            ).isNotEmpty()
            if (!available) {
                speechRetryAfter = SystemClock.uptimeMillis() + 30_000
                pendingSpeech = null
                return
            }
        }
        try {
            val resource = speech.acquire(
                enabled = if (onCommit) speakOnCommit else speakOnKeyPress,
                create = { complete ->
                    TextToSpeech(context) { status ->
                        callbackHandler.post { complete(status == TextToSpeech.SUCCESS) }
                    }
                },
                onInitialized = { engine, ready ->
                    if (ready) {
                        speakPending(engine)
                    } else {
                        speechRetryAfter = SystemClock.uptimeMillis() + 30_000
                        pendingSpeech = null
                    }
                },
            )
            if (resource == null) {
                pendingSpeech = null
            } else if (speech.ready === resource) {
                speakPending(resource)
            }
        } catch (e: Exception) {
            speechRetryAfter = SystemClock.uptimeMillis() + 30_000
            pendingSpeech = null
            Timber.e(e, "Error on initializing speech feedback")
        }
    }

    @MainThread
    private fun speakPending(engine: TextToSpeech) {
        val pending = pendingSpeech ?: return
        pendingSpeech = null
        if (speechAllowed && !PrivacyModeState.active && (if (pending.onCommit) speakOnCommit else speakOnKeyPress)) {
            engine.speak(pending.text, TextToSpeech.QUEUE_FLUSH, null, "TrimeTTS")
        }
    }

    @MainThread
    fun startInput(sensitive: Boolean = false) {
        resetSpeechRequests()
        speechAllowed = !sensitive
        soundPlayback.startInput()
    }

    @MainThread
    fun reloadSoundEffects() {
        soundPlayback.reload()
    }

    private val hasAmplitudeControl =
        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) &&
            vibrator.hasAmplitudeControl()

    private val vibrateOnKeyPress by keyboardPrefs.vibrateOnKeyPress
    private val vibrationDuration by keyboardPrefs.vibrationDuration
    private val vibrationAmplitude by keyboardPrefs.vibrationAmplitude

    /**
     * Makes a key press vibration if the user has this feature enabled in the preferences.
     */
    fun keyPressVibrate(
        view: View,
        longPress: Boolean = false,
    ) {
        if (!vibrateOnKeyPress) return
        val duration: Long = vibrationDuration.toLong()
        val hfc =
            if (longPress) {
                HapticFeedbackConstants.LONG_PRESS
            } else {
                HapticFeedbackConstants.KEYBOARD_TAP
            }

        if (duration != 0L) { // use vibrator
            if (hasAmplitudeControl && vibrationAmplitude != 0) {
                vibrator.vibrate(VibrationEffect.createOneShot(duration, vibrationAmplitude))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ve = VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE)
                vibrator.vibrate(ve)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(duration)
            }
        } else {
            @Suppress("DEPRECATION")
            val flags =
                HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING or HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
            view.performHapticFeedback(hfc, flags)
        }
    }

    private fun querySoundIndex(keyCode: Int): Int {
        val effect = soundPlayback.installedEffect ?: return 0
        val sounds = effect.sound
        if (sounds.isEmpty()) return 0
        val melody = effect.melody
        return if (melody.isNotEmpty()) {
            effectPlayProgress %= melody.size
            val index = sounds.indexOf(melody[effectPlayProgress])
            effectPlayProgress = (effectPlayProgress + 1) % melody.size
            index
        } else {
            var index = 0
            for (key in effect.keyset) {
                val i = key.querySoundIndex(keyCode)
                if (i >= 0) {
                    index = i
                    break
                }
            }
            index
        }
    }

    private val soundOnKeyPress by keyboardPrefs.soundOnKeyPress
    private val soundEffectEnabled by keyboardPrefs.useCustomSoundEffect
    private val soundVolume by keyboardPrefs.soundVolume

    /**
     * Makes a key press sound if the user has this feature enabled in the preferences.
     */
    @MainThread
    fun keyPressSound(keyCode: Int = 0) {
        if (!soundOnKeyPress) return
        val index = if (soundEffectEnabled && soundPlayback.installedEffect != null) querySoundIndex(keyCode) else -1
        val systemVolume = if (soundVolume == 0) -1f else soundVolume.coerceIn(0, 100) / 100f
        soundPlayback.press(index, customSoundGain(soundVolume), keyCode, systemVolume)
    }

    private val speakOnKeyPress by keyboardPrefs.speakOnKeyPress
    private val speakOnCommit by keyboardPrefs.speakOnCommit

    fun keyPressSpeak(keyCode: Int) {
        speechRequests.dispatch {
            if (speakOnKeyPress && speechAllowed && !PrivacyModeState.active) contentSpeakInternal(keyCode, onCommit = false)
        }
    }

    fun textCommitSpeak(text: String) {
        speechRequests.dispatch {
            if (speakOnCommit && speechAllowed && !PrivacyModeState.active) contentSpeakInternal(text, onCommit = true)
        }
    }

    @MainThread
    private inline fun <reified T> contentSpeakInternal(content: T, onCommit: Boolean) {
        if (applicationContext == null) return
        val text =
            when {
                0 is T -> {
                    KeyEvent
                        .keyCodeToString(content as Int)
                        .replace("KEYCODE_", "")
                        .replace("_", " ")
                        .lowercase()
                }

                "" is T -> content as String

                else -> return
            }

        pendingSpeech = PendingSpeech(text, onCommit)
        prepareSpeech(onCommit)
    }

    @MainThread
    fun finishInput() {
        speechAllowed = false
        resetSpeechRequests()
        effectPlayProgress = 0
        soundPlayback.finishInput()
    }

    @MainThread
    fun destroy() {
        speechAllowed = false
        resetSpeechRequests()
        speechRetryAfter = 0
        applicationContext = null
        speech.destroy()
        soundPlayback.destroy()
    }
}
