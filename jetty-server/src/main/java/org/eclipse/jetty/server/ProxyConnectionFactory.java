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

package org.eclipse.jetty.server;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ReadPendingException;
import java.nio.channels.WritePendingException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.AttributesMap;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * <p>ConnectionFactory for the PROXY Protocol.</p>
 * <p>This factory can be placed in front of any other connection factory
 * to process the proxy v1 or v2 line before the normal protocol handling</p>
 *
 * @see <a href="http://www.haproxy.org/download/1.5/doc/proxy-protocol.txt">http://www.haproxy.org/download/1.5/doc/proxy-protocol.txt</a>
 */
public class ProxyConnectionFactory extends AbstractConnectionFactory
{
    private static final Logger LOG = Log.getLogger(ProxyConnectionFactory.class);
    public static final String TLS_VERSION = "TLS_VERSION";

    private final String _next;
    private int _maxProxyHeader = 1024;

    /**
     * Proxy Connection Factory that uses the next ConnectionFactory
     * on the connector as the next protocol
     */
    public ProxyConnectionFactory()
    {
        super("proxy");
        _next = null;
    }

    public ProxyConnectionFactory(String nextProtocol)
    {
        super("proxy");
        _next = nextProtocol;
    }

    public int getMaxProxyHeader()
    {
        return _maxProxyHeader;
    }

    public void setMaxProxyHeader(int maxProxyHeader)
    {
        _maxProxyHeader = maxProxyHeader;
    }

    @Override
    public Connection newConnection(Connector connector, EndPoint endp)
    {
        String next = _next;
        if (next == null)
        {
            for (Iterator<String> i = connector.getProtocols().iterator(); i.hasNext(); )
            {
                String p = i.next();
                if (getProtocol().equalsIgnoreCase(p))
                {
                    next = i.next();
                    break;
                }
            }
        }

        return new ProxyProtocolV1orV2Connection(endp, connector, next);
    }

    public class ProxyProtocolV1orV2Connection extends AbstractConnection
    {
        // Only do a tiny read to figure out what PROXY version it is.
        private final ByteBuffer _buffer = BufferUtil.allocate(16);
        private final Connector _connector;
        private final String _next;

        protected ProxyProtocolV1orV2Connection(EndPoint endp, Connector connector, String next)
        {
            super(endp, connector.getExecutor());
            _connector = connector;
            _next = next;
        }

        @Override
        public void onOpen()
        {
            super.onOpen();
            fillInterested();
        }

        @Override
        public void onFillable()
        {
            try
            {
                while (BufferUtil.space(_buffer) > 0)
                {
                    // Read data
                    int fill = getEndPoint().fill(_buffer);
                    if (fill < 0)
                    {
                        getEndPoint().shutdownOutput();
                        return;
                    }
                    if (fill == 0)
                    {
                        fillInterested();
                        return;
                    }
                }

                // Is it a V1?
                switch (_buffer.get(0))
                {
                    case 'P':
                    {
                        ProxyProtocolV1Connection v1 = new ProxyProtocolV1Connection(getEndPoint(), _connector, _next, _buffer);
                        getEndPoint().upgrade(v1);
                        return;
                    }
                    case 0x0D:
                    {
                        ProxyProtocolV2Connection v2 = new ProxyProtocolV2Connection(getEndPoint(), _connector, _next, _buffer);
                        getEndPoint().upgrade(v2);
                        return;
                    }
                    default:
                    {
                        LOG.warn("Not PROXY protocol for {}", getEndPoint());
                        close();
                        break;
                    }
                }
            }
            catch (Throwable x)
            {
                LOG.warn("PROXY error for " + getEndPoint(), x);
                close();
            }
        }
    }

    public static class ProxyProtocolV1Connection extends AbstractConnection
    {
        // 0     1 2       3       4 5 6
        // 98765432109876543210987654321
        // PROXY P R.R.R.R L.L.L.L R Lrn

