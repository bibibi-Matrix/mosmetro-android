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

import androidx.annotation.Nullable;

import java.util.HashMap;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import pw.thedrhax.mosmetro.R;
import pw.thedrhax.mosmetro.authenticator.FollowRedirectsTask;
import pw.thedrhax.mosmetro.authenticator.InitialConnectionCheckTask;
import pw.thedrhax.mosmetro.authenticator.NamedTask;
import pw.thedrhax.mosmetro.authenticator.Provider;
import pw.thedrhax.mosmetro.httpclient.HttpResponse;
import pw.thedrhax.util.Logger;

/**
 * The RzdFree class is used to gather information about the RZD_FREE
 * authorization portal. The actual authorization algorithm is not
 * implemented yet: this provider only follows and logs every step of
 * the captive portal flow for further analysis.
 *
 * Detection: redirect URL or page content contains "rzd"/"РЖД" markers.
 *
 * @see Provider
 */

public class RzdFree extends Provider {
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
                dump(response);
                return true;
            }
        });

        /**
         * Following the whole redirect chain logging every request
         * and response for further analysis.
         */
        add(new FollowRedirectsTask(this) {
            @Nullable @Override
            public String getInitialRedirect(HashMap<String, Object> vars) {
                return redirect;
            }
        }.setIgnoreErrors(true));

        /**
         * Dumping the final page of the chain.
         */
        add(new NamedTask(context.getString(R.string.auth_rzd_info)) {
            @Override
            public boolean run(HashMap<String, Object> vars) {
                Logger.log(context.getString(R.string.auth_rzd_result));
                vars.put("result", RESULT.NOT_SUPPORTED);
                return false;
            }
        });
    }

    /**
     * Logs structured information about the portal page:
     * title, meta redirects, forms with inputs and scripts.
     */
    private static void dump(HttpResponse response) {
        Logger.log(Logger.LEVEL.DEBUG, "RzdFree | URL: " + response.getUrl());
        Logger.log(Logger.LEVEL.DEBUG,
                "RzdFree | Status: " + response.getResponseCode() + " " + response.getReason());

        if (!response.isHtml()) {
            Logger.log(Logger.LEVEL.DEBUG,
                    "RzdFree | Content-Type: " + response.headers.getMimeType());
            return;
        }

        Document doc = response.getPageContent();

        Logger.log(Logger.LEVEL.DEBUG, "RzdFree | Title: " + doc.title());

        String redirect = response.parseAnyRedirectOrNull();
        if (redirect != null) {
            Logger.log(Logger.LEVEL.DEBUG, "RzdFree | Meta redirect: " + redirect);
        }

        for (Element form : doc.getElementsByTag("form")) {
            StringBuilder inputs = new StringBuilder();

            for (Element input : form.getElementsByTag("input")) {
                if (inputs.length() > 0) inputs.append(", ");
                inputs.append(input.attr("name"))
                      .append("[").append(input.attr("type")).append("]");
            }

            Logger.log(Logger.LEVEL.DEBUG, "RzdFree | Form: "
                    + form.attr("method") + " " + form.absUrl("action")
                    + " {" + inputs + "}");
        }

        for (Element script : doc.getElementsByTag("script")) {
            String src = script.attr("src");
            if (!src.isEmpty()) {
                Logger.log(Logger.LEVEL.DEBUG, "RzdFree | Script: " + src);
            }
        }
    }

    /**
     * Checks if current network is supported by this Provider implementation.
     * @param response  Instance of ParsedResponse.
     * @return          True if response matches this Provider implementation.
     */
    public static boolean match(HttpResponse response) {
        String redirect = response.parseAnyRedirectOrNull();

        if (redirect != null && redirect.toLowerCase().contains("rzd")) {
            return true;
        }

        if (response.isHtml()) {
            String text = response.getPageContent().text().toLowerCase();
            return text.contains("rzd") || text.contains("ржд");
        }

        return false;
    }
}
