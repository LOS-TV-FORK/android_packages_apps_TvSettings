/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.tv.settings.device.util;

import android.content.Context;
import android.text.TextUtils;

import com.android.tv.settings.R;
import com.android.tv.settings.name.DeviceManager;

/**
 * Utility class for handle device information.
 */
public class DeviceUtils {
    /**
     * Get the name of the current device.
     */
    public static String getDeviceName(Context context) {
        String deviceName = context.getString(R.string.config_device_name);
        if (TextUtils.isEmpty(deviceName)) {
            return DeviceManager.getDeviceName(context);
        } else {
            return deviceName;
        }
    }

    /**
     * Get the default volume of the current device.
     */
    public static String getDeviceDefaultVolume(Context context) {
        return context.getString(R.string.config_default_device_volume);
    }
}
