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

import com.google.inject.Inject;
import com.google.inject.name.Named;
import com.mastfrog.acteur.errors.ResponseException;
import com.mastfrog.acteur.server.ServerModule;
import com.mastfrog.url.Path;
import com.mastfrog.util.Streams;
import com.mastfrog.util.time.TimeUtil;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import static io.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.time.ZonedDateTime;
import java.util.concurrent.ExecutorService;

/**
 *
 * @author Tim Boudreau
 */
public class FileFinder {

    private final Config config;
    private final ExecutorService threadPool;

    @Inject
    FileFinder(Config config, @Named(ServerModule.BACKGROUND_THREAD_POOL_NAME) ExecutorService threadPool) {
        this.config = config;
        this.threadPool = threadPool;
    }

    public File find(Path path) {
        File f = new File(config.dir, path.toString());
        if (f.exists() && f.isFile()) {
            return f;
        }
        return null;
    }

    public File folder(Path path) {
        if (path.size() == 0) {
            return config.dir;
        }
        File f = new File(config.dir, path.toString());
        if (f.exists() && f.isDirectory()) {
            return f;
        }
        return null;
    }

    public synchronized File put(final Path path, final File file, final ZonedDateTime lastModified) throws IOException {
        if (file.length() == 0) {
            return file;
        }
        final File target = new File(config.dir, path.toString().replace('/', File.separatorChar));
        if (!target.getParentFile().exists() && !target.getParentFile().mkdirs()) {
            throw new IOException("Could not create dirs " + target.getParent());
        }
        if (!file.renameTo(target)) {
            throw new ResponseException(INTERNAL_SERVER_ERROR, "Could not rename " + file + " to " + target);
        }
        if (lastModified != null) {
            target.setLastModified(lastModified.toInstant().toEpochMilli());
        }
        return target;
    }

    public synchronized void put(final Path path, final ByteBuf content, final ZonedDateTime lastModified) {
        // This method is currently unused, but if we enhance the server to accept
        // uploads, we will likely need code a lot like this
        if (content.readableBytes() == 0) {
            return;
        }
        final ByteBuf buf = content.duplicate();
        threadPool.submit(() -> {
            final File target = new File(config.dir, path.toString().replace('/', File.separatorChar));
            buf.retain();
            if (!target.exists()) {
                if (!target.getParentFile().exists()) {
                    if (!target.getParentFile().mkdirs()) {
                        throw new IOException("Could not create " + target.getParentFile());
                    }
                }
                if (!target.createNewFile()) {
                    throw new IOException("Could not create " + target);
                }
            }
            try (ByteBufInputStream in = new ByteBufInputStream(buf)) {
                try (OutputStream out = new BufferedOutputStream(new FileOutputStream(target))) {
                    Streams.copy(in, out, 1024);
                }
            } catch (IOException ioe) {
                if (target.exists()) {
                    target.delete();
                }
                throw ioe;
            } finally {
                buf.release();
            }
            threadPool.submit(() -> {
                if (lastModified != null) {
                    target.setLastModified(TimeUtil.toUnixTimestamp(lastModified));
                }
            });
            return null;
        });
    }
}
