/*
 * Copyright 2014, Google Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *    * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *    * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *
 *    * Neither the name of Google Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package io.grpc.transport.netty;

import static com.google.common.base.Charsets.UTF_8;
import static io.grpc.transport.netty.Utils.CONTENT_TYPE_GRPC;
import static io.grpc.transport.netty.Utils.CONTENT_TYPE_HEADER;
import static io.grpc.transport.netty.Utils.HTTPS;
import static io.grpc.transport.netty.Utils.HTTP_METHOD;
import static io.grpc.transport.netty.Utils.STATUS_OK;
import static io.grpc.transport.netty.Utils.TE_HEADER;
import static io.grpc.transport.netty.Utils.TE_TRAILERS;
import static io.netty.handler.codec.http2.Http2CodecUtil.DEFAULT_WINDOW_SIZE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.notNull;
import static org.mockito.Mockito.calls;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.grpc.Metadata;
import io.grpc.Status;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelPromise;
import io.netty.handler.codec.http2.DefaultHttp2Connection;
import io.netty.handler.codec.http2.DefaultHttp2ConnectionEncoder;
import io.netty.handler.codec.http2.DefaultHttp2FrameReader;
import io.netty.handler.codec.http2.DefaultHttp2FrameWriter;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2CodecUtil;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2FlowController;
import io.netty.handler.codec.http2.Http2FrameReader;
import io.netty.handler.codec.http2.Http2FrameWriter;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.Http2Stream;
import io.netty.util.AsciiString;
import io.netty.util.concurrent.ImmediateEventExecutor;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link NettyClientHandler}.
 */
@RunWith(JUnit4.class)
public class NettyClientHandlerTest extends NettyHandlerTestBase {

  private NettyClientHandler handler;

  // TODO(zhangkun83): mocking concrete classes is not safe. Consider making NettyClientStream an
  // interface.
  @Mock
  private NettyClientStream stream;

  private ByteBuf content;
  private Http2Headers grpcHeaders;

  /** Set up for test. */
  @Before
  public void setUp() throws Exception {
    MockitoAnnotations.initMocks(this);

    frameWriter = new DefaultHttp2FrameWriter();
    frameReader = new DefaultHttp2FrameReader();
    handler = newHandler(DEFAULT_WINDOW_SIZE, DEFAULT_WINDOW_SIZE);
    content = Unpooled.copiedBuffer("hello world", UTF_8);

    when(channel.isActive()).thenReturn(true);
    mockContext();
    mockFuture(true);

    grpcHeaders = new DefaultHttp2Headers()
        .scheme(HTTPS)
        .authority(as("www.fake.com"))
        .path(as("/fakemethod"))
        .method(HTTP_METHOD)
        .add(as("auth"), as("sometoken"))
        .add(CONTENT_TYPE_HEADER, CONTENT_TYPE_GRPC)
        .add(TE_HEADER, TE_TRAILERS);

    // Simulate activation of the handler to force writing of the initial settings
    handler.handlerAdded(ctx);

    // Simulate receipt of initial remote settings.
    ByteBuf serializedSettings = serializeSettings(new Http2Settings());
    handler.channelRead(ctx, serializedSettings);

    // Reset the context to clear any interactions resulting from the HTTP/2
    // connection preface handshake.
    mockContext();
    mockFuture(promise, true);
  }

  @Test
  public void cancelBufferedStreamShouldChangeClientStreamStatus() throws Exception {
    // Force the stream to be buffered.
    receiveMaxConcurrentStreams(0);
    // Create a new stream with id 3.
    ChannelPromise createPromise =
            new DefaultChannelPromise(channel, ImmediateEventExecutor.INSTANCE);
    handler.write(ctx, new CreateStreamCommand(grpcHeaders, stream), createPromise);
    verify(stream).id(eq(3));
    when(stream.id()).thenReturn(3);
    // Cancel the stream.
    handler.write(ctx, new CancelStreamCommand(stream), promise);

    verify(promise).setSuccess();
    assertTrue(createPromise.isSuccess());
    verify(stream).transportReportStatus(eq(Status.CANCELLED), eq(true),
        any(Metadata.Trailers.class));
  }

