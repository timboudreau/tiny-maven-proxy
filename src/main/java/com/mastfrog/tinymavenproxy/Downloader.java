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
import com.mastfrog.acteur.util.RequestID;
import com.mastfrog.bunyan.Log;
import com.mastfrog.bunyan.Logger;
import com.mastfrog.netty.http.client.HttpClient;
import com.mastfrog.netty.http.client.ResponseFuture;
import com.mastfrog.netty.http.client.ResponseHandler;
import com.mastfrog.netty.http.client.State;
import static com.mastfrog.tinymavenproxy.TinyMavenProxy.DOWNLOAD_LOGGER;
import com.mastfrog.url.Path;
import com.mastfrog.url.URL;
import com.mastfrog.util.thread.Receiver;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.joda.time.DateTime;
import org.joda.time.Duration;

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

    @Inject
    public Downloader(HttpClient client, Config config, FileFinder finder, @Named(DOWNLOAD_LOGGER) Logger logger) {
        this.client = client;
        this.config = config;
        this.finder = finder;
        this.logger = logger;
    }

    interface DownloadReceiver {

        void receive(HttpResponseStatus status, ByteBuf buf, HttpHeaders headers);

        void failed(HttpResponseStatus status);
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
                        DateTime lm = null;
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
            ResponseFuture f = client.get()
                    .setURL(u)
                    .setTimeout(Duration.standardMinutes(2))
                    .execute(new ResponseHandlerImpl(ByteBuf.class, u, impl));
            f.onAnyEvent(new Receiver<State<?>>() {

                @Override
                public void receive(State<?> t) {
                    switch (t.stateType()) {
                        case Closed:
                            impl.onFail(u, HttpResponseStatus.FORBIDDEN);
                            break;
                        case HeadersReceived:
                            State.HeadersReceived hr = (State.HeadersReceived) t;
                            if (hr.get().getStatus().code() > 399) {
                                impl.onFail(u, hr.get().getStatus());
                            }
                    }
                }

            });
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

        void onFail(URL u, HttpResponseStatus status);
    }

    private static class ResponseHandlerImpl extends ResponseHandler<ByteBuf> {

        private final URL u;
        private final Recv recv;

        public ResponseHandlerImpl(Class<ByteBuf> type, URL u, Recv recv) {
            super(type);
            this.u = u;
            this.recv = recv;
        }

        @Override
        protected void receive(HttpResponseStatus status, HttpHeaders headers, ByteBuf obj) {
            if (status.code() >= 200 && status.code() < 300) {
                recv.onSuccess(u, obj, status, headers);
            } else {
                recv.onFail(u, status);
            }
        }

        @Override
        protected void onError(Throwable err) {
            if (err instanceof TimeoutException) {
                recv.onFail(u, HttpResponseStatus.REQUEST_TIMEOUT);
            } else {
                recv.onFail(u, HttpResponseStatus.INTERNAL_SERVER_ERROR);
            }
            err.printStackTrace();
        }

        @Override
        protected void onErrorResponse(HttpResponseStatus status, String content) {
            recv.onFail(u, status);
        }
    }
}
