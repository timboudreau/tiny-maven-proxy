package com.mastfrog.tinymavenproxy;

import com.google.common.io.Files;
import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.name.Named;
import com.google.inject.name.Names;
import com.mastfrog.acteur.annotations.GenericApplicationModule;
import com.mastfrog.acteur.bunyan.ActeurBunyanModule;
import com.mastfrog.acteur.headers.Headers;
import com.mastfrog.acteur.util.Server;
import com.mastfrog.acteur.util.ServerControl;
import com.mastfrog.giulius.Dependencies;
import com.mastfrog.giulius.ShutdownHookRegistry;
import com.mastfrog.giulius.tests.GuiceRunner;
import com.mastfrog.giulius.tests.TestWith;
import com.mastfrog.netty.http.client.State;
import com.mastfrog.netty.http.test.harness.TestHarness;
import com.mastfrog.netty.http.test.harness.TestHarnessModule;
import com.mastfrog.settings.Settings;
import com.mastfrog.tiny.http.server.Responder;
import com.mastfrog.tiny.http.server.ResponseHead;
import com.mastfrog.tiny.http.server.TinyHttpServer;
import com.mastfrog.tinymavenproxy.GetActeurTest.M;
import static com.mastfrog.tinymavenproxy.TinyMavenProxy.DOWNLOAD_LOGGER;
import com.mastfrog.util.Exceptions;
import com.mastfrog.util.Streams;
import com.mastfrog.util.net.PortFinder;
import com.mastfrog.util.thread.Receiver;
import com.mastfrog.util.time.TimeUtil;
import io.netty.handler.codec.http.HttpHeaderNames;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.file.Path;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLException;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 *
 * @author Tim Boudreau
 */
@RunWith(GuiceRunner.class)
@TestWith({TestHarnessModule.class, TinyMavenProxy.class, M.class})
public class GetActeurTest {

    Duration timeout = Duration.ofSeconds(10);

    @Test
    public void testSomeMethod(TestHarness harn, @Named("mdir") File mdir) throws UnsupportedEncodingException, InterruptedException, Throwable {

        String s = harn.get("com/foo/bar.pom")
                .addHeader(Headers.ACCEPT_ENCODING, "identity")
                .setTimeout(timeout)
                .log()
                .on(State.HeadersReceived.class, new Receiver<HttpResponse>() {
                    @Override
                    public void receive(HttpResponse t) {
                        System.out.println("GOT HEADERS \n" + t.headers());
                    }
                })
                .go()
                .await()
                .assertCode(200)
                .content();

        assertEquals("Hello /com/foo/bar.pom", s);

        File f = new File(mdir, "com/foo/bar.pom");
        assertTrue(f.exists());
        assertEquals("Hello /com/foo/bar.pom", Streams.readString(new FileInputStream(f)));

        String s2 = harn.get("com/foo/bar.pom")
                .setTimeout(timeout)
                .log()
                .go()
                .assertHasHeader(CONTENT_LENGTH)
                .await()
                .assertCode(200)
                .content();

        assertEquals("Hello /com/foo/bar.pom", s2);

        String s3 = harn.get("com/foo/whoo.pom")
                .addHeader(Headers.ACCEPT_ENCODING, "gzip")
                .setTimeout(timeout)
                .log()
                .on(State.HeadersReceived.class, new Receiver<HttpResponse>() {
                    @Override
                    public void receive(HttpResponse t) {
                        System.out.println("GOT HEADERS \n" + t.headers());
                    }
                })
                .go()
                .await()
                .assertCode(200)
                .content();

        assertEquals("Hello /com/foo/whoo.pom", s3);

        File f2 = new File(mdir, "com/foo/whoo.pom");
        assertTrue(f2.exists());
        assertEquals("Hello /com/foo/whoo.pom", Streams.readString(new FileInputStream(f2)));

        String s4 = harn.get("com/foo/whoo.pom")
                .setTimeout(timeout)
                .log()
                .go()
                .await()
                .assertHasHeader(CONTENT_LENGTH)
                .assertCode(200)
                .content();

        assertEquals("Hello /com/foo/whoo.pom", s4);
    }

    static final class M extends AbstractModule {

        @Override
        protected void configure() {
            File dir = Files.createTempDir();
            int port = new PortFinder().findAvailableServerPort();
            System.out.println("SERV PORT " + port);
//            bind(HttpClient.class).toInstance(HttpClient.builder().noCompression().dontFollowRedirects().build());
            bind(Integer.class).annotatedWith(Names.named("tsport")).toInstance(port);
            bind(Responder.class).to(ResponderImpl.class);
            bind(Starter.class).asEagerSingleton();
            bind(File.class).annotatedWith(Names.named("mdir")).toInstance(dir);
            install(new ActeurBunyanModule(true).bindLogger(DOWNLOAD_LOGGER).bindLogger("startup"));
            bind(Integer.class).annotatedWith(Names.named("download.threads")).toInstance(3);
            bind(String.class).annotatedWith(Names.named("maven.dir")).toInstance(dir.getAbsolutePath());
            try {
                Settings settings = Settings.builder()
                        .add(Config.SETTINGS_KEY_MIRROR_URLS, "http://localhost:" + port)
                        .add(Config.MAVEN_CACHE_DIR, dir.getAbsolutePath())
                        .build();
                install(new GenericApplicationModule(settings));
                bind(Config.class).toInstance(new Config(settings));
            } catch (IOException ex) {
                Exceptions.chuck(ex);
            }
        }

    }

