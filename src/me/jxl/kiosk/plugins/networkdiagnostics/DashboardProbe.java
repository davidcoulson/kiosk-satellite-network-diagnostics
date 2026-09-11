// SPDX-License-Identifier: Apache-2.0
package me.jxl.kiosk.plugins.networkdiagnostics;

import java.net.HttpURLConnection;
import java.net.URL;

/**
 * How long a plain HTTP(S) GET to a configured URL takes to receive
 * response headers — a rough stand-in for "how responsive is the
 * dashboard right now" when the actual dashboard URL isn't something
 * this plugin can discover on its own (the SDK exposes no read command
 * for Kiosk Satellite's configured Home Assistant/dashboard URL, so the
 * user supplies one as a setting instead — see the manifest).
 *
 * Deliberately not a real page-load timer: it measures time to receive
 * HTTP response headers, not full page render, script execution, or
 * WebSocket handshake — those would need actual browser/WebView
 * instrumentation this plugin has no access to (see the ported LED
 * plugin's own WebView-graphs SDK gap). The body is never read (the
 * connection is dropped the moment headers arrive) — this only measures
 * network+server responsiveness, not payload size.
 */
final class DashboardProbe {
    private DashboardProbe() {}

    static final int CONNECT_TIMEOUT_MS = 5000;
    static final int READ_TIMEOUT_MS = 5000;

    static final class Result {
        final boolean ok;
        final long elapsedMs;
        final int httpStatus; // -1 when the request never completed
        final String error;   // null on success

        private Result(boolean ok, long elapsedMs, int httpStatus, String error) {
            this.ok = ok;
            this.elapsedMs = elapsedMs;
            this.httpStatus = httpStatus;
            this.error = error;
        }

        static Result success(long elapsedMs, int httpStatus) {
            return new Result(true, elapsedMs, httpStatus, null);
        }

        static Result failure(String error) {
            return new Result(false, -1L, -1, error);
        }
    }

    static Result probe(String url) {
        HttpURLConnection conn = null;
        try {
            long start = System.nanoTime();
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(true);
            int status = conn.getResponseCode(); // forces the request through to response headers
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
            return Result.success(elapsedMs, status);
        } catch (Exception e) {
            return Result.failure(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        } finally {
            if (conn != null) {
                // The body is never read — only headers matter here — so disconnect
                // immediately rather than paying for a page download every probe tick.
                conn.disconnect();
            }
        }
    }
}
