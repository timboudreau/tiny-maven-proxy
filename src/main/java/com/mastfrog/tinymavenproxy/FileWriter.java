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

import com.mastfrog.acteur.Closables;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.LastHttpContent;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;

/**
 *
 * @author Tim Boudreau
 */
public class FileWriter implements ChannelFutureListener {

    private static final int BUFFER_SIZE = 1024;

    private final byte[] buffer = new byte[BUFFER_SIZE];
    private final InputStream stream;

    public FileWriter(File file, Closables clos) throws FileNotFoundException {
        stream = clos.add(new BufferedInputStream(new FileInputStream(file), buffer.length));
    }

    @Override
    public void operationComplete(ChannelFuture f) throws Exception {
        if (!f.channel().isOpen()) {
            return;
        }
        ByteBuf buf = f.channel().alloc().buffer(BUFFER_SIZE);
        int bytes = buf.writeBytes(stream, BUFFER_SIZE);
        if (bytes == -1) {
            stream.close();
            f = f.channel().writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT).addListener(CLOSE);
            return;
        }
        f = f.channel().writeAndFlush(new DefaultHttpContent(buf));
        f.addListener(this);
    }
}