        private static final int[] SIZE = {29, 23, 21, 13, 5, 3, 1};
        private final Connector _connector;
        private final String _next;
        private final StringBuilder _builder = new StringBuilder();
        private final String[] _fields = new String[6];
        private int _index;
        private int _length;

        protected ProxyProtocolV1Connection(EndPoint endp, Connector connector, String next, ByteBuffer buffer)
        {
            super(endp, connector.getExecutor());
            _connector = connector;
            _next = next;
            _length = buffer.remaining();
            parse(buffer);
        }

        @Override
        public void onOpen()
        {
            super.onOpen();
            fillInterested();
        }

        private boolean parse(ByteBuffer buffer)
        {
            // Parse fields
            while (buffer.hasRemaining())
            {
                byte b = buffer.get();
                if (_index < 6)
                {
                    if (b == ' ' || b == '\r')
                    {
                        _fields[_index++] = _builder.toString();
                        _builder.setLength(0);
                        if (b == '\r')
                            _index = 6;
                    }
                    else if (b < ' ')
                    {
                        LOG.warn("Bad character {} for {}", b & 0xFF, getEndPoint());
                        close();
                        return false;
                    }
                    else
                    {
                        _builder.append((char)b);
                    }
                }
                else
                {
                    if (b == '\n')
                    {
                        _index = 7;
                        return true;
                    }

                    LOG.warn("Bad CRLF for {}", getEndPoint());
                    close();
                    return false;
                }
            }
            return true;
        }

        @Override
        public void onFillable()
        {
            try
            {
                ByteBuffer buffer = null;
                while (_index < 7)
                {
                    // Create a buffer that will not read too much data
                    // since once read it is impossible to push back for the 
                    // real connection to read it.
                    int size = Math.max(1, SIZE[_index] - _builder.length());
                    if (buffer == null || buffer.capacity() != size)
                        buffer = BufferUtil.allocate(size);
                    else
                        BufferUtil.clear(buffer);

                    // Read data
                    int fill = getEndPoint().fill(buffer);
                    if (fill < 0)
                    {
                        getEndPoint().shutdownOutput();
                        return;
                    }
                    if (fill == 0)
                    {
                        fillInterested();
                        return;
                    }

                    _length += fill;
                    if (_length >= 108)
                    {
                        LOG.warn("PROXY line too long {} for {}", _length, getEndPoint());
                        close();
                        return;
                    }

                    if (!parse(buffer))
                        return;
                }

                // Check proxy
                if (!"PROXY".equals(_fields[0]))
                {
                    LOG.warn("Not PROXY protocol for {}", getEndPoint());
                    close();
                    return;
                }

                String srcIP = _fields[2];
                String srcPort = _fields[4];
                String dstIP = _fields[3];
                String dstPort = _fields[5];
                // If UNKNOWN, we must ignore the information sent, so use the EndPoint's.
                boolean unknown = "UNKNOWN".equalsIgnoreCase(_fields[1]);
                if (unknown)
                {
                    srcIP = getEndPoint().getRemoteAddress().getAddress().getHostAddress();
                    srcPort = String.valueOf(getEndPoint().getRemoteAddress().getPort());
                    dstIP = getEndPoint().getLocalAddress().getAddress().getHostAddress();
                    dstPort = String.valueOf(getEndPoint().getLocalAddress().getPort());
                }
                InetSocketAddress remote = new InetSocketAddress(srcIP, Integer.parseInt(srcPort));
                InetSocketAddress local = new InetSocketAddress(dstIP, Integer.parseInt(dstPort));

                // Create the next protocol
                ConnectionFactory connectionFactory = _connector.getConnectionFactory(_next);
                if (connectionFactory == null)
                {
                    LOG.warn("No next protocol '{}' for {}", _next, getEndPoint());
                    close();
                    return;
                }

                if (LOG.isDebugEnabled())
                    LOG.warn("Next protocol '{}' for {} r={} l={}", _next, getEndPoint(), remote, local);

                EndPoint endPoint = new ProxyEndPoint(getEndPoint(), remote, local);
                Connection newConnection = connectionFactory.newConnection(_connector, endPoint);
                endPoint.upgrade(newConnection);
            }
            catch (Throwable x)
            {
                LOG.warn("PROXY error for " + getEndPoint(), x);
                close();
            }
        }
    }

