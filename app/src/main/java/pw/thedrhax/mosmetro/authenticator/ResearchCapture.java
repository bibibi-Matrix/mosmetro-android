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

import android.content.Context;
import android.content.Intent;
import android.net.DhcpInfo;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;

import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import pw.thedrhax.mosmetro.R;
import pw.thedrhax.mosmetro.httpclient.HttpRequest;
import pw.thedrhax.mosmetro.httpclient.HttpResponse;
import pw.thedrhax.util.WifiUtils;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Collects a full authorization research session into a single
 * shareable text file (filesDir/research-capture.txt).
 *
 * Research mode never modifies traffic; it only records:
 *  - every request: method, URL, headers, body,
 *  - every response: status, headers, parsed body,
 *  - full HTML dump: title, forms with all fields,
 *    meta/script/links, redirects,
 *  - WiFi / routing diagnostics at session start and per request
 *    (SSID, BSSID, IP, gateway, DNS, transport, validation).
 */
public class ResearchCapture {
    private static final String FILE = "research-capture.txt";

    /** Current research session capture, set when a session starts. */
    @Nullable private static ResearchCapture active = null;

    @Nullable
    public static ResearchCapture getActive() {
        return active;
    }

    /**
     * Shares the research capture file through the system share sheet.
     */
    public static void share(final Context context) {
        Intent share = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra(Intent.EXTRA_SUBJECT,
                        context.getString(R.string.report_email_subject,
                                pw.thedrhax.util.Version.getFormattedVersion()))
                .putExtra(Intent.EXTRA_TEXT,
                        "Full authorization research capture (requests, responses, forms, WiFi state).");

        try {
            Uri uri = FileProvider.getUriForFile(context,
                    "pw.thedrhax.mosmetro.provider",
                    new File(context.getFilesDir(), FILE));
            share.putExtra(Intent.EXTRA_STREAM, uri);

            context.startActivity(Intent.createChooser(
                    share, context.getString(R.string.report_choose_client)
            ));
        } catch (Exception ex) {
            pw.thedrhax.util.Logger.log(pw.thedrhax.util.Logger.LEVEL.DEBUG, ex);
        }
    }

    private final Context context;
    private final File file;
    private final WifiUtils wifi;

    private String last_wifi_signature = null;

    private static final int MAX_BODY = 65536; // chars per request body

