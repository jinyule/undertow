/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2014 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package io.undertow.client.http;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.xnio.ChannelListener;
import org.xnio.IoFuture;
import org.xnio.OptionMap;
import org.xnio.Options;
import org.xnio.StreamConnection;
import org.xnio.XnioIoThread;
import org.xnio.XnioWorker;
import org.xnio.ssl.SslConnection;
import org.xnio.ssl.XnioSsl;

import io.undertow.UndertowMessages;
import io.undertow.UndertowOptions;
import io.undertow.xnio.client.ALPNClientSelector;
import io.undertow.xnio.client.ClientCallback;
import io.undertow.xnio.client.ClientConnection;
import io.undertow.xnio.client.ClientProvider;
import io.undertow.client.http2.Http2ClientProvider;
import io.undertow.connector.ByteBufferPool;

/**
 * @author Stuart Douglas
 */
public class HttpClientProvider implements ClientProvider {

    @Override
    public Set<String> handlesSchemes() {
        return new HashSet<>(Arrays.asList(new String[]{"http", "https"}));
    }

    @Override
    public void connect(final ClientCallback<ClientConnection> listener, final URI uri, final XnioWorker worker, final XnioSsl ssl, final ByteBufferPool bufferPool, final OptionMap options) {
        connect(listener, null, uri, worker, ssl, bufferPool, options);
    }

    @Override
    public void connect(final ClientCallback<ClientConnection> listener, final URI uri, final XnioIoThread ioThread, final XnioSsl ssl, final ByteBufferPool bufferPool, final OptionMap options) {
        connect(listener, null, uri, ioThread, ssl, bufferPool, options);
    }

    @Override
    public void connect(ClientCallback<ClientConnection> listener, InetSocketAddress bindAddress, URI uri, XnioWorker worker, XnioSsl ssl, ByteBufferPool bufferPool, OptionMap options) {
        if (uri.getScheme().equals("https")) {
            if (ssl == null) {
                listener.failed(UndertowMessages.MESSAGES.sslWasNull());
                return;
            }
            OptionMap tlsOptions = OptionMap.builder().addAll(options).set(Options.SSL_STARTTLS, true).getMap();
            if (bindAddress == null) {
                ssl.openSslConnection(worker, new InetSocketAddress(uri.getHost(), uri.getPort() == -1 ? 443 : uri.getPort()), createOpenListener(listener, bufferPool, tlsOptions, uri), tlsOptions).addNotifier(createNotifier(listener), null);
            } else {
                ssl.openSslConnection(worker, bindAddress, new InetSocketAddress(uri.getHost(), uri.getPort() == -1 ? 443 : uri.getPort()), createOpenListener(listener, bufferPool, tlsOptions, uri), tlsOptions).addNotifier(createNotifier(listener), null);
            }
        } else {
            if (bindAddress == null) {
                worker.openStreamConnection(new InetSocketAddress(uri.getHost(), uri.getPort() == -1 ? 80 : uri.getPort()), createOpenListener(listener, bufferPool, options, uri), options).addNotifier(createNotifier(listener), null);
            } else {
                worker.openStreamConnection(bindAddress, new InetSocketAddress(uri.getHost(), uri.getPort() == -1 ? 80 : uri.getPort()), createOpenListener(listener, bufferPool, options, uri), null, options).addNotifier(createNotifier(listener), null);
            }
        }
    }

    @Override
    public void connect(ClientCallback<ClientConnection> listener, InetSocketAddress bindAddress, URI uri, XnioIoThread ioThread, XnioSsl ssl, ByteBufferPool bufferPool, OptionMap options) {
        if (uri.getScheme().equals("https")) {
            if (ssl == null) {
                listener.failed(UndertowMessages.MESSAGES.sslWasNull());
                return;
            }
            OptionMap tlsOptions = OptionMap.builder().addAll(options).set(Options.SSL_STARTTLS, true).getMap();
            if (bindAddress == null) {
                ssl.openSslConnection(ioThread, new InetSocketAddress(uri.getHost(), uri.getPort() == -1 ? 443 : uri.getPort()), createOpenListener(listener, bufferPool, tlsOptions, uri), tlsOptions).addNotifier(createNotifier(listener), null);
            } else {
                ssl.openSslConnection(ioThread, bindAddress, new InetSocketAddress(uri.getHost(), uri.getPort() == -1 ? 443 : uri.getPort()), createOpenListener(listener, bufferPool, tlsOptions, uri), tlsOptions).addNotifier(createNotifier(listener), null);
            }
        } else {
            if (bindAddress == null) {
                ioThread.openStreamConnection(new InetSocketAddress(uri.getHost(), uri.getPort() == -1 ? 80 : uri.getPort()), createOpenListener(listener, bufferPool, options, uri), options).addNotifier(createNotifier(listener), null);
            } else {
                ioThread.openStreamConnection(bindAddress, new InetSocketAddress(uri.getHost(), uri.getPort() == -1 ? 80 : uri.getPort()), createOpenListener(listener, bufferPool, options, uri), null, options).addNotifier(createNotifier(listener), null);
            }
        }
    }

    private IoFuture.Notifier<StreamConnection, Object> createNotifier(final ClientCallback<ClientConnection> listener) {
        return new IoFuture.Notifier<StreamConnection, Object>() {
            @Override
            public void notify(IoFuture<? extends StreamConnection> ioFuture, Object o) {
                if (ioFuture.getStatus() == IoFuture.Status.FAILED) {
                    listener.failed(ioFuture.getException());
                }
            }
        };
    }

    private ChannelListener<StreamConnection> createOpenListener(final ClientCallback<ClientConnection> listener, final ByteBufferPool bufferPool, final OptionMap options, final URI uri) {
        return new ChannelListener<StreamConnection>() {
            @Override
            public void handleEvent(StreamConnection connection) {
                handleConnected(connection, listener, bufferPool, options, uri);
            }
        };
    }


    private void handleConnected(final StreamConnection connection, final ClientCallback<ClientConnection> listener, final ByteBufferPool bufferPool, final OptionMap options, URI uri) {

        boolean h2 = options.get(UndertowOptions.ENABLE_HTTP2, false);
        if(connection instanceof SslConnection && (h2)) {
            List<ALPNClientSelector.ALPNProtocol> protocolList = new ArrayList<>();
            if(h2) {
                protocolList.add(Http2ClientProvider.alpnProtocol(listener, uri, bufferPool, options));
            }

            ALPNClientSelector.runAlpn((SslConnection) connection, new ChannelListener<SslConnection>() {
                @Override
                public void handleEvent(SslConnection connection) {
                    listener.completed(new HttpClientConnection(connection, options, bufferPool));
                }
            }, listener, protocolList.toArray(new ALPNClientSelector.ALPNProtocol[protocolList.size()]));
        } else {
            if(connection instanceof SslConnection) {
                try {
                    ((SslConnection) connection).startHandshake();
                } catch (Throwable t) {
                    listener.failed((t instanceof IOException) ? (IOException) t : new IOException(t));
                }
            }
            listener.completed(new HttpClientConnection(connection, options, bufferPool));
        }
    }
}
