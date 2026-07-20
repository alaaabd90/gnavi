/*
 * Copyright (C) 2018-2022 Tachibana General Laboratories, LLC
 * Copyright (C) 2018-2022 Yaroslav Pronin <proninyaroslav@mail.ru>
 * Copyright (C) 2020 8176135 <elsecaller@8176135.xyz>
 *
 * This file is part of Download Navi.
 *
 * Download Navi is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Download Navi is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Download Navi.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.tachibana.downloader.core;

import android.webkit.CookieManager;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.GeneralSecurityException;

import javax.net.ssl.HttpsURLConnection;

import static android.text.format.DateUtils.SECOND_IN_MILLIS;
import static java.net.HttpURLConnection.HTTP_MOVED_PERM;
import static java.net.HttpURLConnection.HTTP_MOVED_TEMP;
import static java.net.HttpURLConnection.HTTP_SEE_OTHER;

/*
 * The wrapper for HttpUrlConnection.
 */

public class HttpConnection implements Runnable
{
    /* Can't be more than 7 */
    private static final int MAX_REDIRECTS = 5;
    public static final int DEFAULT_TIMEOUT = (int)(20 * SECOND_IN_MILLIS);
    /*
     * gnavi: read timeout, separate from the connect timeout above.
     *
     * Upstream shared a single timeout value for both connect and read
     * (java.net.HttpURLConnection#setConnectTimeout/#setReadTimeout), which
     * is fine for a direct connection but wrong for a tunneled one: this
     * fork exists specifically to download over the Gdrive VPN, which
     * relays traffic through Google Drive API objects (poll/discover/batch
     * cycles) rather than a raw socket, so data for any given piece
     * legitimately arrives in bursts with real multi-second gaps between
     * them, even when the transfer is completely healthy. With a 20s read
     * timeout, PieceThreadImpl/DownloadThreadImpl's read() call throws
     * SocketTimeoutException the moment a normal gap runs long, which
     * HttpConnection.Listener#onIOException converts straight into a
     * retryable "Download timeout" -- tearing down and reopening the
     * connection for that piece. That is the exact TCP TIME_WAIT churn
     * (a fresh connection roughly every 5-10s) measured live on the phone
     * while a plain browser download over the same tunnel worked fine,
     * since browsers don't abort a request on a fixed per-read idle
     * timeout the way a segmented download manager does.
     *
     * 100s comfortably clears every real gap observed live against the
     * Gdrive exit (including its own worst-case ~120s single-attempt
     * ceiling for a large contended batch, muxDriveAttemptTimeout in
     * internal/gdrive/mux.go), while still being finite so a genuinely
     * dead connection is eventually noticed and retried.
     */
    public static final int DEFAULT_READ_TIMEOUT = (int)(100 * SECOND_IN_MILLIS);
    public static final int HTTP_TEMPORARY_REDIRECT = 307;
    public static final int HTTP_PERMANENT_REDIRECT = 308;

    private URL url;
    private final TLSSocketFactory socketFactory;
    private Listener listener;
    private int timeout = DEFAULT_TIMEOUT;
    private int readTimeout = DEFAULT_READ_TIMEOUT;
    private String referer;
    private boolean contentRangeLength = false;

    public interface Listener
    {
        void onConnectionCreated(HttpURLConnection conn);

        void onResponseHandle(HttpURLConnection conn, int code, String message);

        void onMoved(String newUrl, boolean permanently);

        void onIOException(IOException e);

        void onTooManyRedirects();
    }

    public HttpConnection(String url) throws MalformedURLException, GeneralSecurityException
    {
        this.url = new URL(url);
        this.socketFactory = new TLSSocketFactory();
    }

    public void setReferer(String referer) {
        this.referer = referer;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /*
     * Trying to get length via `Content-Range` if the server responds with
     * `Transfer-Encoding: chunked` and the `Content-Length` header
     * is not set as required by RFC 7230.
     */
    public void contentRangeLength(boolean contentRangeLength) {
        this.contentRangeLength = contentRangeLength;
    }

    /*
     * The non-negative number of milliseconds to wait before the connection timed out.
     * Zero is interpreted as an infinite timeout
     */

    public void setTimeout(int timeout)
    {
        this.timeout = timeout;
    }

    /*
     * The non-negative number of milliseconds to wait for the next byte of
     * data on an already-open connection before it's considered timed out.
     * Zero is interpreted as an infinite timeout. Defaults to
     * DEFAULT_READ_TIMEOUT, not setTimeout's (connect) value -- see
     * DEFAULT_READ_TIMEOUT's doc comment.
     */

    public void setReadTimeout(int readTimeout)
    {
        this.readTimeout = readTimeout;
    }

    @Override
    public void run()
    {
        var redirectionCount = 0;
        var requestContentRange = false;
        while (redirectionCount++ < MAX_REDIRECTS) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection)url.openConnection();
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(timeout);
                conn.setReadTimeout(readTimeout);
                conn.setRequestProperty("Accept-Encoding", "identity");

                // Get the cookies for the current domain.
                var cookiesString = CookieManager.getInstance().getCookie(url.toString());

                // Only add the cookies if they are not null.
                if (cookiesString != null) {
                    // Add the cookies to the header property.
                    conn.setRequestProperty("Cookie", cookiesString);
                }

                if (referer != null && !referer.isEmpty()) {
                    conn.setRequestProperty("Referer", referer);
                }
                if (requestContentRange) {
                    conn.setRequestProperty("Range", "bytes=0-");
                }

                if (conn instanceof HttpsURLConnection)
                    ((HttpsURLConnection) conn).setSSLSocketFactory(socketFactory);

                if (listener != null)
                    listener.onConnectionCreated(conn);

                int responseCode = conn.getResponseCode();
                switch (responseCode) {
                    case HTTP_MOVED_PERM:
                    case HTTP_MOVED_TEMP:
                    case HTTP_SEE_OTHER:
                    case HTTP_TEMPORARY_REDIRECT:
                    case HTTP_PERMANENT_REDIRECT:
                        String location = conn.getHeaderField("Location");
                        url = new URL(url, location);
                        if (listener != null)
                            listener.onMoved(
                                    url.toString(),
                                    responseCode == HTTP_MOVED_PERM
                                            || responseCode == HTTP_PERMANENT_REDIRECT
                            );
                        continue;
                    default:
                        if (requestContentRange) {
                            if (responseCode != HttpURLConnection.HTTP_OK &&
                                responseCode != HttpURLConnection.HTTP_PARTIAL) {
                                // Try without range
                                requestContentRange = false;
                                continue;
                            }
                        } else if (contentRangeLength) {
                            var noContentLength = conn.getHeaderField("content-length") == null;
                            var chunked = "chunked".equals(conn.getHeaderField("Transfer-Encoding"));
                            if (chunked && noContentLength) {
                                // Server responds with `Transfer-Encoding: chunked` and
                                // the `Content-Length` header is not set as required by RFC 7230.
                                // Trying to get length via `Content-Range`.
                                requestContentRange = true;
                                continue;
                            }
                        }
                        if (listener != null)
                            listener.onResponseHandle(conn, responseCode, conn.getResponseMessage());
                        return;
                }

            } catch (IOException e) {
                if (listener != null)
                    listener.onIOException(e);
                return;

            } finally {
                if (conn != null)
                    conn.disconnect();
            }
        }

        if (listener != null)
            listener.onTooManyRedirects();
    }
}
