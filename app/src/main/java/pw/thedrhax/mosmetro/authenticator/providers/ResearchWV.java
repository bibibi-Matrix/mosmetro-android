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
import android.content.Intent;
import android.app.PendingIntent;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.HashMap;

import pw.thedrhax.mosmetro.R;
import pw.thedrhax.mosmetro.activities.ResearchActivity;
import pw.thedrhax.mosmetro.authenticator.FinalConnectionCheckTask;
import pw.thedrhax.mosmetro.authenticator.Gen204;
import pw.thedrhax.mosmetro.authenticator.InitialConnectionCheckTask;
import pw.thedrhax.mosmetro.authenticator.InterceptorTask;
import pw.thedrhax.mosmetro.authenticator.NamedTask;
import pw.thedrhax.mosmetro.authenticator.Provider;
import pw.thedrhax.mosmetro.authenticator.ResearchCapture;
import pw.thedrhax.mosmetro.authenticator.WaitTask;
import pw.thedrhax.mosmetro.httpclient.Client;
import pw.thedrhax.mosmetro.httpclient.HttpRequest;
import pw.thedrhax.mosmetro.httpclient.HttpResponse;
import pw.thedrhax.mosmetro.httpclient.clients.OkHttp;
import pw.thedrhax.util.Logger;
import pw.thedrhax.util.Notify;

/**
 * The ResearchWV class implements the authorization research mode:
 * when enabled in the debug settings, the app never tries to authorize
 * automatically. Instead, the captive portal of ANY network is opened
 * in a visible embedded WebView where the user completes the
 * authorization manually, while every request and response made by
 * the portal is logged for further analysis.
 *
 * Detection: enabled by preference and any redirect present in
 * the detection response.
 *
 * @see ResearchActivity
 */

public class ResearchWV extends Provider {
    public static final String TAG = "ResearchWV";

    private final ResearchCapture capture;

    private final InterceptorTask request_logger = new InterceptorTask(".*") {
        @Nullable @Override
        public HttpResponse request(Client client, HttpRequest request) throws IOException {
            Logger.log(Logger.LEVEL.DEBUG,
                    TAG + " | -> " + request.getMethod() + " " + request.getUrl());

            capture.logWifiState(TAG + " |   [req]");
            capture.logRequest(TAG + " |   ", request);

            return null; // pass through
        }
    };

    private final InterceptorTask response_logger = new InterceptorTask(".*") {
        @NonNull @Override
        public HttpResponse response(Client client, HttpRequest request, HttpResponse response) throws IOException {
            Logger.log(Logger.LEVEL.DEBUG, TAG + " | <- " +
                    response.getResponseCode() + " " + request.getUrl());

            capture.logResponse(TAG + " |   ", request, response);
            return response;
        }
    };

    private String redirect = null;

    public ResearchWV(final Context context, final HttpResponse res) {
        super(context);

        this.capture = new ResearchCapture(context);
        capture.logWifiState(TAG + " |   [start]");

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
         * Opening the portal in a visible WebView activity
         */
        add(new NamedTask(context.getString(R.string.auth_webview_page)) {
            @Override
            public boolean run(HashMap<String, Object> vars) {
                if (redirect == null) {
                    Logger.log(Logger.LEVEL.DEBUG, TAG + " | No portal redirect found");
                    vars.put("result", RESULT.NOT_SUPPORTED);
                    return false;
                }

                // Fresh client per session: logging interceptors live here
                Client activity_client = new OkHttp(context);
                activity_client.interceptors.add(request_logger);
                activity_client.interceptors.add(response_logger);

                ResearchActivity.pending_client = activity_client;
                ResearchActivity.pending_url = redirect;
                ResearchActivity.setState(ResearchActivity.STATE_RUNNING);

                // Launch the portal window through a notification with a
                // full-screen intent. A direct startActivity() from a
                // background service is silently blocked by Android 10+
                // Background Activity Launch restrictions, so the window
                // would never appear. A full-screen intent auto-launches
                // the activity when the device is locked or the app is in
                // the foreground, and shows a tappable notification otherwise.
                PendingIntent activityIntent = PendingIntent.getActivity(
                        context, 0,
                        new Intent(context, ResearchActivity.class)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                );

                new Notify(context)
                        .channel(Notify.CHANNEL_ID_RESEARCH)
                        .title(context.getString(R.string.pref_debug_research))
                        .text(context.getString(R.string.auth_research_manual))
                        .priority(2)
                        .onClick(activityIntent)
                        .fullScreenIntent(activityIntent, true)
                        .id(3)
                        .show();

                Logger.log(context.getString(R.string.auth_research_manual));
                return true;
            }
        });

        /**
         * Waiting for manual authorization to succeed or be cancelled
         */
        add(new WaitTask(this, context.getString(R.string.auth_research_wait)) {
            @Override
            public boolean until(HashMap<String, Object> vars) {
                if (ResearchActivity.state == ResearchActivity.STATE_CANCELLED) {
                    // User might close the window after the portal has
                    // already passed: verify before reporting cancellation
                    Gen204.Gen204Result res = gen_204.check(true);

                    if (res.isConnected() && !res.isFalseNegative()) {
                        Logger.log(TAG + " | Connection opened");
                        return true;
                    }

                    Logger.log(TAG + " | Cancelled");
                    vars.put("result", RESULT.INTERRUPTED);
                    return true;
                }

                return ResearchActivity.state == ResearchActivity.STATE_CONNECTED;
            }
        }.timeout(300000));

        /**
         * Stop silently if the user cancelled the research session
         */
        add(vars -> !RESULT.INTERRUPTED.equals(vars.get("result")));

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
     * The full details are appended to the research capture file.
     * @param tag       Prefix used to distinguish callers in the log.
     */
    public void dump(String tag, HttpResponse response) {
        Logger.log(Logger.LEVEL.DEBUG, tag + " | URL: " + response.getUrl());
        Logger.log(Logger.LEVEL.DEBUG,
                tag + " | Status: " + response.getResponseCode() + " " + response.getReason());

        capture.logWifiState(tag + " |   [portal]");
        capture.logResponse(tag + " |   ", response.getRequest(), response);

        if (!response.isHtml()) {
            Logger.log(Logger.LEVEL.DEBUG,
                    tag + " | Content-Type: " + response.headers.getMimeType());
            return;
        }

        org.jsoup.nodes.Document doc = response.getPageContent();
        capture.logPageSummary(tag + " |   ", doc);

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
