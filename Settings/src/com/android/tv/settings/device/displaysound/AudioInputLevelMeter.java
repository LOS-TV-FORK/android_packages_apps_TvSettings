/*
 * Copyright (C) 2026 The LineageOS Project
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

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Shows how much signal actually arrives at an audio input.
 *
 * <p>Picking an input in the settings only says what the system was asked to do, which is not
 * the same as sound arriving: the wrong device can be routed, or the right one can stay silent
 * because nothing is plugged into it. Reading the input back is the only way to tell the two
 * apart, so a short recording is made from the selected device and its level reported.
 *
 * <p>The recording only runs while the settings screen is open, and nothing is stored: only the
 * level of each buffer is kept.
 */
final class AudioInputLevelMeter {

    private static final String TAG = "AudioInputLevelMeter";

    /** Speech is enough to show a level, and it keeps the recording cheap. */
    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;

    /** How many steps the reported level has. */
    static final int BARS = 8;

    /** How long a stopping recording is given to release the device. */
    private static final long JOIN_TIMEOUT_MS = 500;

    /** The level is reported relative to full scale, quiet input sits near the bottom. */
    private static final float MIN_DB = -60f;

    interface Listener {
        /** Called on the main thread with the number of lit bars, 0 to {@link #BARS}. */
        void onLevel(int bars);
    }

    private final Listener mListener;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private volatile boolean mRunning;
    private Thread mThread;

    AudioInputLevelMeter(Listener listener) {
        mListener = listener;
    }

    /** Starts measuring {@code device}, or the system default input when it is null. */
    void start(Context context, AudioDeviceInfo device) {
        stop();
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        mRunning = true;
        mThread = new Thread(() -> record(context, device), "AudioInputLevelMeter");
        mThread.start();
    }

    void stop() {
        mRunning = false;
        final Thread thread = mThread;
        if (thread == null) {
            return;
        }
        mThread = null;
        thread.interrupt();
        // Give the previous recording a moment to release the device, otherwise switching
        // inputs can leave two captures open on the same device at once.
        try {
            thread.join(JOIN_TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void record(Context context, AudioDeviceInfo device) {
        final int minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, ENCODING);
        if (minBuffer <= 0) {
            return;
        }
        final AudioRecord record;
        try {
            record = new AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.DEFAULT)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(ENCODING)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(CHANNEL_CONFIG)
                            .build())
                    .setBufferSizeInBytes(minBuffer * 4)
                    .build();
            if (device != null && !record.setPreferredDevice(device)) {
                // The device went away or cannot be addressed; the default input is still
                // worth measuring, so carry on without the preference.
                Log.w(TAG, "Could not prefer " + device.getProductName());
            }
        } catch (UnsupportedOperationException | IllegalArgumentException e) {
            Log.w(TAG, "Cannot record for the level meter", e);
            return;
        }

        try {
            record.startRecording();
            if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                return;
            }
            final short[] buffer = new short[minBuffer / 2];
            while (mRunning) {
                final int read = record.read(buffer, 0, buffer.length);
                if (read <= 0) {
                    continue;
                }
                publish(levelOf(buffer, read));
            }
        } catch (IllegalStateException | IllegalArgumentException e) {
            // Another app may hold the microphone; the meter simply stays empty.
            Log.w(TAG, "Level meter stopped early", e);
        } finally {
            try {
                record.stop();
            } catch (IllegalStateException ignored) {
                // Already stopped.
            }
            record.release();
        }
    }

    private void publish(int bars) {
        mHandler.post(() -> {
            if (mRunning) {
                mListener.onLevel(bars);
            }
        });
    }

    /** Turns a buffer into a number of lit bars, using its peak sample. */
    private static int levelOf(short[] buffer, int length) {
        int peak = 0;
        for (int i = 0; i < length; i++) {
            peak = Math.max(peak, Math.abs(buffer[i]));
        }
        if (peak == 0) {
            return 0;
        }
        final float db = 20f * (float) Math.log10(peak / 32768f);
        final float level = (db - MIN_DB) / -MIN_DB;
        return Math.max(0, Math.min(BARS, Math.round(level * BARS)));
    }

    /** Renders the level as a bar the user can read at a distance. */
    static CharSequence render(int bars) {
        final char filled = '█';
        final char empty = '░';
        final StringBuilder text = new StringBuilder(BARS);
        for (int i = 0; i < BARS; i++) {
            text.append(i < bars ? filled : empty);
        }
        return text;
    }
}