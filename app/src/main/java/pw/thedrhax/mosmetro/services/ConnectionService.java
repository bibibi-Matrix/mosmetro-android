/**
 * Wi-Fi РІ РјРµС‚СЂРѕ (pw.thedrhax.mosmetro, Moscow Wi-Fi autologin)
 * Copyright В© 2015 Dmitry Karikh <the.dr.hax@gmail.com>
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

package pw.thedrhax.mosmetro.services;

import android.app.IntentService;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ClickableSpan;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;

import java.net.NetworkInterface;
import java.util.HashMap;
import java.util.concurrent.locks.ReentrantLock;

import pw.thedrhax.mosmetro.R;
import pw.thedrhax.mosmetro.activities.DebugActivity;
import pw.thedrhax.mosmetro.activities.SafeViewActivity;
import pw.thedrhax.mosmetro.activities.SettingsActivity;
import pw.thedrhax.mosmetro.authenticator.Gen204;
import pw.thedrhax.mosmetro.authenticator.Provider;
import pw.thedrhax.mosmetro.authenticator.Gen204.Gen204Result;
import pw.thedrhax.util.CaptivePortalFix;
import pw.thedrhax.util.Listener;
import pw.thedrhax.util.Logger;
import pw.thedrhax.util.Notify;
import pw.thedrhax.util.Randomizer;
import pw.thedrhax.util.Util;
import pw.thedrhax.util.Version;
import pw.thedrhax.util.WifiUtils;

public class ConnectionService extends IntentService {
    public static final String ACTION_EVENT = "pw.thedrhax.mosmetro.event.ConnectionService";
    public static final String ACTION_STOP = "STOP";
    public static final String ACTION_EVENT_CONNECTED = "pw.thedrhax.mosmetro.event.CONNECTED";
    public static final String ACTION_EVENT_DISCONNECTED = "pw.thedrhax.mosmetro.event.DISCONNECTED";

    public static final String EXTRA_DEBUG = "debug"; // boolean
    public static final String EXTRA_FORCE = "force"; // boolean
    public static final String EXTRA_RUNNING = "RUNNING"; // boolean
    public static final String EXTRA_STOP = "stop"; // boolean

    private static final ReentrantLock lock = new ReentrantLock();
    private static final Listener<Boolean> running = new Listener<>(false);
    private static String SSID = WifiUtils.UNKNOWN_SSID;

    // Debounce of quick restarts (e.g. Wi-Fi off/on bursts in trains)
    private static final long RESTART_DEBOUNCE_MS = 2000;
    private static long last_stop_time = -RESTART_DEBOUNCE_MS;
    private static String last_stop_ssid = WifiUtils.UNKNOWN_SSID;
    private static Provider.RESULT last_stop_result = Provider.RESULT.INTERRUPTED;
    private Provider.RESULT last_result = Provider.RESULT.INTERRUPTED;

    private boolean from_shortcut = false;
    private boolean from_debug = false;

    private ConnectivityManager.NetworkCallback networkCallback = null;
    private Listener<Boolean> isWifi = new Listener<Boolean>(false) {
        @Override
        public void onChange(Boolean new_value) {
            Logger.log(Logger.LEVEL.DEBUG, "Default network: " + (new_value ? "Wi-Fi" : "Mobile"));
        }
    };

    // Watchdog for waitForIP() when the user-defined timeout is disabled
    private static final int IP_WAIT_WATCHDOG = 15 * 60; // seconds

    // Seconds of system "not validated" state tolerated before
    // falling back to our own HTTP connectivity check
    private static final int VALIDATED_FALSE_GRACE = 15;

    // Preferences
    private WifiUtils wifi;
    private CaptivePortalFix cpf;
    private SharedPreferences settings;
    private int pref_retry_count;
    private int pref_ip_wait;
    private int pref_internet_check_interval;
    private boolean pref_internet_check;
    private boolean pref_manual_connection_monitoring;
    private boolean pref_notify_foreground;
    private boolean pref_wifi_any_ssid;
    private boolean pref_research;

    // Notifications
    private Notify notify;

    public ConnectionService () {
		super("ConnectionService");
	}

    @Override
    public void onCreate() {
		super.onCreate();

        wifi = new WifiUtils(this) {
            @Override
            public boolean isConnected(String SSID) {
                return from_shortcut || super.isConnected(SSID);
            }
        };
        cpf = new CaptivePortalFix(this);
        settings = PreferenceManager.getDefaultSharedPreferences(this);
        pref_retry_count = Util.getIntPreference(this, "pref_retry_count", 3);
        pref_ip_wait = Util.getIntPreference(this, "pref_ip_wait", 0);
        pref_notify_foreground = settings.getBoolean("pref_notify_foreground", true);
        pref_internet_check = settings.getBoolean("pref_internet_check", true);
        pref_manual_connection_monitoring = settings.getBoolean("pref_manual_connection_monitoring", true);
        pref_internet_check_interval = Util.getIntPreference(this, "pref_internet_check_interval", 10);
        pref_wifi_any_ssid = settings.getBoolean("pref_wifi_any_ssid", false);
        pref_research = settings.getBoolean("pref_debug_research", false);

        final PendingIntent stop_intent = PendingIntent.getService(
                this, 0,
                new Intent(this, ConnectionService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        notify = new Notify(this) {
            @Override
            public Notify locked(boolean locked) {
                // Show STOP action only if notification is locked
                if (locked) {
                    if (notify.mActions.size() == 0)
                        notify.addAction(getString(R.string.stop), stop_intent);
                } else {
                    while (notify.mActions.size() > 0) notify.mActions.remove(0);
                }
                return super.locked(locked);
            }
        };

        Intent debug = new Intent(this, DebugActivity.class);
        debug.putExtra(DebugActivity.INTENT_VIEW_ONLY, true);

        notify.id(1)
                .onClick(PendingIntent.getActivity(
                        this, 1, debug,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                ))
                .onDelete(stop_intent)
                .locked(pref_notify_foreground);

        // Android 12+ requires calling startForeground() immediately
        // after starting the service with startForegroundService()
        notify.locked(true).show();
        notify.locked(pref_notify_foreground);

        if (Build.VERSION.SDK_INT >= 24) {
            ConnectivityManager cm = wifi.getConnectivityManager();

            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(@NonNull Network network) {
                    NetworkInfo info = cm.getNetworkInfo(network);
                    if (info != null) isWifi.set(info.getType() == ConnectivityManager.TYPE_WIFI);
                }
            };

            // Ignore system bug https://issuetracker.google.com/issues/175055271
            try {
                cm.registerDefaultNetworkCallback(networkCallback);
            } catch (SecurityException ex) {
                networkCallback = null;
                Logger.log(Logger.LEVEL.DEBUG, ex);
                Logger.log(Logger.LEVEL.DEBUG, "Warning: Unable to register network callback");
            }
        }
    }

    private void notify (Provider.RESULT result) {
        notify.hideProgress();

        switch (result) {
            case CONNECTED:
            case ALREADY_CONNECTED:
                boolean enabled = pref_notify_foreground;
                enabled |= settings.getBoolean("pref_notify_success", true);

                if (!enabled || (from_debug && !pref_manual_connection_monitoring)) {
                    notify.hide();
                    break;
                }

                if (settings.getBoolean("pref_notify_success_lock", true)) {
                    notify.locked(true);
                }

                if (from_shortcut && !pref_manual_connection_monitoring) {
                    // TODO: Auto cancel this notification after 30 seconds
                    notify.id(2) // protect this notification from removing
                            .cancelOnClick(true)
                            .locked(false);
                }

                notify.title(getString(R.string.notification_success))
                        .text(getString(R.string.notification_success_log))
                        .icon(R.drawable.ic_notification_success_colored,
                              R.drawable.ic_notification_success)
                        .show();
                break;

            case NOT_REGISTERED:
                notify.hide()
                        .title(getString(R.string.notification_not_registered))
                        .text(getString(R.string.notification_not_registered_register))
                        .icon(R.drawable.ic_notification_register_colored,
                              R.drawable.ic_notification_register)
                        .onClick(PendingIntent.getActivity(this, 0,
                                new Intent(this, SafeViewActivity.class)
                                        .putExtra("data", "http://wi-fi.ru"),
                                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                        .id(2).locked(false).show();
                break;

            case ERROR:
                notify.hide()
                        .title(getString(R.string.notification_error))
                        .text(getString(R.string.notification_error_log))
                        .icon(R.drawable.ic_notification_error_colored,
                              R.drawable.ic_notification_error)
                        .enabled(!from_debug && settings.getBoolean("pref_notify_fail", false))
                        .id(2).locked(false).show();
                break;

            case NOT_SUPPORTED:
                notify.hide()
                        .title(getString(R.string.notification_unsupported))
                        .text(getString(R.string.notification_error_log))
                        .icon(R.drawable.ic_notification_register_colored,
                              R.drawable.ic_notification_register)
                        .enabled(!from_debug && settings.getBoolean("pref_notify_fail", false))
                        .id(2).locked(false).show();
                break;

            case INTERRUPTED: // impossible, but IDE thinks otherwise
                notify.hide();
                break;
        }

        notify // return to defaults
                .id(1)
                .cancelOnClick(false)
                .locked(pref_notify_foreground);
    }

    private boolean waitForIP(boolean silent) {
        if (wifi.getIP() != 0 && !wifi.getDns().isEmpty()) return true;

        int count = 0;

        Logger.log(getString(R.string.ip_wait));

        if (!silent)
            notify.title(getString(R.string.ip_wait))
                    .progress(0, true)
                    .show();

        while (wifi.getIP() == 0 || wifi.getDns().isEmpty()) {
            if (!running.sleep(1000)) return false;

            count++;

            // User-defined timeout
            if (pref_ip_wait != 0 && count == pref_ip_wait) {
                Logger.log(getString(R.string.error,
                        getString(R.string.ip_wait_result,
                            " " + getString(R.string.not), pref_ip_wait
                        )
                ));
                return false;
            }

            // Watchdog: never wait forever, even if the user-defined
            // timeout is disabled (the default), otherwise the service
            // may hang for hours waiting for an IP which never arrives
            if (pref_ip_wait == 0 && count == IP_WAIT_WATCHDOG) {
                Logger.log(getString(R.string.error,
                        getString(R.string.ip_wait_result,
                            " " + getString(R.string.not), IP_WAIT_WATCHDOG
                        )
                ));
                Logger.log(this, "Watchdog: restarting the connection sequence");
                return false;
            }
        }

        Logger.log(getString(R.string.ip_wait_result, "", count/2));
        return true;
    }

    /**
     * Waits until traffic can be routed via Wi-Fi, binding the process
     * to the Wi-Fi network as soon as it appears (it may not be listed
     * right after ASSOCIATING).
     */
    private boolean waitForWiFi() {
        for (int i = 0; i < 10; i++) {
            wifi.bindToWifi();

            if (wifi.isDefaultNetworkWifi()) return true;
            if (!running.sleep(1000)) break;
        }

        return wifi.isDefaultNetworkWifi();
    }

    private Provider.RESULT connect(Provider provider) {
        Provider.RESULT result;
        int count = 0;
        int pref_retry_delay = Util.getIntPreference(this, "pref_retry_delay", 5) * 1000;

        do {
            if (count > 0) {
                String msg = String.format("%s (%s)",
                                getString(R.string.notification_progress_waiting),
                                getString(R.string.try_out_of, count + 1, pref_retry_count)
                );

                Logger.log(msg);
                notify.text(msg).progress(0, true).show();

                if (!running.sleep(pref_retry_delay)) {
                    result = Provider.RESULT.INTERRUPTED;
                    break;
                }
            }

            result = provider.start();

            if (result == Provider.RESULT.NOT_REGISTERED) break;
            if (from_shortcut) break;
        } while (++count < pref_retry_count && running.get() && result == Provider.RESULT.ERROR);

        return result;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        // Stop by intent
        if (ACTION_STOP.equals(intent.getAction()) || intent.getBooleanExtra(EXTRA_STOP, false)) {
            if (running.get() && lock.isLocked()) {
                Logger.log(this, "Stopping by Intent");
                running.set(false);
            }
            return START_NOT_STICKY;
        }

        String source;
        if (intent.getBooleanExtra(EXTRA_DEBUG, false)) {
            source = "Started from DebugActivity";
            from_shortcut = true;
            from_debug = true;
        } else if (intent.getBooleanExtra(EXTRA_FORCE, false)) {
            source = "Started from shortcut";
            from_shortcut = true;
            from_debug = false;
        } else {
            source = "Started by system";
            from_shortcut = false;
            from_debug = false;
        }

        SSID = wifi.getSSID(intent);

        // System starts on Wi-Fi state changes: when the radio is disabled
        // there is nothing to do (e.g. right after DISCONNECTED event)
        if ("Started by system".equals(source) && !wifi.isEnabled()) {
            Logger.log(Logger.LEVEL.DEBUG, "Not starting: Wi-Fi is disabled");
            return START_NOT_STICKY;
        }

        // Ignore quick restarts of a healthy session (Wi-Fi off/on bursts):
        // the next network state change will start the service again
        if (!ACTION_STOP.equals(intent.getAction()) && !intent.getBooleanExtra(EXTRA_STOP, false)
                && SystemClock.elapsedRealtime() - last_stop_time < RESTART_DEBOUNCE_MS
                && SSID.equals(last_stop_ssid)
                && (last_stop_result == Provider.RESULT.CONNECTED ||
                    last_stop_result == Provider.RESULT.ALREADY_CONNECTED)) {
            Logger.log(this, "Not starting: debounced quick restart");
            return START_NOT_STICKY;
        }

        // Ignore if service is already running
        if (lock.isLocked()) {
            // Service is shutting down. Trying to interrupt
            if (!running.get()) { 
                running.set(true);
            }

            return START_NOT_STICKY;
        }

        if (Provider.isSSIDSupported(SSID) || from_shortcut ||
                WifiUtils.UNKNOWN_SSID.equals(SSID) || pref_wifi_any_ssid || pref_research) {
            Logger.log(this, source);
            onStart(intent, startId);
        } else {
            Logger.log(this, "Not starting: SSID is not supported (" + SSID + ")");
        }

        return START_NOT_STICKY;
    }

    public void onHandleIntent(Intent intent) {
        if (lock.tryLock()) {
            Logger.log(this, "Broadcast | ConnectionService (RUNNING = true)");
            sendBroadcast(new Intent(ACTION_EVENT).putExtra(EXTRA_RUNNING, true));

            Logger.date(">>> ");
            Logger.log(getString(R.string.version, Version.getFormattedVersion()));
            // SSID is logged in main() only after the Wi-Fi checks pass,
            // so a disabled Wi-Fi never shows a bogus "<unknown ssid>" line

            running.set(true);
            boolean first_iteration = true;
            while (running.get()) {
                if (!first_iteration) {
                    Logger.log(this, "Still alive!");
                } else {
                    first_iteration = false;
                }

                main();
            }
            lock.unlock();

            // Remember the session state for the restart debounce
            last_stop_time = SystemClock.elapsedRealtime();
            last_stop_ssid = SSID;
            last_stop_result = last_result;

            notify.hide();

            Logger.log(this, "Broadcast | ConnectionService (RUNNING = false)");
            sendBroadcast(new Intent(ACTION_EVENT).putExtra(EXTRA_RUNNING, false));

            Logger.date("<<< ");
        } else {
            Logger.log(this, "Already running");
        }
    }

    private boolean ignore_midsession = false;
    private boolean midsession_solved = false;
    private boolean midsession_fail_reported = false;

    private boolean handleMidsession(Gen204 gen_204, Gen204Result res_204) {
        Provider midsession = Provider.find(this, res_204.getFalseNegative())
                .setRunningListener(running)
                .setGen204(gen_204);

        midsession.add(vars -> {
            Gen204Result last_result = gen_204.getLastResult();

            if (last_result != null && last_result.isFalseNegative()) {
                vars.put("result", Provider.RESULT.ERROR);
            }

            return false;
        });

        Logger.log(getString(R.string.auth_midsession_start));
        Logger.log(Logger.LEVEL.DEBUG, "Midsession | Detected (" + midsession.getName() + ")");
        Logger.log(getString(R.string.algorithm_name, midsession.getName()));

        midsession.start(new HashMap<String, Object>() {{
            put("midsession", true);
        }});

        if (!running.sleep(3000)) return false;

        res_204 = gen_204.check(true);

        if (!res_204.isConnected()) {
            Logger.log(this, "Midsession | Connection lost, aborting...");
        } else if (!res_204.isFalseNegative()) {
            Logger.log(this, "Midsession | Solved successfully");
            midsession_solved = true;
            if (Build.VERSION.SDK_INT >= 21) wifi.report(true);
            ignore_midsession = false;
            return true;
        } else {
            Logger.log(this, "Midsession | Unable to solve, ignoring...");

            // Show the user-facing message only once per service session
            if (!midsession_fail_reported) {
                midsession_fail_reported = true;

                String msg = getString(R.string.auth_midsession_fail);
                SpannableString message = new SpannableString(msg);
                message.setSpan(new ClickableSpan() {
                    @Override
                    public void onClick(@NonNull View widget) {
                        settings.edit().putBoolean("pref_internet_midsession", false).apply();
                        Toast.makeText(ConnectionService.this, R.string.done, Toast.LENGTH_SHORT).show();
                    }
                }, msg.indexOf('>'), msg.indexOf('<') + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                Logger.log(message);
            }
        }

        return false;
    }

    private boolean isConnected(Gen204 gen_204) {
        return isConnected(gen_204, null);
    }

    private boolean isConnected(Gen204 gen_204, Gen204Result res_204) {
        if (res_204 == null) {
            Logger.log(this, "Checking internet connection");
            res_204 = gen_204.check(true);
        }

        if (!res_204.isConnected()) {
            return false;
        }

        // Midsession already solved during this service session:
        // recurring false negatives are caused by endpoint blocking,
        // not by a captive portal
        if (ignore_midsession || midsession_solved || !res_204.isFalseNegative()) {
            return res_204.isConnected();
        }

        // A single blocked endpoint is not a midsession
        if (!gen_204.confirmFalseNegative(res_204.getFalseNegative())) {
            Logger.log(Logger.LEVEL.DEBUG,
                    "Midsession | Only a single endpoint is blocked, ignoring...");
            return res_204.isConnected();
        }

        ignore_midsession = true;

        if (cpf.isApplied()) {
            Logger.log(this, "Midsession | Captive Portal fix is applied");
            return res_204.isConnected();
        }

        if (settings.getBoolean("pref_internet_midsession", false)) {
            boolean solved = handleMidsession(gen_204, res_204);
            res_204 = gen_204.getLastResult();

            if (res_204 == null) {
                res_204 = gen_204.check(true);
            }

            if (solved || !res_204.isConnected()) {
                return res_204.isConnected();
            }
        }

        if (isWifi.get()) {
            return res_204.isConnected();
        }

        String msg = getString(R.string.pref_captive_notification);
        SpannableString spanmsg = new SpannableString(msg);
        spanmsg.setSpan(new ClickableSpan() {
            @Override
            public void onClick(@NonNull View widget) {
                startActivity(
                        new Intent(ConnectionService.this, SettingsActivity.class)
                                .setAction(SettingsActivity.ACTION_MIDSESSION)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                );
            }
        }, msg.indexOf('>'), msg.indexOf('<') + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        Logger.log(spanmsg);

        return res_204.isConnected();
    }

    private void main() {
        // Do not let early exits reuse the previous session's result
        // for the restart debounce
        last_result = Provider.RESULT.INTERRUPTED;

        notify.icon(R.drawable.ic_notification_connecting_colored,
                    R.drawable.ic_notification_connecting);

        new Notify(this).id(2).hide(); // hide error notification

        boolean unknownNetwork = !from_shortcut && !Provider.isSSIDSupported(SSID);

        // Nothing to wait for if the Wi-Fi radio is disabled completely
        if (!wifi.isEnabled()) {
            Logger.log(this, "Not starting: Wi-Fi is disabled");
            notify.hide()
                    .title(getString(R.string.notification_error))
                    .text(getString(R.string.error_wifi_disabled))
                    .icon(R.drawable.ic_notification_error_colored,
                          R.drawable.ic_notification_error)
                    .enabled(!from_debug && settings.getBoolean("pref_notify_fail", false))
                    .id(2).locked(false).show();
            running.set(false);
            return;
        }

        // Bind the process to Wi-Fi before everything else: when both
        // mobile and Wi-Fi are available, the system default may stay
        // on Mobile and the captive portal would never be detected
        wifi.bindToWifi();

        // Authorization is allowed only via Wi-Fi networks
        if (!waitForWiFi()) {
            Logger.log(this, "Stopping by network type (not Wi-Fi)");
            notify.hide()
                    .title(getString(R.string.notification_error))
                    .text(getString(R.string.error_wifi_only))
                    .icon(R.drawable.ic_notification_error_colored,
                          R.drawable.ic_notification_error)
                    .enabled(!from_debug && settings.getBoolean("pref_notify_fail", false))
                    .id(2).locked(false).show();
            running.set(false);
            return;
        }

        Logger.log(getString(R.string.auth_connecting, SSID));

        // Wait for IP before detecting the Provider
        if (!waitForIP(unknownNetwork)) {
            if (running.get()) {
                notify(Provider.RESULT.ERROR);
                running.set(false);
            }
            return;
        }

        Gen204 gen_204 = new Gen204(this, running);

        // Check if already connected (internet works via WiFi or VPN)
        Gen204Result res_204 = gen_204.check(false);
        if (res_204.isConnected()) {
            Logger.log(getString(R.string.auth_already_connected));
            last_result = Provider.RESULT.ALREADY_CONNECTED;
            notify(Provider.RESULT.ALREADY_CONNECTED);
            running.set(false);
            return;
        }

        // VPN is on but no internet — wait for user to disable it
        if (wifi.isVpnConnected()) {
            Logger.log(this, "VPN detected without internet, waiting for disconnect...");

            notify.title(getString(R.string.vpn_wait_title))
                    .text(getString(R.string.vpn_wait_disable))
                    .progress(0, true)
                    .show();

            for (int i = 0; i < 60; i++) {
                if (!running.sleep(1000)) {
                    running.set(false);
                    return;
                }
                if (!wifi.isVpnConnected()) break;
            }

            if (wifi.isVpnConnected()) {
                Logger.log(this, "Stopping by VPN (still connected after 60s)");
                running.set(false);
                return;
            }

            // VPN disconnected — recheck via WiFi
            wifi.bindToWifi();
            if (!waitForWiFi()) {
                Logger.log(this, "Stopping by network type (not Wi-Fi after VPN)");
                running.set(false);
                return;
            }

            res_204 = gen_204.check(false);
            if (res_204.isConnected()) {
                Logger.log(getString(R.string.auth_already_connected));
                last_result = Provider.RESULT.ALREADY_CONNECTED;
                notify(Provider.RESULT.ALREADY_CONNECTED);
                running.set(false);
                return;
            }
        }

        // In research mode every network is interesting, even if its
        // portal does not answer the DNS probes of supported providers
        if (unknownNetwork && !pref_research) {
            if (!Provider.dnsCheck(this)) {
                Logger.log(this, "Stopping by dns probe (unknown network)");
                running.set(false);
                return;
            }
        }

        notify.title(getString(R.string.auth_connecting, SSID))
                .text(getString(R.string.auth_provider_check))
                .progress(0, true)
                .show();

        Provider provider = Provider.find(this, running)
                .setRunningListener(running)
                .setGen204(gen_204)
                .setCallback(new Provider.ICallback() {
                    @Override
                    public void onProgressUpdate(int progress) {
                        notify.progress(progress).show();
                    }

                    @Override
                    public void onProgressUpdate(int progress, String message) {
                        notify.text(message).progress(progress).show();
                    }
                });

        notify.text(getString(R.string.auth_waiting)).show();

        // Try to connect
        Logger.log(getString(R.string.algorithm_name, provider.getName()));
        Provider.RESULT result = connect(provider);
        last_result = result;

        // Notify user if not interrupted
        if (running.get()) {
            notify(result);
        } else {
            return;
        }

        if (wifi.isVpnConnected() && (result == Provider.RESULT.NOT_SUPPORTED || result == Provider.RESULT.ERROR)) {
            Logger.log(getString(R.string.vpn_warning));
        }

        // Stop the service if connection were unsuccessful or started from shortcut
        switch (result) {
            case RESTART:
                Logger.log(this, "Restarting by result (" + result.name() + ")");
                return;
            case CONNECTED:
            case ALREADY_CONNECTED:
                if (Build.VERSION.SDK_INT >= 21) wifi.report(true);

                // Check for midsession in cached Gen204 result
                ignore_midsession = false;
                isConnected(gen_204, gen_204.getLastResult());

                if (!from_shortcut || pref_manual_connection_monitoring) break;
            default:
                Logger.log(this, "Stopping by result (" + result.name() + ")");
                running.set(false);
                return;
        }

        Logger.log(this, "Broadcast | CONNECTED");
        sendBroadcast(new Intent(ACTION_EVENT_CONNECTED)
                .putExtra("SSID", SSID)
                .putExtra("PROVIDER", provider.getName())
        );

        // Wait while internet connection is available
        int legacy_count = 0;
        int validated_false_count = 0;

        while (running.sleep(1000)) {
            // Stop monitoring when the default network is not Wi-Fi anymore
            if (!wifi.isDefaultNetworkWifi()) {
                Logger.log(this, "Default network is not Wi-Fi anymore");
                break;
            }

            if (pref_internet_check) {
                if (Build.VERSION.SDK_INT >= 24) {
                    // Zero-traffic fast path: trust the system connectivity
                    // check while it keeps reporting the network as validated
                    Boolean validated = wifi.isNetworkValidated();

                    if (validated != null && validated) {
                        validated_false_count = 0;
                        legacy_count = 0;
                        continue;
                    }

                    if (validated != null) {
                        // Tolerate short re-validation gaps, then confirm
                        // the real loss with our own HTTP check
                        if (++validated_false_count < VALIDATED_FALSE_GRACE) continue;

                        validated_false_count = 0;

                        if (!isConnected(gen_204)) break;
                        continue;
                    }

                    // Unknown state: fall back to periodic HTTP polling
                }

                // Legacy periodic HTTP polling
                if (++legacy_count == pref_internet_check_interval) {
                    legacy_count = 0;
                    if (!isConnected(gen_204)) break;
                }
            }
        }

        Logger.log(this, "Broadcast | DISCONNECTED");
        sendBroadcast(new Intent(ACTION_EVENT_DISCONNECTED));
        notify.hide();

        // Try to reconnect the Wi-Fi network
        if (settings.getBoolean("pref_wifi_reconnect", false) && Build.VERSION.SDK_INT < 29) {
            Logger.log(this, "Reconnecting to Wi-Fi");
            wifi.getWifiManager().reassociate();
            wifi.getWifiManager().reconnect();
        }
	}

    public static boolean isRunning() {
        return running.get();
    }

    @Override
    public void onDestroy() {
        if (Build.VERSION.SDK_INT >= 21 && networkCallback != null) {
            wifi.getConnectivityManager().unregisterNetworkCallback(networkCallback);
        }

        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Logger.log(this, "onTaskRemoved()");
        if (!settings.getBoolean("pref_notify_foreground", true)) {
            Logger.log("Stopping because of task removal");
            running.set(false);
        }
    }
}
