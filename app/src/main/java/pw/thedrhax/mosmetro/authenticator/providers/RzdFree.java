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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;

import pw.thedrhax.mosmetro.R;
import pw.thedrhax.mosmetro.activities.ResearchActivity;
import pw.thedrhax.mosmetro.authenticator.FinalConnectionCheckTask;
import pw.thedrhax.mosmetro.authenticator.InitialConnectionCheckTask;
import pw.thedrhax.mosmetro.authenticator.InterceptorTask;
import pw.thedrhax.mosmetro.authenticator.NamedTask;
import pw.thedrhax.mosmetro.authenticator.Provider;
import pw.thedrhax.mosmetro.authenticator.WaitTask;
import pw.thedrhax.mosmetro.httpclient.Client;
import pw.thedrhax.mosmetro.httpclient.HttpRequest;
import pw.thedrhax.mosmetro.httpclient.HttpResponse;
import pw.thedrhax.mosmetro.httpclient.clients.OkHttp;
import pw.thedrhax.util.Logger;

/**
 * The RzdFree class handles authorization in the RZD_FREE network
 * (TTK captive portal, wifilogin.ttk.ru).
 *
 * The portal remembers devices by their MAC address, so it asks for
 * the phone number and SMS code only once. All subsequent connections
 * pass automatically right after opening any page of the portal.
 *
 * Therefore this provider simply opens the portal in an embedded
 * WebView window: usually it passes instantly, and the connection is
 * detected by generate_204 polling. First-time users complete the
 * short form manually in the same window.
 *
 * Detection: redirect to any wifilogin.ttk.ru URL.
 */

public class RzdFree extends Provider {
    private static final String TAG = "RzdFree";
    private static final String PORTAL = "https://wifilogin.ttk.ru/cp/isg";

    private String redirect = null;

    public RzdFree(final Context context, final HttpResponse res) {
        super(context);

        /**
         * Checking Internet connection and capturing the initial redirect.
         */
        add(new InitialConnectionCheckTask(this, res) {
            @Override
            public boolean handle_response(HashMap<String, Object> vars, HttpResponse response) {
                redirect = response.parseAnyRedirectOrNull();

                if (redirect == null || !redirect.contains("ttk.ru")) {
                    redirect = PORTAL;
                }

                ResearchWV.dump(TAG, response);
                return true;
            }
        });

        /**
         * Opening the portal in the embedded WebView window
         */
        add(new NamedTask(context.getString(R.string.auth_webview_page)) {
            @Override
            public boolean run(HashMap<String, Object> vars) {
                Client activity_client = buildClient();

                ResearchActivity.pending_client = activity_client;
                ResearchActivity.pending_url = redirect;
                ResearchActivity.setState(ResearchActivity.STATE_RUNNING);

                context.startActivity(new Intent(context, ResearchActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));

                Logger.log(context.getString(R.string.auth_research_manual));
                return true;
            }
        });

        /**
         * Waiting for the portal to pass (usually instant for known
         * devices) or for the user to finish/cancel manually
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
     * Builds a fresh client with logging interceptors for the
     * embedded window session.
     */
    private Client buildClient() {
        Client client = new OkHttp(context);

        client.interceptors.add(new InterceptorTask(
                ".*(ads\\.adfox\\.ru|mc\\.yandex\\.ru|ac\\.yandex\\.ru|top-fwz1\\.mail\\.ru|doubleclick\\.net|googlesyndication\\.com).*") {
            @NonNull @Override
            public HttpResponse request(Client c, HttpRequest request) throws IOException {
                Logger.log(Logger.LEVEL.DEBUG, TAG + " | Blocked: " + request.getUrl());
                return new HttpResponse(request, "");
            }
        });

        client.interceptors.add(new InterceptorTask(".*") {
            @Nullable @Override
            public HttpResponse request(Client c, HttpRequest request) throws IOException {
                Logger.log(Logger.LEVEL.DEBUG,
                        TAG + " | -> " + request.getMethod() + " " + request.getUrl());
                return null; // pass through
            }
        });

        client.interceptors.add(new InterceptorTask(".*") {
            @NonNull @Override
            public HttpResponse response(Client c, HttpRequest request, HttpResponse response) throws IOException {
                Logger.log(Logger.LEVEL.DEBUG, TAG + " | <- " +
                        response.getResponseCode() + " " + request.getUrl());

                // Hook native form submissions to capture POST bodies
                if (response.isHtml()) {
                    response.getPageContent().body().append(
                            "<script>(function(){function ser(f){var d={action:f.action,method:f.method};" +
                            "try{for(var i=0;i<f.elements.length;i++){var e=f.elements[i];" +
                            "if(e.name)d[e.name]=e.value;}}catch(e){}return JSON.stringify(d);}" +
                            "document.addEventListener('submit',function(ev){try{" +
                            "console.log('RESEARCH|FORM|'+ser(ev.target));}catch(e){}},true);" +
                            "var o=HTMLFormElement.prototype.submit;" +
                            "if(o){HTMLFormElement.prototype.submit=function(){try{" +
                            "console.log('RESEARCH|FORM|'+ser(this));}catch(e){}}" +
                            "return o.apply(this,arguments);};})();</script>"
                    );
                }

                return response;
            }
        });

        return client;
    }

    /**
     * Checks if current network is supported by this Provider implementation.
     * @param response  Instance of ParsedResponse.
     * @return          True if response matches this Provider implementation.
     */
    public static boolean match(HttpResponse response) {
        String redirect = response.parseAnyRedirectOrNull();
        return redirect != null && redirect.contains("ttk.ru");
    }
}
