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
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioDeviceAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioProductStrategy;
import android.media.MediaRecorder;
import android.os.SystemProperties;
import android.text.TextUtils;
import android.util.Log;

import com.android.tv.settings.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Manual override of the main audio output / input device.
 *
 * <p>By default Android picks devices itself (for MEDIA: last connected
 * removable wins; for MIC: BT &gt; wired &gt; USB &gt; builtin). The choice
 * made here wins over the automatics via the preferred-device APIs and is
 * re-applied on boot (preferred roles do not survive reboot).
 */
public final class AudioDeviceRouteHelper {
    private static final String TAG = "AudioDeviceRouteHelper";

    public static final int DIRECTION_OUTPUT = 0;
    public static final int DIRECTION_INPUT = 1;

    private static final String PREFS = "audio_device_override";
    private static final String KEY_OUT_TYPE = "output_type";
    private static final String KEY_OUT_ADDRESS = "output_address";
    private static final String KEY_IN_TYPE = "input_type";
    private static final String KEY_IN_ADDRESS = "input_address";
    private static final String KEY_SCO_STARTED = "sco_started";
    private static final String KEY_HDMI_ENABLED = "hdmi_enabled";
    private static final int NO_OVERRIDE = -1;

    // Capture presets covered by the microphone override.
    private static final int[] MIC_PRESETS = {
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.UNPROCESSED,
    };

    /** Saved manual choice; {@code type == NO_OVERRIDE} means Auto. */
    public static final class Selection {
        public final int type;
        public final String address;

        Selection(int type, String address) {
            this.type = type;
            this.address = address == null ? "" : address;
        }

        public boolean isAuto() {
            return type == NO_OVERRIDE;
        }

        boolean matches(AudioDeviceInfo info) {
            return !isAuto() && info.getType() == type
                    && TextUtils.equals(address, info.getAddress());
        }
    }

    private AudioDeviceRouteHelper() {
    }

