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

package pw.thedrhax.mosmetro.authenticator.providers;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.HashMap;

import pw.thedrhax.mosmetro.R;
import pw.thedrhax.mosmetro.authenticator.FinalConnectionCheckTask;
import pw.thedrhax.mosmetro.authenticator.Gen204;
import pw.thedrhax.mosmetro.authenticator.InitialConnectionCheckTask;
import pw.thedrhax.mosmetro.authenticator.InterceptorTask;
import pw.thedrhax.mosmetro.authenticator.NamedTask;
import pw.thedrhax.mosmetro.authenticator.Provider;
import pw.thedrhax.mosmetro.authenticator.WaitTask;
import pw.thedrhax.mosmetro.authenticator.WebViewProvider;
import pw.thedrhax.mosmetro.httpclient.Client;
import pw.thedrhax.mosmetro.httpclient.HttpRequest;
import pw.thedrhax.mosmetro.httpclient.HttpResponse;
import pw.thedrhax.util.Logger;
import pw.thedrhax.util.Util;

/**
 * The RzdFreeWV class is used to research the RZD_FREE authorization
 * algorithm. The captive portal is opened in an embedded WebView where
 * the user completes the authorization manually, while every request
 * made by the portal is logged for further analysis.
 *
 * After the authorization succeeds (detected by generate_204 checks),
 * the collected log contains the full sequence of portal API calls
 * needed to implement the automatic algorithm.
 *
 * Detection: same markers as RzdFree.
 *
 * @see RzdFree
 * @see WebViewProvider
 */

public class RzdFreeWV extends WebViewProvider {
    private String redirect = null;

    public RzdFreeWV(final Context context, final HttpResponse res) {
        super(context);

        /**
         * Checking Internet connection and capturing the initial redirect.
         */
        add(new InitialConnectionCheckTask(this, res) {
            @Override
            public boolean handle_response(HashMap<String, Object> vars, HttpResponse response) {
                redirect = response.parseAnyRedirectOrNull();
                RzdFree.dump(response);
                return true;
            }
        });

        /**
         * Async: Block ads and trackers for speed and cleaner logs
         */
        add(new InterceptorTask(".*(ads\\.adfox\\.ru|mc\\.yandex\\.ru|ac\\.yandex\\.ru|top-fwz1\\.mail\\.ru|\\.mp4$).*") {
            @NonNull @Override
            public HttpResponse request(Client client, HttpRequest request) throws IOException {
                Logger.log(Logger.LEVEL.DEBUG, "RzdFreeWV | Blocked: " + request.getUrl());
                return new HttpResponse(request, "");
            }
        });

        /**
         * Async: Log every request made by the portal inside WebView
         */
        add(new InterceptorTask(".*") {
            @Nullable @Override
            public HttpResponse request(Client client, HttpRequest request) throws IOException {
                Logger.log(Logger.LEVEL.DEBUG,
                        "RzdFreeWV | -> " + request.getMethod() + " " + request.getUrl());
                return null; // pass through
            }
        });

        /**
         * Async: Log every response code
         */
        add(new InterceptorTask(".*") {
            @NonNull @Override
            public HttpResponse response(Client client, HttpRequest request, HttpResponse response) throws IOException {
                Logger.log(Logger.LEVEL.DEBUG, "RzdFreeWV | <- " +
                        response.getResponseCode() + " " + request.getUrl());
                return response;
            }
        });

        /**
         * Opening the portal in the embedded WebView
         */
        add(new NamedTask(context.getString(R.string.auth_webview_page)) {
            @Override
            public boolean run(HashMap<String, Object> vars) {
                if (redirect == null) {
                    Logger.log(Logger.LEVEL.DEBUG, "RzdFreeWV | No portal redirect found");
                    vars.put("result", RESULT.NOT_SUPPORTED);
                    return false;
                }

                Logger.log(context.getString(R.string.auth_rzd_manual));
                wv.get(redirect);
                return true;
            }
        });

        /**
         * Waiting for manual authorization to succeed:
         * poll generate_204 every internet_check_interval seconds
         */
        add(new WaitTask(this, context.getString(R.string.auth_rzd_manual_wait)) {
            private final int interval = Util.getIntPreference(context, "pref_internet_check_interval", 10);
            private int counter = 0;

            @Override
            public boolean until(HashMap<String, Object> vars) {
                if (++counter < interval * 10) return false;

                counter = 0;

                Gen204.Gen204Result res_204 = gen_204.check(true);
                return res_204.isConnected() && !res_204.isFalseNegative();
            }
        }.timeout(300000));

        add(new FinalConnectionCheckTask(this));
    }

    /**
     * Checks if current network is supported by this Provider implementation.
     * @param response  Instance of ParsedResponse.
     * @return          True if response matches this Provider implementation.
     */
    public static boolean match(HttpResponse response) {
        return RzdFree.match(response);
    }
}