    private enum Family
    {
        UNSPEC, INET, INET6, UNIX
    }

    private enum Transport
    {
        UNSPEC, STREAM, DGRAM
    }

    private static final byte[] MAGIC = new byte[]{0x0D, 0x0A, 0x0D, 0x0A, 0x00, 0x0D, 0x0A, 0x51, 0x55, 0x49, 0x54, 0x0A};

    public class ProxyProtocolV2Connection extends AbstractConnection
    {
        private final Connector _connector;
        private final String _next;
        private final boolean _local;
        private final Family _family;
        private final Transport _transport;
        private final int _length;
        private final ByteBuffer _buffer;

        protected ProxyProtocolV2Connection(EndPoint endp, Connector connector, String next, ByteBuffer buffer) throws IOException
        {
            super(endp, connector.getExecutor());
            _connector = connector;
            _next = next;

            if (buffer.remaining() != 16)
                throw new IllegalStateException();

            if (LOG.isDebugEnabled())
                LOG.debug("PROXYv2 header {} for {}", BufferUtil.toHexSummary(buffer), this);

            // struct proxy_hdr_v2 {
            //     uint8_t sig[12];  /* hex 0D 0A 0D 0A 00 0D 0A 51 55 49 54 0A */
            //     uint8_t ver_cmd;  /* protocol version and command */
            //     uint8_t fam;      /* protocol family and address */
            //     uint16_t len;     /* number of following bytes part of the header */
            // };
            for (byte magic : MAGIC)
            {
                if (buffer.get() != magic)
                    throw new IOException("Bad PROXY protocol v2 signature");
            }

            int versionAndCommand = 0xff & buffer.get();
            if ((versionAndCommand & 0xf0) != 0x20)
                throw new IOException("Bad PROXY protocol v2 version");
            _local = (versionAndCommand & 0xf) == 0x00;

            int transportAndFamily = 0xff & buffer.get();
            switch (transportAndFamily >> 4)
            {
                case 0:
                    _family = Family.UNSPEC;
                    break;
                case 1:
                    _family = Family.INET;
                    break;
                case 2:
                    _family = Family.INET6;
                    break;
                case 3:
                    _family = Family.UNIX;
                    break;
                default:
                    throw new IOException("Bad PROXY protocol v2 family");
            }

            switch (0xf & transportAndFamily)
            {
                case 0:
                    _transport = Transport.UNSPEC;
                    break;
                case 1:
                    _transport = Transport.STREAM;
                    break;
                case 2:
                    _transport = Transport.DGRAM;
                    break;
                default:
                    throw new IOException("Bad PROXY protocol v2 family");
            }

            _length = buffer.getChar();

            if (!_local && (_family == Family.UNSPEC || _family == Family.UNIX || _transport != Transport.STREAM))
                throw new IOException(String.format("Unsupported PROXY protocol v2 mode 0x%x,0x%x", versionAndCommand, transportAndFamily));

            if (_length > getMaxProxyHeader())
                throw new IOException(String.format("Unsupported PROXY protocol v2 mode 0x%x,0x%x,0x%x", versionAndCommand, transportAndFamily, _length));

            _buffer = _length > 0 ? BufferUtil.allocate(_length) : BufferUtil.EMPTY_BUFFER;
        }

        @Override
        public void onOpen()
        {
            super.onOpen();
            if (_buffer.remaining() == _length)
                next();
            else
                fillInterested();
        }