  @Test
  public void createStreamShouldSucceed() throws Exception {
    handler.write(ctx, new CreateStreamCommand(grpcHeaders, stream), promise);
    when(promise.isSuccess()).thenReturn(true);
    verify(stream).id(eq(3));

    // Capture and verify the written headers frame.
    ByteBuf serializedHeaders = captureWrite(ctx);
    ChannelHandlerContext ctx = newContext();
    frameReader.readFrame(ctx, serializedHeaders, frameListener);
    ArgumentCaptor<Http2Headers> captor = ArgumentCaptor.forClass(Http2Headers.class);
    verify(frameListener).onHeadersRead(eq(ctx), eq(3), captor.capture(), eq(0),
            eq(Http2CodecUtil.DEFAULT_PRIORITY_WEIGHT), eq(false), eq(0), eq(false));
    Http2Headers headers = captor.getValue();
    assertEquals("https", headers.scheme().toString());
    assertEquals(HTTP_METHOD, headers.method());
    assertEquals("www.fake.com", headers.authority().toString());
    assertEquals(CONTENT_TYPE_GRPC, headers.get(CONTENT_TYPE_HEADER));
    assertEquals(TE_TRAILERS, headers.get(TE_HEADER));
    assertEquals("/fakemethod", headers.path().toString());
    assertEquals("sometoken", headers.get(as("auth")).toString());
  }

  @Test
  public void cancelShouldSucceed() throws Exception {
    createStream();

    handler.write(ctx, new CancelStreamCommand(stream), promise);

    ByteBuf expected = rstStreamFrame(3, (int) Http2Error.CANCEL.code());
    verify(ctx).write(eq(expected), eq(promise));
  }

  @Test
  public void cancelWhileBufferedShouldSucceed() throws Exception {
    handler.connection().local().maxActiveStreams(0);
    handler.write(ctx, new CreateStreamCommand(grpcHeaders, stream), promise);
    mockContext();
    ArgumentCaptor<Integer> idCaptor = ArgumentCaptor.forClass(Integer.class);
    verify(stream).id(idCaptor.capture());
    when(stream.id()).thenReturn(idCaptor.getValue());
    ChannelPromise cancelPromise = mock(ChannelPromise.class);
    handler.write(ctx, new CancelStreamCommand(stream), cancelPromise);
    verify(cancelPromise).setSuccess();
    verify(promise).setSuccess();
    verifyNoMoreInteractions(ctx);
  }

  /**
   * Although nobody is listening to an exception should it occur during cancel(), we don't want an
   * exception to be thrown because it would negatively impact performance, and we don't want our
   * users workarounding around such performance issues.
   */
  @Test
  public void cancelTwiceShouldSucceed() throws Exception {
    createStream();

    handler.write(ctx, new CancelStreamCommand(stream), promise);

    ByteBuf expected = rstStreamFrame(3, (int) Http2Error.CANCEL.code());
    verify(ctx).write(eq(expected), eq(promise));

    promise = mock(ChannelPromise.class);
    handler.write(ctx, new CancelStreamCommand(stream), promise);
    verify(promise).setSuccess();
  }

  @Test
  public void sendFrameShouldSucceed() throws Exception {
    createStream();
    // Send a frame and verify that it was written.
    handler.write(ctx, new SendGrpcFrameCommand(stream, content, true), promise);
    verify(promise, never()).setFailure(any(Throwable.class));
    ByteBuf bufWritten = captureWrite(ctx);
    int startIndex = bufWritten.readerIndex() + Http2CodecUtil.FRAME_HEADER_LENGTH;
    int length = bufWritten.writerIndex() - startIndex;
    ByteBuf writtenContent = bufWritten.slice(startIndex, length);
    assertEquals(content, writtenContent);
  }

