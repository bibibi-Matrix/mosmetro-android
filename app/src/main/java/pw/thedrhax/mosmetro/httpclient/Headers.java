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

package pw.thedrhax.mosmetro.httpclient;

import java.util.LinkedList;
import java.util.List;
import java.util.TreeMap;

import androidx.annotation.Nullable;

public class Headers extends TreeMap<String,List<String>> {
    public static final String ACCEPT = "Accept";
    public static final String ACCEPT_LANGUAGE = "Accept-Language";
    public static final String ACAO = "Access-Control-Allow-Origin";
    public static final String ACAC = "Access-Control-Allow-Credentials";
    public static final String CSP = "Content-Security-Policy";
    public static final String USER_AGENT = "User-Agent";
    public static final String REFERER = "Referer";
    public static final String CSRF = "X-CSRF-Token";
    public static final String LOCATION = "Location";
    public static final String CONTENT_TYPE = "Content-Type";
    public static final String COOKIE = "Cookie";
    public static final String SET_COOKIE = "Set-Cookie";
    public static final String AUTHORIZATION = "Authorization";
    public static final String UPGRADE_INSECURE_REQUESTS = "Upgrade-Insecure-Requests";

    private static final List<String> SENSITIVE_HEADERS = new LinkedList<String>() {{
        add(COOKIE);
        add(SET_COOKIE);
        add(AUTHORIZATION);
    }};

    /**
     * Hide sensitive values (session tokens, credentials) before logging.
     * Cookie names are kept for debugging purposes.
     */
    public static String maskSensitiveValue(String name, String value) {
        boolean sensitive = false;
        for (String header : SENSITIVE_HEADERS) {
            if (header.equalsIgnoreCase(name)) {
                sensitive = true;
                break;
            }
        }

        if (!sensitive) return value;

        if (AUTHORIZATION.equalsIgnoreCase(name)) return "<hidden>";

        StringBuilder masked = new StringBuilder();
        for (String pair : value.split(";")) {
            if (masked.length() > 0) masked.append("; ");

            int eq = pair.indexOf('=');
            if (eq > 0) {
                masked.append(pair, 0, eq).append("=<hidden>");
            } else {
                masked.append("<hidden>");
            }
        }

        return masked.toString();
    }

    public Headers() {
        super(String.CASE_INSENSITIVE_ORDER);
    }

    public Headers setHeader(String name, String value) {
        put(name, new LinkedList<String>() {{
            add(value);
        }});

        return this;
    }

    public Headers addHeader(String name, String value) {
        List<String> header = get(name);

        if (header != null) {
            if (!header.contains(value)) {
                header.add(value);
            }
        } else {
            setHeader(name, value);
        }

        return this;
    }

    @Nullable
    public String getFirst(String name) {
        List<String> header = get(name);
        if (header != null) {
            return header.get(0);
        } else {
            return null;
        }
    }

    public String getContentType() {
        String contentType = getFirst(CONTENT_TYPE);
        return contentType != null ? contentType : "text/plain";
    }

    public String getMimeType() {
        return getContentType().split(";")[0];
    }

    public String getEncoding() {
        String contentType = getFirst(CONTENT_TYPE);

        if (contentType != null && contentType.contains("charset")) {
            return contentType.split("charset=")[1];
        } else {
            return "utf-8";
        }
    }
}
