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

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.mastfrog.settings.Settings;
import static com.mastfrog.tinymavenproxy.TinyMavenProxy.SETTINGS_KEY_DOWNLOAD_CHUNK_SIZE;
import com.mastfrog.url.Path;
import com.mastfrog.url.PathElement;
import com.mastfrog.url.URL;
import com.mastfrog.url.URLBuilder;
import com.mastfrog.util.preconditions.ConfigurationError;
import com.mastfrog.util.strings.Strings;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 *
 * @author Tim Boudreau
 */
@Singleton
public class Config implements Iterable<URL> {

    public static final String SETTINGS_KEY_MIRROR_URLS = "mirror";
    public static final String MAVEN_CACHE_DIR = "maven.dir";
    public static final String SETTINGS_KEY_CACHE_FAILED_PATHS_MINUTES = "failed.path.cache.minutes";
    public static final String SETTINGS_KEY_INDEX_DIR = "index.dir";
    private static final String DEFAULT_URLS = "https://repo.maven.apache.org/maven2/"
            + ",http://bits.netbeans.org/nexus/content/groups/netbeans/"
            //            + ",http://bits.netbeans.org/maven2/"
            + ",http://bits.netbeans.org/nexus/content/repositories/snapshots/"
            + ",https://timboudreau.com/maven/"
            + ",https://maven.java.net/content/groups/public/"
            + ",https://oss.sonatype.org/content/repositories/snapshots/"
            + ",https://oss.sonatype.org/content/repositories/releases/"
            + ",https://oss.sonatype.org/"
            + ",https://maven.atlassian.com/3rdparty/";

    private final URL[] urls;
    public final File dir;
    final File indexDir;
    final boolean debugLog;
    final int bufferSize;
    final int failedPathCacheMinutes;

    @Inject
    Config(Settings s) throws IOException {
        failedPathCacheMinutes = s.getInt(SETTINGS_KEY_CACHE_FAILED_PATHS_MINUTES, 90);
        bufferSize = s.getInt(SETTINGS_KEY_DOWNLOAD_CHUNK_SIZE, 1480);
        debugLog = s.getBoolean("maven.proxy.debug", false);
        String[] u = s.getString(SETTINGS_KEY_MIRROR_URLS, DEFAULT_URLS).split(",");
        urls = new URL[u.length];
        for (int i = 0; i < u.length; i++) {
            String mirror = u[i];
            urls[i] = URL.parse(mirror);
            if (!urls[i].isValid()) {
                urls[i].getProblems().throwIfFatalPresent();
            }
        }
        debugLog("START WITH URLS ", () -> new Object[]{Strings.commas(urls)});
        String dirname = s.getString(MAVEN_CACHE_DIR);
        if (dirname == null) {
            File tmp = new File(System.getProperty("java.io.tmpdir"));
            dir = new File(tmp, "maven");
            if (dir.exists() && !dir.isDirectory()) {
                throw new IOException("Not a folder: " + dir);
            }
            if (!dir.exists()) {
                if (!dir.mkdirs()) {
                    throw new IOException("Could not create " + dir);
                }
            }
        } else {
            dir = new File(dirname);
            if (dir.exists() && !dir.isDirectory()) {
                throw new IOException("Not a folder: " + dir);
            }
            if (!dir.exists()) {
                if (!dir.mkdirs()) {
                    throw new IOException("Could not create " + dir);
                }
            }
        }
        String indexDir = s.getString(SETTINGS_KEY_INDEX_DIR, "_");
        if ("_".equals(indexDir)) {
            indexDir = new File(dir, ".index").getAbsolutePath();
        }
        this.indexDir = new File(indexDir);
        if (!this.indexDir.exists()) {
            if (!this.indexDir.mkdirs()) {
                throw new ConfigurationError("Could not create index dirs " + this.indexDir);
            }
        }
    }

    @JsonProperty("mirroring")
    Set<String> urlStrings() {
        Set<String> result = new HashSet<>();
        for (URL url : urls) {
            result.add(url.toString());
        }
        return result;
    }

    @JsonProperty("dir")
    String path() {
        return dir.getAbsolutePath();
    }

    File indexDir() {
        return indexDir;
    }

    public Collection<URL> withPath(Path path) {
        List<URL> result = new ArrayList(urls.length);
        for (URL u : this) {
            URLBuilder b = URL.builder(u);
            for (PathElement p : path) {
                b.add(p);
            }
            result.add(b.create());
        }
        return result;
    }

    @Override
    public Iterator<URL> iterator() {
        return Arrays.asList(urls).iterator();
    }

    public boolean isDebug() {
        return debugLog;
    }

    final void debugLog(String msg, Supplier<Object[]> lazy) {
        if (debugLog) {
            debugLog(msg, lazy.get());
        }
    }

    final void debugLog(String msg, Object... objs) {
        if (debugLog) {
            StringBuilder sb = new StringBuilder(msg);
            for (int i = 0; i < objs.length; i++) {
                sb.append(' ').append(objs[i]);
                if (i != objs.length - 1) {
                    sb.append(',');
                }
            }
            System.out.println(sb);
        }
    }
}
