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

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import com.mastfrog.acteur.headers.Headers;
import com.mastfrog.acteur.spi.ApplicationControl;
import com.mastfrog.acteur.util.RequestID;
import com.mastfrog.bunyan.Log;
import com.mastfrog.bunyan.Logger;
import com.mastfrog.netty.http.client.HttpClient;
import com.mastfrog.netty.http.client.ResponseFuture;
import com.mastfrog.netty.http.client.State;
import com.mastfrog.netty.http.client.StateType;
import static com.mastfrog.tinymavenproxy.TinyMavenProxy.DOWNLOAD_LOGGER;
import com.mastfrog.url.Path;
import com.mastfrog.url.URL;
import com.mastfrog.util.GUIDFactory;
import com.mastfrog.util.thread.Receiver;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import static io.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import io.netty.handler.codec.http.LastHttpContent;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 *
 * @author Tim Boudreau
 */
@Singleton
public class Downloader {

    private final HttpClient client;
    private final Config config;
    private final FileFinder finder;
    private final Cache<Path, Path> failedURLs = CacheBuilder.newBuilder().expireAfterWrite(5, TimeUnit.MINUTES).build();
    private final Logger logger;
    private final ApplicationControl control;

    @Inject
    public Downloader(HttpClient client, Config config, FileFinder finder, @Named(DOWNLOAD_LOGGER) Logger logger, ApplicationControl control) {
        this.client = client;
        this.config = config;
        this.finder = finder;
        this.logger = logger;
        this.control = control;
    }

    interface DownloadReceiver {

        void receive(HttpResponseStatus status, ByteBuf buf, HttpHeaders headers);

        void receive(HttpResponseStatus status, File file, HttpHeaders headers);

        void failed(HttpResponseStatus status);

        void failed(HttpResponseStatus status, String msg);
    }

    public boolean isFailedPath(Path path) {
        return failedURLs.getIfPresent(path) != null;
    }

    public ChannelFutureListener download(final Path path, final RequestID id, final DownloadReceiver receiver) {
        Collection<URL> urls = config.withPath(path);
        final Map<URL, ResponseFuture> futures = new ConcurrentHashMap<>();
        int size = urls.size();
        final AtomicInteger remaining = new AtomicInteger(size);
        final AtomicBoolean success = new AtomicBoolean();
        class RecvImpl implements Recv {

            @Override
            public void onSuccess(URL u, File file, HttpResponseStatus status, HttpHeaders headers) {
                if (success.compareAndSet(false, true)) {
                    try (Log<?> log = logger.info("download")) {
                        remaining.set(0);
                        for (Map.Entry<URL, ResponseFuture> e : futures.entrySet()) {
                            if (!u.equals(e.getKey())) {
                                e.getValue().cancel();
                            }
                        }
                        futures.clear();
                        String lastModified = headers.get(Headers.LAST_MODIFIED.name());
                        ZonedDateTime lm = null;
                        if (lastModified != null) {
                            lm = Headers.LAST_MODIFIED.toValue(lastModified);
                        }
                        File target = null;
                        try {
                            target = finder.put(path, file, lm);
                            log.add("from", u).add("size", file.length()).add("status", status.code())
                                    .addIfNotNull("server", headers.get("Server"))
                                    .add("id", id);
                        } catch (Exception e) {
                            receiver.failed(INTERNAL_SERVER_ERROR, e.getMessage() == null
                                    ? "Proxy failed to download item from any remote server" : e.getMessage());
                            return;
                        }
                        receiver.receive(status, target, headers);
                    } catch (Exception ex) {
                        control.internalOnError(ex);
                    }
                }
            }

            @Override
            public void onSuccess(URL u, ByteBuf buf, HttpResponseStatus status, HttpHeaders headers) {
                if (success.compareAndSet(false, true)) {
                    try (Log<?> log = logger.info("download")) {
                        remaining.set(0);
                        for (Map.Entry<URL, ResponseFuture> e : futures.entrySet()) {
                            if (!u.equals(e.getKey())) {
                                e.getValue().cancel();
                            }
                        }
                        futures.clear();
                        String lastModified = headers.get(Headers.LAST_MODIFIED.name());
                        ZonedDateTime lm = null;
                        if (lastModified != null) {
                            lm = Headers.LAST_MODIFIED.toValue(lastModified);
                        }
                        finder.put(path, buf, lm);
                        log.add("from", u).add("size", buf.readableBytes()).add("status", status.code())
                                .addIfNotNull("server", headers.get("Server"))
                                .add("id", id);
                        receiver.receive(status, buf, headers);
                    }
                }
            }

            final AtomicBoolean failed = new AtomicBoolean();

            @Override
            public void onFail(URL u, HttpResponseStatus status) {
                if (success.get() || !failed.compareAndSet(false, true)) {
                    return;
                }
                int remain = remaining.decrementAndGet();
                ResponseFuture f = futures.get(u);
                futures.remove(u);
                if (f != null) {
                    f.cancel();
                }
                if (remain == 0) {
                    try (Log<?> log = logger.info("downloadFailed")) {
                        log.add("path", path).add("status", status).add("id", id);
                        receiver.failed(status == null ? HttpResponseStatus.NOT_FOUND : status);
                        failedURLs.put(path, path);
                    }
                }
            }
        }
        for (final URL u : urls) {
            final RecvImpl impl = new RecvImpl();
            Receiver<State<?>> im = new RespHandler(u, impl);
            ResponseFuture f = client.get()
                    .setURL(u)
                    .setTimeout(Duration.ofMinutes(2))
                    .onEvent(im)
                    //                    .execute(new ResponseHandlerImpl(ByteBuf.class, u, impl));
                    .dontAggregateResponse()
                    .onEvent(new Receiver<State<?>>() {

                        @Override
                        public void receive(State<?> t) {
                            switch (t.stateType()) {
                                case Closed:
                                    impl.onFail(u, HttpResponseStatus.FORBIDDEN);
                                    break;
                                case HeadersReceived:
                                    State.HeadersReceived hr = (State.HeadersReceived) t;
                                    if (hr.get().status().code() > 399) {
                                        impl.onFail(u, hr.get().status());
                                    }
                            }
                        }

                    })
                    .execute();

            futures.put(u, f);
        }
        return new ChannelFutureListener() {

            @Override
            public void operationComplete(ChannelFuture f) throws Exception {
                if (remaining.get() > 0) {
                    for (ResponseFuture fu : futures.values()) {
                        fu.cancel();
                    }
                }
            }
        };
    }

