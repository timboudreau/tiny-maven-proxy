/*
 * The MIT License
 *
 * Copyright 2015 Tim Boudreau.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.mastfrog.tinymavenproxy;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.io.File;

/**
 *
 * @author Tim Boudreau
 */
class DownloadResult {

    final HttpResponseStatus status;
    ByteBuf buf;
    HttpHeaders headers;
    File file;

    DownloadResult(HttpResponseStatus status, File file, HttpHeaders headers) {
        this.file = file;
        this.status = status;
        this.headers = headers;
    }

    DownloadResult(HttpResponseStatus status, ByteBuf message) {
        this(status, message, null);
    }

    DownloadResult(HttpResponseStatus status, ByteBuf buf, HttpHeaders headers) {
        // This method is currently unused, but if we enhance the server to accept
        // uploads, we will likely need code a lot like this
        this.status = status;
        this.buf = buf;
        this.headers = headers;
    }

    DownloadResult(HttpResponseStatus status) {
        this.status = status;
    }

    boolean isFile() {
        return this.file != null;
    }

    boolean isFail() {
        return status.code() > 399 || (buf == null && file == null);
    }

}
