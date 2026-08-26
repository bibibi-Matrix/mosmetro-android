/**
 * Wi-Fi в метро (pw.thedrhax.mosmetro, Moscow Wi-Fi autologin)
 * Copyright © 2015 Dmitry Karikh <the.dr.hax@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package pw.thedrhax.util;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.DhcpInfo;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.preference.PreferenceManager;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

public class WifiUtils {
    public static final String UNKNOWN_SSID = "<unknown ssid>";

    private final SharedPreferences settings;
    private final ConnectivityManager cm;
    private final WifiManager wm;

    public WifiUtils(@NonNull Context context) {
        this.settings = PreferenceManager.getDefaultSharedPreferences(context);
        this.cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        this.wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
    }

    /*
     * Read-only methods
     */

    // Wi-Fi connectivity conditions
    public boolean isConnected(String SSID) {
        if (!wm.isWifiEnabled()) return false;
        if (!getSSID().equalsIgnoreCase(SSID)) return false;
        return true;
    }

    // Clear SSID from platform-specific symbols
    private static String clear (String text) {
        return (text != null && !text.isEmpty()) ? text.replace("\"", "") : UNKNOWN_SSID;
    }

    // Get WifiInfo from Intent or, if not available, from WifiManager
    public WifiInfo getWifiInfo(Intent intent) {
        if (intent != null) {
            WifiInfo result = intent.getParcelableExtra(WifiManager.EXTRA_WIFI_INFO);
            if (result != null) return result;
        }
        return wm.getConnectionInfo();
    }

    // Get SSID from Intent's EXTRA_WIFI_INFO (API > 14)
    public String getSSID(Intent intent) {
        return clear(getWifiInfo(intent).getSSID());
    }

    // Get SSID directly from WifiManager
    public String getSSID() {
        return getSSID(null);
    }

    // Get current IP from WifiManager
    public int getIP() {
        return wm.getConnectionInfo().getIpAddress();
    }

    @RequiresApi(21)
    public LinkProperties getLinkProperies() {
        Network network = getWifiNetwork();
        if (network == null) return null;
        return cm.getLinkProperties(network);
    }

    // Get IP addresses of DNS servers from DHCP
    public List<InetAddress> getDns() {
        if (Build.VERSION.SDK_INT >= 21) {
            LinkProperties props = getLinkProperies();
            if (props != null) {
                List<InetAddress> dns = props.getDnsServers();
                if (!dns.isEmpty()) {
                    return dns;
                }
            }
        }

        DhcpInfo dhcp = wm.getDhcpInfo();
        List<InetAddress> result = new LinkedList<>();

        try {
            if (dhcp.dns1 != 0)
                result.add(Util.intToAddr(dhcp.dns1));

            if (dhcp.dns2 != 0)
                result.add(Util.intToAddr(dhcp.dns2));
        } catch (UnknownHostException ignored) {}

        return result;
    }

    // Get main Wi-Fi state
    public boolean isEnabled() {
        return wm.isWifiEnabled();
    }

    // Get Private DNS state (API 28+)
    public boolean isPrivateDnsActive() {
        if (Build.VERSION.SDK_INT < 28) return false;
        LinkProperties props = getLinkProperies();
        if (props == null) return false;
        return props.isPrivateDnsActive();
    }

    // Get Network by type
    @Nullable
    @RequiresApi(21)
    public Network getNetwork(int type) {
        for (Network network : cm.getAllNetworks()) {
            NetworkInfo info = cm.getNetworkInfo(network);
            if (info != null && info.getType() == type) {
                return network;
            }
        }
        return null;
    }

    public boolean isVpnConnected() {
        if (Build.VERSION.SDK_INT < 21) return false;
        return getNetwork(ConnectivityManager.TYPE_VPN) != null;
    }

    /**
     * Check whether the system connectivity check considers the current
     * network validated. Returns null when the state is unknown
     * (no network or missing capabilities).
     */
    @Nullable
    public Boolean isNetworkValidated() {
        Network network = cm.getActiveNetwork();
        if (network == null) return null;

        NetworkCapabilities caps = cm.getNetworkCapabilities(network);
        if (caps == null) return null;

        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    /**
     * Check whether the process can currently route traffic via Wi-Fi:
     * either the active (possibly bound) network is Wi-Fi, or any
     * Wi-Fi-transport network exists to bind to.
     */
    public boolean isDefaultNetworkWifi() {
        Network network = cm.getActiveNetwork();

        if (network != null) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);

            if (caps != null &&
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                return true;
            }
        }

        // A captive portal network is never validated by the system,
        // so the default may stay on Mobile: look for the Wi-Fi
        // network itself instead of relying on the routing table
        return getWifiNetwork() != null;
    }

    /**
     * Check whether the ACTIVE (system-default) network is Wi-Fi,
     * with no fallback. Used in the monitoring loop to detect the
     * case where Wi-Fi exists but the system keeps routing via Mobile.
     */
    public boolean isActiveNetworkWifi() {
        Network network = cm.getActiveNetwork();
        if (network == null) return false;

        NetworkCapabilities caps = cm.getNetworkCapabilities(network);
        if (caps == null) return false;

        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
    }

    /*
     * Control methods
     */

    // Bind to Network
    @RequiresApi(21)
    private void bindToNetwork(@Nullable Network network) {
        if (Build.VERSION.SDK_INT < 23) {
            try {
                ConnectivityManager.setProcessDefaultNetwork(network);
            } catch (IllegalStateException ignored) {}
        } else {
            cm.bindProcessToNetwork(network);
        }
    }

    // Bind current process to Wi-Fi
    // Refactored answer from Stack Overflow: http://stackoverflow.com/a/28664841
    /**
     * Finds any network with Wi-Fi transport, including portals which
     * are not validated by the system yet.
     */
    @Nullable
    public Network getWifiNetwork() {
        for (Network network : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);

            if (caps != null &&
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                return network;
            }
        }

        return null;
    }

    public void bindToWifi() {
        if (!settings.getBoolean("pref_wifi_bind", true)) return;

        if (Build.VERSION.SDK_INT < 21) {
            cm.setNetworkPreference(ConnectivityManager.TYPE_WIFI);
            return;
        }

        Network network = getWifiNetwork();

        // Do not reset the binding to the default network when there is
        // nothing to bind to yet (the Wi-Fi network has not appeared)
        if (network == null) return;

        bindToNetwork(network);
    }

    // Report connectivity status to system
    @RequiresApi(21)
    public void report(boolean status) {
        Network network = getWifiNetwork();
        if (network == null) return;

        if (Build.VERSION.SDK_INT >= 23)
            cm.reportNetworkConnectivity(network, status);
        else
            cm.reportBadNetwork(network);
    }

    /*
     * External access
     */

    public ConnectivityManager getConnectivityManager() {
        return cm;
    }

    public WifiManager getWifiManager() {
        return wm;
    }
}
