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
import com.mastfrog.acteur.Acteur;
import com.mastfrog.acteur.HttpEvent;
import com.mastfrog.acteur.annotations.HttpCall;
import com.mastfrog.acteur.headers.Headers;
import static com.mastfrog.acteur.headers.Method.GET;
import static com.mastfrog.acteur.headers.Method.HEAD;
import com.mastfrog.acteur.preconditions.Methods;
import com.mastfrog.acteur.util.CacheControl;
import com.mastfrog.url.Path;
import com.mastfrog.util.Streams;
import com.mastfrog.util.collections.MapBuilder;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_MODIFIED;
import java.io.File;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.joda.time.DateTime;

/**
 *
 * @author Tim Boudreau
 */
@HttpCall(order = Integer.MAX_VALUE)
@Methods({GET, HEAD})
public class Browse extends Acteur {

    @Inject
    Browse(HttpEvent evt, FileFinder finder, DateTime startTime) throws NoSuchAlgorithmException {
        Path path = evt.getPath();
        if (path.size() == 0 && !"true".equals(evt.getParameter("browse"))) {
            DateTime headerTime = evt.getHeader(Headers.IF_MODIFIED_SINCE);
            if (headerTime != null && (headerTime.equals(startTime) || headerTime.isAfter(startTime))) {
                reply(NOT_MODIFIED);
                return;
            }
            add(Headers.CACHE_CONTROL, CacheControl.PUBLIC_MUST_REVALIDATE_MAX_AGE_1_DAY);
            add(Headers.CONTENT_TYPE, MediaType.HTML_UTF_8);
            ok();
            setResponseBodyWriter(IndexPageWriter.class);
            setChunked(true);
            return;
        }
        File f = finder.folder(path);
        if (f == null) {
            notFound();
            return;
        }
        MessageDigest digest = MessageDigest.getInstance("MD5");
        long newest = 0;
        File[] kids = f.listFiles();
        List<Map<String, Object>> result = new ArrayList(kids.length);
        for (File file : f.listFiles()) {
            if ("index.html".equals(file.getName()) || ".index".equals(file.getName())) {
                continue;
            }
            MapBuilder mb1 = new MapBuilder(digest);
            mb1.put("name", file.getName());
            boolean isFile = file.isFile();
            mb1.put("file", file.isFile());
            if (isFile) {
                mb1.put("length", file.length());
            }
            long lm = file.lastModified();
            mb1.put("lastModified", lm);
            newest = Math.max(lm, newest);
            result.add(mb1.build());
        }
        add(Headers.LAST_MODIFIED, new DateTime(newest));
        String etag = Base64.getEncoder().encodeToString(digest.digest());
        add(Headers.ETAG, etag);
        if (etag.equals(evt.getHeader(Headers.IF_NONE_MATCH))) {
            reply(NOT_MODIFIED);
            return;
        }
        if (evt.getMethod() == HEAD) {
            ok();
            return;
        }
        ok(result);
    }

    static class IndexPageWriter implements ChannelFutureListener {

        @Override
        public void operationComplete(ChannelFuture f) throws Exception {
            ByteBuf buf = f.channel().alloc().buffer();
            try (ByteBufOutputStream out = new ByteBufOutputStream(buf)) {
                try (InputStream in = IndexPageWriter.class.getResourceAsStream("index.html")) {
                    Streams.copy(in, out);
                }
            }
            f.channel().writeAndFlush(new DefaultLastHttpContent(buf)).addListener(CLOSE);
        }
    }
}
