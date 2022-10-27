/*
 * The MIT License
 *
 * Copyright 2019 Mastfrog Technologies.
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

import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import com.mastfrog.acteur.Application;
import com.mastfrog.acteur.annotations.GenericApplicationModule;
import com.mastfrog.acteur.bunyan.ActeurBunyanModule;
import com.mastfrog.acteur.header.entities.Connection;
import com.mastfrog.acteur.headers.Headers;
import com.mastfrog.acteur.server.ServerLifecycleHook;
import com.mastfrog.acteur.server.ServerModule;
import com.mastfrog.acteur.util.Server;
import com.mastfrog.function.throwing.ThrowingRunnable;
import com.mastfrog.giulius.Dependencies;
import com.mastfrog.giulius.bunyan.java.v2.LoggingModule;
import com.mastfrog.netty.http.client.HttpClient;
import com.mastfrog.netty.http.client.HttpRequestBuilder;
import com.mastfrog.netty.http.client.ResponseFuture;
import com.mastfrog.netty.http.client.ResponseHandler;
import com.mastfrog.netty.http.client.State;
import com.mastfrog.settings.Settings;
import com.mastfrog.tiny.http.server.Responder;
import com.mastfrog.tiny.http.server.ResponseHead;
import com.mastfrog.tiny.http.server.TinyHttpServer;
import static com.mastfrog.tinymavenproxy.TinyMavenProxy.DOWNLOAD_LOGGER;
import com.mastfrog.util.file.FileUtils;
import com.mastfrog.util.net.PortFinder;
import com.mastfrog.util.preconditions.Exceptions;
import com.mastfrog.util.streams.Streams;
import com.mastfrog.util.strings.Strings;
import com.mastfrog.util.thread.Receiver;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_FOUND;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import io.netty.util.ResourceLeakDetector;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.AfterClass;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 *
 * @author Tim Boudreau
 */
public class FailedDownloadsTest {

