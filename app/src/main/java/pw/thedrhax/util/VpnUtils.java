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

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import androidx.annotation.Nullable;

import com.topjohnwu.superuser.CallbackList;
import com.topjohnwu.superuser.Shell;

import java.util.LinkedList;
import java.util.List;

/**
 * Disables and re-enables a user-configured VPN application around
 * the authorization process. Requires root access (libsu).
 */
public class VpnUtils {
    private static final String ALWAYS_ON_VPN = "always_on_vpn";
    private static final String ALWAYS_ON_VPN_LOCKDOWN = "always_on_vpn_lockdown";

    private static final CallbackList<String> SHELL_LOG = new CallbackList<String>() {
        @Override
        public void onAddElement(String s) {
            Logger.log(Logger.LEVEL.DEBUG, "VpnUtils.Shell | " + s);
        }
    };

    private final SharedPreferences settings;

    /**
     * Value of always_on_vpn setting saved before disabling,
     * so it can be restored later. Null when unknown.
     */
    @Nullable
    private String saved_alwayson = null;

    public VpnUtils(Context context) {
        settings = PreferenceManager.getDefaultSharedPreferences(context);
    }

    /**
     * Package name of the VPN app configured by user.
     * @return Package name or an empty string when not configured.
     */
    public String getPackage() {
        String pkg = settings.getString("pref_vpn_package", "");
        return pkg != null ? pkg.trim() : "";
    }

    public boolean isRootAvailable() {
        try {
            return Boolean.TRUE.equals(Shell.isAppGrantedRoot());
        } catch (Exception ex) {
            Logger.log(Logger.LEVEL.DEBUG, ex);
            return false;
        }
    }

    /**
     * Try to disable the VPN app using root: remove the Always-On VPN
     * setting and force-stop the application.
     * @param pkg   Package name of the VPN app.
     * @return      True when all commands succeeded.
     */
    public boolean disableByRoot(String pkg) {
        saved_alwayson = getGlobalSetting(ALWAYS_ON_VPN);

        return exec(
                "settings delete global " + ALWAYS_ON_VPN,
                "settings delete global " + ALWAYS_ON_VPN_LOCKDOWN,
                "am force-stop " + pkg
        );
    }

    /**
     * Enable the VPN app back using root: restore the Always-On VPN setting
     * if it pointed to this package before disabling, then launch the app.
     * @param pkg   Package name of the VPN app.
     */
    public void enableByRoot(String pkg) {
        LinkedList<String> commands = new LinkedList<>();

        if (pkg.equals(saved_alwayson)) {
            commands.add("settings put global " + ALWAYS_ON_VPN + " " + pkg);
        }

        commands.add("monkey -p " + pkg + " -c android.intent.category.LAUNCHER 1");

        exec(commands.toArray(new String[0]));
    }

    @Nullable
    private String getGlobalSetting(String name) {
        Shell.Result result = Shell.cmd("settings get global " + name).exec();

        List<String> out = result.getOut();
        if (out == null || out.isEmpty()) return null;

        String value = out.get(0).trim();
        return value.isEmpty() || "null".equals(value) ? "" : value;
    }

    private static boolean exec(String... commands) {
        Shell.Result result = Shell.cmd(commands).to(SHELL_LOG).exec();
        return result.isSuccess();
    }
}
