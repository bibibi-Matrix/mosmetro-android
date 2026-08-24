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
 * The RzdFree class implements automatic authorization in the RZD_FREE
 * network (TTK captive portal, wifilogin.ttk.ru).
 *
 * Algorithm:
 *   1. Any HTTP request is redirected to /cp/isg?dst=<url>;
 *      this request establishes session cookies (dst, wnam, mac).
 *   2. POST /cp/sms with the phone number sends the SMS code.
 *   3. The code itself must be entered by the user in the embedded
 *      window; the connection is detected automatically afterwards.
 *
 * Detection: redirect to any wifilogin.ttk.ru URL.
 *
 * @see ResearchActivity
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

                dump(TAG, response);
                return true;
            }
        });

        /**
         * Sending phone number: GET portal page (establishes cookies),
         * find the SMS form and submit it with the configured number.
         */
        add(new NamedTask(context.getString(R.string.auth_rzd_phone_send)) {
            @Override
            public boolean run(HashMap<String, Object> vars) {
                String phone = settings.getString("pref_rzd_phone", "")
                        .replaceAll("[^0-9]", "");

                if (phone.length() == 11 && phone.startsWith("8")) {
                    phone = "7" + phone.substring(1);
                }

                if (phone.isEmpty()) {
                    Logger.log(context.getString(R.string.error,
                            context.getString(R.string.auth_rzd_no_phone)
                    ));
                    vars.put("result", RESULT.NOT_SUPPORTED);
                    return false;
                }

                try {
                    HttpResponse page = client.get(redirect).execute();
                    Document doc = page.getPageContent();

                    Element sms_form = findForm(doc, "/cp/sms");
                    if (sms_form == null) {
                        Logger.log(Logger.LEVEL.DEBUG,
                                TAG + " | SMS form not found on the page");
                        vars.put("result", RESULT.NOT_SUPPORTED);
                        return false;
                    }

                    Map<String, String> fields = collectInputs(sms_form);
                    String phone_field = detectPhoneField(sms_form);
                    fields.put(phone_field, phone);

                    String action = sms_form.absUrl("action");
                    if (action.isEmpty()) action = redirect;

                    HttpResponse result = client.post(action, fields).execute();
                    Logger.log(Logger.LEVEL.DEBUG, TAG + " | SMS form result: "
                            + result.getResponseCode());

                    return true;
                } catch (Exception ex) {
                    Logger.log(Logger.LEVEL.DEBUG, ex);
                    Logger.log(context.getString(R.string.error,
                            context.getString(R.string.auth_error_server)
                    ));
                    vars.put("result", RESULT.ERROR);
                    return false;
                }
            }
        });

        /**
         * The SMS code can not be received automatically: open the code
         * entry page in the embedded window and let the user finish.
         */
        add(new NamedTask(context.getString(R.string.auth_rzd_code)) {
            @Override
            public boolean run(HashMap<String, Object> vars) {
                Client activity_client = new OkHttp(context);
                activity_client.interceptors.add(request_logger());
                activity_client.interceptors.add(response_logger());

                ResearchActivity.pending_client = activity_client;
                ResearchActivity.pending_url =
                        "https://wifilogin.ttk.ru/cp/sms";
                ResearchActivity.setState(ResearchActivity.STATE_RUNNING);

                context.startActivity(new Intent(context, ResearchActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));

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
     * Finds a form which posts to the given path fragment.
     */
    @Nullable
    private static Element findForm(Document doc, String action_part) {
        for (Element form : doc.getElementsByTag("form")) {
            if (form.attr("action").contains(action_part)) {
                return form;
            }
        }

        // Fall back to the first form with any inputs
        for (Element form : doc.getElementsByTag("form")) {
            if (!form.getElementsByTag("input").isEmpty()) {
                return form;
            }
        }

        return null;
    }

    /**
     * Collects all named inputs of the form preserving hidden values.
     */
    private static Map<String, String> collectInputs(Element form) {
        Map<String, String> fields = new HashMap<>();

        for (Element input : form.getElementsByTag("input")) {
            String name = input.attr("name");
            if (name.isEmpty()) continue;

            String type = input.attr("type").toLowerCase();
            if ("checkbox".equals(type) && !input.hasAttr("checked")) continue;

            fields.put(name, input.attr("value"));
        }

        return fields;
    }

    /**
     * Detects the phone input of the form: by type=tel, by name,
     * or falls back to the first visible text input.
     */
    private static String detectPhoneField(Element form) {
        for (Element input : form.getElementsByTag("input")) {
            if ("tel".equalsIgnoreCase(input.attr("type"))) {
                return input.hasAttr("name") ? input.attr("name") : "phone";
            }
        }

        for (Element input : form.getElementsByTag("input")) {
            String name = input.attr("name").toLowerCase();
            if (name.contains("phone")) {
                return input.attr("name");
            }
        }

        return "phone";
    }

    /**
     * Interceptor used to log requests made by the embedded window.
     */
    private InterceptorTask request_logger() {
        return new InterceptorTask(".*") {
            @Nullable @Override
            public HttpResponse request(Client client, HttpRequest request) throws IOException {
                Logger.log(Logger.LEVEL.DEBUG,
                        TAG + " | -> " + request.getMethod() + " " + request.getUrl());
                return null; // pass through
            }
        };
    }

    /**
     * Interceptor used to log responses inside the embedded window.
     */
    private InterceptorTask response_logger() {
        return new InterceptorTask(".*") {
            @NonNull @Override
            public HttpResponse response(Client client, HttpRequest request, HttpResponse response) throws IOException {
                Logger.log(Logger.LEVEL.DEBUG, TAG + " | <- " +
                        response.getResponseCode() + " " + request.getUrl());
                return response;
            }
        };
    }

    /**
     * Logs structured information about the portal page.
     */
    private void dump(String tag, HttpResponse response) {
        ResearchWV.dump(tag, response);
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
