package com.mastfrog.tinymavenproxy;

import com.google.common.net.MediaType;
import com.google.inject.Inject;
import com.mastfrog.acteur.Acteur;
import com.mastfrog.acteur.CheckIfModifiedSinceHeader;
import com.mastfrog.acteur.annotations.HttpCall;
import com.mastfrog.acteur.annotations.Precursors;
import com.mastfrog.acteur.headers.Headers;
import static com.mastfrog.acteur.headers.Method.GET;
import com.mastfrog.acteur.preconditions.Methods;
import com.mastfrog.acteur.preconditions.Path;
import com.mastfrog.acteur.util.CacheControl;
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
            add(Headers.CONTENT_TYPE, MediaType.JSON_UTF_8);
            next();
        }
    }
}