    public static void main(String[] args) throws CertificateException, SSLException, InterruptedException, IOException {
//        new TinyHttpServer(new ResponderImpl(), 8080, 8081).await();
        Server server = Dependencies.builder().add(new M()).build().getInstance(Server.class);
        server.start().await();
    }

    static final class ResponderImpl implements Responder {

        @Override
        public Object receive(HttpRequest req, ResponseHead response) throws Exception {
            System.out.println("TM REQUEST " + req.headers());
            response.header(HttpHeaderNames.CONTENT_TYPE).set("text/plain; charset=UTF-8");
            response.header(HttpHeaderNames.LAST_MODIFIED).set(TimeUtil.toHttpHeaderFormat(TimeUtil.EPOCH.withZoneSameInstant(TimeUtil.GMT)));
            String uri = req.uri();
            String result = "Hello " + uri;
            return result;
        }
    }

    static final class Starter implements Runnable, Comparator<Path> {

        private final String cacheDir;

        @Inject
        Starter(ServerImpl server, ShutdownHookRegistry reg, @Named(Config.MAVEN_CACHE_DIR) String cacheDir) throws IOException {
            server.start();
            this.cacheDir = cacheDir;
            reg.add(this);
        }

        @Override
        public void run() {
            File dir = new File(cacheDir);
            if (dir.exists()) {
                try {
                    List<Path> paths = new ArrayList<>();
                    java.nio.file.Files.walk(dir.toPath()).forEach(f -> paths.add(f));
                    Collections.sort(paths, this);
                    for (Path pth : paths) {
                        java.nio.file.Files.delete(pth);
                    }
                } catch (IOException ex) {
                    Exceptions.chuck(ex);
                }
            }
        }

        @Override
        public int compare(Path o1, Path o2) {
            return o1.getNameCount() > o2.getNameCount() ? -1 : o2.getNameCount() == o1.getNameCount() ? 0
                    : 1;
        }
    }

    static final class ServerImpl implements Server, ServerControl, Runnable {

        private final Responder responder;

        private final int port;
        private TinyHttpServer server;

        @Inject
        ServerImpl(Responder responder, @Named("tsport") int port, ShutdownHookRegistry reg) {
            this.responder = responder;
            this.port = port;
            reg.add(this);
        }

        @Override
        public int getPort() {
            return port;
        }

        @Override
        public ServerControl start() throws IOException {
            try {
                this.server = new TinyHttpServer(responder, port, port + 1);
            } catch (CertificateException | SSLException | InterruptedException ex) {
                return Exceptions.chuck(ex);
            }
            return this;
        }

        @Override
        public ServerControl start(int i) throws IOException {
            try {
                this.server = new TinyHttpServer(responder, port, port + 1);
            } catch (CertificateException | SSLException | InterruptedException ex) {
                return Exceptions.chuck(ex);
            }
            return this;
        }

        @Override
        public ServerControl start(boolean bln) throws IOException {
            try {
                this.server = new TinyHttpServer(responder, port, port + 1);
            } catch (CertificateException | SSLException | InterruptedException ex) {
                return Exceptions.chuck(ex);
            }
            return this;
        }

        @Override
        public ServerControl start(int i, boolean bln) throws IOException {
            try {
                this.server = new TinyHttpServer(responder, port, port + 1);
            } catch (CertificateException | SSLException | InterruptedException ex) {
                return Exceptions.chuck(ex);
            }
            return this;
        }

        @Override
        public void shutdown(boolean bln, long l, TimeUnit tu) throws InterruptedException {
            if (this.server != null) {
                this.server.shutdown();
            }
        }

        @Override
        public void shutdown(boolean bln) throws InterruptedException {
            if (this.server != null) {
                this.server.shutdown();
            }
        }

        @Override
        public void await() throws InterruptedException {
            if (this.server != null) {
                this.server.await();
            }
        }

        @Override
        public void awaitUninterruptibly() {
            if (this.server != null) {
                try {
                    this.server.await();
                } catch (InterruptedException ex) {
                    return;
                }
            }
        }

        @Override
        public long awaitNanos(long nanosTimeout) throws InterruptedException {
            await();
            return 0;
        }

        @Override
        public boolean await(long time, TimeUnit unit) throws InterruptedException {
            await();
            return true;
        }

        @Override
        public boolean awaitUntil(Date deadline) throws InterruptedException {
            await();
            return true;
        }

        @Override
        public void signal() {
            // do nothing
        }

        @Override
        public void signalAll() {
            // do nothing
        }

        @Override
        public void run() {
            if (this.server != null) {
                try {
                    this.server.shutdown();
                } catch (InterruptedException ex) {
                    Exceptions.chuck(ex);
                }
            }
        }
    }
}
