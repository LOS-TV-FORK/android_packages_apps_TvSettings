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

package com.android.tv.settings.library.network;

import android.annotation.IntDef;
import android.annotation.MainThread;
import android.annotation.TargetApi;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.text.TextUtils;

import androidx.annotation.NonNull;

import com.android.wifitrackerlib.PasspointWifiEntry;
import com.android.wifitrackerlib.WifiEntry;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;

@TargetApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
public class AccessPoint implements Comparable<AccessPoint> {
    public static final int SECURITY_NONE = WifiInfo.SECURITY_TYPE_OPEN;
    public static final int SECURITY_WEP = WifiInfo.SECURITY_TYPE_WEP;
    public static final int SECURITY_PSK = WifiInfo.SECURITY_TYPE_PSK;
    public static final int SECURITY_EAP = WifiInfo.SECURITY_TYPE_EAP;
    public static final int SECURITY_OWE = WifiInfo.SECURITY_TYPE_OWE;
    public static final int SECURITY_SAE = WifiInfo.SECURITY_TYPE_SAE;
    public static final int SECURITY_EAP_SUITE_B =
            WifiInfo.SECURITY_TYPE_EAP_WPA3_ENTERPRISE_192_BIT;

    public static final String KEY_PREFIX_AP = "AP:";

    @IntDef({Speed.NONE, Speed.SLOW, Speed.MODERATE, Speed.FAST, Speed.VERY_FAST})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Speed {
        /**
         * Constant value representing an unlabeled / unscored network.
         */
        int NONE = 0;
        /**
         * Constant value representing a slow speed network connection.
         */
        int SLOW = 5;
        /**
         * Constant value representing a medium speed network connection.
         */
        int MODERATE = 10;
        /**
         * Constant value representing a fast speed network connection.
         */
        int FAST = 20;
        /**
         * Constant value representing a very fast speed network connection.
         */
        int VERY_FAST = 30;
    }

    private final WifiEntry mWifiEntry;

    private final WifiEntry.WifiEntryCallback mEntryCallback = new WifiEntry.WifiEntryCallback() {
        @Override
        public void onUpdated() {
            if (mAccessPointListener != null) {
                mAccessPointListener.onAccessPointChanged(AccessPoint.this);
                mAccessPointListener.onLevelChanged(AccessPoint.this);
            }
        }
    };

    AccessPoint.AccessPointListener mAccessPointListener;

    public AccessPoint(WifiEntry wifiEntry) {
        mWifiEntry = wifiEntry;
    }

    /*
     * Use this for any new code that was not written using legacy WifiTracker AccessPoint
     * interface.
     */
    public WifiEntry getWifiEntry() {
        return mWifiEntry;
    }

