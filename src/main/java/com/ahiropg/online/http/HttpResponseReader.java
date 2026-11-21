package com.ahiropg.online.http;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Just enough HTTP/1.1 response parsing for a small JSON GET: status line, the
 * headers we need, and a body that is either length delimited, chunked, or runs
 * until the connection closes.
 *
 * <p>Used by {@link Socks5Transport}, which owns the socket itself and therefore
 * cannot use the JDK's HTTP clients.</p>
 */
final class HttpResponseReader {

    private HttpResponseReader() {
    }

    static Transport.Response read(InputStream raw) throws IOException {
        InputStream in = raw instanceof BufferedInputStream ? raw : new BufferedInputStream(raw);

        String statusLine = readLine(in);
        if (statusLine == null || statusLine.isEmpty()) {
            throw new IOException("the server closed the connection without sending a response");
        }
        int status = parseStatus(statusLine);

        long contentLength = -1;
        boolean chunked = false;
        String header;
        while ((header = readLine(in)) != null && !header.isEmpty()) {
            int colon = header.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String name = header.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = header.substring(colon + 1).trim();
            if (name.equals("content-length")) {
                try {
                    contentLength = Long.parseLong(value);
                } catch (NumberFormatException ignored) {
                    // Leave it unset and fall back to reading until EOF.
                }
            } else if (name.equals("transfer-encoding") && value.toLowerCase(Locale.ROOT).contains("chunked")) {
                chunked = true;
            }
        }

        byte[] body;
        if (chunked) {
            body = readChunked(in);
        } else if (contentLength >= 0) {
            body = readFixed(in, (int) Math.min(contentLength, Integer.MAX_VALUE));
        } else {
            body = in.readAllBytes();
        }
        return new Transport.Response(status, new String(body, StandardCharsets.UTF_8));
    }

    /** Reads one CRLF terminated header line; null at end of stream. */
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(64);
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                String line = buffer.toString(StandardCharsets.ISO_8859_1);
                return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
            }
            buffer.write(b);
        }
        return buffer.size() == 0 ? null : buffer.toString(StandardCharsets.ISO_8859_1);
    }

    private static int parseStatus(String statusLine) throws IOException {
        // e.g. "HTTP/1.1 204 No Content"
        String[] parts = statusLine.split(" ", 3);
        if (parts.length < 2) {
            throw new IOException("malformed status line: " + statusLine);
        }
        try {
            return Integer.parseInt(parts[1].trim());
        } catch (NumberFormatException e) {
            throw new IOException("malformed status line: " + statusLine, e);
        }
    }

    private static byte[] readFixed(InputStream in, int length) throws IOException {
        byte[] data = new byte[length];
        int read = 0;
        while (read < length) {
            int n = in.read(data, read, length - read);
            if (n < 0) {
                break;
            }
            read += n;
        }
        return read == length ? data : java.util.Arrays.copyOf(data, read);
    }

    private static byte[] readChunked(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (true) {
            String sizeLine = readLine(in);
            if (sizeLine == null) {
                break;
            }
            int semicolon = sizeLine.indexOf(';');
            String hex = (semicolon >= 0 ? sizeLine.substring(0, semicolon) : sizeLine).trim();
            if (hex.isEmpty()) {
                continue;
            }
            int size;
            try {
                size = Integer.parseInt(hex, 16);
            } catch (NumberFormatException e) {
                throw new IOException("malformed chunk size: " + sizeLine, e);
            }
            if (size == 0) {
                // Discard any trailers.
                String trailer;
                while ((trailer = readLine(in)) != null && !trailer.isEmpty()) {
                    // ignored
                }
                break;
            }
            out.write(readFixed(in, size));
            readLine(in); // trailing CRLF after the chunk data
        }
        return out.toByteArray();
    }
}
