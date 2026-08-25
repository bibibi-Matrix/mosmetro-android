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

package pw.thedrhax.mosmetro.activities;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import java.util.concurrent.atomic.AtomicBoolean;

import pw.thedrhax.mosmetro.R;
import pw.thedrhax.mosmetro.authenticator.Gen204;
import pw.thedrhax.mosmetro.httpclient.Client;
import pw.thedrhax.mosmetro.httpclient.InterceptedWebViewClient;
import pw.thedrhax.mosmetro.httpclient.clients.OkHttp;
import pw.thedrhax.util.Listener;
import pw.thedrhax.util.Logger;

/**
 * Visible WebView used for captive portal authorization when the
 * HTTP-based auto-login fails. The user completes the authorization
 * manually in the WebView while the Activity polls generate_204
 * every 10 seconds.
 *
 * When the Internet connection opens, reports success via setState()
 * and finishes. The system back button reports cancellation.
 */
public class PortalActivity extends Activity {
    public static final String EXTRA_URL = "url";

    public static final int STATE_RUNNING = 0;
    public static final int STATE_CONNECTED = 1;
    public static final int STATE_CANCELLED = 2;

    @Nullable public static volatile Client pending_client = null;
    @Nullable public static volatile String pending_url = null;
    public static volatile int state = STATE_RUNNING;

    private final AtomicBoolean stopped = new AtomicBoolean(false);
    private Gen204 gen_204;

    public static void setState(int new_state) {
        state = new_state;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.portal_activity);

        Client client = pending_client != null ?
                pending_client : new OkHttp(getApplicationContext());
        String url = pending_url != null ? pending_url : "about:blank";

        pending_client = null;
        pending_url = null;

        gen_204 = new Gen204(getApplicationContext(), new Listener<>(true));

        WebView webview = findViewById(R.id.webview);

        InterceptedWebViewClient webviewclient =
                new InterceptedWebViewClient(this, client, webview);
        webviewclient.setup();

        setTitle(R.string.auth_webview_page);

        startPolling();
        webview.loadUrl(url);
    }

    private void startPolling() {
        final Handler handler = new Handler(Looper.getMainLooper());

        final Runnable[] poll = new Runnable[1];
        poll[0] = () -> new Thread(() -> {
            boolean connected;

            try {
                Gen204.Gen204Result res = gen_204.check(true);
                connected = res.isConnected() && !res.isFalseNegative();
            } catch (RuntimeException ex) {
                connected = false;
            }

            final boolean result = connected;

            if (stopped.get()) return;

            handler.post(() -> {
                if (stopped.get()) return;

                if (result) {
                    Logger.log(this, "Portal | Connection opened");
                    Toast.makeText(this, R.string.done, Toast.LENGTH_SHORT).show();
                    setState(STATE_CONNECTED);
                    finish();
                    return;
                }

                handler.postDelayed(poll[0], 10000);
            });
        }).start();
    }

    @Override
    public void onBackPressed() {
        Logger.log(this, "Portal | Cancelled by user");

        setState(STATE_CANCELLED);
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        stopped.set(true);
        super.onDestroy();
    }
}