    public ResearchCapture(Context context) {
        this.context = context;
        this.file = new File(context.getFilesDir(), FILE);
        this.wifi = new WifiUtils(context);
        active = this;

        // Append a session header to the (possibly existing) capture file.
        try (BufferedWriter w = newWriter()) {
            w.write("== Research capture session from "
                    + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH).format(new Date())
                    + " ==\n");
        } catch (IOException ignored) {}
    }

    private BufferedWriter newWriter() throws IOException {
        return new BufferedWriter(new FileWriter(file, true));
    }

    private void write(String text) {
        try (BufferedWriter w = newWriter()) {
            w.write(text);
        } catch (IOException ex) {
            pw.thedrhax.util.Logger.log(pw.thedrhax.util.Logger.LEVEL.DEBUG, ex);
        }
    }

    private void writeLine(String prefix, String line) {
        write(prefix + line + "\n");
    }

    /*
     * WiFi / routing diagnostics
     */

    private static String ipToString(int ip) {
        return (ip & 0xff) + "." + ((ip >> 8) & 0xff) + "." +
               ((ip >> 16) & 0xff) + "." + ((ip >> 24) & 0xff);
    }

    private String macToString(@Nullable String bssid) {
        return bssid != null && !bssid.isEmpty() ? bssid.toUpperCase(Locale.ENGLISH) : "<null>";
    }

    /**
     * Current simple signature of the routing-relevant WiFi state.
     * Used to emit the full WiFi dump only when something changes.
     */
    private String wifiSignature() {
        try {
            WifiManager wm = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            WifiInfo info = wm != null ? wm.getConnectionInfo() : null;

            String ssid = info != null ? clear(info.getSSID()) : "<off>";
            String ip = info != null ? ipToString(info.getIpAddress()) : "0.0.0.0";

            DhcpInfo dhcp = wm != null ? wm.getDhcpInfo() : null;
            String gw = dhcp != null ? ipToString(dhcp.gateway) : "0.0.0.0";

            String active = "<none>";
            if (Build.VERSION.SDK_INT >= 21) {
                Network network = wifi.getConnectivityManager().getActiveNetwork();
                if (network != null) {
                    NetworkCapabilities caps = wifi.getConnectivityManager()
                            .getNetworkCapabilities(network);
                    if (caps != null && caps.hasTransport(
                            NetworkCapabilities.TRANSPORT_WIFI) &&
                            !caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                        active = "WIFI";
                    } else {
                        active = "OTHER";
                    }
                }
            }

            return active + "|" + ssid + "|" + ip + "|" + gw;
        } catch (RuntimeException ex) {
            return "<exception>";
        }
    }

    /**
     * Logs a compact one-line routing summary (always written).
     */
    public void logWifiSummary(String prefix) {
        write(prefix + "wifi: " + wifiSignature() + "\n");
    }

    /**
     * Dumps full WiFi and routing state, but only when it changed
     * since the last dump. Always dumps on the first call.
     */
    public void logWifiState(String prefix) {
        String signature = wifiSignature();

        if (signature.equals(last_wifi_signature)) {
            return;
        }
        last_wifi_signature = signature;

        write(prefix + "wifi: " + signature + "\n");

        try {
            WifiManager wm = (WifiManager) context.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            WifiInfo info = wm != null ? wm.getConnectionInfo() : null;

            writeLine(prefix, "SSID........: "
                    + (info != null ? clear(info.getSSID()) : "<unavailable>"));
            writeLine(prefix, "BSSID.......: "
                    + (info != null ? macToString(info.getBSSID()) : "<unavailable>"));
            writeLine(prefix, "Wifi IP.....: "
                    + (info != null ? ipToString(info.getIpAddress()) : "<unknown>"));
            writeLine(prefix, "Wifi RSSI...: "
                    + (info != null ? info.getRssi() : "<unknown>") + " dBm");
            writeLine(prefix, "Wifi link...: "
                    + (info != null ? info.getLinkSpeed() + " Mbps" : "<unknown>"));

            DhcpInfo dhcp = wm != null ? wm.getDhcpInfo() : null;
            if (dhcp != null) {
                writeLine(prefix, "Gateway.....: " + ipToString(dhcp.gateway));
                writeLine(prefix, "DNS........: " + ipToString(dhcp.dns1)
                        + (dhcp.dns2 != 0 ? ", " + ipToString(dhcp.dns2) : ""));
            } else {
                writeLine(prefix, "Gateway.....: <unknown>");
                writeLine(prefix, "DNS........: <unknown>");
            }

            // Per-network capabilities
            if (Build.VERSION.SDK_INT >= 21) {
                for (Network network : wifi.getConnectivityManager().getAllNetworks()) {
                    NetworkCapabilities caps = wifi.getConnectivityManager()
                            .getNetworkCapabilities(network);
                    if (caps == null) continue;

                    StringBuilder transports = new StringBuilder();
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
                        transports.append("WIFI,");
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
                        transports.append("CELLULAR,");
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
                        transports.append("VPN,");
                    if (transports.length() == 0) transports.append("<none>");

                    NetworkInfo ni = wifi.getConnectivityManager().getNetworkInfo(network);
                    String state = ni != null ? ni.getDetailedState().toString() : "<no-state>";

                    writeLine(prefix, "Network....: " + network +
                            " {" + transports + "} " + state +
                            " validated=" + caps.hasCapability(
                                    NetworkCapabilities.NET_CAPABILITY_VALIDATED) +
                            " captivePortal=" + caps.hasCapability(
                                    NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL));
                }

                Network active = wifi.getConnectivityManager().getActiveNetwork();
                NetworkCapabilities activeCaps = active != null ?
                        wifi.getConnectivityManager().getNetworkCapabilities(active) : null;
                writeLine(prefix, "Active.....: " + (active != null ? active.toString()
                        : "<none>") + (activeCaps != null && activeCaps.hasTransport(
                                NetworkCapabilities.TRANSPORT_WIFI) &&
                        !activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                        ? " (WiFi)" : " (not plain WiFi)"));
            }
        } catch (RuntimeException ex) {
            writeLine(prefix, "WiFi state exception: " + ex);
        }
        write("");
    }

    private static String clear(String ssid) {
        return ssid != null && !ssid.isEmpty() ? ssid.replace("\"", "") : "<unknown>";
    }

    /*
     * Requests and responses
     */

    /**
     * Records a request before it is sent.
     */
    public void logRequest(String prefix, HttpRequest request) {
        StringBuilder sb = new StringBuilder();

        sb.append(prefix).append(">>> ").append(request.getMethod()).append(" ")
                .append(request.getUrl()).append("\n");

        for (String name : request.headers.keySet()) {
            List<String> values = request.headers.get(name);
            if (values == null) continue;
            for (String value : values) {
                sb.append(prefix).append("    > ").append(name).append(": ")
                        .append(pw.thedrhax.mosmetro.httpclient.Headers
                                .maskSensitiveValue(name, value)).append("\n");
            }
        }

        if (request.getBody() != null && !request.getBody().isEmpty()) {
            sb.append(prefix).append("    > body: ");
            sb.append(truncate(request.getBody(), MAX_BODY)).append("\n");
        }

        write(sb.toString());
    }

    /**
     * Records a response after it was downloaded.
     */
    public void logResponse(String prefix, HttpRequest request, HttpResponse response) {
        StringBuilder sb = new StringBuilder();

        sb.append(prefix).append("<<< ").append(request.getMethod()).append(" ")
                .append(request.getUrl()).append("\n");
        sb.append(prefix).append("    < status: ")
                .append(response.getResponseCode()).append(" ")
                .append(response.getReason()).append("\n");

        for (String name : response.headers.keySet()) {
            List<String> values = response.headers.get(name);
            if (values == null) continue;
            for (String value : values) {
                sb.append(prefix).append("    < ").append(name).append(": ")
                        .append(value).append("\n");
            }
        }

        String redirect = response.parseAnyRedirectOrNull();
        if (redirect != null) {
            sb.append(prefix).append("    < redirect to: ")
                    .append(redirect).append("\n");
        }

        if (response.isHtml()) {
            sb.append(dumpForms(prefix, response.getPageContent()));
        }

        String page = response.getPage();
        String mime = response.headers.getMimeType();

        if (!(page == null || page.isEmpty())) {
            if (mime.contains("json") || mime.contains("x-www-form-urlencoded")
                    || mime.contains("xhtml") || mime.contains("xml")) {
                sb.append(prefix).append("    < body: ")
                        .append(truncate(HttpResponse.maskBody(page), MAX_BODY)).append("\n");
            }
        }

        write(sb.toString());
        write("");
    }

    /**
     * Dumps full form structure: action, method, every input with
     * name/type/value/checked, selects with options, textareas.
     */
    private String dumpForms(String prefix, Document doc) {
        StringBuilder sb = new StringBuilder();

        sb.append(prefix).append("    < forms:\n");

        List<Element> forms = doc.getElementsByTag("form");
        if (forms.isEmpty()) {
            sb.append(prefix).append("    <   (none)\n");
        }

        for (int i = 0; i < forms.size(); i++) {
            Element form = forms.get(i);
            String action = form.absUrl("action");
            if (action.isEmpty()) action = form.attr("action");

            sb.append(prefix).append("    <   form[").append(i).append("] ")
                    .append(form.attr("method").toUpperCase(Locale.ENGLISH))
                    .append(" ").append(action).append("\n");

            // id, name, onsubmit
            if (!form.id().isEmpty())
                sb.append(prefix).append("    <       id=").append(form.id()).append("\n");
            if (!form.attr("name").isEmpty())
                sb.append(prefix).append("    <       name=").append(form.attr("name")).append("\n");

            for (Element input : form.getElementsByTag("input")) {
                sb.append(prefix).append("    <       input ")
                        .append("type=").append(quote(input.attr("type")))
                        .append(" name=").append(quote(input.attr("name")))
                        .append(" value=").append(quote(input.attr("value")))
                        .append(" id=").append(quote(input.id()));
                if (!input.attr("checked").isEmpty())
                    sb.append(" checked");
                if (!input.attr("disabled").isEmpty())
                    sb.append(" disabled");
                sb.append("\n");
            }

            for (Element select : form.getElementsByTag("select")) {
                sb.append(prefix).append("    <       select name=")
                        .append(quote(select.attr("name")))
                        .append(" id=").append(quote(select.id())).append("\n");

                for (Element opt : select.getElementsByTag("option")) {
                    sb.append(prefix).append("    <           option value=")
                            .append(quote(opt.attr("value")));
                    if (!opt.attr("selected").isEmpty()) sb.append(" [selected]");
                    sb.append(" text=").append(quote(opt.text())).append("\n");
                }
            }

            for (Element ta : form.getElementsByTag("textarea")) {
                sb.append(prefix).append("    <       textarea name=")
                        .append(quote(ta.attr("name")))
                        .append(" value=").append(quote(ta.text())).append("\n");
            }

            for (Element button : form.getElementsByTag("button")) {
                sb.append(prefix).append("    <       button type=")
                        .append(quote(button.attr("type")))
                        .append(" name=").append(quote(button.attr("name")))
                        .append(" text=").append(quote(button.text())).append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * Dumps the non-form page summary: title, meta tags, scripts.
     */
    public void logPageSummary(String prefix, Document doc) {
        StringBuilder sb = new StringBuilder();

        sb.append(prefix).append("    < title: ")  .append(doc.title()).append("\n");
        sb.append(prefix).append("    < baseUrl: ").append(doc.baseUri()).append("\n");

        for (Element meta : doc.getElementsByTag("meta")) {
            sb.append(prefix).append("    < meta ")
                    .append(quote(meta.attr("name")))
                    .append(quote(meta.attr("http-equiv")))
                    .append(" content=").append(quote(meta.attr("content"))).append("\n");
        }

        write(sb.toString());
    }

    /*
     * Helpers
     */

    private static String quote(String value) {
        if (value == null || value.isEmpty()) return "<empty>";
        return "\"" + value + "\"";
    }

    private static String truncate(String text, int limit) {
        if (text == null || text.isEmpty()) return "";
        return text.length() > limit ? text.substring(0, limit) + "…" : text;
    }
}
