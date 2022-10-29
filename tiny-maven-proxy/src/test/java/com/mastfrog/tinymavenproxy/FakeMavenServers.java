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

import com.mastfrog.function.throwing.ThrowingBiFunction;
import com.mastfrog.function.throwing.ThrowingRunnable;
import com.mastfrog.url.URL;
import com.mastfrog.util.net.PortFinder;
import com.mastfrog.util.strings.Strings;
import java.io.IOException;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 *
 * @author Tim Boudreau
 */
public class FakeMavenServers {

    private final Random rnd = new Random(39244094092049L);
    private final List<FakeMavenServer> all = new ArrayList<>();
    private final PortFinder finder;
    private final ThrowingRunnable shutdown;

    public FakeMavenServers(int servers, int filesPer) throws IOException, NoSuchAlgorithmException, InterruptedException {
        this(servers, filesPer, null);
    }

    public FakeMavenServers(int servers, int filesPer, PortFinder finder) throws IOException, NoSuchAlgorithmException, InterruptedException {
        this.finder = finder == null ? new PortFinder(10000, 22000) : finder;
        FakeMavenServer.startN(servers, rnd, filesPer, finder, all::add);
        ThrowingRunnable shut = ThrowingRunnable.oneShot(true);
        for (FakeMavenServer f : all) {
            shut = shut.andAlways(f.shutdown());
        }
        this.shutdown = shut;
    }

    public <T> T randomPathAndContent(ThrowingBiFunction<Path, String, T> c) {
        List<Map.Entry<Path, String>> l = content();
        Map.Entry<Path, String> e = l.get(rnd.nextInt(l.size()));
        return c.toNonThrowing().apply(e.getKey(), e.getValue());
    }

    public PortFinder finder() {
        return finder;
    }

    public String urlsString() {
        return Strings.join(',', urls());
    }

    public List<URL> urls() {
        List<URL> result = new ArrayList<>();
        uris().forEach(u -> result.add(URL.parse(u)));
        return result;
    }

    public List<String> uris() {
        Set<String> result = new TreeSet<>();
        for (FakeMavenServer fms : all) {
            result.add(fms.baseUri());
        }
        return new ArrayList<>(result);
    }

    public List<Map.Entry<Path, String>> content() {
        Map<Path, String> result = new TreeMap<>();
        for (FakeMavenServer f : all) {
            result.putAll(f.contents());
        }
        return new ArrayList<>(result.entrySet());
    }

    public String contentFor(Path path) {
        for (FakeMavenServer f : all) {
            String cnt = f.content(path);
            if (cnt != null) {
                return cnt;
            }
        }
        return null;
    }

    public Set<Path> allPaths() {
        Set<Path> result = new TreeSet<>();
        for (FakeMavenServer f : all) {
            result.addAll(f.paths());
        }
        return result;
    }

    public void shutdown() throws Exception {
        shutdown.run();
        for (FakeMavenServer fms : all) {
            fms.await();
        }
    }

}
