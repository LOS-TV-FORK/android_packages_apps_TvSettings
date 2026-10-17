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

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;

/**
 * Plays a short tune on an audio output, so the user can hear whether the output they just picked
 * is the one they hear the sound from.
 *
 * <p>Choosing an output in the settings only says what the system was asked to do: the row can be
 * checked while nothing is plugged into it, or while the amplifier is off, and the list looks the
 * same either way. A tune makes the difference audible right where the choice is made, instead of
 * only afterwards in another app.
 *
 * <p>The tune is a few notes of a melody rather than a single beep. A beep only proves that
 * something came out; a tune that is recognised at once reads as music playing normally, which is
 * what the user is actually trying to confirm, and it does not startle anyone who is still sitting
 * close to the speakers.
 *
 * <p>The first phrase of Beethoven's Ode to Joy, in the public domain. Everything is generated in
 * memory and stored nowhere. It plays through the media stream, so it is heard at the volume the
 * user actually set; if the volume is at zero the settings screen stays silent, as it should.
 */
final class AudioOutputTestTone {

    private static final String TAG = "AudioOutputTestTone";

    /**
     * Ode to Joy, opening phrase: E E F G G F E D, in Hz.
     *
     * <p>A pure sine would sound like a test beep and would be unnervingly flat. These are the
     * first three partials of a struck string, which is where the difference between a beep and
     * an instrument comes from.
     */
    private static final double[] MELODY = {
        329.63, 329.63, 349.23, 392.00, 392.00, 349.23, 329.63, 293.66,
    };

    /** Longer than the gap between notes, so each one is heard separately. */
    private static final double NOTE_S = 0.30;

    /** The partials, strongest first; they are normalised before use. */
    private static final double[] PARTIALS = {1.0, 0.32, 0.14};

    /** How fast a note dies away, in e-folds per second. */
    private static final double DECAY = 7.0;

    /** A few milliseconds at the start of a note, so it does not click. */
    private static final double ATTACK_S = 0.004;

    /** And at the end, where one note hands over to the next. */
    private static final double RELEASE_S = 0.02;

    /** About -11 dBFS per note: clearly audible, not startling at a normal volume. */
    private static final double AMPLITUDE = 0.28;

    /** Interleaved stereo: the melody is sent to both sides. */
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_STEREO;
    private static final int CHANNELS = 2;

    /** Used when the platform reports nothing better for the media stream. */
    private static final int FALLBACK_RATE = 48000;

    /** Lets a newer request take over from one that is still playing. */
    private volatile int mGeneration;

    /** Plays {@code device}, or the system default output when it is null. */
    void play(final Context context, final AudioDeviceInfo device) {
        final int generation = ++mGeneration;
        new Thread(() -> tune(context, device, generation), TAG).start();
    }

    /** Cuts a tune that is still playing off. */
    void stop() {
        mGeneration++;
    }

    private void tune(Context context, AudioDeviceInfo device, int generation) {
        final int rate = sampleRate(context);
        final short[] wave = generate(rate);
        final AudioTrack track;
        try {
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate)
                            .setChannelMask(CHANNEL_CONFIG)
                            .build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(wave.length * 2)
                    .build();
        } catch (UnsupportedOperationException | IllegalArgumentException e) {
            Log.w(TAG, "Cannot play the test tune", e);
            return;
        }

        try {
            if (device != null && !track.setPreferredDevice(device)) {
                // The device went away or cannot be addressed. The media strategy still carries
                // the selection, so playing anyway is better than staying silent.
                Log.w(TAG, "Could not prefer " + device.getProductName());
            }
            track.write(wave, 0, wave.length);
            track.play();
            final int frames = wave.length / CHANNELS;
            waitUntilPlayed(track, frames, frames / (rate / 1000L) + 300, generation);
        } catch (IllegalStateException | IllegalArgumentException e) {
            Log.w(TAG, "Test tune stopped early", e);
        } finally {
            try {
                track.stop();
            } catch (IllegalStateException ignored) {
                // Already stopped.
            }
            track.release();
        }
    }

    /** Waits for the tune out, giving up as soon as a newer one replaces it. */
    private void waitUntilPlayed(AudioTrack track, int frames, long timeoutMs, int generation) {
        final long end = System.currentTimeMillis() + timeoutMs;
        while (mGeneration == generation && System.currentTimeMillis() < end) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            // The playback head counts frames since play(); in static mode it stops at the end
            // of the buffer, which is the point where the tune has actually been heard.
            if (track.getPlaybackHeadPosition() >= frames) {
                return;
            }
        }
    }

    /** Plays at the rate the media stream runs at, so no resampling is needed. */
    private static int sampleRate(Context context) {
        final int native_ = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC);
        return native_ > 0 ? native_ : FALLBACK_RATE;
    }

    /** Builds the tune: each note struck and left to fade before the next one. */
    private static short[] generate(int rate) {
        final int noteSamples = Math.max(1, (int) (rate * NOTE_S));
        final short[] wave = new short[noteSamples * MELODY.length];
        final int attack = Math.max(1, (int) (rate * ATTACK_S));
        final int release = Math.max(1, (int) (rate * RELEASE_S));
        double norm = 0;
        for (double partial : PARTIALS) {
            norm += partial;
        }

        for (int n = 0; n < MELODY.length; n++) {
            final int start = noteSamples * n;
            for (int i = 0; i < noteSamples; i++) {
                final double t = i / (double) rate;
                double env = Math.exp(-t * DECAY);
                if (i < attack) {
                    env *= i / (double) attack;
                } else if (i >= noteSamples - release) {
                    env *= (noteSamples - i) / (double) release;
                }
                double value = 0;
                for (int p = 0; p < PARTIALS.length; p++) {
                    value += PARTIALS[p] * Math.sin(2 * Math.PI * MELODY[n] * (p + 1) * t);
                }
                wave[start + i] += (short) (value / norm * env * AMPLITUDE * Short.MAX_VALUE);
            }
        }
        return wave;
    }
}