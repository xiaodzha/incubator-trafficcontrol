/*
 * Copyright 2015 Comcast Cable Communications Management, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.comcast.cdn.traffic_control.traffic_router.core.dns.protocol;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.eq;
import static org.powermock.api.mockito.PowerMockito.whenNew;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.Section;
import org.xbill.DNS.Type;

import com.comcast.cdn.traffic_control.traffic_router.core.dns.NameServer;
import com.comcast.cdn.traffic_control.traffic_router.core.dns.protocol.TCP.TCPSocketHandler;
import com.comcast.cdn.traffic_control.traffic_router.core.dns.DNSAccessRecord;

@RunWith(PowerMockRunner.class)
@PrepareForTest({AbstractProtocol.class, Message.class})
public class TCPTest {
    private Socket socket;
    private ExecutorService executorService;
    private NameServer nameServer;

    private TCP tcp;
    private InetAddress client;
    private ByteArrayInputStream in;
    private ByteArrayOutputStream out;

    @Before
    public void setUp() throws Exception {
        ServerSocket serverSocket = mock(ServerSocket.class);
        socket = mock(Socket.class);
        executorService = mock(ExecutorService.class);
        nameServer = mock(NameServer.class);
        tcp = new TCP();
        tcp.setServerSocket(serverSocket);
        tcp.setExecutorService(executorService);
        tcp.setNameServer(nameServer);

        in = mock(ByteArrayInputStream.class);
        client = InetAddress.getLocalHost();
        when(socket.getInetAddress()).thenReturn(client);
        when(socket.getInputStream()).thenReturn(in);
    }

    @Test
    public void testGetMaxResponseLength() {
        assertEquals(Integer.MAX_VALUE, tcp.getMaxResponseLength(null));
    }

    @Test
    public void testSubmit() {
        final Runnable r = mock(Runnable.class);
        tcp.submit(r);
        verify(executorService).submit(r);
    }

    @Test
    public void testTCPSocketHandler() throws Exception {
        client = InetAddress.getLocalHost();
        final TCPSocketHandler handler = tcp.new TCPSocketHandler(socket);

        final Name name = Name.fromString("www.foo.bar.");
        final Record question = Record.newRecord(name, Type.A, DClass.IN);
        final Message request = Message.newQuery(question);
        final byte[] wireRequest = request.toWire();

        final ByteArrayOutputStream requestOut = new ByteArrayOutputStream();
        final DataOutputStream dos = new DataOutputStream(requestOut);
        dos.writeShort(wireRequest.length);
        dos.write(wireRequest);

        in = new ByteArrayInputStream(requestOut.toByteArray());
        out = new ByteArrayOutputStream();

        when(socket.getInputStream()).thenReturn(in);
        when(socket.getOutputStream()).thenReturn(out);

        when(nameServer.query(any(Message.class), eq(client), any(DNSAccessRecord.Builder.class))).thenReturn(request);
        handler.run();
        assertArrayEquals(requestOut.toByteArray(), out.toByteArray());
    }

    @Test
    public void testTCPSocketHandlerBadMessage() throws Exception {
        final InetAddress client = InetAddress.getLocalHost();
        final TCPSocketHandler handler = tcp.new TCPSocketHandler(socket);

        final byte[] wireRequest = new byte[0];

        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        final DataOutputStream dos = new DataOutputStream(baos);
        dos.writeShort(wireRequest.length);
        dos.write(wireRequest);

        final ByteArrayOutputStream out = new ByteArrayOutputStream();

        when(socket.getOutputStream()).thenReturn(out);

        handler.run();
        assertThat(out.toByteArray().length, equalTo(0));
    }

    @Test
    public void testTCPSocketHandlerQueryFail() throws Exception {
        final InetAddress client = InetAddress.getLocalHost();

        final Name name = Name.fromString("www.foo.bar.");
        final Record question = Record.newRecord(name, Type.A, DClass.IN);
        final Message request = Message.newQuery(question);

        final Message response = new Message();
        response.setHeader(request.getHeader());

        for (int i = 0; i < 4; i++) {
            response.removeAllRecords(i);
        }

        response.addRecord(question, Section.QUESTION);
        response.getHeader().setRcode(Rcode.SERVFAIL);

        final byte[] serverFail = response.toWire();

        final ByteArrayOutputStream expectedResponseOut = new ByteArrayOutputStream();
        final DataOutputStream dos2 = new DataOutputStream(expectedResponseOut);
        dos2.writeShort(serverFail.length);
        dos2.write(serverFail);

        final ByteArrayOutputStream responseOut = new ByteArrayOutputStream();
        when(socket.getOutputStream()).thenReturn(responseOut);
        when(nameServer.query(any(Message.class), eq(client), any(DNSAccessRecord.Builder.class))).thenThrow(new RuntimeException("TCP Query Boom!"));

        Message tmp = new Message();
        whenNew(Message.class).withParameterTypes(byte[].class).withArguments(any(byte[].class)).thenReturn(request);
        whenNew(Message.class).withNoArguments().thenReturn(tmp);

        final TCPSocketHandler handler = tcp.new TCPSocketHandler(socket);
        handler.run();
        verify(socket).close();

        final byte[] expected = expectedResponseOut.toByteArray();
        final byte[] actual = responseOut.toByteArray();
        assertArrayEquals(expected, actual);
    }

}
