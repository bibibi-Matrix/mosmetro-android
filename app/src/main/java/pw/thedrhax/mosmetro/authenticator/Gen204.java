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

package pw.thedrhax.mosmetro.authenticator;

import java.io.IOException;
import java.util.HashSet;
import java.util.LinkedList;

import android.content.Context;

import androidx.annotation.Nullable;

import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;

import pw.thedrhax.mosmetro.httpclient.Client;
import pw.thedrhax.mosmetro.httpclient.Headers;
import pw.thedrhax.mosmetro.httpclient.HttpResponse;
import pw.thedrhax.mosmetro.httpclient.clients.OkHttp;
import pw.thedrhax.util.Listener;
import pw.thedrhax.util.Logger;
import pw.thedrhax.util.Randomizer;

public class Gen204 {
    /**
     * Unreliable generate_204 endpoints (might be intercepted by provider)
     */
    public static final String[] URL_DEFAULT = {
            "connectivitycheck.gstatic.com/generate_204",
            "www.gstatic.com/generate_204",
            "connectivitycheck.android.com/generate_204",
            "play.googleapis.com/generate_204",
            "clients1.google.com/generate_204"
    };

    /**
     * Reliable generate_204 endpoints (confirmed to not be intercepted)
     */
    public static final String[] URL_RELIABLE = {
            "www.google.ru/generate_204",
            "www.google.ru/gen_204",
            "google.com/generate_204",
            "gstatic.com/generate_204",
            "maps.google.com/generate_204",
            "mt0.google.com/generate_204",
            "mt1.google.com/generate_204",
            "mt2.google.com/generate_204",
            "mt3.google.com/generate_204",
            "www.google.com/generate_204"
    };

    private final Listener<Boolean> running = new Listener<Boolean>(true);
    private final Client client;
    private final Randomizer random;

    /**
     * Endpoints which are permanently blocked by the network while
     * the Internet connection is actually working (e.g. server-side
     * 403 for a single generate_204 host). Skipped during rotation
     * until the end of this Gen204 instance (service session).
     */
    private final HashSet<String> blocked_hosts = new HashSet<>();

    private Gen204Result last_result = null;

    public Gen204(Context context, Listener<Boolean> running) {
        this.running.subscribe(running);

        client = new OkHttp(context)
                .setFollowRedirects(false)
                .setRunningListener(this.running);

        random = new Randomizer(context);
    }

    /**
     * Perform logged request to specified URL.
     */
    @Nullable
    private HttpResponse request(String schema, String[] urls) {
        LinkedList<String> available = new LinkedList<>();
        for (String url : urls) {
            if (!blocked_hosts.contains(schema + "://" + url)) {
                available.add(url);
            }
        }

        if (available.isEmpty()) {
            Logger.log(this, "All endpoints are blacklisted, skipping request");
            return null;
        }

        for (int i = 0; i < 3; i++) {
            String url = schema + "://" + random.choose(available.toArray(new String[0]));

            try {
                HttpResponse res = client.get(url).execute();
                Logger.log(this, url + " | " + res.getResponseCode());
                return res;
            } catch (IOException ex) {
                Logger.log(this, url + " | " + ex);

                if (ex instanceof SSLPeerUnverifiedException) break;

                if (ex instanceof SSLHandshakeException) {
                    String message = ex.getMessage();

                    if (message == null) break;

                    // Ignore "I/O error during system call, Connection reset by peer"
                    if (message.contains("Connection reset by peer")) continue;

                    break;
                }
            }
        }

        return null;
    }

    public Gen204Result check(boolean expectPositive) {
        HttpResponse rel, unrel;

        if (expectPositive) {
            // Run both checks in parallel to halve the verification time:
            // both endpoints are always requested in this mode anyway
            final HttpResponse[] unrel_box = new HttpResponse[1];

            Thread unrel_thread = new Thread(() -> unrel_box[0] = request("http", URL_DEFAULT));
            unrel_thread.start();

            rel = request("https", URL_RELIABLE);

            try {
                // 3 attempts x default client timeout
                unrel_thread.join(15000);
            } catch (InterruptedException ignored) {}

            unrel = unrel_box[0];
        } else {
            rel = request("http", URL_RELIABLE);

            if (rel != null && rel.getResponseCode() != 204) {
                return new Gen204Result(rel); // negative
            }

            unrel = request("http", URL_DEFAULT);
        }

        if (rel == null) {
            return new Gen204Result(unrel); // probably negative
        } else {
            Gen204Result res = new Gen204Result(rel, unrel);

            if (res.isFalseNegative()) {
                if (last_result == null || !last_result.isFalseNegative()) {
                    Logger.log(this, "False negative detected");
                }

                // Reliable endpoint confirmed the Internet connection,
                // so the failing endpoint is blocked by the network itself.
                // Force-blacklist even portal redirects because the
                // unreliable HTTP endpoint is clearly being intercepted.
                blacklist(res.getFalseNegative(), true);
            }

            return res; // positive with possible false negative
        }
    }

