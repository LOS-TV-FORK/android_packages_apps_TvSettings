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

package com.android.tv.settings.device.eco;

import android.content.Context;
import android.content.ContentResolver;
import android.os.Bundle;
import android.provider.Settings;

import androidx.annotation.Keep;
import androidx.preference.PreferenceCategory;

import com.android.settingslib.core.AbstractPreferenceController;
import com.android.tv.settings.PreferenceControllerFragment;
import com.android.tv.settings.R;
import com.android.tv.settings.RadioPreference;

import java.util.List;

/**
 * Lets the user pick what a press of the power button does.
 *
 * <p>The behaviour itself lives in {@code PhoneWindowManager}, which reads
 * {@code Settings.Global.POWER_BUTTON_LONG_PRESS} and its short press counterpart. The rows here
 * are rebuilt from that setting every time, so what is ticked is what the button will do, and a
 * choice made anywhere else shows up too.
 *
 * <p>Leaving the entry on "default" clears the setting instead of writing a value, so the platform
 * falls back to {@code config_longPressOnPowerBehavior}. That keeps the device following the
 * built-in default across a wipe or an OS upgrade, when the sensible answer for the hardware may
 * have changed.
 */
public abstract class PowerButtonActionFragment extends PreferenceControllerFragment {

    private static final String KEY_CATEGORY = "power_button_actions";

    /** Means no override is stored, so the platform default applies. */
    private static final int USE_DEFAULT = -1;

    private PreferenceCategory mCategory;

    @Override
    protected int getPreferenceScreenResId() {
        return R.xml.power_button_actions;
    }

    @Override
    protected List<AbstractPreferenceController> onCreatePreferenceControllers(Context context) {
        return null;
    }

    /** The setting this screen edits. */
    protected abstract String getSettingKey();

    /** Labels of the actions, in display order. */
    protected abstract int[] getActionLabels();

    /**
     * The values behind those labels, same order.
     *
     * <p>These are the constants of {@code PhoneWindowManager}, which is not visible from here, so
     * each subclass spells them out with the names they have there.
     */
    protected abstract int[] getActionValues();

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.power_button_actions, null);
        mCategory = findPreference(KEY_CATEGORY);
        refresh();
    }

    @Override
    public void onResume() {
        super.onResume();
        // The setting can be changed by other means, so do not trust the rows built earlier.
        refresh();
    }

    private void refresh() {
        if (mCategory == null || getContext() == null) {
            return;
        }
        mCategory.removeAll();

        final Context context = getContext();
        final int current = readAction(context);
        final int[] labels = getActionLabels();
        final int[] values = getActionValues();

        RadioPreference selected = null;
        for (int i = 0; i < labels.length; i++) {
            final int value = values[i];
            RadioPreference row = new RadioPreference(context);
            row.setTitle(labels[i]);
            row.setKey(KEY_CATEGORY + "_" + value);
            row.setPersistent(false);
            row.setOnPreferenceClickListener(pref -> {
                applyAction(context, value);
                refresh();
                return true;
            });
            if (value == current) {
                row.setChecked(true);
                selected = row;
            }
            mCategory.addPreference(row);
        }

        if (selected != null) {
            // Exactly one row carries the tick, and it is redrawn even when its value did not
            // change, because these are check boxes drawn as radio buttons and a recycled row
            // otherwise keeps the tick of the entry it used to show.
            for (int i = 0; i < mCategory.getPreferenceCount(); i++) {
                RadioPreference row = (RadioPreference) mCategory.getPreference(i);
                row.setChecked(row == selected);
                row.forceRedraw();
            }
        }
    }

    private int readAction(Context context) {
        return Settings.Global.getInt(context.getContentResolver(), getSettingKey(), USE_DEFAULT);
    }

    /** Stores the choice, or removes it when the user asks for the platform default. */
    private void applyAction(Context context, int value) {
        final ContentResolver resolver = context.getContentResolver();
        if (value == USE_DEFAULT) {
            // A null value is how Settings removes the key, which is what puts the platform
            // default back in charge.
            Settings.Global.putString(resolver, getSettingKey(), null);
        } else {
            Settings.Global.putInt(resolver, getSettingKey(), value);
        }
    }

    /** Action for a short press. */
    @Keep
    public static class ShortPressActionFragment extends PowerButtonActionFragment {
        public static ShortPressActionFragment newInstance() {
            return new ShortPressActionFragment();
        }

        @Override
        protected String getSettingKey() {
            return Settings.Global.POWER_BUTTON_SHORT_PRESS;
        }

        @Override
        protected int[] getActionLabels() {
            return new int[] {
                R.string.power_button_action_default,
                R.string.no_application,
                R.string.power_button_action_sleep,
                R.string.power_button_action_sleep_no_doze,
                R.string.power_button_action_go_home,
                R.string.device_daydream,
                R.string.power_button_action_shutdown_confirm,
                R.string.power_button_action_power_menu,
            };
        }

        @Override
        protected int[] getActionValues() {
            // PhoneWindowManager: SHORT_PRESS_POWER_NOTHING, _GO_TO_SLEEP, _REALLY_GO_TO_SLEEP,
            // _GO_HOME, _DREAM_OR_SLEEP, _SHUT_OFF, _GLOBAL_ACTIONS.
            return new int[] {USE_DEFAULT, 0, 1, 2, 4, 7, 10, 11};
        }
    }

    /** Action for a long press. */
    @Keep
    public static class LongPressActionFragment extends PowerButtonActionFragment {
        public static LongPressActionFragment newInstance() {
            return new LongPressActionFragment();
        }

        @Override
        protected String getSettingKey() {
            return Settings.Global.POWER_BUTTON_LONG_PRESS;
        }

        @Override
        protected int[] getActionLabels() {
            return new int[] {
                R.string.power_button_action_default,
                R.string.no_application,
                R.string.power_button_action_power_menu,
                R.string.power_button_action_shutdown_confirm,
                R.string.power_button_action_shutdown_no_confirm,
                R.string.power_button_action_sleep,
            };
        }

        @Override
        protected int[] getActionValues() {
            // PhoneWindowManager: LONG_PRESS_POWER_NOTHING, _GLOBAL_ACTIONS, _SHUT_OFF,
            // _SHUT_OFF_NO_CONFIRM, _GO_TO_SLEEP.
            return new int[] {USE_DEFAULT, 0, 1, 2, 3, 6};
        }
    }
}