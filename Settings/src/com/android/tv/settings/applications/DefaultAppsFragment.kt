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
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Bundle
import androidx.annotation.Keep
import androidx.preference.Preference
import com.android.tv.settings.R
import com.android.tv.settings.SettingsPreferenceFragment

/**
 * Lists the default application roles the platform exposes (home, browser, dialer, sms) and lets
 * the user switch the holder of each one. Roles with no installable candidates are hidden, so on a
 * device without telephony only the relevant rows show up.
 */
@Keep
class DefaultAppsFragment : SettingsPreferenceFragment() {

    private lateinit var roleManager: RoleManager
    private lateinit var pm: PackageManager

    override fun onAttach(context: Context) {
        super.onAttach(context)
        roleManager = context.getSystemService(RoleManager::class.java)!!
        pm = context.packageManager
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = requireContext()
        val screen = preferenceManager.createPreferenceScreen(context) ?: return
        screen.title = getString(R.string.default_apps_title)
        for (role in ROLES) {
            if (candidatesFor(context, pm, role.roleName).isEmpty()) continue
            val pref = Preference(context).apply {
                key = KEY_PREFIX + role.roleName
                title = getString(role.titleRes)
                isPersistent = false
                icon = context.getDrawable(R.drawable.ic_applications)
                fragment = DefaultAppPickerFragment::class.java.name
                extras.putString(DefaultAppPickerFragment.EXTRA_ROLE, role.roleName)
            }
            screen.addPreference(pref)
        }
        preferenceScreen = screen
    }

    override fun onResume() {
        super.onResume()
        refreshSummaries()
    }

    private fun refreshSummaries() {
        val screen = preferenceScreen ?: return
        val fallback = requireContext().getDrawable(R.drawable.ic_applications)
        for (role in ROLES) {
            val pref = screen.findPreference<Preference>(KEY_PREFIX + role.roleName) ?: continue
            val pkg = runCatching { roleManager.getDefaultApplication(role.roleName) }.getOrNull()
            if (pkg.isNullOrEmpty()) {
                pref.summary = getString(R.string.default_app_none)
                pref.icon = fallback
            } else {
                pref.summary = runCatching { pm.getApplicationInfo(pkg, 0).loadLabel(pm) }
                    .getOrDefault(pkg)
                pref.icon = runCatching { pm.getApplicationIcon(pkg) }.getOrDefault(fallback)
            }
        }
    }

    companion object {
        private const val KEY_PREFIX = "default_app_"

        data class DefaultRole(val roleName: String, val titleRes: Int)

        private val ROLES = listOf(
            DefaultRole(RoleManager.ROLE_HOME, R.string.default_app_home),
            DefaultRole(RoleManager.ROLE_BROWSER, R.string.default_app_browser),
            DefaultRole(RoleManager.ROLE_DIALER, R.string.default_app_dialer),
            DefaultRole(RoleManager.ROLE_SMS, R.string.default_app_sms),
        )

        @JvmStatic
        fun titleResFor(roleName: String): Int =
            ROLES.firstOrNull { it.roleName == roleName }?.titleRes ?: R.string.default_apps_title

        /**
         * Apps that can satisfy [roleName], resolved from the role's representative intent. The
         * role system does not expose a candidate list on this platform build, so the equivalent
         * intent query is used. Returns an empty list for roles that are not represented here.
         * The Settings app itself is never offered as a candidate (its package hosts the
         * internal fallback home, which is not a launcher a user can pick).
         */
        @JvmStatic
        fun candidatesFor(context: Context, pm: PackageManager, roleName: String): List<ResolveInfo> {
            val settingsPackage = context.packageName
            // Primary activity that represents the role, plus the extra components a package
            // must also expose to actually qualify. Matching only the primary intent lets a
            // framework stub package (which advertises a lone DIAL filter) masquerade as a
            // dialer; requiring the role's full component set excludes it.
            val (primary, extraRequired) = when (roleName) {
                RoleManager.ROLE_HOME ->
                    Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_HOME)
                        .addCategory(Intent.CATEGORY_DEFAULT) to emptyList()
                RoleManager.ROLE_BROWSER ->
                    Intent(Intent.ACTION_VIEW)
                        .addCategory(Intent.CATEGORY_BROWSABLE)
                        .setData(Uri.parse("http://")) to emptyList()
                RoleManager.ROLE_DIALER ->
                    Intent(Intent.ACTION_DIAL).setData(Uri.parse("tel:")) to
                        listOf(Intent("android.telecom.InCallService"))
                RoleManager.ROLE_SMS ->
                    Intent(Intent.ACTION_SENDTO).setData(Uri.parse("smsto:")) to emptyList()
                else -> return emptyList()
            }
            val activities = pm.queryIntentActivities(primary, 0)
                .filter { it.activityInfo != null && it.activityInfo.packageName != settingsPackage }
            if (extraRequired.isEmpty()) return activities
            val qualifying = activities.map { it.activityInfo.packageName }.distinct().filter { pkg ->
                extraRequired.all { extra ->
                    pm.queryIntentActivities(extra, 0).any { it.activityInfo?.packageName == pkg } ||
                        pm.queryIntentServices(extra, 0).any { it.serviceInfo?.packageName == pkg }
                }
            }.toSet()
            return activities.filter { it.activityInfo.packageName in qualifying }
        }
    }
}
