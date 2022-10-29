/*
 * The MIT License
 *
 * Copyright 2022 Mastfrog Technologies.
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
import com.google.inject.name.Named;
import com.mastfrog.acteur.errors.ResponseException;
import com.mastfrog.acteur.headers.Headers;
import com.mastfrog.acteur.server.ServerModule;
import com.mastfrog.acteur.spi.ApplicationControl;
import com.mastfrog.acteur.util.RequestID;
import com.mastfrog.bunyan.java.v2.Log;
import com.mastfrog.bunyan.java.v2.Logs;
import com.mastfrog.function.state.Int;
import com.mastfrog.tinymavenproxy.TempFiles.TempFile;
import static com.mastfrog.tinymavenproxy.TinyMavenProxy.DOWNLOAD_LOGGER;
import com.mastfrog.url.Path;
import com.mastfrog.url.URL;
import com.mastfrog.util.libversion.VersionInfo;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponseStatus;
import static io.netty.handler.codec.http.HttpResponseStatus.GONE;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodySubscriber;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author Tim Boudreau
 */
public class DownloaderV2A {

    private final HttpClient client;
    private final Config config;
    private final FileFinder finder;
    private final Cache<Path, Path> failedURLs;
    private final Logs logger;
    private final ApplicationControl control;

    static final AtomicLong counter = new AtomicLong();
    private final TempFiles tempFiles;
    private final String runId;
    private final String userAgent;
    private final ExecutorService pool;

    @Inject
    public DownloaderV2A(HttpClient client, Config config, FileFinder finder,
            @Named(DOWNLOAD_LOGGER) Logs logger, ApplicationControl control,
            @Named("runId") String runId, TempFiles tempFiles, VersionInfo ver,
            @Named(ServerModule.BACKGROUND_THREAD_POOL_NAME) ExecutorService pool) {
        this.pool = pool;
        failedURLs = CacheBuilder.newBuilder().expireAfterWrite(config.failedPathCacheMinutes, TimeUnit.MINUTES).build();
        this.client = client;
        this.config = config;
        this.finder = finder;
        this.logger = logger.child("rid", runId);
        this.control = control;
        this.runId = runId;
        this.tempFiles = tempFiles;
        userAgent = "tmpx-" + ver.version;
    }

    String nextDownloadId() {
        return runId + Long.toString(counter.getAndIncrement(), 36);
    }

    public boolean isFailedPath(Path path) {
        return failedURLs.getIfPresent(path) != null;
    }

    CompletableFuture<TempFile> download(Path path, RequestID rid, DownloadReceiver recv) throws URISyntaxException {
        CompletableFuture<TempFile> tf = download(path, rid);
        Logs requestLog = logger.child("download", rid);
        tf.whenComplete((file, thrown) -> {
            if (thrown != null) {
                failedURLs.put(path, path);
//                System.out.println("DO FAIL FOR " + thrown + " " + path);
                recv.failed(GONE, thrown.getMessage());
            } else if (file != null) {
                HttpResponseStatus status = file.info().map(info -> {
                    return HttpResponseStatus.valueOf(info.statusCode());
                }).orElse(OK);
                HttpHeaders nettyHeaders = new DefaultHttpHeaders(false);
                file.info().ifPresent(info -> {
                    info.headers().map().forEach(nettyHeaders::add);
                });
                recv.receive(status, file.path().toFile(), nettyHeaders);
            }
        });
        return tf;
    }

