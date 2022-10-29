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

import com.mastfrog.acteur.bunyan.ActeurBunyanModule;
import com.mastfrog.acteur.server.ServerModule;
import com.mastfrog.acteur.util.ServerControl;
import com.mastfrog.function.throwing.ThrowingRunnable;
import com.mastfrog.giulius.bunyan.java.v2.LoggingModule;
import com.mastfrog.settings.Settings;
import com.mastfrog.simple.webserver.FileServer;
import com.mastfrog.util.file.FileUtils;
import com.mastfrog.util.net.PortFinder;
import com.mastfrog.util.preconditions.ConfigurationError;
import com.mastfrog.util.strings.RandomStrings;
import com.mastfrog.util.strings.Strings;
import static com.mastfrog.util.strings.Strings.capitalize;
import com.telenav.cactus.wordy.WordList;
import com.telenav.cactus.wordy.WordLists;
import static com.telenav.cactus.wordy.WordLists.ADJECTIVES;
import static com.telenav.cactus.wordy.WordLists.NOUNS;
import static com.telenav.cactus.wordy.WordLists.POSESSIVES;
import static com.telenav.cactus.wordy.WordLists.PREPOSITIONS;
import static com.telenav.cactus.wordy.WordLists.VERBS;
import java.io.IOException;
import java.io.OutputStream;
import static java.nio.charset.StandardCharsets.UTF_8;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import static java.util.Collections.unmodifiableMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;

/**
 *
 * @author Tim Boudreau
 */
public final class FakeMavenServer {

    public static Path COMMON_PATH = Paths.get("com/mastfrog/fake-maven-server/1.0.0");
    public static Path COMMON_POM = Paths.get("com/mastfrog/fake-maven-server/1.0.0/com.mastfrog.fake-maven-server-1.0.0.pom");
    public static final String COMMON_BODY = "This is served by all fake-maven-servers.  All righty, then.\nWhaddaya think about that?\n\nSo there.\n";

    private final Random rnd;
    private final Map<Path, String> relativePaths = new HashMap<>();
    private final Path dir;
    private final PortFinder portFinder;
    private int port;
    private ServerControl ctrl;

    public FakeMavenServer(Random rnd, int count, PortFinder portFinder) throws IOException, NoSuchAlgorithmException {
        this.rnd = rnd;
        dir = FileUtils.newTempDir("fake-maven");
        RandomStrings rs = new RandomStrings(rnd);
        populate(count);
        this.portFinder = portFinder;
    }

    public static void startN(int n, Random rnd, int filesPer, PortFinder finder, Consumer<FakeMavenServer> c) throws IOException, NoSuchAlgorithmException, InterruptedException {
        for (int i = 0; i < n; i++) {
            FakeMavenServer oneServer = new FakeMavenServer(rnd, filesPer, finder);
            oneServer.start();
            c.accept(oneServer);
        }
    }

    public static void main(String[] args) throws Exception {
        Random r = new Random(3298234);
        PortFinder pf = new PortFinder(10000, 20000, 1000);
        List<FakeMavenServer> all = new ArrayList<>();
        startN(3, r, 2, pf, all::add);
        all.get(0).await();
    }

    public void await() throws InterruptedException {
        ServerControl ctrl;
        synchronized (this) {
            ctrl = this.ctrl;
        }
        if (ctrl == null) {
            throw new ConfigurationError("Not started");
        }
        ctrl.await();
    }

    public synchronized int port() {
        return port;
    }

    public String baseUri() {
        return "http://localhost:" + port + "/";
    }

    public Set<Path> paths() {
        return relativePaths.keySet();
    }

    public Map<Path, String> contents() {
        return unmodifiableMap(relativePaths);
    }

    public String content(Path path) {
        return relativePaths.get(path);
    }

    public ThrowingRunnable shutdown() {
        return () -> {
            ServerControl ctrl;
            synchronized (FakeMavenServer.this) {
                ctrl = this.ctrl;
            }
            try {
                if (ctrl != null) {
                    ctrl.shutdown(true);
                }
            } finally {
                cleanup();
            }
        };
    }

    private void cleanup() throws IOException {
        FileUtils.deltree(dir);
    }

    public ServerControl start() throws IOException, InterruptedException {
        if (ctrl == null) {
            FileServer server = new FileServer(dir.toFile());
            ActeurBunyanModule mod = new ActeurBunyanModule()
                    .setRequestLoggerLevel("info");
            server.withModule(mod);
            port = portFinder.findAvailableServerPort();
            Settings settings = Settings.builder()
                    .add(LoggingModule.SETTINGS_KEY_ASYNC_LOGGING, true)
                    .add(LoggingModule.SETTINGS_KEY_LOG_LEVEL, "info")
                    .add(ServerModule.PORT, port)
                    .add(ServerModule.EVENT_THREADS, 3)
                    .add(ServerModule.WORKER_THREADS, 12)
                    .build();
            ctrl = server.startServer(settings);
            server.latch().await();
        }
        return ctrl;
    }

    private void populate(int count) throws IOException, NoSuchAlgorithmException {
        populateConsistent();
        for (int i = 0; i < count; i++) {
            populateOne();
        }
    }

    private void populateConsistent() throws IOException, NoSuchAlgorithmException {
        Path base = dir.resolve(COMMON_PATH);
        String fileBase = "com.mastfrog.fake-maven-server-1.0.0";
        String txt = COMMON_BODY;
        Path fl = base.resolve(fileBase + ".pom");
        writeFileAndContent(fl, txt);
        String sha = sha1(txt);
        Path shaFile = base.resolve(fileBase + ".pom.sha1");
        writeFileAndContent(shaFile, sha);
    }

