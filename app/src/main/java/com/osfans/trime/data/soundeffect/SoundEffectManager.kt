/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.data.soundeffect

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.osfans.trime.core.data.DataManager
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.ime.keyboard.InputFeedbackManager
import com.osfans.trime.util.FileUtils
import com.osfans.trime.util.DEFAULT_YAML_CODE_POINT_LIMIT
import com.osfans.trime.util.readTextAtMostCodePoints
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.decodeFromString
import timber.log.Timber
import java.io.File

object SoundEffectManager {
    private val selectionLock = Any()

    private val userDir: File
        get() {
            val dest = File(DataManager.userDataDir, "soundeffect")
            val old = File(DataManager.userDataDir, "sound")
            return FileUtils.rename(old, dest.name).getOrDefault(dest.also { it.mkdirs() })
        }

    /**
     * Descriptors are standalone files with their own key spelling (`inOrder`,
     * `keyset`), so unlike [com.osfans.trime.data.theme.ThemeYaml] no naming
     * strategy applies and unknown keys are ignored.
     */
    private val yaml = Yaml(
        configuration = YamlConfiguration(
            strictMode = false,
        ),
    )

    private fun listSounds(root: File = userDir): MutableList<SoundEffect> {
        val files = root.listFiles { f -> f.name.endsWith("sound.yaml") }
        var invalidLogged = false
        return files
            ?.mapNotNull decode@{ f ->
                val effect = try {
                    val result = yaml.decodeFromString<SoundEffect>(f.readTextAtMostCodePoints(DEFAULT_YAML_CODE_POINT_LIMIT))
                    if (result.name.isEmpty()) {
                        result.copy(name = f.name.substringBefore("."))
                    } else {
                        result
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    if (!invalidLogged) {
                        Timber.w("Key sound failure stage=CONFIG_INVALID")
                        invalidLogged = true
                    }
                    null
                }
                return@decode effect
            }?.toMutableList() ?: mutableListOf()
    }

    /** Immutable data read off-main; reading never changes the installed selection. */
    internal data class SelectionSnapshot(val name: String, val root: File, val effect: SoundEffect?)

    internal fun readSelection(name: String): SelectionSnapshot {
        val root = userDir
        return SelectionSnapshot(name, root, if (name.isEmpty()) null else listSounds(root).find { it.name == name })
    }

    /** Publish only the latest preference on the owner thread. */
    internal fun installSelection(snapshot: SelectionSnapshot): Boolean = synchronized(selectionLock) {
        if (soundEffectPref != snapshot.name) return@synchronized false
        activeSoundEffect = snapshot.effect
        true
    }

    private fun getEffect(name: String) = userEffects.find { it.name == name }

    private val userEffects: MutableList<SoundEffect> get() = listSounds()

    private var soundEffectPref by AppPrefs.defaultInstance().keyboard.customSoundEffect

    fun switchEffect(name: String) {
        val effect = getEffect(name)
        if (effect == null) {
            Timber.w("Key sound failure stage=CONFIG_UNKNOWN")
            return
        }
        switchEffect(effect)
    }

    fun switchEffect(effect: SoundEffect) {
        synchronized(selectionLock) {
            activeSoundEffect = effect
            soundEffectPref = effect.name
        }
        InputFeedbackManager.reloadSoundEffects()
    }

    fun init() {
        val selected = synchronized(selectionLock) { soundEffectPref }
        val loaded = if (selected.isEmpty()) null else getEffect(selected)
        synchronized(selectionLock) {
            if (selected == soundEffectPref) activeSoundEffect = loaded
        }
    }

    @Volatile
    var activeSoundEffect: SoundEffect? = null
        private set

    val activeAudioPaths: List<String>
        get() {
            return activeSoundEffect?.let { e ->
                val subPath = e.folder
                e.sound.map { userDir.resolve(subPath).resolve(it).path }
            } ?: listOf()
        }

    fun getAllSoundEffects(): List<SoundEffect> = userEffects
}