  @Test
  public void sendForUnknownStreamShouldFail() throws Exception {
    when(stream.id()).thenReturn(3);
    handler.write(ctx, new SendGrpcFrameCommand(stream, content, true), promise);
    verify(promise).setFailure(any(Throwable.class));
  }

  @Test
  public void inboundHeadersShouldForwardToStream() throws Exception {
    createStream();

    // Read a headers frame first.
    Http2Headers headers = new DefaultHttp2Headers().status(STATUS_OK)
        .set(CONTENT_TYPE_HEADER, CONTENT_TYPE_GRPC);
    ByteBuf headersFrame = headersFrame(3, headers);
    handler.channelRead(ctx, headersFrame);
    verify(stream).transportHeadersReceived(headers, false);
  }

  @Test
  public void inboundDataShouldForwardToStream() throws Exception {
    createStream();

    // Create a data frame and then trigger the handler to read it.
    // Need to retain to simulate what is done by the stream.
    ByteBuf frame = dataFrame(3, false).retain();
    handler.channelRead(ctx, frame);
    verify(stream).transportDataReceived(eq(content), eq(false));
  }

  @Test
  public void receivedGoAwayShouldCancelBufferedStream() throws Exception {
    // Force the stream to be buffered.
    receiveMaxConcurrentStreams(0);
    ChannelPromise promise = new DefaultChannelPromise(channel, ImmediateEventExecutor.INSTANCE);
    handler.write(ctx, new CreateStreamCommand(grpcHeaders, stream), promise);
    handler.channelRead(ctx, goAwayFrame(0));
    assertTrue(promise.isDone());
    assertFalse(promise.isSuccess());
    verify(stream).transportReportStatus(any(Status.class), eq(false),
        notNull(Metadata.Trailers.class));
  }

  @Test
  public void receivedGoAwayShouldFailUnknownStreams() throws Exception {
    handler.write(ctx, new CreateStreamCommand(grpcHeaders, stream), promise);

    // Read a GOAWAY that indicates our stream was never processed by the server.
    handler.channelRead(ctx,
        goAwayFrame(0, 8 /* Cancel */, Unpooled.copiedBuffer("this is a test", UTF_8)));
    ArgumentCaptor<Status> captor = ArgumentCaptor.forClass(Status.class);
    verify(stream).transportReportStatus(captor.capture(), eq(false),
        notNull(Metadata.Trailers.class));
    assertEquals(Status.CANCELLED.getCode(), captor.getValue().getCode());
    assertEquals("HTTP/2 error code: CANCEL\nthis is a test",
        captor.getValue().getDescription());
  }

  @Test
  public void receivedGoAwayShouldFailUnknownBufferedStreams() throws Exception {
    receiveMaxConcurrentStreams(0);

    ChannelPromise promise1 = new DefaultChannelPromise(channel, ImmediateEventExecutor.INSTANCE);

    handler.write(ctx, new CreateStreamCommand(grpcHeaders, stream), promise1);

    // Read a GOAWAY that indicates our stream was never processed by the server.
    handler.channelRead(ctx,
        goAwayFrame(0, 8 /* Cancel */, Unpooled.copiedBuffer("this is a test", UTF_8)));
    ArgumentCaptor<Status> captor = ArgumentCaptor.forClass(Status.class);
    verify(stream).transportReportStatus(captor.capture(), eq(false),
        notNull(Metadata.Trailers.class));
    assertEquals(Status.CANCELLED.getCode(), captor.getValue().getCode());
    assertEquals("HTTP/2 error code: CANCEL\nthis is a test",
        captor.getValue().getDescription());
  }

  @Test
  public void cancelStreamShouldCreateAndThenFailBufferedStream() throws Exception {
    receiveMaxConcurrentStreams(0);
    handler.write(ctx, new CreateStreamCommand(grpcHeaders, stream), promise);
    verify(stream).id(3);
    when(stream.id()).thenReturn(3);
    handler.write(ctx, new CancelStreamCommand(stream), promise);
    verify(promise, times(2)).setSuccess();
    verify(stream).transportReportStatus(eq(Status.CANCELLED), eq(true),
        any(Metadata.Trailers.class));
  }

