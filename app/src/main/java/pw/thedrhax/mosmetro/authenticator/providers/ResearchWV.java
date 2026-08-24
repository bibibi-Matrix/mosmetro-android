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
import android.content.SharedPreferences;

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
 * The ResearchWV class implements the authorization research mode:
 * when enabled in the debug settings, the app never tries to authorize
 * automatically. Instead, the captive portal of ANY network is opened
 * in an embedded WebView where the user completes the authorization
 * manually, while every request and response made by the portal is
 * logged for further analysis.
 *
 * Detection: enabled by preference and any redirect present in
 * the detection response.
 *
 * @see WebViewProvider
 */

public class ResearchWV extends WebViewProvider {
    public static final String TAG = "ResearchWV";

    private String redirect = null;

    public ResearchWV(final Context context, final HttpResponse res) {
        super(context);

        /**
         * Checking Internet connection and capturing the initial redirect.
         */
        add(new InitialConnectionCheckTask(this, res) {
            @Override
            public boolean handle_response(HashMap<String, Object> vars, HttpResponse response) {
                redirect = response.parseAnyRedirectOrNull();
                dump(TAG, response);
                return true;
            }
        });

        /**
         * Async: Block ads and trackers for speed and cleaner logs
         */
        add(new InterceptorTask(".*(ads\\.adfox\\.ru|mc\\.yandex\\.ru|ac\\.yandex\\.ru|top-fwz1\\.mail\\.ru|doubleclick\\.net|googlesyndication\\.com|\\.mp4$).*") {
            @NonNull @Override
            public HttpResponse request(Client client, HttpRequest request) throws IOException {
                Logger.log(Logger.LEVEL.DEBUG, TAG + " | Blocked: " + request.getUrl());
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
                        TAG + " | -> " + request.getMethod() + " " + request.getUrl());
                return null; // pass through
            }
        });

        /**
         * Async: Log every response code
         */
        add(new InterceptorTask(".*") {
            @NonNull @Override
            public HttpResponse response(Client client, HttpRequest request, HttpResponse response) throws IOException {
                Logger.log(Logger.LEVEL.DEBUG, TAG + " | <- " +
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
                    Logger.log(Logger.LEVEL.DEBUG, TAG + " | No portal redirect found");
                    vars.put("result", RESULT.NOT_SUPPORTED);
                    return false;
                }

                Logger.log(context.getString(R.string.auth_research_manual));
                wv.get(redirect);
                return true;
            }
        });

        /**
         * Waiting for manual authorization to succeed:
         * poll generate_204 every internet_check_interval seconds
         */
        add(new WaitTask(this, context.getString(R.string.auth_research_wait)) {
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
     * Checks whether the research mode is enabled and the response
     * contains a captive portal redirect for any network.
     */
    public static boolean match(HttpResponse response, SharedPreferences settings) {
        if (!settings.getBoolean("pref_debug_research", false)) return false;

        return response.parseAnyRedirectOrNull() != null;
    }

    /**
     * Logs structured information about the portal page:
     * title, meta redirects, forms with inputs and scripts.
     * @param tag       Prefix used to distinguish callers in the log.
     */
    public static void dump(String tag, HttpResponse response) {
        Logger.log(Logger.LEVEL.DEBUG, tag + " | URL: " + response.getUrl());
        Logger.log(Logger.LEVEL.DEBUG,
                tag + " | Status: " + response.getResponseCode() + " " + response.getReason());

        if (!response.isHtml()) {
            Logger.log(Logger.LEVEL.DEBUG,
                    tag + " | Content-Type: " + response.headers.getMimeType());
            return;
        }

        org.jsoup.nodes.Document doc = response.getPageContent();

        Logger.log(Logger.LEVEL.DEBUG, tag + " | Title: " + doc.title());

        String redirect = response.parseAnyRedirectOrNull();
        if (redirect != null) {
            Logger.log(Logger.LEVEL.DEBUG, tag + " | Meta redirect: " + redirect);
        }

        for (org.jsoup.nodes.Element form : doc.getElementsByTag("form")) {
            StringBuilder inputs = new StringBuilder();

            for (org.jsoup.nodes.Element input : form.getElementsByTag("input")) {
                if (inputs.length() > 0) inputs.append(", ");
                inputs.append(input.attr("name"))
                      .append("[").append(input.attr("type")).append("]");
            }

            Logger.log(Logger.LEVEL.DEBUG, tag + " | Form: "
                    + form.attr("method") + " " + form.absUrl("action")
                    + " {" + inputs + "}");
        }

        for (org.jsoup.nodes.Element script : doc.getElementsByTag("script")) {
            String src = script.attr("src");
            if (!src.isEmpty()) {
                Logger.log(Logger.LEVEL.DEBUG, tag + " | Script: " + src);
            }
        }
    }
}
