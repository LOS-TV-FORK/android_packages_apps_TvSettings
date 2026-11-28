/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.tv.settings.device.displaysound

import android.content.Context
import android.os.Bundle
import android.os.SystemProperties
import androidx.annotation.Keep
import androidx.preference.ListPreference
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreference
import com.android.tv.settings.R
import com.android.tv.settings.SettingsPreferenceFragment

/**
 * "Video decoding" sub-screen. The top switch is the global automatic mode (the former
 * "Hardware video decoding" toggle, backed by persist.sys.los.media.sf.hwaccel): hardware when the
 * codec has a working backend, software otherwise. When it is off, each codec can be pinned to
 * software. Every codec row also shows which path actually decoded the last playback. A
 * troubleshooting section exposes the pixel format and the experimental zero-copy / fast paths,
 * which are the usual culprits for a black screen on hardware that does support the codec.
 */
@Keep
class VideoDecodingFragment : SettingsPreferenceFragment() {

    private lateinit var autoSwitch: SwitchPreference
    private lateinit var pixelFormatPref: ListPreference
    private lateinit var drmSwitch: SwitchPreference
    private lateinit var fastSwitch: SwitchPreference
    private lateinit var diagSwitch: SwitchPreference
    private val codecPrefs = ArrayList<Pair<String, CodecModePreference>>()

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = requireContext()
        val screen = preferenceManager.createPreferenceScreen(context) ?: return
        screen.title = getString(R.string.video_decoding_title)

        autoSwitch = SwitchPreference(context).apply {
            key = KEY_AUTO
            isPersistent = false
            title = getString(R.string.video_decoding_auto_title)
            summary = getString(R.string.video_decoding_auto_summary)
            isChecked = SystemProperties.get(PROP_AUTO, "1") != "0"
            setOnPreferenceChangeListener { _, newValue ->
                val auto = newValue as Boolean
                SystemProperties.set(PROP_AUTO, if (auto) "1" else "0")
                updateCodecEnabled(auto)
                true
            }
        }
        screen.addPreference(autoSwitch)

        val keys = resources.getStringArray(R.array.video_decoding_codec_keys)
        val labels = resources.getStringArray(R.array.video_decoding_codec_labels)
        for (i in keys.indices) {
            val codecKey = keys[i]
            val pref = CodecModePreference(context).apply {
                key = "hwaccel_$codecKey"
                isPersistent = false
                title = labels[i]
                mode = SystemProperties.get(PROP_CODEC_PREFIX + codecKey, "auto")
                onModeChanged = { value ->
                    SystemProperties.set(PROP_CODEC_PREFIX + codecKey, value)
                }
            }
            codecPrefs.add(codecKey to pref)
            screen.addPreference(pref)
        }

        val troubleshooting = PreferenceCategory(context).apply {
            key = KEY_TROUBLESHOOTING
            title = getString(R.string.video_decoding_troubleshooting_title)
        }
        screen.addPreference(troubleshooting)

        pixelFormatPref = ListPreference(context).apply {
            key = KEY_PIXELFORMAT
            isPersistent = false
            title = getString(R.string.video_decoding_pixelformat_title)
            summary = getString(R.string.video_decoding_pixelformat_summary)
            setEntries(R.array.video_decoding_pixelformat_entries)
            setEntryValues(R.array.video_decoding_pixelformat_values)
            value = SystemProperties.get(PROP_PIXELFORMAT, "")
            setOnPreferenceChangeListener { _, newValue ->
                SystemProperties.set(PROP_PIXELFORMAT, newValue as String)
                true
            }
        }
        troubleshooting.addPreference(pixelFormatPref)

        drmSwitch = SwitchPreference(context).apply {
            key = KEY_DRM
            isPersistent = false
            title = getString(R.string.video_decoding_drm_title)
            summary = getString(R.string.video_decoding_drm_summary)
            isChecked = SystemProperties.get(PROP_DRM, "0") == "1"
            setOnPreferenceChangeListener { _, newValue ->
                SystemProperties.set(PROP_DRM, if (newValue as Boolean) "1" else "0")
                true
            }
        }
        troubleshooting.addPreference(drmSwitch)

