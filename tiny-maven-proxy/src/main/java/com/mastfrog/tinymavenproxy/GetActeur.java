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

import com.google.common.net.MediaType;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import com.mastfrog.acteur.Acteur;
import com.mastfrog.acteur.Closables;
import com.mastfrog.acteur.HttpEvent;
import com.mastfrog.acteur.Response;
import com.mastfrog.acteur.annotations.Concluders;
import com.mastfrog.acteur.annotations.HttpCall;
import com.mastfrog.acteur.errors.Err;
import com.mastfrog.acteur.headers.Headers;
import static com.mastfrog.acteur.headers.Headers.ACCEPT_ENCODING;
import static com.mastfrog.acteur.headers.Headers.LAST_MODIFIED;
import static com.mastfrog.acteur.headers.Method.GET;
import static com.mastfrog.acteur.headers.Method.HEAD;
import com.mastfrog.acteur.preconditions.Description;
import com.mastfrog.acteur.preconditions.Methods;
import static com.mastfrog.acteur.server.ServerModule.X_INTERNAL_COMPRESS_HEADER;
import com.mastfrog.acteur.spi.ApplicationControl;
import com.mastfrog.acteur.util.CacheControl;
import com.mastfrog.acteur.util.RequestID;
import com.mastfrog.acteurbase.Deferral;
import com.mastfrog.acteurbase.Deferral.Resumer;
import com.mastfrog.bunyan.Log;
import com.mastfrog.bunyan.Logger;
import com.mastfrog.tinymavenproxy.Downloader.DownloadReceiver;
import com.mastfrog.tinymavenproxy.GetActeur.ConcludeHttpRequest;
import static com.mastfrog.tinymavenproxy.TinyMavenProxy.ACCESS_LOGGER;
import com.mastfrog.url.Path;
import com.mastfrog.util.streams.Streams;
import com.mastfrog.util.strings.Strings;
import com.mastfrog.util.time.TimeUtil;
import static com.mastfrog.util.time.TimeUtil.GMT;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.DefaultFileRegion;
import io.netty.channel.FileRegion;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponseStatus;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_MODIFIED;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.CharsetUtil;
import static io.netty.util.CharsetUtil.UTF_8;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoField;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.zip.Deflater;
import java.util.zip.GZIPOutputStream;

/**
 *
 * @author Tim Boudreau
 */
@HttpCall(order = Integer.MAX_VALUE - 1, scopeTypes = DownloadResult.class)
@Concluders(ConcludeHttpRequest.class)
@Methods({GET, HEAD})
@Description(category = "Download", value = "Download maven artifacts, fetching them from remote repositories "
        + "and caching the results if necessary")
public class GetActeur extends Acteur {

    private final ApplicationControl ctrl;

    @Inject
    GetActeur(HttpEvent req, Deferral def, Config config, FileFinder finder,
            Closables clos, Downloader dl, @Named(ACCESS_LOGGER) Logger accessLog,
            RequestID id, ApplicationControl ctrl) throws IOException {
        this.ctrl = ctrl;
        setChunked(true);
        if ("true".equals(req.urlParameter("browse")) || "true".equals(req.urlParameter("index"))) {
            reject();
            return;
        }
        Path path = req.path().normalize();
        if (path.size() == 0) {
            reject();
            return;
        }
        if (!path.isValid()) {
            badRequest("Invalid path " + path);
            return;
        }
        if (path.toString().contains("..")) {
            setState(new RespondWith(Err.badRequest("Relative paths not allowed")));
            return;
        }
        File file = finder.find(path.elideEmptyElements());
        if (file != null) {
            config.debugLog("send existing file ", file);
            try (Log<?> log = accessLog.info("fetch")) {
                log.add("path", path).add("id", id).add("cached", true);
                add(Headers.LAST_MODIFIED, TimeUtil.fromUnixTimestamp(file.lastModified()).withZoneSameInstant(GMT));
                add(Headers.CONTENT_TYPE, findMimeType(path));
                add(Headers.CACHE_CONTROL, CacheControl.PUBLIC_MUST_REVALIDATE);
                ZonedDateTime inm = req.header(Headers.IF_MODIFIED_SINCE);
                if (inm != null) {
                    long theirs = TimeUtil.toUnixTimestamp(inm.with(ChronoField.MILLI_OF_SECOND, 0));
                    long ours = TimeUtil.toUnixTimestamp(TimeUtil.fromUnixTimestamp(file.lastModified()).with(ChronoField.MILLI_OF_SECOND, 0));
                    if (ours <= theirs) {
                        reply(NOT_MODIFIED);
                        return;
                    }
                }
                ok();
                if (req.method() != HEAD) {
//                    add(Headers.CONTENT_LENGTH, file.length());
                    setResponseBodyWriter(writerFor(req, file, accessLog, config, ctrl, response()));
                }
            }
        } else {
            if (dl.isFailedPath(path)) {
                notFound();
                setChunked(false);
//                setResponseBodyWriter(ChannelFutureListener.CLOSE);
                return;
            }
            String el = path.getLastElement().toString();
            if (el.toString().indexOf('.') < 0) {
                config.debugLog("Skip for not having . ", el);
                reject();
                return;
            }
            if (VERSION_PATTERN.matcher(el).find()) {
                config.debugLog("Skip for matching pattern", el);
                reject();
                return;
            }
            Path pth = path.elideEmptyElements();
            def.defer((Resumer res) -> {
                config.debugLog("  defer and download ", pth);
                ChannelFutureListener l = dl.download(pth, id, new DownloadReceiverImpl(res, config));
                req.channel().closeFuture().addListener(l);
            });
            next();
        }
    }

