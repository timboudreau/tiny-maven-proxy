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

import com.google.inject.name.Named;
import com.mastfrog.acteur.Acteur;
import com.mastfrog.acteur.CheckIfModifiedSinceHeader;
import com.mastfrog.acteur.HttpEvent;
import com.mastfrog.acteur.annotations.HttpCall;
import com.mastfrog.acteur.annotations.Precursors;
import com.mastfrog.acteur.errors.Err;
import com.mastfrog.acteur.headers.Headers;
import static com.mastfrog.acteur.headers.Method.GET;
import static com.mastfrog.acteur.headers.Method.HEAD;
import com.mastfrog.acteur.preconditions.Description;
import com.mastfrog.acteur.preconditions.Methods;
import com.mastfrog.acteur.preconditions.PathRegex;
import com.mastfrog.acteur.header.entities.CacheControl;
import com.mastfrog.bunyan.java.v2.Logs;
import com.mastfrog.tinymavenproxy.GetIndex.FindIndexFile;
import static com.mastfrog.tinymavenproxy.TinyMavenProxy.ACCESS_LOGGER;
import com.mastfrog.util.time.TimeUtil;
import static com.mastfrog.util.time.TimeUtil.GMT;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.DefaultFileRegion;
import io.netty.channel.FileRegion;
import java.io.File;
import javax.inject.Inject;

/**
 *
 * @author Tim Boudreau
 */
@HttpCall(scopeTypes = File.class, order = -1)
@Methods({GET, HEAD})
@PathRegex("^.index\\/[^\\/].*$")
@Precursors({FindIndexFile.class, CheckIfModifiedSinceHeader.class})
@Description(category = "Download", value = "Fetch the index for this repository")
public class GetIndex extends Acteur implements ChannelFutureListener {

    private final File target;
    private final Logs accessLog;
    private final boolean close;

    @Inject
    GetIndex(File target, @Named(ACCESS_LOGGER) Logs accessLog, HttpEvent evt) {
        this.target = target;
        this.accessLog = accessLog;
        ok();
        close = !evt.requestsConnectionStayOpen();
        if (evt.method() != HEAD) {
            setResponseBodyWriter(this);
        }
    }

    @Override
    public void operationComplete(ChannelFuture f) throws Exception {
        if (f.cause() != null) {
            accessLog.error("dlIndex").add(f.cause()).add("file", target.getPath()).close();
            return;
        }
        FileRegion reg = new DefaultFileRegion(target, 0, target.length());
        f = f.channel().writeAndFlush(reg);
        if (close) {
            f.addListener(CLOSE);
        }
    }

    static final class FindIndexFile extends Acteur {

        @Inject
        FindIndexFile(HttpEvent evt, Config config) {
            File target = new File(config.indexDir(), evt.path().getChildPath().toString());
            if (!target.exists() || !target.isFile()) {
                reply(Err.gone("No such file: " + target.getAbsolutePath()));
                return;
            }
            add(Headers.LAST_MODIFIED, TimeUtil.fromUnixTimestamp(target.lastModified(), GMT));
            add(Headers.CACHE_CONTROL, CacheControl.PUBLIC_MUST_REVALIDATE);
            next(target);
        }
    }

}
