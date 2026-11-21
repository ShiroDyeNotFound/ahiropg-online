package com.ahiropg.online.http;

import java.io.IOException;
import java.net.URI;

/**
 * One blocking HTTP GET, optionally through a proxy.
 *
 * <p>Blocking on purpose: callers run transports on their own executor, and keeping
 * the interface synchronous means the SOCKS5 transport does not have to pretend to be
 * asynchronous.</p>
 */
public interface Transport {

    /** The parts of a response the plugin cares about. */
    record Response(int status, String body) {
    }

    Response get(URI uri) throws IOException;
}
