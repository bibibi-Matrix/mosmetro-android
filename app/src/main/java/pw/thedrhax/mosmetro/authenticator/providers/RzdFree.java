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

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import pw.thedrhax.mosmetro.R;
import pw.thedrhax.mosmetro.activities.PortalActivity;
import pw.thedrhax.mosmetro.authenticator.FinalConnectionCheckTask;
import pw.thedrhax.mosmetro.authenticator.Gen204;
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
 * The portal remembers devices by MAC, so known devices are logged in
 * automatically: the portal page contains an auto-submitting form
 * pointing to /cp/login which is replayed over plain HTTP without any
 * UI. First-time devices get the SMS form instead -- in that case the
 * page is opened in an embedded WebView window for manual completion.
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
         * Checking Internet connection
         * ⇒ GET generate_204 < res
         * ⇐ Meta-redirect: https://wifilogin.ttk.ru/cp/isg > redirect
         */
        add(new InitialConnectionCheckTask(this, res) {
            @Override
            public boolean handle_response(HashMap<String, Object> vars, HttpResponse response) {
                redirect = response.parseAnyRedirectOrNull();

                if (redirect == null || !redirect.contains("ttk.ru")) {
                    redirect = PORTAL;
                }

                Logger.log(Logger.LEVEL.DEBUG, TAG + " | Redirect: " + redirect);

                return true;
            }
        });

        /**
         * Fetching the portal page
         * ⇒ GET https://wifilogin.ttk.ru/cp/isg < redirect
         * ⇐ HTML with form (auto-login or SMS)
         */
        add(new NamedTask(context.getString(R.string.auth_auth_page)) {
            @Override
            public boolean run(HashMap<String, Object> vars) {
                try {
                    HttpResponse page = client.get(redirect).execute();
                    Logger.log(Logger.LEVEL.DEBUG, TAG + " | Portal page: " + page.getResponseCode());

                    Document doc = page.getPageContent();

                    Element login_form = findForm(doc, "/cp/login");

                    if (login_form != null) {
                        Logger.log(Logger.LEVEL.DEBUG, TAG + " | Auto-login form found");
                        vars.put("login_form", login_form);
                        vars.put("login_action", login_form.absUrl("action").isEmpty()
                                ? redirect : login_form.absUrl("action"));
                    } else {
                        Logger.log(Logger.LEVEL.DEBUG, TAG + " | No auto-login form (first-time device)");
                    }

                    return true;
                } catch (IOException ex) {
                    Logger.log(Logger.LEVEL.DEBUG, ex);
                    Logger.log(context.getString(R.string.error,
                            context.getString(R.string.auth_error_auth_page)
                    ));
                    return false;
                }
            }
        });

        /**
         * Submitting the auto-login form (if present)
         * ⇒ POST action < form fields
         * ⇐ 200 OK → check internet
         */
        add(new NamedTask(context.getString(R.string.auth_auth_form)) {
            @Override
            public boolean run(HashMap<String, Object> vars) {
                Element login_form = (Element) vars.get("login_form");

                if (login_form == null) {
                    Logger.log(Logger.LEVEL.DEBUG, TAG + " | Skipping form submit (no form)");
                    openWindow(vars);
                    return true;
                }

                try {
                    Map<String, String> fields = collectInputs(login_form);
                    String action = (String) vars.get("login_action");

                    Logger.log(Logger.LEVEL.DEBUG, TAG + " | POST " + action);
                    HttpResponse result = client.post(action, fields).execute();
                    Logger.log(Logger.LEVEL.DEBUG,
                            TAG + " | Login form result: " + result.getResponseCode());

                    Gen204.Gen204Result check = gen_204.check(true);

                    if (check.isConnected() && !check.isFalseNegative()) {
                        Logger.log(Logger.LEVEL.DEBUG, TAG + " | Auto-login succeeded");
                        vars.put("result", RESULT.CONNECTED);
                        return false;
                    }

                    Logger.log(Logger.LEVEL.DEBUG, TAG + " | Auto-login did not open internet");
                } catch (IOException ex) {
                    Logger.log(Logger.LEVEL.DEBUG, ex);
                }

                openWindow(vars);
                return true;
            }
        });

        /**
         * Waiting for manual authorization to succeed or be cancelled
         */
        add(new WaitTask(this, context.getString(R.string.auth_webview_script)) {
            @Override
            public boolean until(HashMap<String, Object> vars) {
                if (PortalActivity.state == PortalActivity.STATE_CANCELLED) {
                    Gen204.Gen204Result res = gen_204.check(true);

                    if (res.isConnected() && !res.isFalseNegative()) {
                        Logger.log(TAG + " | Connection opened");
                        return true;
                    }

                    Logger.log(TAG + " | Cancelled");
                    vars.put("result", RESULT.INTERRUPTED);
                    return true;
                }

                return PortalActivity.state == PortalActivity.STATE_CONNECTED;
            }
        }.timeout(300000));

        /**
         * Stop silently if the user cancelled the session
         */
        add(vars -> !RESULT.INTERRUPTED.equals(vars.get("result")));

        add(new FinalConnectionCheckTask(this));
    }

    /**
     * Opens the portal page in the embedded WebView window with
     * logging interceptors attached.
     */
    private void openWindow(HashMap<String, Object> vars) {
        Client activity_client = new OkHttp(context);

        activity_client.interceptors.add(new InterceptorTask(
                ".*(ads\\.adfox\\.ru|mc\\.yandex\\.ru|ac\\.yandex\\.ru|top-fwz1\\.mail\\.ru|doubleclick\\.net|googlesyndication\\.com).*") {
            @NonNull @Override
            public HttpResponse request(Client c, HttpRequest request) throws IOException {
                Logger.log(Logger.LEVEL.DEBUG, TAG + " | Blocked: " + request.getUrl());
                return new HttpResponse(request, "");
            }
        });

        activity_client.interceptors.add(new InterceptorTask(".*") {
            @Nullable @Override
            public HttpResponse request(Client c, HttpRequest request) throws IOException {
                Logger.log(Logger.LEVEL.DEBUG,
                        TAG + " | -> " + request.getMethod() + " " + request.getUrl());
                return null;
            }
        });

        activity_client.interceptors.add(new InterceptorTask(".*") {
            @NonNull @Override
            public HttpResponse response(Client c, HttpRequest request, HttpResponse response) throws IOException {
                Logger.log(Logger.LEVEL.DEBUG, TAG + " | <- " +
                        response.getResponseCode() + " " + request.getUrl());
                return response;
            }
        });

        PortalActivity.pending_client = activity_client;
        PortalActivity.pending_url = redirect;
        PortalActivity.setState(PortalActivity.STATE_RUNNING);

        context.startActivity(new Intent(context, PortalActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));

        Logger.log(context.getString(R.string.auth_webview_page));
    }

    /**
     * Finds a form which posts to the given path fragment.
     */
    @Nullable
    private static Element findForm(Document doc, String action_part) {
        for (Element form : doc.getElementsByTag("form")) {
            if (form.attr("action").contains(action_part)) {
                return form;
            }
        }

        return null;
    }

    /**
     * Collects all named inputs of the form preserving hidden values.
     */
    private static HashMap<String, String> collectInputs(Element form) {
        HashMap<String, String> fields = new HashMap<>();

        for (Element input : form.getElementsByTag("input")) {
            String name = input.attr("name");
            if (name.isEmpty()) continue;

            fields.put(name, input.attr("value"));
        }

        return fields;
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