    interface Recv {

        void onSuccess(URL u, ByteBuf buf, HttpResponseStatus status, HttpHeaders headers);

        void onSuccess(URL u, File file, HttpResponseStatus status, HttpHeaders headers);

        void onFail(URL u, HttpResponseStatus status);
    }

    private class RespHandler extends Receiver<State<?>> {

        private File tempfile;
        private FileChannel out;
        private FileOutputStream fos;
        private final URL u;
        private final Recv recv;
        private HttpHeaders headers;

        RespHandler(URL u, Recv recv) {
            this.u = u;
            this.recv = recv;
        }

        private void createTempfile() throws IOException {
            File tmp = new File(System.getProperty("java.io.tmpdir"));
            tempfile = new File(tmp, "maven-dl-" + GUIDFactory.get().newGUID(1, 4) + Long.toString(System.currentTimeMillis()));
            if (!tempfile.createNewFile()) {
                throw new IOException("Could not create " + tempfile);
            }
            fos = new FileOutputStream(tempfile);
            out = fos.getChannel();
        }

        boolean done;

        @Override
        public synchronized void receive(State<?> state) {
            if (done) {
                return;
            }
            Object object = state.get();
            try {
                if (object instanceof FullHttpResponse) {
                    FullHttpResponse full = (FullHttpResponse) object;
                    if (OK.equals(full.status())) {
                        ByteBuffer buffer = full.content().nioBuffer();
                        out.write(buffer);
                        out.close();
                        done = true;
                        recv.onSuccess(u, tempfile, full.status(), headers);
                    } else {
                        close();
                        done = true;
                        recv.onFail(u, full.status());
                    }
                } else if (object instanceof HttpResponse) {
                    HttpResponse resp = (HttpResponse) object;
                    if (OK.equals(resp.status()) || HttpResponseStatus.NON_AUTHORITATIVE_INFORMATION.equals(resp.status())) {
                        createTempfile();
                        headers = resp.headers();
                    } else {
                        switch (resp.status().code()) {
                            case 300:
                            case 301:
                            case 302:
                            case 303:
                            case 305:
                            case 307:
                                // redirect, do nothing
                                return;
                            default:
                                close();
                                recv.onFail(u, resp.status());
                                done = true;
                        }
                    }
                } else if (tempfile != null && object instanceof HttpContent) {
                    HttpContent content = (HttpContent) object;
                    if (content.content().readableBytes() > 0) {
                        ByteBuffer buffer = content.content().nioBuffer();
                        out.write(buffer);
                        content.content().discardReadBytes();
                    }
                    if (content instanceof LastHttpContent) {
                        File file = close();
                        try {
                            recv.onSuccess(u, file, OK, headers);
                            done = true;
                        } finally {
                            cleanup();
                        }
                    }
                } else if (state.get() instanceof State.Error) {
                    State.Error err = (State.Error) state.get();
                    recv.onFail(u, HttpResponseStatus.INTERNAL_SERVER_ERROR);
                    try (Log lg = logger.error(err.get() == null ? "Error" : err.get())) {
                        lg.add("url", u.toString());
                    }
                } else if (state.stateType() == StateType.Closed) {
                    recv.onFail(u, HttpResponseStatus.FORBIDDEN);
                    done = true;
                }
            } catch (Exception ex) {
                control.internalOnError(ex);
                recv.onFail(u, HttpResponseStatus.INTERNAL_SERVER_ERROR);
            }
        }

        synchronized void cleanup() throws IOException {
            if (tempfile != null) {
                out.close();
                fos.close();
                out = null;
                if (tempfile.exists()) {
                    if (!tempfile.delete()) {
                        throw new IOException("Could not delete " + tempfile);
                    }
                }
            }
        }

        synchronized File close() throws IOException {
            File old = tempfile;
            if (old != null) {
                try {
                    out.close();
                    fos.close();
                } finally {
                    fos = null;
                    tempfile = null;
                    out = null;
                }
            }
            return old;
        }

        @Override
        public synchronized void onFail() {
            try {
                close();
            } catch (IOException ex) {
                control.internalOnError(ex);
            }
        }

        @Override
        public synchronized <E extends Throwable> void onFail(E exception) throws E {
            try {
                close();
            } catch (IOException ex) {
                control.internalOnError(ex);
            }
            control.internalOnError(exception);
        }
    }
}