        @Override
        public void onFillable()
        {
            try
            {
                while (_buffer.remaining() < _length)
                {
                    // Read data
                    int fill = getEndPoint().fill(_buffer);
                    if (fill < 0)
                    {
                        getEndPoint().shutdownOutput();
                        return;
                    }
                    if (fill == 0)
                    {
                        fillInterested();
                        return;
                    }
                }
                next();
            }
            catch (Throwable x)
            {
                LOG.warn("PROXY error for " + getEndPoint(), x);
                close();
            }
        }

        private void next()
        {
            if (LOG.isDebugEnabled())
                LOG.debug("PROXYv2 next {} from {} for {}", _next, BufferUtil.toHexSummary(_buffer), this);

            // Create the next protocol
            ConnectionFactory connectionFactory = _connector.getConnectionFactory(_next);
            if (connectionFactory == null)
            {
                LOG.info("Next protocol '{}' for {}", _next, getEndPoint());
                close();
                return;
            }

            // Do we need to wrap the endpoint?
            EndPoint endPoint = getEndPoint();
            if (!_local)
            {
                try
                {
                    InetAddress src;
                    InetAddress dst;
                    int sp;
                    int dp;

                    switch (_family)
                    {
                        case INET:
                        {
                            byte[] addr = new byte[4];
                            _buffer.get(addr);
                            src = Inet4Address.getByAddress(addr);
                            _buffer.get(addr);
                            dst = Inet4Address.getByAddress(addr);
                            sp = _buffer.getChar();
                            dp = _buffer.getChar();
                            break;
                        }

                        case INET6:
                        {
                            byte[] addr = new byte[16];
                            _buffer.get(addr);
                            src = Inet6Address.getByAddress(addr);
                            _buffer.get(addr);
                            dst = Inet6Address.getByAddress(addr);
                            sp = _buffer.getChar();
                            dp = _buffer.getChar();
                            break;
                        }

                        default:
                            throw new IllegalStateException();
                    }

                    // Extract Addresses
                    InetSocketAddress remote = new InetSocketAddress(src, sp);
                    InetSocketAddress local = new InetSocketAddress(dst, dp);
                    ProxyEndPoint proxyEndPoint = new ProxyEndPoint(endPoint, remote, local);
                    endPoint = proxyEndPoint;

                    // Any additional info?
                    while (_buffer.hasRemaining())
                    {
                        int type = 0xff & _buffer.get();
                        int length = _buffer.getShort();
                        byte[] value = new byte[length];
                        _buffer.get(value);

                        if (LOG.isDebugEnabled())
                            LOG.debug(String.format("T=%x L=%d V=%s for %s", type, length, TypeUtil.toHexString(value), this));

                        switch (type)
                        {
                            case 0x20: // PP2_TYPE_SSL
                            {
                                int client = value[0] & 0xFF;
                                switch (client)
                                {
                                    case 0x01: // PP2_CLIENT_SSL
                                    {
                                        int i = 5; // Index of the first sub_tlv, after verify.
                                        while (i < length)
                                        {
                                            int subType = value[i++] & 0xFF;
                                            int subLength = (value[i++] & 0xFF) * 256 + (value[i++] & 0xFF);
                                            byte[] subValue = new byte[subLength];
                                            System.arraycopy(value, i, subValue, 0, subLength);
                                            i += subLength;
                                            switch (subType)
                                            {
                                                case 0x21: // PP2_SUBTYPE_SSL_VERSION
                                                    String tlsVersion = new String(subValue, StandardCharsets.US_ASCII);
                                                    proxyEndPoint.setAttribute(TLS_VERSION, tlsVersion);
                                                    break;
                                                case 0x22: // PP2_SUBTYPE_SSL_CN
                                                case 0x23: // PP2_SUBTYPE_SSL_CIPHER
                                                case 0x24: // PP2_SUBTYPE_SSL_SIG_ALG
                                                case 0x25: // PP2_SUBTYPE_SSL_KEY_ALG
                                                default:
                                                    break;
                                            }
                                        }
                                        break;
                                    }
                                    case 0x02: // PP2_CLIENT_CERT_CONN
                                    case 0x04: // PP2_CLIENT_CERT_SESS
                                    default:
                                        break;
                                }
                                break;
                            }
                            case 0x01: // PP2_TYPE_ALPN
                            case 0x02: // PP2_TYPE_AUTHORITY
                            case 0x03: // PP2_TYPE_CRC32C
                            case 0x04: // PP2_TYPE_NOOP
                            case 0x30: // PP2_TYPE_NETNS
                            default:
                                break;
                        }
                    }

                    if (LOG.isDebugEnabled())
                        LOG.debug("{} {}", getEndPoint(), proxyEndPoint.toString());
                }
                catch (Exception e)
                {
                    LOG.warn(e);
                }
            }

            Connection newConnection = connectionFactory.newConnection(_connector, endPoint);
            endPoint.upgrade(newConnection);
        }
    }

