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
package com.android.tv.settings.applications

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import androidx.annotation.Keep
import androidx.preference.Preference
import com.android.settingslib.applications.DefaultAppInfo
import com.android.tv.settings.R
import com.android.tv.settings.RadioPreference
import com.android.tv.settings.SettingsPreferenceFragment

/**
 * Picks the holder of a single default application role. Mirrors the autofill picker layout: one
 * radio row per candidate, the current holder preselected. Choosing a row writes the role through
 * [RoleManager.setDefaultApplication] and returns to the list.
 */
@Keep
class DefaultAppPickerFragment : SettingsPreferenceFragment() {

    private lateinit var roleManager: RoleManager
    private var role: String? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onAttach(context: Context) {
        super.onAttach(context)
        roleManager = context.getSystemService(RoleManager::class.java)!!
        role = arguments?.getString(EXTRA_ROLE)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val roleName = role
        if (roleName == null) {
            activity?.finish()
            return
        }
        val context = requireContext()
        val pm = context.packageManager
        val screen = preferenceManager.createPreferenceScreen(context) ?: return
        screen.title = getString(DefaultAppsFragment.titleResFor(roleName))
        val current = runCatching { roleManager.getDefaultApplication(roleName) }.getOrNull()
        for (ri in DefaultAppsFragment.candidatesFor(context, pm, roleName)) {
            val pkg = ri.activityInfo.packageName
            val label = DefaultAppInfo(
                context, pm, UserHandle.myUserId(), ComponentName(pkg, ri.activityInfo.name)
            ).loadLabel()
            val radio = RadioPreference(context).apply {
                key = pkg
                isPersistent = false
                title = label
                setRadioGroup(RADIO_GROUP)
                setLayoutResource(R.layout.preference_reversed_widget)
                isChecked = pkg == current
            }
            screen.addPreference(radio)
        }
        preferenceScreen = screen
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        if (preference is RadioPreference) {
            val roleName = role ?: return true
            val pkg = preference.key ?: return true
            val current = runCatching { roleManager.getDefaultApplication(roleName) }.getOrNull()
            if (pkg != current) {
                preference.isChecked = true
                preference.clearOtherRadioPreferences(preferenceScreen)
                roleManager.setDefaultApplication(roleName, pkg, 0, requireContext().mainExecutor) { _ ->
                    handler.post { goBack() }
                }
            } else {
                goBack()
            }
            return true
        }
        return super.onPreferenceTreeClick(preference)
    }

    private fun goBack() {
        if (!parentFragmentManager.popBackStackImmediate()) {
            activity?.finish()
        }
    }

    companion object {
        const val EXTRA_ROLE = "role"
        private const val RADIO_GROUP = "default_app_group"
    }
}
