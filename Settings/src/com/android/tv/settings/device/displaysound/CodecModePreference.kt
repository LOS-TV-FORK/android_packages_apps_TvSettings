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
import android.util.AttributeSet
import android.widget.TextView
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.android.tv.settings.R

/**
 * Inline value pill for a single codec's decoding path. Shows the current state (AUTO / SW) inside
 * a themed pill that inverts on focus. Selecting the row (D-pad OK / click) toggles between the
 * two, no dialog.
 */
class CodecModePreference @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : Preference(context, attrs) {

    var mode: String = MODE_AUTO
        set(value) {
            if (field != value) {
                field = value
                notifyChanged()
            }
        }

    // When false the row stays focusable (so the list scrolls normally) but selecting it does
    // nothing. Used while the global automatic mode is on: the per-codec choice is locked, not
    // removed from navigation.
    var editable: Boolean = true
        set(value) {
            if (field != value) {
                field = value
                notifyChanged()
            }
        }

    var onModeChanged: ((String) -> Unit)? = null

    init {
        widgetLayoutResource = R.layout.video_decoding_mode_widget
    }

    override fun onClick() {
        if (!editable) return
        mode = if (mode == MODE_AUTO) MODE_SW else MODE_AUTO
        onModeChanged?.invoke(mode)
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.itemView.alpha = if (editable) 1f else 0.5f
        val pill = holder.itemView.findViewById<TextView>(R.id.codec_mode_pill)
        pill?.text = context.getString(
            if (mode == MODE_AUTO) R.string.video_decoding_seg_auto
            else R.string.video_decoding_seg_sw
        )
    }

    companion object {
        const val MODE_AUTO = "auto"
        const val MODE_SW = "sw"
    }
}
