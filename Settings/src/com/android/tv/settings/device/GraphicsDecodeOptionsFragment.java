/*
 * Copyright (C) 2026 The LOS21ATV Project
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

package com.android.tv.settings.device;

import android.app.tvsettings.TvSettingsEnums;
import android.os.Bundle;
import android.os.SystemProperties;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.SwitchPreference;

import com.android.tv.settings.R;
import com.android.tv.settings.SettingsPreferenceFragment;

/**
 * Graphics / decoding option switches that toggle system properties directly,
 * adapted for a container-less Android-x86/TV build (no Waydroid).
 *
 * The values map to media / graphics components that the platform must actually
 * ship (ffmpeg-codec2, Intel MediaSDK-C2, libva, gbm/minigbm). If a backend
 * component is absent, the corresponding toggle simply has no effect.
 */
@androidx.annotation.Keep
public class GraphicsDecodeOptionsFragment extends SettingsPreferenceFragment
        implements Preference.OnPreferenceChangeListener {

    private static final String TAG = "GraphicsDecodeOptionsFragment";

    private static final String KEY_CURRENT_CODEC2_IMPL = "current_codec2_impl";
    private static final String KEY_CODEC2_IMPL = "codec2_impl";
    private static final String KEY_CURRENT_GRALLOC_IMPL = "current_gralloc_impl";
    private static final String KEY_GRALLOC_IMPL = "gralloc_impl";
    private static final String KEY_HWACCEL = "hwaccel";
    private static final String KEY_HWACCEL_DRM_PRIME = "hwaccel_drm_prime";
    private static final String KEY_HWACCEL_FORCE = "hwaccel_force";

    // All switches are staged into persist.sys.los.* (system_prop, settable by system_app,
    // survives reboot). The boot init script (device/generic/x86_64_tv/init.sh) applies
    // them to the real engine properties read by the shipped ffmpeg-codec2 engine
    // (external/stagefright-plugins): media.sf.hwaccel and debug.ffmpeg-codec2.hwaccel.drm.
    // persist.sys.los.hardware.gralloc is applied to ro.hardware.gralloc by init.sh too.
    private static final String PROPERTY_CODEC2_IMPL = "persist.sys.los.codec2-impl";
    private static final String PROPERTY_GRALLOC = "persist.sys.los.hardware.gralloc";
    private static final String SETTING_HWACCEL = "persist.sys.los.media.sf.hwaccel";
    private static final String SETTING_HWACCEL_DRM_PRIME = "persist.sys.los.media.sf.hwaccel.drm";
    private static final String SETTING_HWACCEL_FORCE = "persist.sys.los.media.sf.hwaccel.force";

    private ListPreference mCodec2Impl;
    private ListPreference mGrallocImpl;
    private SwitchPreference mHwaccel;
    private SwitchPreference mHwaccelDrmPrime;
    private SwitchPreference mHwaccelForce;

    private boolean setListPrefValue(ListPreference pref, String value) {
        CharSequence[] values = pref.getEntryValues();
        for (int i = 0; i < values.length; i++) {
            if (values[i].toString().equals(value)) {
                pref.setValueIndex(i);
                return true;
            }
        }
        return false;
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.graphics_decode_options, null);

        final String currentCodec2 = SystemProperties.get(PROPERTY_CODEC2_IMPL, "unset");
        final String currentGralloc = SystemProperties.get(PROPERTY_GRALLOC, "unset");

        final Preference currentCodec2Pref = findPreference(KEY_CURRENT_CODEC2_IMPL);
        if (currentCodec2Pref != null) {
            currentCodec2Pref.setSummary(currentCodec2);
        }
        final Preference currentGrallocPref = findPreference(KEY_CURRENT_GRALLOC_IMPL);
        if (currentGrallocPref != null) {
            currentGrallocPref.setSummary(currentGralloc);
        }

        mCodec2Impl = (ListPreference) findPreference(KEY_CODEC2_IMPL);
        mGrallocImpl = (ListPreference) findPreference(KEY_GRALLOC_IMPL);
        mHwaccel = (SwitchPreference) findPreference(KEY_HWACCEL);
        mHwaccelDrmPrime = (SwitchPreference) findPreference(KEY_HWACCEL_DRM_PRIME);
        mHwaccelForce = (SwitchPreference) findPreference(KEY_HWACCEL_FORCE);

        mCodec2Impl.setOnPreferenceChangeListener(this);
        mGrallocImpl.setOnPreferenceChangeListener(this);
        mHwaccel.setOnPreferenceChangeListener(this);
        mHwaccelDrmPrime.setOnPreferenceChangeListener(this);
        mHwaccelForce.setOnPreferenceChangeListener(this);

        // Persisted values (selected by user) start from current property / setting.
        setListPrefValue(mCodec2Impl, SystemProperties.get(PROPERTY_CODEC2_IMPL, "auto"));
        setListPrefValue(mGrallocImpl, SystemProperties.get(PROPERTY_GRALLOC, "auto"));

        mHwaccel.setChecked(SystemProperties.getInt(SETTING_HWACCEL, 1) != 0);
        mHwaccelDrmPrime.setChecked(SystemProperties.getInt(SETTING_HWACCEL_DRM_PRIME, 0) != 0);
        mHwaccelForce.setChecked(SystemProperties.getInt(SETTING_HWACCEL_FORCE, 0) != 0);
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        if (preference == mCodec2Impl) {
            SystemProperties.set(PROPERTY_CODEC2_IMPL, newValue.toString());
        } else if (preference == mGrallocImpl) {
            SystemProperties.set(PROPERTY_GRALLOC, newValue.toString());
        } else if (preference == mHwaccel) {
            SystemProperties.set(SETTING_HWACCEL, (Boolean) newValue ? "1" : "0");
        } else if (preference == mHwaccelDrmPrime) {
            SystemProperties.set(SETTING_HWACCEL_DRM_PRIME, (Boolean) newValue ? "1" : "0");
        } else if (preference == mHwaccelForce) {
            SystemProperties.set(SETTING_HWACCEL_FORCE, (Boolean) newValue ? "1" : "0");
        }
        return true;
    }

    @Override
    protected int getPageId() {
        return TvSettingsEnums.SYSTEM;
    }
}