    @Override
    public int compareTo(@NonNull AccessPoint other) {
        return WifiEntry.WIFI_PICKER_COMPARATOR.compare(mWifiEntry, other.mWifiEntry);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof AccessPoint)) return false;
        return (this.compareTo((AccessPoint) other) == 0);
    }

    @Override
    public int hashCode() {
        return mWifiEntry.hashCode();
    }

    @Override
    public String toString() {
        return mWifiEntry.toString();
    }

    /**
     * Generates an AccessPoint key for a given scan result
     *
     * @param result Scan result
     * @return AccessPoint key
     */
    public static String getKey(ScanResult result) {
        return getKey(result.SSID, result.BSSID, getSecurity(result));
    }

    /**
     * Returns the AccessPoint key for a normal non-Passpoint network by ssid/bssid and security.
     */
    private static String getKey(String ssid, String bssid, int security) {
        StringBuilder builder = new StringBuilder();
        builder.append(KEY_PREFIX_AP);
        if (TextUtils.isEmpty(ssid)) {
            builder.append(bssid);
        } else {
            builder.append(ssid);
        }
        builder.append(',').append(security);
        return builder.toString();
    }

    public String getKey() {
        return getKey(getSsidStr(), mWifiEntry.getMacAddress(), getSecurity());
    }

    public WifiConfiguration getConfig() {
        return mWifiEntry.getWifiConfiguration();
    }

    public WifiInfo getInfo() {
        return null;
    }

    /**
     * Returns the number of levels to show for a Wifi icon, from 0 to
     * {@link WifiManager#getMaxSignalLevel()}.
     */
    public int getLevel() {
        return mWifiEntry.getLevel();
    }

    /**
     * Returns if the network should be considered metered.
     */
    public boolean isMetered() {
        return mWifiEntry.isMetered();
    }

    public static int getSecurity(WifiEntry wifiEntry) {
        return getSingleSecurityTypeFromMultipleSecurityTypes(wifiEntry.getSecurityTypes());
    }

    /**
     * Returns a single WifiInfo security type from the list of multiple WifiInfo security
     * types supported by an entry.
     *
     * Single security types will have a 1-to-1 mapping.
     * Multiple security type networks will collapse to the lowest security type in the group:
     *     - Open/OWE -> Open
     *     - PSK/SAE -> PSK
     *     - EAP/EAP-WPA3 -> EAP
     * This mapping is copied from {@link WifiEntry} to avoid unintentional changes to TVSettings
     * behavior when connecting to a given network.
     */
    private static int getSingleSecurityTypeFromMultipleSecurityTypes(
            @NonNull List<Integer> securityTypes) {
        if (securityTypes.isEmpty()) {
            return WifiInfo.SECURITY_TYPE_UNKNOWN;
        }

        if (securityTypes.size() == 2) {
            if (securityTypes.contains(WifiInfo.SECURITY_TYPE_OPEN)) {
                return WifiInfo.SECURITY_TYPE_OPEN;
            }
            if (securityTypes.contains(WifiInfo.SECURITY_TYPE_PSK)) {
                return WifiInfo.SECURITY_TYPE_PSK;
            }
            if (securityTypes.contains(WifiInfo.SECURITY_TYPE_EAP)) {
                return WifiInfo.SECURITY_TYPE_EAP;
            }
        }

        // Default to the first security type if we don't need any special mapping.
        return securityTypes.get(0);
    }

    public int getSecurity() {
        return getSecurity(mWifiEntry);
    }

    public String getSsidStr() {
        return mWifiEntry.getSsid();
    }

    public CharSequence getSsid() {
        return getSsidStr();
    }

    /**
     * Returns the display title for the AccessPoint, such as for an AccessPointPreference's title.
     */
    public String getTitle() {
        return mWifiEntry.getTitle();
    }

    public String getSettingsSummary() {
        return "";
    }

    public boolean isActive() {
        return mWifiEntry.getConnectedState() == WifiEntry.CONNECTED_STATE_CONNECTED;
    }

    /**
     * Return true if this AccessPoint represents a Passpoint AP.
     */
    public boolean isPasspoint() {
        return mWifiEntry instanceof PasspointWifiEntry;
    }

    public boolean isSaved() {
        return mWifiEntry.isSaved();
    }

    public void setListener(AccessPoint.AccessPointListener listener) {
        mAccessPointListener = listener;
        mWifiEntry.setListener(listener != null ? mEntryCallback : null);
    }

    public static String convertToQuotedString(String string) {
        return "\"" + string + "\"";
    }

    private static int getSecurity(ScanResult result) {
        List<Integer> securityTypes = new ArrayList<>();
        for (int securityType : result.getSecurityTypes()) {
            securityTypes.add(securityType);
        }
        return getSingleSecurityTypeFromMultipleSecurityTypes(securityTypes);
    }

    /**
     * Callbacks relaying changes to the AccessPoint representation.
     *
     * <p>All methods are invoked on the Main Thread.
     */
    public interface AccessPointListener {

        /**
         * Indicates a change to the externally visible state of the AccessPoint trigger by an
         * update of ScanResults, saved configuration state, connection state, or score
         * (labels/metered) state.
         *
         * <p>Clients should refresh their view of the AccessPoint to match the updated state when
         * this is invoked. Overall this method is extraneous if clients are listening to
         * {@link WifiTracker.WifiListener#onAccessPointsChanged()} callbacks.
         *
         * <p>Examples of changes include signal strength, connection state, speed label, and
         * generally anything that would impact the summary string.
         *
         * @param accessPoint The accessPoint object the listener was registered on which has
         *                    changed
         */
        @MainThread
        void onAccessPointChanged(AccessPoint accessPoint);

        /**
         * Indicates the "wifi pie signal level" has changed, retrieved via calls to
         * {@link AccessPoint#getLevel()}.
         *
         * <p>This call is a subset of {@link #onAccessPointChanged(AccessPoint)} , hence is also
         * extraneous if the client is already reacting to that or the
         * {@link WifiTracker.WifiListener#onAccessPointsChanged()} callbacks.
         *
         * @param accessPoint The accessPoint object the listener was registered on whose level has
         *                    changed
         */
        @MainThread
        void onLevelChanged(AccessPoint accessPoint);
    }
}
