/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static android.content.DialogInterface.OnClickListener;
import static android.view.Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION;
import static android.view.Display.HdrCapabilities.HDR_TYPE_INVALID;
import static com.android.tv.settings.device.displaysound.DisplaySoundUtils.createAlertDialog;
import static com.android.tv.settings.device.displaysound.DisplaySoundUtils.doesCurrentModeNotSupportDvBecauseLimitedTo4k30;
import static com.android.tv.settings.device.displaysound.DisplaySoundUtils.isHdrFormatSupported;
import static com.android.tv.settings.device.displaysound.DisplaySoundUtils.sendHdrSettingsChangedBroadcast;
import static com.android.tv.settings.device.displaysound.PreferredDynamicRangeFragment.selectForceHdrConversion;
import static com.android.tv.settings.device.displaysound.ResolutionSelectionInfo.HDR_TYPES_ARRAY;

import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.DisplayManagerGlobal;
import android.hardware.display.HdrConversionMode;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.widget.Button;

import androidx.annotation.Keep;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceGroup;

import com.android.settingslib.core.AbstractPreferenceController;
import com.android.tv.settings.PreferenceControllerFragment;
import com.android.tv.settings.R;
import com.android.tv.settings.RadioPreference;
import com.android.tv.settings.overlay.FlavorUtils;
import com.android.tv.settings.util.ResolutionSelectionUtils;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * This Fragment is responsible for allowing the user to choose the resolution and refresh rate
 * from the list of resolution and refresh rates which are supported by device.
 */
@Keep
public class ResolutionSelectionFragment extends PreferenceControllerFragment {
    static final String KEY_MODE_SELECTION = "resolution_selection_option";
    static final String KEY_RESOLUTION_PREFIX = "resolution_selection_";
    static final String KEY_RESOLUTION_SELECTION_AUTO = "resolution_selection_auto";

    private static final String TAG = ResolutionSelectionFragment.class.getSimpleName();
    private DisplayManager mDisplayManager;
    private Display.Mode[] mModes;
    private int mUserPreferredModeIndex;
    private PreferenceCategory mResolutionPreferenceCategory;
    private Display.Mode mAutoMode;
    private AlertDialog mConfirmDialog;

    static final Set<Integer> STANDARD_RESOLUTIONS_IN_ORDER = Set.of(2160, 1080, 720, 576, 480);
    static final int DIALOG_TIMEOUT_MILLIS = 12000;
    static final int DIALOG_START_MILLIS = 1000;

    /** @return the new instance of the class */
    public static ResolutionSelectionFragment newInstance() {
        return new ResolutionSelectionFragment();
    }

    @Override
    public void onAttach(Context context) {
        mDisplayManager = getDisplayManager();
        super.onAttach(context);
    }

    @Override
    protected int getPreferenceScreenResId() {
        return R.xml.resolution_selection;
    }

    @Override
    protected List<AbstractPreferenceController> onCreatePreferenceControllers(Context context) {
        return null;
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.resolution_selection, null);
        mResolutionPreferenceCategory = findPreference(KEY_MODE_SELECTION);

        Display display = mDisplayManager.getDisplay(Display.DEFAULT_DISPLAY);
        mModes = display.getSupportedModes();
        Arrays.sort(mModes, new Comparator<Display.Mode>() {
            // Sort in descending order of refresh rate.
            @Override
            public int compare(Display.Mode o1, Display.Mode o2) {
                int resolution1 = Math.min(o1.getPhysicalHeight(), o1.getPhysicalWidth());
                int resolution2 = Math.min(o2.getPhysicalHeight(), o2.getPhysicalWidth());

                // The resolution which is in list of standard resolutions appears before the one
                // which is not.
                if (STANDARD_RESOLUTIONS_IN_ORDER.contains(resolution2)
                        && !STANDARD_RESOLUTIONS_IN_ORDER.contains(resolution1)) {
                    return 1;
                }
                if (STANDARD_RESOLUTIONS_IN_ORDER.contains(resolution1)
                        && !STANDARD_RESOLUTIONS_IN_ORDER.contains(resolution2)) {
                    return -1;
                }
                if (resolution2 == resolution1) {
                    return (int) o2.getRefreshRate() - (int) o1.getRefreshRate();
                }
                return resolution2 - resolution1;
            }
        });

