//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.javax.common.messages;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.javax.common.MessageSink;

/**
 * Support class for reading a WebSocket BINARY message via a InputStream.
 * <p>
 * An InputStream that can access a queue of ByteBuffer payloads, along with expected InputStream blocking behavior.
 * </p>
 */
public class MessageInputStream extends InputStream implements MessageSink
{
    private static final Logger LOG = Log.getLogger(MessageInputStream.class);
    private static final CallbackBuffer EOF = new CallbackBuffer(Callback.NOOP, BufferUtil.EMPTY_BUFFER);
    private final Deque<CallbackBuffer> buffers = new ArrayDeque<>(2);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private CallbackBuffer activeFrame;

    @Override
    public void accept(Frame frame, Callback callback)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("accepting {}", frame);

        // If closed, we should just toss incoming payloads into the bit bucket.
        if (closed.get())
        {
            callback.failed(new IOException("Already Closed"));
            return;
        }

        if (!frame.hasPayload() && !frame.isFin())
        {
            callback.succeeded();
            return;
        }

        synchronized (buffers)
        {
            boolean notify = false;
            if (frame.hasPayload())
            {
                buffers.offer(new CallbackBuffer(callback, frame.getPayload()));
                notify = true;
            }
            else
            {
                // We cannot wake up blocking read for a zero length frame.
                callback.succeeded();
            }

            if (frame.isFin())
            {
                buffers.offer(EOF);
                notify = true;
            }

            if (notify)
            {
                // notify other thread
                buffers.notify();
            }
        }
    }

    @Override
    public void close() throws IOException
    {
        if (LOG.isDebugEnabled())
            LOG.debug("close()");

        if (closed.compareAndSet(false, true))
        {
            synchronized (buffers)
            {
                buffers.offer(EOF);
                buffers.notify();
            }
        }
        super.close();
    }

    public CallbackBuffer getActiveFrame() throws InterruptedIOException
    {
        if (activeFrame == null)
        {
            // sync and poll queue
            CallbackBuffer result;
            synchronized (buffers)
            {
                try
                {
                    while ((result = buffers.poll()) == null)
                    {
                        // TODO: handle read timeout here?
                        buffers.wait();
                    }
                }
                catch (InterruptedException e)
                {
                    shutdown();
                    throw new InterruptedIOException();
                }
            }
            activeFrame = result;
        }

        return activeFrame;
    }

    private void shutdown()
    {
        if (LOG.isDebugEnabled())
            LOG.debug("shutdown()");
        synchronized (buffers)
        {
            closed.set(true);
            Throwable cause = new IOException("Shutdown");
            for (CallbackBuffer buffer : buffers)
            {
                buffer.callback.failed(cause);
            }
            // Removed buffers that may have remained in the queue.
            buffers.clear();
        }
    }

    @Override
    public void mark(int readlimit)
    {
        // Not supported.
    }

    @Override
    public boolean markSupported()
    {
        return false;
    }

    @Override
    public int read() throws IOException
    {
        byte[] buf = new byte[1];
        while (true)
        {
            int len = read(buf, 0, 1);
            if (len < 0) // EOF
                return -1;
            if (len > 0) // did read something
                return buf[0];
            // reading nothing (len == 0) tries again
        }
    }

    @Override
    public int read(final byte[] b, final int off, final int len) throws IOException
    {
        if (closed.get())
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Stream closed");
            return -1;
        }

        CallbackBuffer result = getActiveFrame();

        if (LOG.isDebugEnabled())
            LOG.debug("result = {}", result);

        if (result == EOF)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Read EOF");
            shutdown();
            return -1;
        }

        // We have content
        int fillLen = Math.min(result.buffer.remaining(), len);
        result.buffer.get(b, off, fillLen);

        if (!result.buffer.hasRemaining())
        {
            activeFrame = null;
            result.callback.succeeded();
        }

        // return number of bytes actually copied into buffer
        return fillLen;
    }

    @Override
    public void reset() throws IOException
    {
        throw new IOException("reset() not supported");
    }
}
