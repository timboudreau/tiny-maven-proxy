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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.net.MediaType;
import static com.google.common.net.MediaType.JSON_UTF_8;
import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.name.Named;
import com.google.inject.util.Providers;
import com.mastfrog.acteur.Acteur;
import com.mastfrog.acteur.HttpEvent;
import com.mastfrog.acteur.annotations.HttpCall;
import com.mastfrog.acteur.headers.Headers;
import static com.mastfrog.acteur.headers.Method.GET;
import static com.mastfrog.acteur.headers.Method.HEAD;
import com.mastfrog.acteur.preconditions.Description;
import com.mastfrog.acteur.preconditions.Methods;
import com.mastfrog.acteur.server.PathFactory;
import com.mastfrog.acteur.util.CacheControl;
import com.mastfrog.url.Path;
import static com.mastfrog.util.collections.CollectionUtils.map;
import com.mastfrog.util.strings.Strings;
import com.mastfrog.util.time.TimeUtil;
import static com.mastfrog.util.time.TimeUtil.GMT;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_MODIFIED;
import java.io.File;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 *
 * @author Tim Boudreau
 */
@HttpCall(order = Integer.MAX_VALUE)
@Methods({GET, HEAD})
@Description(category = "Download", value = "Serves directory listings and the HTML index page")
public class Browse extends Acteur {

    @Inject
    Browse(HttpEvent evt, FileFinder finder, ZonedDateTime startTime, PathFactory paths,
            @Named("index") Provider<ByteBuf> indexPage,
            @Named("indexHash") Provider<String> indexPageHash,
            ObjectMapper mapper) throws NoSuchAlgorithmException, JsonProcessingException {
        Path path = evt.path();
        add(Headers.CACHE_CONTROL, CacheControl.PUBLIC_MUST_REVALIDATE_MAX_AGE_1_DAY);
        if (path.size() == 0 && !"true".equals(evt.urlParameter("browse"))) {
            ZonedDateTime headerTime = evt.header(Headers.IF_MODIFIED_SINCE);
//            if (headerTime != null && (TimeUtil.equalsToSeconds(startTime, headerTime) || headerTime.isAfter(startTime))) {
            CharSequence ifNoneMatch = evt.header(Headers.IF_NONE_MATCH);
            if (ifNoneMatch != null && Strings.charSequencesEqual(ifNoneMatch, indexPageHash.get())) {
                reply(NOT_MODIFIED);
                return;
            } else if (TimeUtil.equalsToSecondsOrAfter(startTime, headerTime)) {
                reply(NOT_MODIFIED);
                return;
            }
            add(Headers.ETAG, indexPageHash.get());
            add(Headers.CONTENT_TYPE, MediaType.HTML_UTF_8);
            if (HEAD.is(evt.method())) {
                ok();
            } else {
                ByteBuf buf = indexPage.get();
                add(Headers.CONTENT_LENGTH, buf.readableBytes());
                ok(indexPage.get());
            }
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
            String name = file.getName();
            if ("index.html".equals(name) || ".index".equals(name)) {
                continue;
            } else if (name.charAt(0) == '_') {
                // Gzipped cache files
                continue;
            } else if ("maven-metadata-local.xml".equals(name)) {
                continue;
            }

            long lastModified = file.lastModified();
            newest = Math.max(lastModified, newest);
            result.add(map("name").to(file.getName()).map("file").to(file.isFile())
                    .maybeMap(file::isFile, mb -> {
                        mb.map("length").to(file.length());
                    }).map("lastModified").finallyTo(lastModified));
        }
        add(Headers.LAST_MODIFIED, TimeUtil.fromUnixTimestamp(newest, GMT));
        String etag = Base64.getEncoder().encodeToString(digest.digest());
        add(Headers.ETAG, etag);
        add(Headers.CONTENT_TYPE, JSON_UTF_8);
        CharSequence inm = evt.header(Headers.IF_NONE_MATCH);
        if (inm != null && Strings.charSequencesEqual(etag, inm)) {
            reply(NOT_MODIFIED);
            return;
        } else if (TimeUtil.equalsToSecondsOrAfter(TimeUtil.fromUnixTimestamp(newest, GMT), evt.header(Headers.IF_MODIFIED_SINCE))) {
            reply(NOT_MODIFIED);
            return;
        }
        if (evt.method().is(HEAD)) {
            ok();
            return;
        }
        setChunked(true);
        ok();
        byte[] bytes = mapper.writeValueAsBytes(result);
        ByteBuf buf = evt.channel().alloc().ioBuffer(bytes.length, bytes.length);
        buf.writeBytes(bytes);
        setResponseBodyWriter(new IndexPageWriter(Providers.of(buf)));
    }

    static class IndexPageWriter implements ChannelFutureListener {

        private final Provider<ByteBuf> indexBytes;

        @Inject
        IndexPageWriter(@Named("index") Provider<ByteBuf> buf) {
            indexBytes = buf;
        }

        @Override
        public void operationComplete(ChannelFuture f) throws Exception {
            if (!f.isDone() || f.isSuccess()) {
                f.channel().writeAndFlush(new DefaultLastHttpContent(indexBytes.get()));
            }
        }
    }
}