    private static final Pattern VERSION_PATTERN = Pattern.compile("^\\d+\\.\\d+.*?");

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
        ConcludeHttpRequest(HttpEvent evt, DownloadResult res, @Named(ACCESS_LOGGER) Logger accessLog,
                RequestID id, Config config, ApplicationControl ctrl) throws FileNotFoundException, IOException {
            setChunked(true);
            if (!res.isFail()) {
                try (Log<?> log = accessLog.info("fetch")) {
                    ok();
                    add(Headers.CONTENT_TYPE, findMimeType(evt.path()));
                    if (res.headers.contains(LAST_MODIFIED.name())) {
                        add(LAST_MODIFIED, LAST_MODIFIED.toValue(res.headers.get(LAST_MODIFIED.name())));
                    }
                    if (evt.method() != HEAD) {
                        add(Headers.CACHE_CONTROL, CacheControl.PUBLIC_MUST_REVALIDATE);
                        if (res.isFile()) {
                            log.add("file", res.file.getPath());
//                            setResponseBodyWriter(new FW(res.file, accessLog, config, config.bufferSize, true, ctrl));
                            setResponseBodyWriter(writerFor(evt, res.file, accessLog, config, ctrl, response()));
                        } else {
                            log.add("internalBuffer", true);
                            setResponseBodyWriter(new Responder2(res.buf, config, true, ctrl));
                        }
                    }
                    log.add("path", evt.path()).add("id", id).add("cached", false);
                }
            } else if (res.isFail() && res.buf != null) {
                reply(res.status);
//                setResponseWriter(new Responder(res.buf, config));
                setResponseBodyWriter(new Responder2(res.buf, config, true, ctrl));
            } else {
//                reply(NOT_FOUND, "Not cached and could not download " + evt.path());
                reject();
            }
        }
    }

    static final class FW implements ChannelFutureListener {

        private final File file;
        private final Logger logger;
        private final Config config;
        private SeekableByteChannel channel;
        private final ByteBuffer buffer;
        private final boolean chunked;
        private final ApplicationControl ctrl;

        FW(File file, Logger logger, Config config, int bufferLength, boolean chunked, ApplicationControl ctrl) {
            this.file = file;
            this.logger = logger;
            this.config = config;
            this.buffer = ByteBuffer.allocateDirect(bufferLength);
            this.chunked = chunked;
            this.ctrl = ctrl;
        }

        private SeekableByteChannel channel(ChannelFuture fut) throws IOException {
            synchronized (this) {
                if (channel == null) {
                    channel = Files.newByteChannel(file.toPath(), StandardOpenOption.READ);
                    fut.channel().closeFuture().addListener(f -> {
                        channel.close();
                    });
                }
            }
            return channel;
        }

        @Override
        public void operationComplete(ChannelFuture f) throws Exception {
            if (f.cause() != null) {
                if (f.channel().isOpen()) {
                    f.channel().close();
                }
                logger.warn("filewrite").add(f.cause()).close();
                return;
            }
            try {
                SeekableByteChannel channel = channel(f);
                if (!channel.isOpen() || channel.position() == channel.size()) {
                    if (chunked) {
                        ctrl.logFailure(f.channel().writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT));//.addListener(CLOSE);
//                    } else {
//                        f.addListener(CLOSE);
                    }
                    return;
                }
                buffer.rewind();
                channel.read(buffer);
                buffer.flip();
                ByteBuf buf = Unpooled.wrappedBuffer(buffer);
                if (chunked) {
                    ctrl.logFailure(f.channel().writeAndFlush(new DefaultHttpContent(buf)).addListener(this));
                } else {
                    ctrl.logFailure(f.channel().writeAndFlush(buf).addListener(this));
                }
            } catch (Exception ex) {
                if (channel != null) {
                    channel.close();
                }
                f.channel().close();
                logger.warn("filewrite").add(ex).close();
            }
        }
    }

    static final FileWriter writerFor(HttpEvent request, File f, Logger logger, Config config, ApplicationControl ctrl, Response resp) throws IOException {
        CharSequence acceptEncoding = request.header(ACCEPT_ENCODING);

        boolean acceptsGzip = acceptEncoding != null
                && (acceptEncoding == HttpHeaderValues.GZIP
                || acceptEncoding == HttpHeaderValues.GZIP_DEFLATE
                || Strings.charSequenceContains(acceptEncoding, HttpHeaderValues.GZIP, true));

        long uncompressedLength = f.length();

        resp.chunked(true);
        if (!acceptsGzip) {
            resp.add(Headers.CONTENT_ENCODING, HttpHeaderValues.IDENTITY);
            resp.add(Headers.CONTENT_LENGTH, uncompressedLength);
            return new FileWriter(f, logger, config, ctrl);
        } else {
            if (isGzipCacheFile(f)) {
                resp.add(Headers.CONTENT_ENCODING, HttpHeaderValues.IDENTITY);
                resp.add(Headers.CONTENT_LENGTH, uncompressedLength);
                return new FileWriter(f, logger, config, ctrl);
            }
            File gzippedFile = new File(f.getParentFile(), "_" + f.getName() + ".gz");

            if (!gzippedFile.exists()) {
                int bufferSize = Math.max(2048, (int) f.length());
                if (!gzippedFile.createNewFile()) {
                    throw new IOException("Could not create " + gzippedFile);
                }
                try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(gzippedFile), bufferSize)) {
                    try (GZO gzOut = new GZO(out, 2048, true)) {
                        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(f), 2048)) {
                            Streams.copy(in, gzOut);
                        }
                    }
                }
                if (config.isDebug()) {
                    resp.add(Headers.header("X-New-Gzip-File"), "1");
                }
                gzippedFile.setLastModified(f.lastModified());
            }
            long compressedLength = gzippedFile.length();
            if (compressedLength > uncompressedLength) {
                resp.add(Headers.CONTENT_ENCODING, HttpHeaderValues.IDENTITY);
                resp.add(Headers.CONTENT_LENGTH, uncompressedLength);
                return new FileWriter(f, logger, config, ctrl);
            }
            resp.add(Headers.CONTENT_LENGTH, compressedLength);
            resp.add(X_INTERNAL_COMPRESS_HEADER, "true");
            resp.add(Headers.CONTENT_ENCODING, HttpHeaderValues.GZIP);
            return new FileWriter(gzippedFile, logger, config, ctrl);
        }
    }

    static boolean isGzipCacheFile(File f) {
        return f.getName().charAt(0) == '_' && f.getName().endsWith(".gz");
    }

    static File gzip(File f) throws IOException {
        File gzippedFile = new File(f.getParentFile(), "_" + f.getName() + ".gz");

        if (!gzippedFile.exists()) {
            int bufferSize = Math.max(2048, (int) f.length());
            if (!gzippedFile.createNewFile()) {
                throw new IOException("Could not create " + gzippedFile);
            }
            try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(gzippedFile), bufferSize)) {
                try (GZO gzOut = new GZO(out, 2048, true)) {
                    try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(f), 2048)) {
                        Streams.copy(in, gzOut);
                    }
                }
            }
            gzippedFile.setLastModified(f.lastModified());
        }
        return gzippedFile;
    }

    static final class GZO extends GZIPOutputStream {

        public GZO(OutputStream out, int size) throws IOException {
            super(out, size);
            this.def.setLevel(Deflater.BEST_COMPRESSION);
        }

        public GZO(OutputStream out, int size, boolean syncFlush) throws IOException {
            super(out, size, syncFlush);
            this.def.setLevel(Deflater.BEST_COMPRESSION);
        }
    }

    static final class FileWriter implements ChannelFutureListener {

        private final File file;
        private final Logger logger;
        private final Config config;
        private final ApplicationControl ctrl;

        FileWriter(File file, Logger logger, Config config, ApplicationControl ctrl) {
            this.file = file;
            this.logger = logger;
            this.config = config;
            this.ctrl = ctrl;
        }

        @Override
        public void operationComplete(ChannelFuture f) throws Exception {
            if (!f.isDone() || f.isSuccess()) {
                config.debugLog("Send file region for ", file);
                FileRegion region = new DefaultFileRegion(file, 0, file.length());
                ctrl.logFailure(f.channel().writeAndFlush(region)).addListener((ChannelFuture f1) -> {
                    if (!f1.isDone() || f1.isSuccess()) {
                        ctrl.logFailure(f1.channel().writeAndFlush(DefaultLastHttpContent.EMPTY_LAST_CONTENT));
                    }
                });
            } else if (f.channel().isOpen()) {
                f.channel().close();
                if (f.cause() != null) {
                    logger.warn("download").add("file", file.getPath()).add(f.cause());
                }
            }
        }

        public String toString() {
            return "FileWriter-" + file.getName();
        }
    }

    static class Responder2 implements ChannelFutureListener {

        private final ByteBuf buf;
        private final Config config;
        private final boolean chunked;
        private final ApplicationControl ctrl;

        public Responder2(ByteBuf buf, Config config, boolean chunked, ApplicationControl ctrl) {
            this.buf = buf;
            this.config = config;
            this.chunked = chunked;
            this.ctrl = ctrl;
        }

        public String toString() {
            return "Responder2 " + buf.toString(UTF_8);
        }

        @Override
        public void operationComplete(ChannelFuture future) throws Exception {
            config.debugLog("use responder2 with ", buf.readableBytes());
            if (future.isDone() && !future.isSuccess()) {
                if (future.cause() != null) {
                    future.cause().printStackTrace();
                }
                return;
            }
            if (chunked) {
                ctrl.logFailure(future.channel().writeAndFlush(new DefaultLastHttpContent(buf.retain())));
            } else {
                ctrl.logFailure(future.channel().writeAndFlush(buf.retain()));
            }
        }
    }

    private static class DownloadReceiverImpl implements DownloadReceiver {

        private final Resumer r;
        private final Config config;

        public DownloadReceiverImpl(Resumer r, Config config) {
            this.r = new WrapperResumer(r);
            this.config = config;
        }

        @Override
        public void receive(HttpResponseStatus status, ByteBuf buf, HttpHeaders headers) {
            config.debugLog("  resume with ", buf.readableBytes());
            r.resume(new DownloadResult(status, buf, headers));
        }

        @Override
        public void failed(final HttpResponseStatus status) {
            config.debugLog(" fail ", status);
            r.resume(new DownloadResult(status));
        }

        @Override
        public void receive(HttpResponseStatus status, File file, HttpHeaders headers) {
            config.debugLog("resume with ", file);
            r.resume(new DownloadResult(status, file, headers));
        }

        @Override
        public void failed(HttpResponseStatus status, String msg) {
            config.debugLog("  fail ", status, msg);
            ByteBuf buf = Unpooled.buffer(msg.length() + 4);
            buf.writeCharSequence(msg, CharsetUtil.UTF_8);
            buf.writeChar('\n');
            r.resume(new DownloadResult(status, buf));
        }
    }

    private static class WrapperResumer implements Resumer {

        private AtomicBoolean resumed = new AtomicBoolean();
        private final Resumer resumer;
        private Exception ex;

        public WrapperResumer(Resumer resumer) {
            this.resumer = resumer;
        }

        @Override
        public void resume(Object... os) {
            if (resumed.compareAndSet(false, true)) {
                ex = new Exception();
                resumer.resume(os);
            } else {
                throw new IllegalStateException("Already resumed", ex);
            }
        }
    }
}
