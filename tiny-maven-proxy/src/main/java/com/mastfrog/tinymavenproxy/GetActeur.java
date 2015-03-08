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

import com.mastfrog.acteur.io.FileWriter;
import com.google.common.net.MediaType;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import com.mastfrog.acteur.Acteur;
import com.mastfrog.acteur.Closables;
import com.mastfrog.acteur.Event;
import com.mastfrog.acteur.HttpEvent;
import com.mastfrog.acteur.ResponseWriter;
import com.mastfrog.acteur.annotations.Concluders;
import com.mastfrog.acteur.annotations.HttpCall;
import com.mastfrog.acteur.errors.Err;
import com.mastfrog.acteur.headers.Headers;
import static com.mastfrog.acteur.headers.Headers.LAST_MODIFIED;
import static com.mastfrog.acteur.headers.Method.GET;
import static com.mastfrog.acteur.headers.Method.HEAD;
import com.mastfrog.acteur.preconditions.Description;
import com.mastfrog.acteur.preconditions.Methods;
import com.mastfrog.acteur.preconditions.PathRegex;
import com.mastfrog.acteur.util.RequestID;
import com.mastfrog.acteurbase.Deferral;
import com.mastfrog.acteurbase.Deferral.Resumer;
import com.mastfrog.bunyan.Log;
import com.mastfrog.bunyan.Logger;
import com.mastfrog.settings.Settings;
import com.mastfrog.tinymavenproxy.Downloader.DownloadReceiver;
import com.mastfrog.tinymavenproxy.GetActeur.ConcludeHttpRequest;
import static com.mastfrog.tinymavenproxy.TinyMavenProxy.ACCESS_LOGGER;
import static com.mastfrog.tinymavenproxy.TinyMavenProxy.DEFAULT_DOWNLOAD_CHUNK_SIZE;
import static com.mastfrog.tinymavenproxy.TinyMavenProxy.SETTINGS_KEY_DOWNLOAD_CHUNK_SIZE;
import com.mastfrog.url.Path;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.io.File;
import java.io.FileNotFoundException;
import org.joda.time.DateTime;

/**
 *
 * @author Tim Boudreau
 */
@HttpCall(order = Integer.MAX_VALUE - 1)
@Concluders(ConcludeHttpRequest.class)
@Methods({GET, HEAD})
@Description("Download maven artifacts, fetching them from remote repositories "
        + "and caching the results if necessary")
public class GetActeur extends Acteur {

    @Inject
    GetActeur(HttpEvent req, Deferral def, Config config, FileFinder finder, Closables clos, Downloader dl, @Named(ACCESS_LOGGER) Logger accessLog, RequestID id) throws FileNotFoundException {
        if ("true".equals(req.getParameter("browse")) || "true".equals(req.getParameter("index"))) {
            reject();
            return;
        }
        Path path = req.getPath();
        if (path.size() == 0) {
            reject();
            return;
        }
        if (path.toString().contains("..")) {
            setState(new RespondWith(Err.badRequest("Relative paths not allowed")));
            return;
        }
        File file = finder.find(path);
        if (file != null) {
            try (Log<?> log = accessLog.info("fetch")) {
                log.add("path", path).add("id", id).add("cached", true);
                add(Headers.LAST_MODIFIED, new DateTime(file.lastModified()));
                setState(new RespondWith(HttpResponseStatus.OK));
                add(Headers.CONTENT_TYPE, findMimeType(path));
                if (req.getMethod() != HEAD) {
                    setResponseBodyWriter(new FileWriter(file, clos));
                }
            }
        } else {
            if (dl.isFailedPath(path)) {
                setState(new RespondWith(404));
                setResponseBodyWriter(ChannelFutureListener.CLOSE);
                return;
            }
            setChunked(true);
            final Resumer r = def.defer();
            ChannelFutureListener l = dl.download(path, id, new DownloadReceiverImpl(r));
            req.getChannel().closeFuture().addListener(l);
            next();
        }
    }

    private static MediaType findMimeType(Path path) {
        if (path.size() == 0) {
            return MediaType.ANY_APPLICATION_TYPE;
        }
        String file = path.getLastElement().toString();
        int ix = file.lastIndexOf(".");
        if (ix < 0) {
            return MediaType.ANY_APPLICATION_TYPE;
        }
        String ext = file.substring(ix + 1);
        switch (ext) {
            case "html":
                return MediaType.HTML_UTF_8;
            case "jar":
                return MediaType.parse("application/java-archive");
            case "xml":
            case "pom":
                return MediaType.XML_UTF_8;
            case "sha1":
            default:
                return MediaType.PLAIN_TEXT_UTF_8;
        }
    }

    static class ConcludeHttpRequest extends Acteur {

        @Inject
        ConcludeHttpRequest(HttpEvent evt, DownloadResult res, @Named(ACCESS_LOGGER) Logger accessLog, RequestID id, Closables clos, Settings settings) throws FileNotFoundException {
            int bufferSize = settings.getInt(SETTINGS_KEY_DOWNLOAD_CHUNK_SIZE, DEFAULT_DOWNLOAD_CHUNK_SIZE);
            setChunked(true);
            if (!res.isFail()) {
                try (Log<?> log = accessLog.info("fetch")) {
                    ok();
                    add(Headers.CONTENT_TYPE, findMimeType(evt.getPath()));
                    if (res.headers.contains(LAST_MODIFIED.name())) {
                        add(LAST_MODIFIED, LAST_MODIFIED.toValue(res.headers.get(LAST_MODIFIED.name())));
                    }
                    if (evt.getMethod() != HEAD) {
                        if (res.isFile()) {
                            setResponseBodyWriter(new FileWriter(res.file, clos, bufferSize));
                        } else {
                            setResponseWriter(new Responder(res.buf));
                        }
                    }
                    log.add("path", evt.getPath()).add("id", id).add("cached", false);
                }
            } else {
                notFound();
            }
        }
    }

    static class Responder extends ResponseWriter {

        private final ByteBuf buf;

        public Responder(ByteBuf buf) {
            this.buf = buf;
        }

        @Override
        public Status write(Event<?> evt, Output out, int iteration) throws Exception {
            out.write(buf);
            return Status.DONE;
        }
    }

    private static class DownloadReceiverImpl implements DownloadReceiver {

        private final Resumer r;

        public DownloadReceiverImpl(Resumer r) {
            this.r = r;
        }

        @Override
        public void receive(HttpResponseStatus status, ByteBuf buf, HttpHeaders headers) {
            r.resume(new DownloadResult(status, buf, headers));
        }

        @Override
        public void failed(final HttpResponseStatus status) {
            r.resume(new DownloadResult(status));
        }

        @Override
        public void receive(HttpResponseStatus status, File file, HttpHeaders headers) {
            r.resume(new DownloadResult(status, file, headers));
        }
    }
}
