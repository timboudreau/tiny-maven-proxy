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

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.mastfrog.function.throwing.ThrowingRunnable;
import com.mastfrog.settings.Settings;
import com.mastfrog.shutdown.hooks.ShutdownHooks;
import java.io.IOException;
import static java.lang.System.getProperty;
import static java.lang.Thread.holdsLock;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.ResponseInfo;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import static java.nio.file.Files.deleteIfExists;
import static java.nio.file.Files.move;
import static java.nio.file.Files.createDirectories;
import static java.nio.file.Files.exists;
import static java.nio.file.Files.newByteChannel;
import java.nio.file.Path;
import java.nio.file.Paths;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.StandardOpenOption.CREATE_NEW;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.ZonedDateTime;
import static java.util.Collections.newSetFromMap;
import static java.util.Collections.synchronizedSet;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 *
 * @author Tim Boudreau
 */
@Singleton
final class TempFiles implements ThrowingRunnable {

    private final java.nio.file.Path tmp;
    private static final String PREFIX = "m-dl-";
    private static final AtomicLong COUNTER = new AtomicLong();
    private final Set<TempFile> unclosed
            = synchronizedSet(newSetFromMap(new WeakHashMap<>()));

    @Inject
    @SuppressWarnings("LeakingThisInConstructor")
    TempFiles(Settings settings, ShutdownHooks onShutdown) {
        this.tmp = Paths.get(settings.getString("download-tmp",
                getProperty("java.io.tmpdir")));
        onShutdown.addLastThrowing(this);
    }

    private TempFile addTempFile(TempFile file) {
        unclosed.add(file);
        return file;
    }

    public TempFile tempFile(String dlId) {
        Path nue = tmp.resolve(PREFIX + "-" + dlId
                + "-" + Long.toString(COUNTER.incrementAndGet(), 36));
        return addTempFile(new TempFile(nue));
    }

    @Override
    public void run() throws Exception {
        for (TempFile file : unclosed) {
            file.close();
        }
    }

    static final class TempFile implements AutoCloseable {

        static Boolean atomicMoves;
        private final Path path;
        private SeekableByteChannel channel;
        private boolean closed;
        private Instant lastModified;
        private HttpResponse.ResponseInfo info;
        private Path dest;

        public TempFile(Path path) {
            this.path = path;
        }

        static void atomicMoveBroken() {
            atomicMoves = false;
        }

        static boolean canAtomicMove() {
            Boolean b = atomicMoves;
            if (b == null) {
                return true;
            }
            return b;
        }

        public Path path() {
            synchronized (this) {
                if (dest != null) {
                    return dest;
                }
            }
            return path;
        }

        public Optional<Instant> lastModified() {
            return Optional.ofNullable(lastModified);
        }

        public boolean isClosed() {
            return closed;
        }

        public Optional<ResponseInfo> info() {
            return Optional.ofNullable(info);
        }

        public synchronized TempFile setLastModified(ZonedDateTime zdt) {
            this.lastModified = zdt.toInstant();
            return this;
        }

        public synchronized TempFile setLastModified(Instant instant) {
            this.lastModified = instant;
            return this;
        }

        public synchronized TempFile withResponseInfo(ResponseInfo info) {
            this.info = info;
            return this;
        }

        private SeekableByteChannel channel() throws IOException {
            assert Thread.holdsLock(this);
            if (channel == null) {
                channel = newByteChannel(path, WRITE, CREATE_NEW, TRUNCATE_EXISTING);
            }
            return channel;
        }

        public synchronized void append(ByteBuffer buf) throws IOException {
            if (closed) {
                return;
            }
            channel().write(buf);
        }

        private boolean closeChannel() throws IOException {
            assert holdsLock(this);
            SeekableByteChannel ch = channel;
            if (ch != null) {
                ch.close();
                return true;
            }
            return false;
        }

        public synchronized boolean close(Path moveTo) throws IOException {
            if (!closed) {
                closed = true;
                closeChannel();
                if (!exists(moveTo.getParent())) {
                    try {
                        createDirectories(moveTo.getParent());
                    } catch (FileAlreadyExistsException ex) {
                        // do nothing
                    }
                }
                if (canAtomicMove()) {
                    try {
                        move(path, moveTo, REPLACE_EXISTING, ATOMIC_MOVE);
                    } catch (IOException ex) {
                        atomicMoveBroken();
                        move(path, moveTo, REPLACE_EXISTING);
                    }
                } else {
                    move(path, moveTo, REPLACE_EXISTING);
                }
                Instant lm = lastModified;
                if (lm != null) {
                    Files.setLastModifiedTime(moveTo, FileTime.from(lm));
                }
                dest = moveTo;
                return true;
            }
            return false;
        }

        @Override
        public synchronized void close() throws IOException {
            if (!closed) {
                closed = true;
                if (closeChannel()) {
                    deleteIfExists(path);
                }
            }
        }
    }
}