  @Test
  public void channelShutdownShouldCancelBufferedStreams() throws Exception {
    // Force a stream to get added to the pending queue.
    receiveMaxConcurrentStreams(0);
    handler.write(ctx, new CreateStreamCommand(grpcHeaders, stream), promise);

    handler.channelInactive(ctx);
    verify(promise).setFailure(any(Throwable.class));
  }

  @Test
  public void channelShutdownShouldFailInFlightStreams() throws Exception {
    createStream();

    handler.channelInactive(ctx);
    ArgumentCaptor<Status> captor = ArgumentCaptor.forClass(Status.class);
    InOrder inOrder = inOrder(stream);
    inOrder.verify(stream, calls(1)).transportReportStatus(captor.capture(), eq(false),
        notNull(Metadata.Trailers.class));
    assertEquals(Status.UNAVAILABLE.getCode(), captor.getValue().getCode());
  }

  @Test
  public void connectionWindowShouldBeOverridden() throws Exception {
    int connectionWindow = 1048576; // 1MiB
    handler = newHandler(connectionWindow, DEFAULT_WINDOW_SIZE);
    handler.handlerAdded(ctx);
    Http2Stream connectionStream = handler.connection().connectionStream();
    Http2FlowController localFlowController = handler.connection().local().flowController();
    int actualInitialWindowSize = localFlowController.initialWindowSize(connectionStream);
    int actualWindowSize = localFlowController.windowSize(connectionStream);
    assertEquals(connectionWindow, actualWindowSize);
    assertEquals(connectionWindow, actualInitialWindowSize);
  }

  @Test
  public void createIncrementsIdsForActualAndBufferdStreams() throws Exception {
    receiveMaxConcurrentStreams(2);
    handler.write(ctx, new CreateStreamCommand(grpcHeaders, stream), promise);
    verify(stream).id(eq(3));
    handler.write(ctx, new CreateStreamCommand(grpcHeaders, stream), promise);
    verify(stream).id(eq(5));
    handler.write(ctx, new CreateStreamCommand(grpcHeaders, stream), promise);
    verify(stream).id(eq(7));
  }

  private void receiveMaxConcurrentStreams(int max) throws Exception {
    ByteBuf serializedSettings = serializeSettings(new Http2Settings().maxConcurrentStreams(max));
    handler.channelRead(ctx, serializedSettings);
    // Reset the context to clear this write.
    mockContext();
  }

  private ByteBuf dataFrame(int streamId, boolean endStream) {
    // Need to retain the content since the frameWriter releases it.
    content.retain();
    ChannelHandlerContext ctx = newContext();
    frameWriter.writeData(ctx, streamId, content, 0, endStream, newPromise());
    return captureWrite(ctx);
  }

  private void createStream() throws Exception {
    // Create the stream.
    handler.write(ctx, new CreateStreamCommand(grpcHeaders, stream), promise);
    when(stream.id()).thenReturn(3);
    // Reset the context mock to clear recording of sent headers frame.
    mockContext();
  }

  private static NettyClientHandler newHandler(int connectionWindowSize, int streamWindowSize) {
    Http2Connection connection = new DefaultHttp2Connection(false);
    Http2FrameReader frameReader = new DefaultHttp2FrameReader();
    Http2FrameWriter frameWriter = new DefaultHttp2FrameWriter();
    BufferingHttp2ConnectionEncoder encoder = new BufferingHttp2ConnectionEncoder(
            new DefaultHttp2ConnectionEncoder(connection, frameWriter));
    return new NettyClientHandler(encoder, connection, frameReader, connectionWindowSize,
        streamWindowSize);
  }

  private AsciiString as(String string) {
    return new AsciiString(string);
  }
}