    public CompletableFuture<TempFile> download(Path path, RequestID rid) throws URISyntaxException {
        Collection<URL> urls = config.withPath(path);
        List<CompletableFuture<TempFile>> futures = new ArrayList<>(urls.size());
        Int remainder = Int.createAtomic();
        remainder.set(urls.size());
        CompletableFuture<TempFile> result = new CompletableFuture<>();
        Logs requestLog = logger.child("req", rid);

        final Object lock = new Object();

        Consumer<CompletableFuture<TempFile>> cancelOthers = fut -> {
            synchronized (lock) {
                List<CompletableFuture<TempFile>> copy = new ArrayList<>(futures);
                futures.clear();
                for (CompletableFuture<TempFile> f : copy) {
                    if (f != fut) {
                        f.cancel(false);
                    }
                }
            }
        };

        result.whenCompleteAsync((file, thrown) -> {
            if (thrown instanceof CancellationException) {
                requestLog.info("request-cancelled-killing-downloads")
                        .add("tasks", futures.size())
                        .add("alive", remainder.get()).close();
                cancelOthers.accept(null);
            } else if (thrown != null) {
                Log l = requestLog.warn("all-failed");
                if (!(thrown instanceof ResponseException)) {
                    l.add(thrown).close();
                }
            }
        });

        for (URL u : urls) {
            String dlId = nextDownloadId();
            Logs perUrl = requestLog.child("dl", dlId)
                    .child("url", u.toString());
            HttpRequest req
                    = HttpRequest.newBuilder(u.toURI())
                            .header("User-Agent", userAgent)
                            .timeout(Duration.ofMinutes(2))
                            .GET()
                            .build();
            CompletableFuture<TempFile> fut = new CompletableFuture<>();
            fut.whenComplete((file, thrown) -> {
//                synchronized (lock) {
                remainder.decrement();
                int remaining = remainder.getAsInt();
                if (remaining < 0) {
                    throw new IllegalStateException("" + remaining);
                }
                try (Log lr = perUrl.debug("completed")) {
                    if (result.isDone()) {
                        if (file != null) {
                            file.close();
                        }
                        return;
                    }
                    if (file != null) {
                        cancelOthers.accept(fut);
                        if (fut.isCancelled()) {
                            return;
                        }
                        lr.add("file", file.path().toString());
                        file.info().ifPresent(info -> {
                            lr.add("status", info.statusCode());
                        });
                        file.lastModified().ifPresent(lm -> lr.add("lastModified", lm));
                        ZonedDateTime zdt = file.lastModified().map(ins -> ZonedDateTime.ofInstant(ins, ZoneId.of("Z"))).orElse(ZonedDateTime.now());
                        File dest = finder.put(path, file);
                        lr.add("saved", dest.toString());
                        result.complete(file);
                    } else if (thrown instanceof CancellationException) {
                        lr.add("cancelled");
                        if (remaining == 0) {
//                                System.out.println("REMAINING 0 COMPLETE 1 " + path);
                            result.completeExceptionally(new ResponseException(GONE, "No result " + path));
                        }
                    } else if (thrown != null) {
                        if (remaining == 0) {
//                                System.out.println("REMAINING 0 COMPLETE 2 " + path);
                            result.completeExceptionally(thrown);
                        }
                        lr.add(thrown);
                    } else {
                        lr.add("state", "No info.");
                        if (remaining == 0) {
//                                System.out.println("REMAINING 0 COMPLETE 3 " + path);
                            result.completeExceptionally(new ResponseException(GONE, "No result " + path));
                        }
                    }
                } catch (IOException ex) {
                    result.completeExceptionally(ex);
                }
//                }
            });
//            fut.whenComplete(onComplete);
            BH bh = new BH(dlId, u, fut, perUrl);
            futures.add(fut);
            client.sendAsync(req, bh);
        }
        return result;
    }

    static void sleep() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException ex) {
            ex.printStackTrace();
        }
    }

    private final static Noop<TempFile> NO_OP = new Noop<>();

    private static final String LAST_MODIFIED = Headers.LAST_MODIFIED.name().toString();

    class BH implements BodyHandler<TempFile> {

        private final CompletableFuture<TempFile> result;
        private final String downloadId;
        private final URL url;
        private final Logs logs;

        public BH(String downloadId, URL url, CompletableFuture<TempFile> result, Logs logs) {
            this.downloadId = downloadId;
            this.url = url;
            this.result = result;
            this.logs = logs;
        }

        @Override
        public BodySubscriber<TempFile> apply(HttpResponse.ResponseInfo info) {
            if (info.statusCode() > 399) {
                logs.warn("request-failed")
                        .add("status", info.statusCode()).close();
//                        .add("headers", info.headers().map()).close();
                result.cancel(true);
                return NO_OP;
            } else {
                logs.info("potential-success")
                        .add("status", info.statusCode()).close();
//                        .add("headers", info.headers().map()).close();
                TempFile tempFile = tempFiles.tempFile(downloadId);
                info.headers().firstValue(LAST_MODIFIED)
                        .map(Headers.LAST_MODIFIED)
                        .ifPresent(tempFile::setLastModified);
                return new BS(tempFile.withResponseInfo(info), result, logs);
            }
        }
    }

    static class BS implements BodySubscriber<TempFile> {

        private final TempFile file;
        private Flow.Subscription subscription;
        private final CompletableFuture<TempFile> result;
        private final Logs logs;

        BS(TempFile file, CompletableFuture<TempFile> result, Logs logs) {
            this.file = file;
            this.result = result;
            this.logs = logs;
        }

        synchronized void cancel() {
            logs.info("cancelled").close();
            try {
                file.close();
                result.cancel(true);
            } catch (Exception ex) {
                result.completeExceptionally(ex);
            }
        }

        @Override
        public CompletionStage<TempFile> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            if (result.isDone()) {
                return;
            }
            if (subscription == null) {
                logs.warn("null subscription").close();
                return;
            }
            synchronized (this) {
                this.subscription = subscription;
            }
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> item) {
            if (result.isDone()) {
                subscription.cancel();
                return;
            }
            try (Log log = logs.info("onNext")) {
                log.add("buffers", item.size());
                for (int i = 0; i < item.size(); i++) {
                    ByteBuffer b = item.get(i);
                    log.add("buf_" + i, b.remaining());
                }
                for (ByteBuffer b : item) {
                    try {
                        file.append(b);
                    } catch (IOException ex) {
                        log.add(ex);
                        onError(ex);
                        return;
                    }
                }
            }
        }

        @Override
        public void onError(Throwable throwable) {
            try {
                file.close();
            } catch (Exception ex) {
                throwable.addSuppressed(ex);
            }
            logs.error("onError").add(throwable).close();
            result.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            result.complete(file);
            if (result.isDone()) {
                logs.warn("done-but-already-complete").close();
            }
        }
    }
}
