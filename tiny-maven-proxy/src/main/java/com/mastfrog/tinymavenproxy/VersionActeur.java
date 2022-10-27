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

import com.google.inject.Inject;
import com.mastfrog.acteur.Acteur;
import com.mastfrog.acteur.CheckIfModifiedSinceHeader;
import com.mastfrog.acteur.annotations.HttpCall;
import com.mastfrog.acteur.annotations.Precursors;
import com.mastfrog.acteur.headers.Headers;
import static com.mastfrog.acteur.headers.Method.GET;
import com.mastfrog.acteur.preconditions.Methods;
import com.mastfrog.acteur.preconditions.Path;
import com.mastfrog.acteur.header.entities.CacheControl;
import com.mastfrog.mime.MimeType;
import com.mastfrog.tinymavenproxy.VersionActeur.RevLastModifiedActeur;
import static com.mastfrog.util.collections.CollectionUtils.map;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 *
 * @author Tim Boudreau
 */
@HttpCall
@Methods(GET)
@Path("/_version")
@Precursors({RevLastModifiedActeur.class, CheckIfModifiedSinceHeader.class})
public class VersionActeur extends Acteur {

    @Inject
    public VersionActeur() {
        String revision = com.mastfrog.tinymavenproxy.RevisionInfo.VERSION;
        ok(map("version").finallyTo(revision));
    }

    static class RevLastModifiedActeur extends Acteur {

        RevLastModifiedActeur() {
            ZonedDateTime when = ZonedDateTime.ofInstant(com.mastfrog.tinymavenproxy.RevisionInfo.COMMIT_TIMESTAMP, ZoneId.systemDefault());
            add(Headers.LAST_MODIFIED, when);
            add(Headers.CACHE_CONTROL, CacheControl.PUBLIC_MUST_REVALIDATE);
            add(Headers.CONTENT_TYPE, MimeType.JSON_UTF_8);
            next();
        }
    }
}
