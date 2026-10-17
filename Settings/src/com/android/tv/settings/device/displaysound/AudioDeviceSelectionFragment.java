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
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

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

    /** Routing is asynchronous, so the tone waits for the switch to reach the policy. */
    private static final long ROUTE_SETTLE_MS = 250;

    private PreferenceCategory mCategory;

    /** Level meter for the input screen; null on the output screen and while paused. */
    private AudioInputLevelMeter mMeter;

    private final Handler mUiHandler = new Handler(Looper.getMainLooper());

    /** Test tone for the output screen; null on the input screen and until the first pick. */
    private AudioOutputTestTone mTestTone;

    /** The output the pending tone should go to. */
    private AudioDeviceInfo mToneDevice;

    private final Runnable mPlayTestTone = this::playTestTone;

    /** The row that is measured, and the device it points at; null means the system choice. */
    private Preference mMeasuredRow;
    private AudioDeviceInfo mMeasuredDevice;

    private int mInputLevel;

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
        if (getDirection() == AudioDeviceRouteHelper.DIRECTION_INPUT) {
            startMeter();
        }
    }

    @Override
    public void onPause() {
        mUiHandler.removeCallbacks(mPlayTestTone);
        if (mTestTone != null) {
            mTestTone.stop();
        }
        stopMeter();
        super.onPause();
    }

    /**
     * Starts reading the selected input back, so the list shows what actually arrives there
     * rather than what was only requested. Without this a wrong routing and a silent input
     * look exactly the same.
     */
    private void startMeter() {
        if (getContext() == null) {
            return;
        }
        if (mMeter == null) {
            mMeter = new AudioInputLevelMeter(bars -> {
                mInputLevel = bars;
                updateMeasuredSummary();
            });
        }
        mMeter.start(getContext(), mMeasuredDevice);
        updateMeasuredSummary();
    }

    private void stopMeter() {
        if (mMeter != null) {
            mMeter.stop();
            mInputLevel = 0;
        }
    }

    /** Repaints the row of the measured input with the current level. */
    private void updateMeasuredSummary() {
        final Preference row = mMeasuredRow;
        if (row == null || getContext() == null) {
            return;
        }
        row.setSummary(getString(R.string.audio_device_input_level,
                AudioInputLevelMeter.render(mInputLevel)));
    }

    /**
     * Points the meter at the row the user picked and restarts it, because another input means
     * another recording.
     */
    private void retargetMeter() {
        if (getDirection() != AudioDeviceRouteHelper.DIRECTION_INPUT || getContext() == null) {
            return;
        }
        if (mMeasuredRow != null) {
            mMeasuredRow.setSummary(mMeasuredDevice != null
                    ? AudioDeviceRouteHelper.describeDetails(mMeasuredDevice)
                    : getAutoSummary(getContext()));
        }
        mInputLevel = 0;
        if (mMeter != null) {
            mMeter.start(getContext(), mMeasuredDevice);
            updateMeasuredSummary();
        }
    }

    /**
     * Puts the tick on exactly one row.
     *
     * <p>These rows are {@link RadioPreference}s, which are check boxes wearing a radio button,
     * so the widget flips itself on every tap and nothing stops two rows from being ticked at
     * once. {@code setChecked()} only refreshes the view when the value actually changed, which
     * lets a recycled row keep the tick of the device it used to show. Both are fixed by writing
     * every row and then forcing it to redraw, so the list always reads as one choice.
     */
    private void applyCheckedState(String checkedKey) {
        PreferenceGroup group = getPreferenceGroup();
        if (group == null) {
            return;
        }
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference p = group.getPreference(i);
            if (!(p instanceof RadioPreference)) {
                continue;
            }
            final RadioPreference row = (RadioPreference) p;
            row.setChecked(TextUtils.equals(p.getKey(), checkedKey));
            // Redraw even when the value did not change, so no row keeps a stale tick.
            row.forceRedraw();
        }
    }

    private Preference findRow(String key) {
        PreferenceGroup group = getPreferenceGroup();
        if (group == null) {
            return null;
        }
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference p = group.getPreference(i);
            if (TextUtils.equals(p.getKey(), key)) {
                return p;
            }
        }
        return null;
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
            onDevicePicked(null, KEY_DEVICE_AUTO);
            return true;
        });
        mCategory.addPreference(auto);

        List<AudioDeviceInfo> devices =
                AudioDeviceRouteHelper.listDevices(context, getDirection());
        AudioDeviceRouteHelper.Selection saved = getSavedSelection(context);
        String checkedKey = KEY_DEVICE_AUTO;
        mMeasuredRow = auto;
        mMeasuredDevice = null;
        for (int i = 0; i < devices.size(); i++) {
            AudioDeviceInfo info = devices.get(i);
            RadioPreference pref = new RadioPreference(context);
            pref.setTitle(AudioDeviceRouteHelper.describe(info));
            pref.setSummary(AudioDeviceRouteHelper.describeDetails(info));
            pref.setKey(KEY_DEVICE_PREFIX + i);
            final AudioDeviceInfo picked = info;
            pref.setOnPreferenceClickListener(p -> {
                onDevicePicked(picked, p.getKey());
                return true;
            });
            if (!saved.isAuto() && saved.matches(info)) {
                checkedKey = pref.getKey();
                mMeasuredRow = pref;
                mMeasuredDevice = info;
            }
            mCategory.addPreference(pref);
        }
        applyCheckedState(checkedKey);
        retargetMeter();

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

    /**
     * Applies the chosen device and moves the tick onto it.
     *
     * <p>The list is left standing instead of being rebuilt: a rebuild on every tap is what let
     * the tick land on more than one row, and it also threw away the row state that was already
     * correct. Only the tick is rewritten, and writing it is idempotent, so tapping the row that
     * is already chosen leaves it chosen.
     */
    private void onDevicePicked(AudioDeviceInfo info, String key) {
        if (getDirection() == AudioDeviceRouteHelper.DIRECTION_OUTPUT) {
            AudioDeviceRouteHelper.applyOutputOverride(getContext(), info);
            scheduleTestTone(info);
        } else {
            AudioDeviceRouteHelper.applyInputOverride(getContext(), info);
        }
        applyCheckedState(key);
        mMeasuredRow = findRow(key);
        mMeasuredDevice = info;
        mInputLevel = 0;
        retargetMeter();
    }

    /**
     * Plays the tone on the output that was just picked, so the choice can be checked by ear
     * before leaving the screen. Waiting for the routing to settle matters: played too early the
     * tone would leave through the previous device and mark the right one as silent.
     */
    private void scheduleTestTone(AudioDeviceInfo info) {
        mUiHandler.removeCallbacks(mPlayTestTone);
        mToneDevice = info;
        mUiHandler.postDelayed(mPlayTestTone, ROUTE_SETTLE_MS);
    }

    private void playTestTone() {
        if (getDirection() != AudioDeviceRouteHelper.DIRECTION_OUTPUT || getContext() == null) {
            return;
        }
        if (mTestTone == null) {
            mTestTone = new AudioOutputTestTone();
        }
        mTestTone.play(getContext(), mToneDevice);
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