    public Gen204Result check() {
        HttpResponse unrel, rel_https, rel_http;

        // Unreliable HTTP check (needs to be rechecked by HTTPS)
        unrel = request("http", URL_DEFAULT);

        if (unrel == null) {
            // network is most probably unreachable
            return new Gen204Result();
        }

        // Reliable HTTPS check
        rel_https = request("https", URL_RELIABLE);

        if (unrel.getResponseCode() == 204) {
            if (rel_https == null || rel_https.getResponseCode() != 204) {
                // Reliable HTTP check
                rel_http = request("http", URL_RELIABLE);

                if (rel_http != null && rel_http.getResponseCode() != 204) {
                    Logger.log(this, "False positive detected");
                    return new Gen204Result(rel_http); // false positive
                }
            } else {
                return new Gen204Result(rel_https); // confirmed positive
            }
        } else {
            if (rel_https == null) {
                return new Gen204Result(unrel); // confirmed negative
            } else if (rel_https.getResponseCode() == 204) {
                if (last_result == null || !last_result.isFalseNegative()) {
                    Logger.log(this, "False negative detected");
                }
                return new Gen204Result(rel_https, unrel);
            }
        }

        Logger.log(this, "Unexpected state");
        return new Gen204Result();
    }

    @Nullable
    public Gen204Result getLastResult() {
        return last_result;
    }

    /**
     * Check whether the false negative is real or caused by blocking of a
     * single endpoint (e.g. server-side 403 for one generate_204 host).
     * @param false_negative    Response which caused the false negative.
     * @return                  True if another unreliable endpoint also fails,
     *                          i.e. midsession must be handled; false when only
     *                          a single endpoint is blocked. True is also returned
     *                          when no other endpoint could be checked.
     */
    public boolean confirmFalseNegative(@Nullable HttpResponse false_negative) {
        boolean checked = false;
        boolean portal_seen = false;

        for (int i = 0; i < 3; i++) {
            String url = "http://" + random.choose(URL_DEFAULT);

            if (false_negative != null && url.equals(false_negative.getRequest().getUrl()))
                continue;

            try {
                HttpResponse res = client.get(url).execute();
                Logger.log(this, url + " | " + res.getResponseCode());
                checked = true;

                if (res.getResponseCode() != 204) {
                    // A redirect to a captive portal means the whole
                    // network dropped the session (real midsession);
                    // anything else is an endpoint-specific block
                    String location = res.headers.getFirst(Headers.LOCATION);

                    if (location != null && isPortalUrl(location)) {
                        portal_seen = true;
                    } else {
                        Logger.log(this, url + " | Blocked without portal redirect");
                    }
                }
            } catch (IOException ex) {
                Logger.log(this, url + " | " + ex);
            }
        }

        if (!checked || portal_seen) {
            // Real midsession: nothing must be blacklisted
            return true;
        }

        // All other endpoints work: only endpoint-specific blocks
        blacklist(false_negative, false);
        return false;
    }

    /**
     * Checks whether the URL points to one of the known captive portals.
     */
    private static boolean isPortalUrl(String url) {
        return url.contains("wi-fi.ru") ||
               url.contains("ttk.ru") ||
               url.contains("lbpfs.bmstu.ru");
    }

    /**
     * Adds the endpoint to the blacklist until the end of the session.
     * Captive portal redirects are skipped unless force is true (i.e.
     * when reliable HTTPS confirmed that Internet actually works).
     */
    private void blacklist(@Nullable HttpResponse response, boolean force) {
        if (response == null) return;

        String url = response.getRequest().getUrl();

        if (!force) {
            String location = response.headers.getFirst(Headers.LOCATION);
            if (location != null && isPortalUrl(location)) {
                Logger.log(this, "Not blacklisting captive redirect: " + url);
                return;
            }
        }

        blocked_hosts.add(url);
        Logger.log(this, "Blacklisted blocked endpoint: " + url);
    }

    public class Gen204Result {
        private final HttpResponse response;
        private final HttpResponse falseNegative;

        public Gen204Result(HttpResponse response, HttpResponse falseNegative) {
            if (response == null) {
                this.response = HttpResponse.EMPTY(client);
                this.falseNegative = null;
            } else {
                this.response = response;
                this.falseNegative = falseNegative;
            }

            last_result = this;
        }

        public Gen204Result(HttpResponse response) {
            this(response, null);
        }

        public Gen204Result() {
            this(null);
        }

        public HttpResponse getResponse() {
            return response;
        }

        public boolean isConnected() {
            return response.getResponseCode() == 204;
        }

        public boolean isFalseNegative() {
            return falseNegative != null && falseNegative.getResponseCode() != 204;
        }

        public HttpResponse getFalseNegative() {
            return falseNegative;
        }
    }
}