        fastSwitch = SwitchPreference(context).apply {
            key = KEY_FAST
            isPersistent = false
            title = getString(R.string.video_decoding_fast_title)
            summary = getString(R.string.video_decoding_fast_summary)
            isChecked = SystemProperties.get(PROP_FAST, "0") == "1"
            setOnPreferenceChangeListener { _, newValue ->
                SystemProperties.set(PROP_FAST, if (newValue as Boolean) "1" else "0")
                true
            }
        }
        troubleshooting.addPreference(fastSwitch)

        diagSwitch = SwitchPreference(context).apply {
            key = KEY_DIAG
            isPersistent = false
            title = getString(R.string.video_decoding_diag_title)
            summary = getString(R.string.video_decoding_diag_summary)
            isChecked = SystemProperties.get(PROP_DIAG, "0") == "1"
            setOnPreferenceChangeListener { _, newValue ->
                SystemProperties.set(PROP_DIAG, if (newValue as Boolean) "1" else "0")
                true
            }
        }
        troubleshooting.addPreference(diagSwitch)

        preferenceScreen = screen
    }

    override fun onResume() {
        super.onResume()
        autoSwitch.isChecked = SystemProperties.get(PROP_AUTO, "1") != "0"
        pixelFormatPref.value = SystemProperties.get(PROP_PIXELFORMAT, "")
        drmSwitch.isChecked = SystemProperties.get(PROP_DRM, "0") == "1"
        fastSwitch.isChecked = SystemProperties.get(PROP_FAST, "0") == "1"
        diagSwitch.isChecked = SystemProperties.get(PROP_DIAG, "0") == "1"
        updateCodecEnabled(autoSwitch.isChecked)
        updateCodecSummaries()
    }

    private fun updateCodecEnabled(autoOn: Boolean) {
        val editable = !autoOn
        for ((_, pref) in codecPrefs) {
            pref.editable = editable
        }
    }

    private fun updateCodecSummaries() {
        for ((codecKey, pref) in codecPrefs) {
            pref.summary = lastUsedSummary(requireContext(), codecKey)
        }
    }

    private fun lastUsedSummary(context: Context, codecKey: String): String {
        val value = SystemProperties.get(PROP_LAST_PREFIX + codecKey, "")
        return when {
            value.isEmpty() -> context.getString(R.string.video_decoding_last_none)
            value == "sw" -> context.getString(R.string.video_decoding_last_sw)
            value.startsWith("hw:") ->
                context.getString(R.string.video_decoding_last_hw, value.substring(3))
            else -> context.getString(R.string.video_decoding_last_hw, value)
        }
    }

    companion object {
        private const val KEY_AUTO = "video_decoding_auto"
        private const val KEY_TROUBLESHOOTING = "video_decoding_troubleshooting"
        private const val KEY_PIXELFORMAT = "video_decoding_pixelformat"
        private const val KEY_DRM = "video_decoding_drm"
        private const val KEY_FAST = "video_decoding_fast"
        private const val KEY_DIAG = "video_decoding_diag"
        private const val PROP_AUTO = "persist.sys.los.media.sf.hwaccel"
        private const val PROP_PIXELFORMAT = "persist.ffmpeg-codec2.pixel_format"
        private const val PROP_DRM = "persist.sys.los.media.sf.hwaccel.drm"
        private const val PROP_FAST = "persist.sys.los.media.sf.hwaccel.force"
        private const val PROP_DIAG = "persist.sys.los.media.diag"
        private const val PROP_CODEC_PREFIX = "persist.sys.los.media.hwaccel."
        private const val PROP_LAST_PREFIX = "persist.ffmpeg-codec2.last."
    }
}
