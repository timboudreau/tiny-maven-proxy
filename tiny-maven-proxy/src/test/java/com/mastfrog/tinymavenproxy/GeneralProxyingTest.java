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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.Scopes;
import com.google.inject.TypeLiteral;
import com.google.inject.name.Named;
import com.google.inject.name.Names;
import com.mastfrog.acteur.Application;
import com.mastfrog.acteur.annotations.GenericApplicationModule;
import com.mastfrog.acteur.bunyan.ActeurBunyanModule;
import com.mastfrog.acteur.headers.Headers;
import com.mastfrog.acteur.server.ServerLifecycleHook;
import com.mastfrog.acteur.server.ServerModule;
import com.mastfrog.acteur.util.Server;
import com.mastfrog.acteur.util.ServerControl;
import com.mastfrog.function.throwing.ThrowingRunnable;
import com.mastfrog.giulius.tests.GuiceRunner;
import com.mastfrog.giulius.tests.OnInjection;
import com.mastfrog.giulius.tests.TestWith;
import com.mastfrog.http.harness.HarnessLogLevel;
import com.mastfrog.http.harness.HttpTestHarness;
import com.mastfrog.http.harness.TestResults;
import com.mastfrog.settings.Settings;
import com.mastfrog.shutdown.hooks.ShutdownHooks;
import static com.mastfrog.tinymavenproxy.Config.MAVEN_CACHE_DIR;
import static com.mastfrog.tinymavenproxy.Config.SETTINGS_KEY_MIRROR_URLS;
import static com.mastfrog.tinymavenproxy.FakeMavenServer.WHEN;
import com.mastfrog.tinymavenproxy.GeneralProxyingTest.M;
import static com.mastfrog.tinymavenproxy.TinyMavenProxy.DOWNLOAD_LOGGER;
import com.mastfrog.util.file.FileUtils;
import com.mastfrog.util.libversion.VersionInfo;
import com.mastfrog.util.net.PortFinder;
import com.mastfrog.util.preconditions.Exceptions;
import io.netty.channel.Channel;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import javax.inject.Provider;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 *
 * @author Tim Boudreau
 */
@RunWith(GuiceRunner.class)
@TestWith({M.class, TinyMavenProxy.class})
public class GeneralProxyingTest {

    static final PortFinder FINDER = new PortFinder();
    static final Duration TIMEOUT = Duration.ofSeconds(60);

    @Test(timeout = 40000)
    public void testIt(HttpTestHarness<Object> harness,
            FakeMavenServers servers, Server server) throws Throwable {

//        System.out.println("PORT " + server.getPort());
//        System.out.println("HAVE SERVERS: ");
//        System.out.println(servers.urlsString());
//        for (Path p : servers.allPaths()) {
//            System.out.println(" * " + p);
//        }
        Thread.sleep(2000);
        Map<Path, TestResults<HttpResponse<String>>> pending = new LinkedHashMap<>();

        for (Map.Entry<Path, String> e : servers.content()) {
            Thread.currentThread().setName(e.getKey().toString());
            Thread.sleep(100);
            pending.put(e.getKey(), harness.get(e.getKey())
                    .responseStartTimeout(TIMEOUT)
                    .responseFinishedTimeout(TIMEOUT)
                    .applyingAssertions(asser -> {
                        asser.assertBody(e.getValue().trim())
                                .assertOk()
                                .assertHasHeader("last-modified")
                                .assertHeader("last-modified", lm -> {
                                    if (lm == null) {
                                        return true;
                                    }
                                    return Headers.LAST_MODIFIED.toValue(lm).toInstant()
                                            .equals(WHEN);
                                });
                    }));
        }

        for (int i = 0; i < 5; i++) {
            String pth = "xx11/yy22/zz33" + i + ".pom";
            Thread.currentThread().setName(pth);
            pending.put(Paths.get(pth), harness.get(pth)
                    .responseFinishedTimeout(TIMEOUT)
                    .test(assr -> {
                        assr.assertGone();
                    }));
        }

        for (Map.Entry<Path, TestResults<HttpResponse<String>>> r : pending.entrySet()) {
            r.getValue().await(TIMEOUT);
        }
        Throwable ae = null;
        for (Map.Entry<Path, TestResults<HttpResponse<String>>> r : pending.entrySet()) {
            try {
                r.getValue().assertAllSucceeded();
                System.out.println("OK: " + r.getKey());
            } catch (Exception | Error e) {
                if (ae == null) {
                    ae = e;
                } else {
                    ae.addSuppressed(e);
                }
            }
        }

        if (ae != null) {
            throw ae;
        }
    }

    @OnInjection
    public void initServers(HttpTestHarness<Object> harness,
            FakeMavenServers servers) throws IOException, InterruptedException {
        HttpClient cl = HttpClient.newHttpClient();
        for (String u : servers.uris()) {
            HttpRequest req
                    = HttpRequest.newBuilder(URI.create(u))
                            .GET()
                            .build();
            cl.send(req, BodyHandlers.discarding());
        }
    }

    static class M extends AbstractModule {