    private static AudioManager audioManager(Context context) {
        return context.getSystemService(AudioManager.class);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Devices the user can pick from. Remote-submix targets are skipped. */
    public static List<AudioDeviceInfo> listDevices(Context context, int direction) {
        List<AudioDeviceInfo> out = new ArrayList<>();
        AudioDeviceInfo[] infos = audioManager(context).getDevices(
                direction == DIRECTION_OUTPUT
                        ? AudioManager.GET_DEVICES_OUTPUTS
                        : AudioManager.GET_DEVICES_INPUTS);
        if (infos == null) {
            return out;
        }
        for (AudioDeviceInfo info : infos) {
            if (info.getType() == AudioDeviceInfo.TYPE_REMOTE_SUBMIX) {
                continue;
            }
            out.add(info);
        }
        return out;
    }

    /** Human-readable label: product name when present, type name otherwise. */
    public static String describe(AudioDeviceInfo info) {
        if (info == null) {
            return "";
        }
        CharSequence name = info.getProductName();
        if (!TextUtils.isEmpty(name)) {
            return name.toString();
        }
        return deviceTypeName(info.getType());
    }

    /** Detail line: type + address (address disambiguates same-type devices). */
    public static String describeDetails(AudioDeviceInfo info) {
        if (info == null) {
            return "";
        }
        String address = info.getAddress();
        if (TextUtils.isEmpty(address)) {
            return deviceTypeName(info.getType());
        }
        return deviceTypeName(info.getType()) + " · " + address;
    }

    public static Selection getSavedOutput(Context context) {
        SharedPreferences p = prefs(context);
        return new Selection(p.getInt(KEY_OUT_TYPE, NO_OVERRIDE),
                p.getString(KEY_OUT_ADDRESS, ""));
    }

    public static Selection getSavedInput(Context context) {
        SharedPreferences p = prefs(context);
        return new Selection(p.getInt(KEY_IN_TYPE, NO_OVERRIDE),
                p.getString(KEY_IN_ADDRESS, ""));
    }

    /** Apply the output override; {@code info == null} resets to Auto. */
    public static void applyOutputOverride(Context context, AudioDeviceInfo info) {
        AudioManager am = audioManager(context);
        android.media.audiopolicy.AudioProductStrategy strategy = mediaStrategy();
        if (strategy == null) {
            Log.w(TAG, "No MEDIA strategy, cannot route output");
            return;
        }
        if (info == null) {
            am.removePreferredDeviceForStrategy(strategy);
        } else {
            am.setPreferredDeviceForStrategy(
                    strategy, new AudioDeviceAttributes(info));
        }
        prefs(context).edit()
                .putInt(KEY_OUT_TYPE, info == null ? NO_OVERRIDE : info.getType())
                .putString(KEY_OUT_ADDRESS, info == null ? "" : info.getAddress())
                .apply();
    }

    /** Apply the microphone override; {@code info == null} resets to Auto. */
    public static void applyInputOverride(Context context, AudioDeviceInfo info) {
        AudioManager am = audioManager(context);
        if (info == null) {
            for (int preset : MIC_PRESETS) {
                try {
                    am.clearPreferredDevicesForCapturePreset(preset);
                } catch (Exception e) {
                    Log.w(TAG, "clear mic preset failed", e);
                }
            }
        } else {
            AudioDeviceAttributes attrs = new AudioDeviceAttributes(info);
            for (int preset : MIC_PRESETS) {
                try {
                    am.setPreferredDeviceForCapturePreset(preset, attrs);
                } catch (Exception e) {
                    Log.w(TAG, "set mic preset failed", e);
                }
            }
        }
        prefs(context).edit()
                .putInt(KEY_IN_TYPE, info == null ? NO_OVERRIDE : info.getType())
                .putString(KEY_IN_ADDRESS, info == null ? "" : info.getAddress())
                .apply();
        updateScoState(context, info);
    }

    /** Re-apply saved choices, e.g. from BOOT_COMPLETED. */
    public static void reapplySavedRoutes(Context context) {
        if (isHdmiEnabled(context)) {
            reportHdmiConnection(context, true);
        }
        Selection out = getSavedOutput(context);
        Selection in = getSavedInput(context);
        if (!out.isAuto()) {
            AudioDeviceInfo match = findMatch(listDevices(context, DIRECTION_OUTPUT), out);
            if (match != null) {
                applyOutputOverride(context, match);
            }
        }
        if (!in.isAuto()) {
            AudioDeviceInfo match = findMatch(listDevices(context, DIRECTION_INPUT), in);
            if (match != null) {
                applyInputOverride(context, match);
            }
        }
    }

    /** HDMI/DP output toggle. Reports an AUX_DIGITAL wired device so the
     * policy lists it; the HAL opens the HDMI PCM on that routing. Off by
     * default; re-applied at boot. */
    public static boolean isHdmiEnabled(Context context) {
        return prefs(context).getBoolean(KEY_HDMI_ENABLED, false);
    }

    public static void setHdmiEnabled(Context context, boolean enabled) {
        reportHdmiConnection(context, enabled);
        prefs(context).edit().putBoolean(KEY_HDMI_ENABLED, enabled).apply();
        if (!enabled) {
            // Drop a selection pointing at the now-gone HDMI device.
            Selection out = getSavedOutput(context);
            if (!out.isAuto()
                    && (out.type == AudioDeviceInfo.TYPE_AUX_LINE
                            || out.type == AudioDeviceInfo.TYPE_HDMI
                            || out.type == AudioDeviceInfo.TYPE_HDMI_ARC
                            || out.type == AudioDeviceInfo.TYPE_HDMI_EARC)) {
                applyOutputOverride(context, null);
            }
        }
    }

    private static void reportHdmiConnection(Context context, boolean connected) {
        try {
            audioManager(context).setWiredDeviceConnectionState(
                    AudioManager.DEVICE_OUT_AUX_DIGITAL, connected ? 1 : 0, "", "");
        } catch (Exception e) {
            Log.w(TAG, "HDMI connection report failed", e);
        }
    }

    private static AudioDeviceInfo findMatch(List<AudioDeviceInfo> infos, Selection sel) {        for (AudioDeviceInfo info : infos) {
            if (sel.matches(info)) {
                return info;
            }
        }
        return null;
    }

    /** One-line summary for the host screen: current choice or Auto. */
    public static CharSequence getOutputSummary(Context context) {
        Selection saved = getSavedOutput(context);
        AudioDeviceInfo match =
                findMatch(listDevices(context, DIRECTION_OUTPUT), saved);
        if (match != null) {
            return describe(match);
        }
        return context.getString(R.string.audio_device_auto_title);
    }

    /** One-line summary for the host screen: current choice or Auto. */
    public static CharSequence getInputSummary(Context context) {
        Selection saved = getSavedInput(context);
        AudioDeviceInfo match =
                findMatch(listDevices(context, DIRECTION_INPUT), saved);
        if (match != null) {
            return describe(match);
        }
        return context.getString(R.string.audio_device_auto_title);
    }

    private static android.media.audiopolicy.AudioProductStrategy mediaStrategy() {
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build();
        return android.media.audiopolicy.AudioProductStrategy
                .getAudioProductStrategyForAudioAttributes(
                        attrs, /* fallbackOnDefault= */ true);
    }

    /**
     * A BT headset mic needs an open SCO link on top of routing; without it
     * there is routing but no voice. Open it when a SCO mic is chosen,
     * close it when moving away (only if we opened it).
     */
    private static void updateScoState(Context context, AudioDeviceInfo info) {
        AudioManager am = audioManager(context);
        SharedPreferences p = prefs(context);
        boolean wantSco = info != null
                && info.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO;
        boolean started = p.getBoolean(KEY_SCO_STARTED, false);
        try {
            if (wantSco && !started) {
                am.startBluetoothSco();
                p.edit().putBoolean(KEY_SCO_STARTED, true).apply();
            } else if (!wantSco && started) {
                am.stopBluetoothSco();
                p.edit().putBoolean(KEY_SCO_STARTED, false).apply();
            }
        } catch (Exception e) {
            Log.w(TAG, "BT SCO switch failed", e);
        }
    }

    private static String deviceTypeName(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:
                return "Speaker";
            case AudioDeviceInfo.TYPE_HDMI:
            case AudioDeviceInfo.TYPE_HDMI_ARC:
            case AudioDeviceInfo.TYPE_HDMI_EARC:
            case AudioDeviceInfo.TYPE_LINE_DIGITAL:
            case AudioDeviceInfo.TYPE_AUX_LINE:
                return "HDMI";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                return "Wired headset";
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
                return "Wired headphones";
            case AudioDeviceInfo.TYPE_USB_DEVICE:
            case AudioDeviceInfo.TYPE_USB_HEADSET:
                return "USB audio";
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                return "Bluetooth audio";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                return "Bluetooth headset";
            case AudioDeviceInfo.TYPE_BUILTIN_MIC:
                return "Built-in mic";
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE:
                return "Earpiece";
            case AudioDeviceInfo.TYPE_DOCK:
                return "Dock";
            default:
                return "Audio device";
        }
    }
}
