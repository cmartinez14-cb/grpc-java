/*
 * Copyright 2024 The gRPC Authors
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

package io.grpc.xds;

import static com.google.common.base.Preconditions.checkNotNull;

import io.grpc.CallOptions;
import io.grpc.ChannelCredentials;
import io.grpc.ClientCall;
import io.grpc.Grpc;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import java.util.concurrent.TimeUnit;

final class GrpcXdsTransportFactory implements XdsTransportFactory {

  static final XdsTransportFactory DEFAULT_XDS_TRANSPORT_FACTORY = new GrpcXdsTransportFactory();

  @Override
  public XdsTransport create(Bootstrapper.ServerInfo serverInfo) {
    return new GrpcXdsTransport(serverInfo);
  }

  private class GrpcXdsTransport implements XdsTransport {

    private final ManagedChannel channel;

    public GrpcXdsTransport(Bootstrapper.ServerInfo serverInfo) {
      String target = serverInfo.target();
      ChannelCredentials channelCredentials = serverInfo.channelCredentials();
      this.channel = Grpc.newChannelBuilder(target, channelCredentials)
          .keepAliveTime(5, TimeUnit.MINUTES)
          .build();
    }

    @Override
    public <ReqT, RespT> StreamingCall<ReqT, RespT> createStreamingCall(
        String fullMethodName,
        MethodDescriptor.Marshaller<ReqT> reqMarshaller,
        MethodDescriptor.Marshaller<RespT> respMarshaller) {
      return new XdsStreamingCall<>(fullMethodName, reqMarshaller, respMarshaller);
    }

    @Override
    public void shutdown() {
      channel.shutdown();
    }

    private class XdsStreamingCall<ReqT, RespT> implements
        XdsTransportFactory.StreamingCall<ReqT, RespT> {

      private final ClientCall<ReqT, RespT> call;

      public XdsStreamingCall(String methodName, MethodDescriptor.Marshaller<ReqT> reqMarshaller,
                              MethodDescriptor.Marshaller<RespT> respMarshaller) {
        this.call = channel.newCall(
            MethodDescriptor.<ReqT, RespT>newBuilder()
                .setFullMethodName(methodName)
                .setType(MethodDescriptor.MethodType.BIDI_STREAMING)
                .setRequestMarshaller(reqMarshaller)
                .setResponseMarshaller(respMarshaller)
                .build(),
            CallOptions.DEFAULT); // TODO(zivy): support waitForReady
      }

      @Override
      public void start(EventHandler<RespT> eventHandler) {
        call.start(new EventHandlerToCallListenerAdapter<>(eventHandler), new Metadata());
        call.request(1);
      }

      @Override
      public void sendMessage(ReqT message) {
        call.sendMessage(message);
      }

      @Override
      public void startRecvMessage() {
        call.request(1);
      }

      @Override
      public void sendError(Exception e) {
        call.cancel("Cancelled by XdsClientImpl", e);
      }

      @Override
      public boolean isReady() {
        return call.isReady();
      }
    }
  }

  private static class EventHandlerToCallListenerAdapter<T> extends ClientCall.Listener<T> {
    private final EventHandler<T> handler;

    EventHandlerToCallListenerAdapter(EventHandler<T> eventHandler) {
      this.handler = checkNotNull(eventHandler, "eventHandler");
    }

    @Override
    public void onHeaders(Metadata headers) {}

    @Override
    public void onMessage(T message) {
      handler.onRecvMessage(message);
    }

    @Override
    public void onClose(Status status, Metadata trailers) {
      handler.onStatusReceived(status);
    }

    @Override
    public void onReady() {
      handler.onReady();
    }
  }
}