    private void populateOne() throws IOException, NoSuchAlgorithmException {
        Path base = dir;
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            String part = null;
            switch (i) {
                case 0:
                    String[] s = new String[]{"com", "org", "edu", "io"};
                    String val = s[rnd.nextInt(s.length)];
                    base = base.resolve(val);
                    parts.add(val);
                    continue;
                case 1:
                    part = randomWord(WordLists.ADVERBS);
                    break;
                case 2:
                    part = randomWord(WordLists.VERBS);
                    break;
                case 3:
                    part = randomWord(WordLists.NOUNS);
            }
            base = base.resolve(part);
            parts.add(part);
        }
        List<String> ver = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            int val = rnd.nextInt(12) + 1;
            String vs = Integer.toString(val);
            ver.add(vs);
        }

        String gid = Strings.join(".", parts.subList(0, parts.size() - 1));
        String aid = parts.get(parts.size() - 1);
        String version = Strings.join('.', ver);
        String fileBase = gid + "." + aid + "-" + version;

        base = base.resolve(version);

        Path pomFile = base.resolve(fileBase + ".pom");
        String cnt = randomContent();
        writeFileAndContent(pomFile, cnt);

//        System.out.println("--- " + base.relativize(pomFile) + " ---");
//        System.out.println(cnt);
        String sha = sha1(cnt);
        Path shaFile = base.resolve(fileBase + ".pom.sha1");
        writeFileAndContent(shaFile, sha);
    }

    private String sha1(String what) throws NoSuchAlgorithmException {
        byte[] bt = what.getBytes(UTF_8);
        MessageDigest dig = MessageDigest.getInstance("SHA-1");
        return Strings.toPaddedHex(dig.digest(bt));
    }

    private void writeFileAndContent(Path file, String content) throws IOException {
        if (!Files.exists(file.getParent())) {
            Files.createDirectories(file.getParent());
        }
        try (OutputStream out = Files.newOutputStream(file, CREATE, WRITE, TRUNCATE_EXISTING)) {
            out.write(content.getBytes(UTF_8));
        }
        relativePaths.put(dir.relativize(file), content);

        // Give them all an unlikely but consistent date
        Files.setLastModifiedTime(file, FileTime.from(WHEN));
    }

    public static Instant WHEN = Instant.ofEpochMilli(0)
            .plus(Duration.ofDays(365 * 2))
            .plus(Duration.ofDays(128));

    private String randomContent() {
        StringBuilder sents = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            if (sents.length() >= 0) {
                sents.append(' ');
            }
            String noun = randomWord(NOUNS);
            String adj = rnd.nextBoolean() ? randomWord(ADJECTIVES) : null;

            String firstWord = adj == null ? noun : adj;
            String ar = capitalize(articleFor(firstWord));

            sents.append(ar).append(' ');

            if (adj != null) {
                sents.append(adj).append(' ');
            }
            sents.append(noun).append(' ');

            sents.append(poorMansPresentTensify(randomWord(VERBS))).append(' ');

            adj = rnd.nextBoolean() ? randomWord(ADJECTIVES) : null;

            String nextNoun = randomWord(NOUNS);

            String nextWord = adj == null ? nextNoun : adj;

            if (rnd.nextBoolean()) {
                sents.append(randomWord(POSESSIVES)).append(' ');
            } else {
                sents.append(articleFor(nextWord)).append(' ');
            }
            if (adj != null) {
                sents.append(adj).append(' ');
            }

            sents.append(nextNoun).append(", ");

            if (rnd.nextBoolean()) {
                switch (rnd.nextInt(3)) {
                    case 0:
                        sents.append("that ");
                        break;
                    case 1:
                        sents.append("which ");
                        break;
                    case 2:
                        sents.append("because it ");
                }
                switch (rnd.nextInt(3)) {
                    case 0:
                        sents.append("turned ");
                        break;
                    case 1:
                        sents.append("is ");
                        break;
                    case 2:
                        sents.append("was ");
                        break;
                    case 3:
                        sents.append("became ");
                        break;
                    case 4:
                        sents.append("likes ");
                        break;
                }
                sents.append(randomWord(ADJECTIVES));
                sents.append(", ");
            }

            sents.append(randomWord(PREPOSITIONS)).append(' ');
            sents.append(randomWord(POSESSIVES)).append(' ');
            sents.append(randomWord(NOUNS)).append('.');
        }
        return sents.toString().trim() + '\n';
    }

    private String poorMansPresentTensify(String verb) {
        int last = verb.length() - 1;
        switch (verb.charAt(last)) {
            case 's':
                return verb;
            case 'h':
            case 'c':
                return verb + "es";
            default:
                return verb + "s";
        }
    }

    private String articleFor(String wd) {
        if (rnd.nextBoolean()) {
            return "the";
        } else {
            if (isVowelStart(wd)) {
                return "an";
            } else {
                return "a";
            }
        }
    }

    private boolean isVowelStart(String wd) {
        switch (wd.charAt(0)) {
            case 'a':
            case 'e':
            case 'i':
            case 'o':
            case 'u':
                return true;
            default:
                return false;
        }
    }

    private String randomWord(WordList list) {
        int sz = list.size();
        return list.word(rnd.nextInt(sz));
    }
}