    static {
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.PARANOID);
    }

    private static ThrowingRunnable whenDone;
    private static final int SERVER_COUNT = 6;
    private static final PortFinder pf = new PortFinder();
    private static List<ServerInfo> infos;
    private static Path tempDir;
    private static int serverPort;
    private static HttpClient client;

    @Test
    public void test() throws InterruptedException, IOException {
        request("commons-cli/commons-cli/1.0/commons-cli-1.0.pom", NOT_FOUND);
        request("log4j/log4j/1.2.14/log4j-1.2.14.jar", NOT_FOUND);
        request("log4j/log4j/1.2.14/log4j-1.2.14.jar", NOT_FOUND);
        request("com/mastfrog/bunyan/bunyan-java-2.5.0.jar", NOT_FOUND);
    }

    @Test
    public void testLeaks() throws InterruptedException, IOException {
        for (int i = 0; i < 10; i++) {
            request("/com/mastfrog/tiny-maven-proxy/1." + i
                    + "/tiny-maven-proxy-1." + i + ".pom", OK);
        }
    }

    @Test
    public void sanityCheckDummyServers() throws Throwable {
        for (ServerInfo si : infos) {
            executeRequest(client.get()
                    .addHeader(Headers.CONNECTION, Connection.close)
                    .setHost("127.0.0.1")
                    .setPort(si.httpPort)
                    .setPath("commons-cli/commons-cli/1.0/commons-cli-1.0.pom"), NOT_FOUND);
        }
    }

    private void request(String path, HttpResponseStatus expectedStatus) throws InterruptedException, IOException {
        executeRequest(client.get()
                .setHost("127.0.0.1")
                .setPort(serverPort)
                .setPath(path), expectedStatus);
    }

    private void executeRequest(HttpRequestBuilder b, HttpResponseStatus expectedStatus) throws InterruptedException, IOException {
        class CH extends Receiver<Channel> implements ChannelFutureListener {

            private final CountDownLatch latch = new CountDownLatch(1);
            private Channel channel;

            @Override
            public void receive(Channel object) {
                channel = object;
                object.closeFuture().addListener(this);
            }

            @Override
            public void operationComplete(ChannelFuture f) throws Exception {
                latch.countDown();
            }

            void await() throws InterruptedException {
                assertNotNull("Never got a channel", channel);
                latch.await(10, TimeUnit.SECONDS);
            }
        }
        CH ch = new CH();
        b.on(State.Connected.class, ch);

        St<HttpResponse> hr = new St<>("headersReceived");
        b.on(State.HeadersReceived.class, hr);
        St<ByteBuf> fc = new St<>("fullContent");
        b.on(State.FullContentReceived.class, fc);
        St<FullHttpResponse> fc2 = new St<>("fullResponse");
        b.on(State.Finished.class, fc2);
        System.out.println("await finished");
        RH rh = new RH();
        ResponseFuture fut = b.execute(rh);
        hr.await();
        assertNotNull("Headers not received", hr.obj);
        ch.await();
        fut.await();
        assertEquals(expectedStatus, hr.obj.status());
        if (OK.equals(expectedStatus)) {
            assertNotNull(fc2.obj);
            String content = Streams.readUTF8String(new ByteBufInputStream(fc2.obj.content()));
            assertNotNull(content);
            assertEquals("<xml>stuff here</xml>\n", content);
        }
        checkThrown();
    }

    static final class St<T> extends Receiver<T> {

        private T obj;

        private final CountDownLatch latch = new CountDownLatch(1);
        private final String name;

        St(String name) {
            this.name = name;
        }

        @Override
        public void receive(T object) {
            System.out.println("received " + object + " for " + name);
            obj = object;
            latch.countDown();
        }

        void await() throws InterruptedException {
            latch.await(10, TimeUnit.SECONDS);
            assertNotNull(name, obj);
        }
    }

    class RH extends ResponseHandler<String> {

        public RH() {
            super(String.class);
        }

        @Override
        public Class<String> type() {
            return String.class;
        }

        @Override
        protected void onError(Throwable err) {
            err.printStackTrace();
        }

        @Override
        protected void onErrorResponse(HttpResponseStatus status, HttpHeaders headers, String content) {
            System.out.println("onErrorResponse " + status);
        }

        @Override
        protected void onErrorResponse(HttpResponseStatus status, String content) {
            System.out.println("onErrorResponse2 " + status);
        }

        @Override
        protected void onErrorResponse(String content) {
            System.out.println("onErrorResponse3 " + content);
        }

        @Override
        protected void receive(String obj) {
            System.out.println("Receive " + obj);
        }

        @Override
        protected void receive(HttpResponseStatus status, HttpHeaders headers, String obj) {
            System.out.println("Receive " + status + " " + headers + " " + obj);
        }

        @Override
        protected void receive(HttpResponseStatus status, String obj) {
            super.receive(status, obj); //To change body of generated methods, choose Tools | Templates.
        }

        @Override
        public boolean await(long l, TimeUnit tu) throws InterruptedException {
            return super.await(l, tu); //To change body of generated methods, choose Tools | Templates.
        }

        @Override
        public void await() throws InterruptedException {
            super.await(); //To change body of generated methods, choose Tools | Templates.
        }
    }

    @BeforeClass
    public static void setup() throws Exception {
        whenDone = ThrowingRunnable.oneShot(true);
        infos = new ArrayList<>();
        List<String> urls = new ArrayList<>();
        for (int i = 0; i < SERVER_COUNT; i++) {
            ServerInfo info = startOne();
            infos.add(info);
            urls.add(info.httpsUrl());
        }
        checkThrown();
        tempDir = FileUtils.newTempDir("tiny-maven-proxy");
        serverPort = pf.findAvailableServerPort();
        Settings settings = Settings.builder()
                .add(Config.SETTINGS_KEY_MIRROR_URLS, Strings.join(",", urls))
                .add(Config.MAVEN_CACHE_DIR, tempDir.toString())
                .add(LoggingModule.SETTINGS_KEY_LOG_TO_CONSOLE, true)
                .add(LoggingModule.SETTINGS_KEY_LOG_HOSTNAME, "tmtest")
                .add(LoggingModule.SETTINGS_KEY_LOG_LEVEL, "trace")
                .add(ServerModule.HTTP_COMPRESSION, "true")
                .add(ServerModule.WORKER_THREADS, 7)
                .add(ServerModule.EVENT_THREADS, 1)
                .add(ServerModule.PORT, serverPort)
                .add("download.threads", 23)
                .add("maven.proxy.debug", true)
                .build();

        Dependencies deps = Dependencies.builder()
                .add(settings)
                .add(new M(settings)).build();

        whenDone.andAlways(deps::shutdown);
        Server server = deps.getInstance(Server.class);
        client = HttpClient.builder()
                .threadCount(5)
                //                .resolveAllHostsToLocalhost()
                .build();
        whenDone.andAlways(client::shutdown);
        server.start();
        deps.getInstance(StartWait.class).await();
        System.out.println("up and running");
    }

    private static final class StartWait extends ServerLifecycleHook {

        private final CountDownLatch latch = new CountDownLatch(1);
        private final Integer port;

        @Inject
        StartWait(Registry reg, @Named(ServerModule.PORT) Integer port) {
            super(reg);
            this.port = port;
        }

        void await() throws InterruptedException {
            latch.await(10, TimeUnit.SECONDS);
            System.out.println("countdown exited");
        }

        @Override
        protected void onStartup(Application application, Channel channel) throws Exception {
            System.out.println("server started on " + port);
            latch.countDown();
        }
    }

    @AfterClass
    public static void teardown() throws Exception {
        whenDone.run();
        FileUtils.deltree(tempDir);
    }

    private static void checkThrown() {
        for (ServerInfo info : infos) {
            info.server.throwLast();
        }
    }

    static ServerInfo startOne() throws Exception {
        R r = new R();
        int httpPort = pf.findAvailableServerPort();
        int httpsPort = pf.findAvailableServerPort();
        System.out.println("start 404-only server on " + httpPort);
        TinyHttpServer server = new TinyHttpServer(r, httpPort, httpsPort);
        whenDone.andAlways(server::shutdown);
        return new ServerInfo(r, httpPort, httpsPort, server);
    }

    static final class ServerInfo {

        private final R r;
        private final int httpPort;
        private final int httpsPort;
        private final TinyHttpServer server;

        public ServerInfo(R r, int httpPort, int httpsPort, TinyHttpServer server) {
            this.r = r;
            this.httpPort = httpPort;
            this.httpsPort = httpsPort;
            this.server = server;
        }

        public String httpUrl() {
            return "http://localhost:" + httpPort;
        }

        public String httpsUrl() {
            return "https://localhost:" + httpsPort;
        }

        public void assertRequested(String uri) {
            assertTrue("No such request in " + Strings.join(",", r.uris), r.uris.remove(uri));
        }

    }

    static final class R implements Responder {

        Set<String> uris = new HashSet<>();
        static final Random RND = new Random(120910932L);

        @Override
        public Object receive(HttpRequest req, ResponseHead response) throws Exception {
            System.out.println("URI: " + req.uri());
//            if ("/com/mastfrog/tiny-maven-proxy/1.7/tiny-maven-proxy-1.7.pom".equals(req.uri())) {
            if (req.uri().startsWith("/com/mastfrog/tiny-maven-proxy")) {
                // Stagger so some requests will be cancelled -
                // We are trying to ensure Netty's leak detection will catch any
                // buffers from cancelled requests whose reference count was
                // never decremented to zero
                Thread.sleep(RND.nextInt(100));
                response.status(200);
                return "<xml>stuff here</xml>\n";
            }
            System.out.println("send not-found for " + req.uri());
            uris.add(req.uri());
            response.status(404);

            return "Not found: " + req.uri();
        }
    }

    static final class M extends AbstractModule {

        private final Settings settings;

        M(Settings settings) {
            this.settings = settings;
        }

        @Override
        protected void configure() {
            install(new TinyMavenProxy());
            install(new ActeurBunyanModule(true).bindLogger(DOWNLOAD_LOGGER).bindLogger("startup"));
            bind(StartWait.class).asEagerSingleton();
            try {
                install(new GenericApplicationModule(settings));
                bind(Config.class).toInstance(new Config(settings));
            } catch (IOException ex) {
                Exceptions.chuck(ex);
            }
        }
    }

    static {
        System.setProperty("acteur.debug", "true");
    }
}