        createPreferences();

        mUserPreferredModeIndex = lookupModeIndex(
                mDisplayManager.getGlobalUserPreferredDisplayMode());
        if (mUserPreferredModeIndex != -1) {
            selectRadioPreference(findPreference(KEY_RESOLUTION_PREFIX + mUserPreferredModeIndex));
        } else {
            selectRadioPreference(findPreference(KEY_RESOLUTION_SELECTION_AUTO));
        }
    }

    private void createPreferences() {
        RadioPreference pref = new RadioPreference(getContext());
        pref.setTitle(getContext().getString(R.string.resolution_selection_auto_title));
        pref.setKey(KEY_RESOLUTION_SELECTION_AUTO);

        Display display = mDisplayManager.getDisplay(Display.DEFAULT_DISPLAY);
        mAutoMode = display.getSystemPreferredDisplayMode();
        if (mAutoMode == null) {
            mAutoMode = display.getMode();
        }
        if (mAutoMode == null) {
            mAutoMode = mModes[0];
        }
        final String summary = getResources().getString(R.string.resolution_display_mode,
                ResolutionSelectionUtils.getResolutionString(
                        mAutoMode.getPhysicalWidth(), mAutoMode.getPhysicalHeight()),
                ResolutionSelectionUtils.getRefreshRateString(mAutoMode.getRefreshRate()));
        pref.setSummary(summary);
        configureResolutionPreference(pref);
        pref.getExtras().putIntArray(HDR_TYPES_ARRAY, mAutoMode.getSupportedHdrTypes());
        mResolutionPreferenceCategory.addPreference(pref);

        final boolean showSubHdRes =
                getContext().getResources().getBoolean(R.bool.config_showSubHdResolutions);
        for (int i = 0; i < mModes.length; i++) {
            if (!showSubHdRes && mModes[i].getPhysicalHeight() < 720) {
                continue;
            }
            mResolutionPreferenceCategory.addPreference(createResolutionPreference(mModes[i], i));
        }
    }

    /** Returns a radio preference for each display mode. */
    private RadioPreference createResolutionPreference(Display.Mode mode, int resolution) {
        final String title = getResources().getString(R.string.resolution_display_mode,
                ResolutionSelectionUtils.getResolutionString(
                        mode.getPhysicalWidth(), mode.getPhysicalHeight()),
                ResolutionSelectionUtils.getRefreshRateString(mode.getRefreshRate()));

        String summary = ResolutionSelectionUtils.getResolutionSummary(mode.getPhysicalWidth(),
                mode.getPhysicalHeight());
        RadioPreference pref = new RadioPreference(getContext());
        pref.setTitle(title);
        pref.setSummary(summary);
        pref.setKey(KEY_RESOLUTION_PREFIX + resolution);
        configureResolutionPreference(pref);
        pref.getExtras().putIntArray(HDR_TYPES_ARRAY, mode.getSupportedHdrTypes());
        return pref;
    }

    @VisibleForTesting
    DisplayManager getDisplayManager() {
        return getContext().getSystemService(DisplayManager.class);
    }

    private PreferenceGroup getPreferenceGroup() {
        return (PreferenceGroup) findPreference(KEY_MODE_SELECTION);
    }

    private void selectRadioPreference(Preference preference) {
        final RadioPreference radioPreference = (RadioPreference) preference;
        if (radioPreference == null) {
            Log.w(TAG, "RadioPreference is null");
            return;
        }
        radioPreference.setChecked(true);
        radioPreference.clearOtherRadioPreferences(getPreferenceGroup());
    }

    private void configureResolutionPreference(Preference pref) {
        if (FlavorUtils.isTwoPanel(getContext())) {
            pref.setFragment(ResolutionSelectionInfo.HDRInfoFragment.class.getName());
        }
    }

    @Override
    public boolean onPreferenceTreeClick(Preference preference) {
        String key = preference.getKey();

        if (key == null) {
            return super.onPreferenceTreeClick(preference);
        }

        if (preference instanceof RadioPreference) {
            selectRadioPreference(preference);

            // A previous confirm dialog (with its own countdown) must not
            // survive a new pick: dismiss it, its timer no-ops on dismiss.
            if (mConfirmDialog != null && mConfirmDialog.isShowing()) {
                mConfirmDialog.dismiss();
            }
            mConfirmDialog = null;

            Display.Mode newMode = null;
            Display.Mode previousMode = mDisplayManager.getGlobalUserPreferredDisplayMode();
            boolean isAutoPick = key.equals(KEY_RESOLUTION_SELECTION_AUTO);
            if (isAutoPick) {
                applyModeTemporarily(null);
            } else if (key.contains(KEY_RESOLUTION_PREFIX)) {
                int modeIndex = Integer.parseInt(key.substring(KEY_RESOLUTION_PREFIX.length()));
                newMode = mModes[modeIndex];
                applyModeTemporarily(newMode);
            }
            // if newMode is null, it means it is the automatic mode
            Display.Mode finalNewMode = Objects.requireNonNullElse(newMode, mAutoMode);
            Display.Mode finalPreviousMode = Objects.requireNonNullElse(previousMode, mAutoMode);
            // Show the dialog after a delay of 1 second. If the dialog or any UX
            // is shown when the resolution change is under process, the dialog is lost.
            new Handler().postDelayed(new Runnable() {
                public void run() {
                    showWarningDialogOnResolutionChange(finalNewMode, finalPreviousMode,
                            isAutoPick);
                }
            }, DIALOG_START_MILLIS);
        }

        return super.onPreferenceTreeClick(preference);
    }

    /**
     * Applies a mode without persisting it. Nothing survives a reboot until
     * the user confirms with OK: persisting on bare selection bricked boxes
     * when the new mode kills video (a reboot re-applies the stored killer
     * vote and the system never comes back).
     */
    private void applyModeTemporarily(Display.Mode mode) {
        applyRefreshRateVote(mode);
        DisplayManagerGlobal.getInstance().setUserPreferredDisplayMode(
                Display.INVALID_DISPLAY, mode, false);
    }

    /**
     * SurfaceFlinger picks the refresh rate from the frame rate vote
     * (Settings.System peak/min_refresh_rate), not from the mode that was asked for. Picking a
     * different refresh rate at the same resolution therefore does nothing on its own: the
     * mode request is accepted and the old rate is kept. Vote for the rate of the selected mode
     * as well so the choice actually takes effect.
     *
     * The value has to be rounded to a whole number: display modes report their rate with more
     * precision than the setting is compared with (e.g. 74.97 Hz for 75 Hz), and the exact
     * float is then never matched against any mode.
     */
    private void applyRefreshRateVote(@Nullable Display.Mode mode) {
        final ContentResolver resolver = getContext().getContentResolver();
        if (mode == null || mode.getRefreshRate() <= 0) {
            // Back to automatic: drop the vote so the display is free to pick its own rate.
            Settings.System.putString(resolver, Settings.System.PEAK_REFRESH_RATE, null);
            Settings.System.putString(resolver, Settings.System.MIN_REFRESH_RATE, null);
            return;
        }
        final float rate = Math.round(mode.getRefreshRate());
        Settings.System.putFloat(resolver, Settings.System.PEAK_REFRESH_RATE, rate);
        Settings.System.putFloat(resolver, Settings.System.MIN_REFRESH_RATE, rate);
    }

    /** Returns the index of Display mode that matches UserPreferredMode */
    public int lookupModeIndex(Display.Mode userPreferredMode) {
        if (userPreferredMode != null) {
            for (int i = 0; i < mModes.length; i++) {
                if (mModes[i].matches(userPreferredMode.getPhysicalWidth(),
                        userPreferredMode.getPhysicalHeight(),
                        userPreferredMode.getRefreshRate())) {
                    return i;
                }
            }
        }
        return -1;
    }

    private void setUserPreferredMode(Display.Mode mode) {
        // Revert path (Cancel/timeout): re-apply without persisting. The
        // stored vote was never overwritten by the temporary pick, so this
        // restores exactly the previously persisted choice.
        int modeIndex = lookupModeIndex(mode);
        if (modeIndex != -1) {
            selectRadioPreference(findPreference(KEY_RESOLUTION_PREFIX + modeIndex));
            applyModeTemporarily(mModes[modeIndex]);
        } else {
            selectRadioPreference(findPreference(KEY_RESOLUTION_SELECTION_AUTO));
            applyModeTemporarily(null);
        }
    }

    private void showWarningDialogOnResolutionChange(
            Display.Mode currentMode, Display.Mode previousMode, boolean isAutoPick) {
        final CountDownTimer[] timerTask = {null};
        String resolutionString = ResolutionSelectionUtils.modeToString(currentMode, getContext());
        Display display = mDisplayManager.getDisplay(Display.DEFAULT_DISPLAY);
        boolean doesCurrentModeNotSupportDvBecauseLimitedTo4k30 =
                isHdrFormatSupported(previousMode, HDR_TYPE_DOLBY_VISION)
                && doesCurrentModeNotSupportDvBecauseLimitedTo4k30(display);
        final String dialogDescription = descriptionForNewMode(resolutionString,
                doesCurrentModeNotSupportDvBecauseLimitedTo4k30);
        final String title = titleForNewMode(resolutionString,
                doesCurrentModeNotSupportDvBecauseLimitedTo4k30);

        OnClickListener onOkClicked = (dialog, which) -> {
            // Persist only on explicit confirm. Until now the pick was only
            // applied temporarily, so an unconfirmed killer mode can never
            // survive a reboot.
            if (isAutoPick) {
                mDisplayManager.clearGlobalUserPreferredDisplayMode();
            } else {
                mDisplayManager.setGlobalUserPreferredDisplayMode(currentMode);
            }
            changeHdrConversionFormatToOneSupportedByMode(display.getMode());
            dialog.dismiss();
            timerTask[0].cancel();
        };
        OnClickListener onCancelClicked = (dialog, which) -> {
            setUserPreferredMode(previousMode);
            dialog.dismiss();
            timerTask[0].cancel();
        };

        AlertDialog dialog = createAlertDialog(getContext(), title, dialogDescription, onOkClicked,
                onCancelClicked);
        mConfirmDialog = dialog;

        dialog.setOnShowListener(dialog1 -> {
            final Button cancelButton =
                    ((AlertDialog) dialog1).getButton(AlertDialog.BUTTON_NEGATIVE);
            final CharSequence negativeButtonText = cancelButton.getText();
            timerTask[0] = new CountDownTimer(DIALOG_TIMEOUT_MILLIS, 1000) {
                @Override
                public void onTick(long millisUntilFinished) {
                    cancelButton.setText(String.format(Locale.getDefault(),
                            "%s (%d)", negativeButtonText,
                            //add one to timeout so it never displays zero
                            TimeUnit.MILLISECONDS.toSeconds(millisUntilFinished) + 1
                    ));
                }

                @Override
                public void onFinish() {
                    if (((AlertDialog) dialog1).isShowing()) {
                        setUserPreferredMode(previousMode);
                        dialog1.dismiss();
                    }
                }
            };
            timerTask[0].start();
        });
        dialog.show();
    }

    private void changeHdrConversionFormatToOneSupportedByMode(Display.Mode currentMode) {
        int preferredHdrFormat = mDisplayManager.getHdrConversionMode().getPreferredHdrOutputType();
        if (preferredHdrFormat != HDR_TYPE_INVALID
                && !isHdrFormatSupported(currentMode, preferredHdrFormat)) {
            HdrConversionMode systemHdrConversionMode = new HdrConversionMode(
                    HdrConversionMode.HDR_CONVERSION_SYSTEM);
            mDisplayManager.setHdrConversionMode(systemHdrConversionMode);
            sendHdrSettingsChangedBroadcast(getContext());
        }
    }

    private String titleForNewMode(String resolutionString,
            boolean dolbyVisionSupportDroppedOnNewMode) {
        return dolbyVisionSupportDroppedOnNewMode ?
                getResources().getString(R.string.resolution_selection_with_mode_dialog_title,
                        resolutionString)
                : getResources().getString(R.string.resolution_selection_dialog_title);

    }

    private String descriptionForNewMode(String resolutionString,
            boolean dolbyVisionSupportDroppedOnNewMode) {
        return dolbyVisionSupportDroppedOnNewMode ?
                getResources().getString(
                        R.string.resolution_selection_disabled_dolby_vision_dialog_desc,
                        resolutionString)
                : getResources().getString(R.string
                                .resolution_selection_dialog_desc,
                        resolutionString);
    }
}
