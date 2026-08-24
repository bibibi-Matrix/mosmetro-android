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

import java.util.HashMap;

/**
 * Formerly collected anonymous connection statistics and sent them to
 * the developer's backend. All telemetry has been removed; the class
 * is kept as a no-op to preserve the Provider lifecycle contract.
 */
public class ProviderMetrics {
    private final Provider p;

    ProviderMetrics(Provider provider) {
        this.p = provider;
    }

    public ProviderMetrics start() {
        return this;
    }

    @SuppressWarnings("unchecked")
    public boolean end(HashMap<String, Object> vars) {
        // Telemetry disabled: nothing to report
        return false;
    }
}
