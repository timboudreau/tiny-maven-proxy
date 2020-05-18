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

import com.mastfrog.settings.Settings;
import static com.mastfrog.tinymavenproxy.Config.SETTINGS_KEY_INDEX_DIR;
import static com.mastfrog.tinymavenproxy.Config.SETTINGS_KEY_MIRROR_URLS;
import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * https://github.com/timboudreau/tiny-maven-proxy/issues/5
 *
 * @author Tim Boudreau
 */
public class TestBug5 {

    private static final String URL = "http://foo.cloud.engineering.mycompany.com/";

    @Test
    public void ensureManyLabelsInURLHost() throws IOException {
        String filePath = System.getProperty("java.io.tmpdir") + "/TestBug5";
        Settings s = Settings.builder()
                .add(SETTINGS_KEY_MIRROR_URLS, URL)
                .add(SETTINGS_KEY_INDEX_DIR, filePath)
                .build();
        Config config = new Config(s);
        File exp = new File(filePath).getAbsoluteFile();
        assertEquals(exp, config.indexDir.getAbsoluteFile());
        Set<String> urls = new HashSet<>();
        config.forEach(url -> {
            System.out.println("URL: " + url);
            urls.add(url.toString());
        });
        assertFalse(urls.isEmpty());
        assertTrue(urls.contains(URL));
    }
}