    public static class ProxyEndPoint extends AttributesMap implements EndPoint
    {
        private final EndPoint _endp;
        private final InetSocketAddress _remote;
        private final InetSocketAddress _local;

        public ProxyEndPoint(EndPoint endp, InetSocketAddress remote, InetSocketAddress local)
        {
            _endp = endp;
            _remote = remote;
            _local = local;
        }

        @Override
        public InetSocketAddress getLocalAddress()
        {
            return _local;
        }

        @Override
        public InetSocketAddress getRemoteAddress()
        {
            return _remote;
        }

        @Override
        public String toString()
        {
            return String.format("%s@%x[remote=%s,local=%s,endpoint=%s]",
                getClass().getSimpleName(),
                hashCode(),
                _remote,
                _local,
                _endp);
        }

        @Override
        public boolean isOpen()
        {
            return _endp.isOpen();
        }

        @Override
        public long getCreatedTimeStamp()
        {
            return _endp.getCreatedTimeStamp();
        }

        @Override
        public void shutdownOutput()
        {
            _endp.shutdownOutput();
        }

        @Override
        public boolean isOutputShutdown()
        {
            return _endp.isOutputShutdown();
        }

        @Override
        public boolean isInputShutdown()
        {
            return _endp.isInputShutdown();
        }

        @Override
        public void close(Throwable cause)
        {
            _endp.close(cause);
        }

        @Override
        public int fill(ByteBuffer buffer) throws IOException
        {
            return _endp.fill(buffer);
        }

        @Override
        public boolean flush(ByteBuffer... buffer) throws IOException
        {
            return _endp.flush(buffer);
        }

        @Override
        public Object getTransport()
        {
            return _endp.getTransport();
        }

        @Override
        public long getIdleTimeout()
        {
            return _endp.getIdleTimeout();
        }

        @Override
        public void setIdleTimeout(long idleTimeout)
        {
            _endp.setIdleTimeout(idleTimeout);
        }

        @Override
        public void fillInterested(Callback callback) throws ReadPendingException
        {
            _endp.fillInterested(callback);
        }

        @Override
        public boolean tryFillInterested(Callback callback)
        {
            return _endp.tryFillInterested(callback);
        }

        @Override
        public boolean isFillInterested()
        {
            return _endp.isFillInterested();
        }

        @Override
        public void write(Callback callback, ByteBuffer... buffers) throws WritePendingException
        {
            _endp.write(callback, buffers);
        }

        @Override
        public Connection getConnection()
        {
            return _endp.getConnection();
        }

        @Override
        public void setConnection(Connection connection)
        {
            _endp.setConnection(connection);
        }

        @Override
        public void onOpen()
        {
            _endp.onOpen();
        }

        @Override
        public void onClose(Throwable cause)
        {
            _endp.onClose(cause);
        }

        @Override
        public void upgrade(Connection newConnection)
        {
            _endp.upgrade(newConnection);
        }
    }
}