        @Override
        protected void configure() {
            try {
                Path cacheDir = FileUtils.newTempDir();
                Files.createDirectories(cacheDir);
                int port = FINDER.findAvailableServerPort();
                FakeMavenServers fakes = new FakeMavenServers(4, 2, FINDER);
                bind(PortFinder.class).toInstance(FINDER);
                bind(FakeMavenServers.class).toInstance(fakes);
                bind(new TypeLiteral<HttpTestHarness<Object>>() {
                }).toProvider(HttpTestHarnessProvider.class)
                        .in(Scopes.SINGLETON);
                bind(String.class)
                        .annotatedWith(Names.named(SETTINGS_KEY_MIRROR_URLS))
                        .toProvider(MirrorProvider.class).in(Scopes.SINGLETON);

                install(new ActeurBunyanModule(true)
                        .bindLogger(DOWNLOAD_LOGGER).bindLogger("startup")
                        .setRequestLoggerLevel("info")
                //                        .useProbe(true)
                );

                Settings settings = TinyMavenProxy.defaultSettings()
                        .add(ServerModule.PORT, port)
                        .add(ServerModule.EVENT_THREADS, 3)
                        .add(ServerModule.WORKER_THREADS, 12)
                        .add(ServerModule.BACKGROUND_THREADS, 12)
                        .add(SETTINGS_KEY_MIRROR_URLS, fakes.urlsString())
                        .add("neverKeepAlive", true)
                        .add(ServerModule.HTTP_COMPRESSION, false)
                        .add(MAVEN_CACHE_DIR, cacheDir.toString())
                        .build();

                bind(Config.class).toInstance(new Config(settings));

                bind(VersionInfo.class)
                        .toInstance(VersionInfo.find(TinyMavenProxy.class,
                                "com.mastfrog", "tiny-maven-proxy"));
                bind(Path.class).annotatedWith(Names.named("theDir"))
                        .toInstance(cacheDir);
                bind(DeleteCacheDir.class).asEagerSingleton();
                bind(Hooks.class).asEagerSingleton();
                install(new GenericApplicationModule(settings));
                bind(Integer.class).annotatedWith(Names.named(ServerModule.PORT))
                        .toInstance(port);
                bind(CountDownLatch.class).toInstance(new CountDownLatch(1));
                bind(ServersShutdown.class).asEagerSingleton();
                bind(ServerStartStop.class).asEagerSingleton();
                bind(HarnessShutdown.class).asEagerSingleton();

            } catch (Exception ex) {
                Exceptions.chuck(ex);
            }
        }
    }

    static class DeleteCacheDir implements ThrowingRunnable {

        private final Path path;

        @Inject
        DeleteCacheDir(@Named("theDir") Path path, ShutdownHooks hooks) {
            this.path = path;
        }

        @Override
        public void run() throws Exception {
            FileUtils.deleteIfExists(path);
        }
    }

    static class MirrorProvider implements Provider<String> {

        private final Provider<FakeMavenServers> servers;

        @Inject
        MirrorProvider(Provider<FakeMavenServers> servers) {
            this.servers = servers;
        }

        @Override
        public String get() {
            return servers.get().urlsString();
        }

    }

    static class ServersShutdown {

        @Inject
        ServersShutdown(ShutdownHooks hooks, FakeMavenServers servers) throws IOException, NoSuchAlgorithmException {
            hooks.addThrowing(() -> servers.shutdown());
        }
    }

    static class ServerStartStop implements ThrowingRunnable {

        final ServerControl server;

        @Inject
        @SuppressWarnings("LeakingThisInConstructor")
        ServerStartStop(Server server, ShutdownHooks hooks, @Named(ServerModule.PORT) int port) throws IOException {
            this.server = server.start(port);
            hooks.addThrowing(this);
        }

        @Override
        public void run() throws Exception {
            server.shutdown(true);
        }
    }

    static class HarnessShutdown implements ThrowingRunnable {

        private final HttpTestHarness<Object> harn;

        @Inject
        HarnessShutdown(HttpTestHarness<Object> harn, ShutdownHooks hooks) {
            this.harn = harn;
            hooks.addThrowing(this);
        }

        @Override
        public void run() throws Exception {
            harn.shutdown();
        }
    }

    static class Hooks extends ServerLifecycleHook {

        private final CountDownLatch latch;

        @Inject
        Hooks(Registry reg, CountDownLatch latch) {
            super(reg);
            this.latch = latch;
        }

        @Override
        protected void onStartup(Application aplctn, Channel chnl) throws Exception {
            latch.countDown();
        }

        @Override
        protected void onShutdown() throws Exception {
            super.onShutdown();
        }

    }

    static class HttpTestHarnessProvider implements Provider<HttpTestHarness<Object>> {

        private final HttpTestHarness<Object> harn;

        @Inject
        HttpTestHarnessProvider(@Named(ServerModule.PORT) int port, ObjectMapper mapper, CountDownLatch latch) {
            harn = HttpTestHarness.builder()
                    //                    .logToStderr()
                    .withMapper(mapper)
                    .withDefaultRequestIdProvider()
                    .withMinimumLogLevel(HarnessLogLevel.IMPORTANT)
                    .awaitingReadinessOn(latch)
                    //                    .throttlingRequestsWith(new Semaphore(3))
                    .withInitialResponseTimeout(TIMEOUT)
                    .withDefaultResponseTimeout(TIMEOUT)
                    .withWatchdogInterval(Duration.ofSeconds(1))
                    .withHttpVersion(HttpClient.Version.HTTP_1_1)
                    .build()
                    .convertingToUrisWith((Object pth) -> {
                        return URI.create("http://localhost:" + port + "/" + pth);
                    });
        }

        @Override
        public HttpTestHarness<Object> get() {
            return harn;
        }

    }
}
