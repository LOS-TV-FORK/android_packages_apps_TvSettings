/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.tv.settings.device.displaysound;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.os.Bundle;

import androidx.annotation.Keep;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceGroup;
import androidx.preference.SwitchPreference;

import com.android.settingslib.core.AbstractPreferenceController;
import com.android.tv.settings.PreferenceControllerFragment;
import com.android.tv.settings.R;
import com.android.tv.settings.RadioPreference;

import java.util.List;

/**
 * Manual override of the main audio output ({@link AudioOutputSelectionFragment})
 * or microphone ({@link AudioInputSelectionFragment}). "Auto" hands routing
 * back to the system.
 */
public abstract class AudioDeviceSelectionFragment extends PreferenceControllerFragment {
    static final String KEY_DEVICE_SELECTION = "audio_device_selection_option";
    static final String KEY_DEVICE_PREFIX = "audio_device_selection_";
    static final String KEY_DEVICE_AUTO = "audio_device_selection_auto";
    static final String KEY_HDMI_ENABLE = "audio_hdmi_enable";

    private PreferenceCategory mCategory;

    protected abstract int getDirection();

    protected abstract CharSequence getAutoSummary(Context context);

    @Override
    protected int getPreferenceScreenResId() {
        return R.xml.audio_device_selection;
    }

    @Override
    protected List<AbstractPreferenceController> onCreatePreferenceControllers(Context context) {
        return null;
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.audio_device_selection, null);
        mCategory = findPreference(KEY_DEVICE_SELECTION);
        refresh();
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        if (mCategory == null) {
            return;
        }
        mCategory.removeAll();
        Context context = getContext();

        RadioPreference auto = new RadioPreference(context);
        auto.setTitle(getString(R.string.audio_device_auto_title));
        auto.setSummary(getAutoSummary(context));
        auto.setKey(KEY_DEVICE_AUTO);
        auto.setOnPreferenceClickListener(pref -> {
            onDevicePicked(null);
            return true;
        });
        mCategory.addPreference(auto);

        List<AudioDeviceInfo> devices =
                AudioDeviceRouteHelper.listDevices(context, getDirection());
        AudioDeviceRouteHelper.Selection saved = getSavedSelection(context);
        boolean anyChecked = false;
        for (int i = 0; i < devices.size(); i++) {
            AudioDeviceInfo info = devices.get(i);
            RadioPreference pref = new RadioPreference(context);
            pref.setTitle(AudioDeviceRouteHelper.describe(info));
            pref.setSummary(AudioDeviceRouteHelper.describeDetails(info));
            pref.setKey(KEY_DEVICE_PREFIX + i);
            final AudioDeviceInfo picked = info;
            pref.setOnPreferenceClickListener(p -> {
                onDevicePicked(picked);
                return true;
            });
            if (!saved.isAuto() && saved.matches(info)) {
                pref.setChecked(true);
                pref.clearOtherRadioPreferences(getPreferenceGroup());
                anyChecked = true;
            }
            mCategory.addPreference(pref);
        }
        if (!anyChecked) {
            auto.setChecked(true);
            auto.clearOtherRadioPreferences(getPreferenceGroup());
        }

        if (getDirection() == AudioDeviceRouteHelper.DIRECTION_OUTPUT) {
            SwitchPreference hdmi = new SwitchPreference(context);
            hdmi.setTitle(getString(R.string.audio_hdmi_enable_title));
            hdmi.setSummary(getString(R.string.audio_hdmi_enable_summary));
            hdmi.setKey(KEY_HDMI_ENABLE);
            hdmi.setChecked(AudioDeviceRouteHelper.isHdmiEnabled(context));
            hdmi.setOnPreferenceClickListener(pref -> {
                AudioDeviceRouteHelper.setHdmiEnabled(context,
                        !AudioDeviceRouteHelper.isHdmiEnabled(context));
                // Policy applies the connection state asynchronously; rebuild
                // the list after it lands, otherwise HDMI seems missing.
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                        this::refresh, 600);
                refresh();
                return true;
            });
            mCategory.addPreference(hdmi);
        }
    }

    private AudioDeviceRouteHelper.Selection getSavedSelection(Context context) {
        return getDirection() == AudioDeviceRouteHelper.DIRECTION_OUTPUT
                ? AudioDeviceRouteHelper.getSavedOutput(context)
                : AudioDeviceRouteHelper.getSavedInput(context);
    }

    private void onDevicePicked(AudioDeviceInfo info) {
        if (getDirection() == AudioDeviceRouteHelper.DIRECTION_OUTPUT) {
            AudioDeviceRouteHelper.applyOutputOverride(getContext(), info);
        } else {
            AudioDeviceRouteHelper.applyInputOverride(getContext(), info);
        }
        refresh();
    }

    private PreferenceGroup getPreferenceGroup() {
        return (PreferenceGroup) findPreference(KEY_DEVICE_SELECTION);
    }

    /** Main output device override. */
    @Keep
    public static class AudioOutputSelectionFragment extends AudioDeviceSelectionFragment {
        public static AudioOutputSelectionFragment newInstance() {
            return new AudioOutputSelectionFragment();
        }

        @Override
        protected int getDirection() {
            return AudioDeviceRouteHelper.DIRECTION_OUTPUT;
        }

        @Override
        protected CharSequence getAutoSummary(Context context) {
            return getString(R.string.audio_device_auto_output_summary);
        }
    }

    /** Main microphone override. */
    @Keep
    public static class AudioInputSelectionFragment extends AudioDeviceSelectionFragment {
        public static AudioInputSelectionFragment newInstance() {
            return new AudioInputSelectionFragment();
        }

        @Override
        protected int getDirection() {
            return AudioDeviceRouteHelper.DIRECTION_INPUT;
        }

        @Override
        protected CharSequence getAutoSummary(Context context) {
            return getString(R.string.audio_device_auto_input_summary);
        }
    }
}
