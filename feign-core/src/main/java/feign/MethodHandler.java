/*
 * Copyright 2013 Netflix, Inc.
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
package feign;

import java.io.IOException;
import java.io.Reader;
import java.util.concurrent.Executor;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;

import dagger.Lazy;
import feign.Request.Options;
import feign.codec.Decoder;
import feign.codec.ErrorDecoder;
import feign.codec.ObserverDecoder;

import static feign.FeignException.errorExecuting;
import static feign.FeignException.errorReading;
import static feign.Util.checkNotNull;
import static feign.Util.ensureClosed;

abstract class MethodHandler {

  /**
   * same approach as retrofit: temporarily rename threads
   */
  static final String THREAD_PREFIX = "Feign-";
  static final String IDLE_THREAD_NAME = THREAD_PREFIX + "Idle";

  /**
   * Those using guava will implement as {@code Function<Object[], RequestTemplate>}.
   */
  static interface BuildTemplateFromArgs {
    public RequestTemplate apply(Object[] argv);
  }

  static class Factory {

    private final Client client;
    private final Lazy<Executor> httpExecutor;
    private final Provider<Retryer> retryer;
    private final Wire wire;

    @Inject Factory(Client client, @Named("http") Lazy<Executor> httpExecutor, Provider<Retryer> retryer, Wire wire) {
      this.client = checkNotNull(client, "client");
      this.httpExecutor = checkNotNull(httpExecutor, "httpExecutor");
      this.retryer = checkNotNull(retryer, "retryer");
      this.wire = checkNotNull(wire, "wire");
    }

    public MethodHandler create(Target<?> target, MethodMetadata md, BuildTemplateFromArgs buildTemplateFromArgs,
                                Options options, Decoder decoder, ErrorDecoder errorDecoder) {
      return new SynchronousMethodHandler(target, client, retryer, wire, md, buildTemplateFromArgs, options,
          decoder, errorDecoder);
    }

    public MethodHandler create(Target<?> target, MethodMetadata md, BuildTemplateFromArgs buildTemplateFromArgs,
                                Options options, ObserverDecoder<?> observerDecoder, ErrorDecoder errorDecoder) {
      return new ObserverMethodHandler(target, client, retryer, wire, md, buildTemplateFromArgs, options,
          observerDecoder, errorDecoder, httpExecutor);
    }
  }

  static final class ObserverMethodHandler extends MethodHandler {
    private final Lazy<Executor> httpExecutor;
    private final ObserverDecoder<?> observerDecoder;

    private ObserverMethodHandler(Target<?> target, Client client, Provider<Retryer> retryer, Wire wire,
                                  MethodMetadata metadata, BuildTemplateFromArgs buildTemplateFromArgs,
                                  Options options, ObserverDecoder<?> observerDecoder, ErrorDecoder errorDecoder,
                                  Lazy<Executor> httpExecutor) {
      super(target, client, retryer, wire, metadata, buildTemplateFromArgs, options, errorDecoder);
      this.httpExecutor = checkNotNull(httpExecutor, "httpExecutor for %s", target);
      this.observerDecoder = checkNotNull(observerDecoder, "observerDecoder for %s", target);
    }

    @Override public Object invoke(final Object[] argv) throws Throwable {
      httpExecutor.get().execute(new Runnable() {
        @Override public void run() {
          Error error = null;
          Observer<Object> observer = Observer.class.cast(argv[metadata.observerIndex()]);
          try {
            ObserverMethodHandler.super.invoke(argv);
            observer.onSuccess();
          } catch (Error cause) {
            // assign to a variable in case .onFailure throws a RTE
            error = cause;
            observer.onFailure(cause);
          } catch (Throwable cause) {
            observer.onFailure(cause);
          } finally {
            Thread.currentThread().setName(IDLE_THREAD_NAME);
            if (error != null)
              throw error;
          }
        }
      });
      return null; // void.
    }

    @Override protected Object decode(Object[] argv, Response response) throws Throwable {
      Observer<Object> observer = Observer.class.cast(argv[metadata.observerIndex()]);
      if (metadata.decodeInto().equals(Response.class)) {
        observer.onNext(response);
      } else if (metadata.decodeInto() != Void.class) {
        Response.Body body = response.body();
        if (body == null)
          return null;
        Reader reader = body.asReader();
        try {
          observerDecoder.decode(reader, metadata.decodeInto(), observer);
        } finally {
          ensureClosed(body);
        }
      }
      return null; // void
    }

    @Override protected Request targetRequest(RequestTemplate template) {
      Request request = super.targetRequest(template);
      Thread.currentThread().setName(THREAD_PREFIX + metadata.configKey());
      return request;
    }
  }

  static final class SynchronousMethodHandler extends MethodHandler {
    private final Decoder decoder;

    private SynchronousMethodHandler(Target<?> target, Client client, Provider<Retryer> retryer, Wire wire, MethodMetadata metadata,
                                     BuildTemplateFromArgs buildTemplateFromArgs, Options options, Decoder decoder, ErrorDecoder errorDecoder) {
      super(target, client, retryer, wire, metadata, buildTemplateFromArgs, options, errorDecoder);
      this.decoder = checkNotNull(decoder, "decoder for %s", target);
    }

    @Override protected Object decode(Object[] argv, Response response) throws Throwable {
      if (metadata.decodeInto().equals(Response.class)) {
        return response;
      } else if (metadata.decodeInto() == void.class) {
        return null;
      }
      return decoder.decode(response, metadata.decodeInto());
    }
  }

  protected final MethodMetadata metadata;
  protected final Target<?> target;
  protected final Client client;
  protected final Provider<Retryer> retryer;
  protected final Wire wire;

  protected final BuildTemplateFromArgs buildTemplateFromArgs;
  protected final Options options;
  protected final ErrorDecoder errorDecoder;

  private MethodHandler(Target<?> target, Client client, Provider<Retryer> retryer, Wire wire, MethodMetadata metadata,
                        BuildTemplateFromArgs buildTemplateFromArgs, Options options, ErrorDecoder errorDecoder) {
    this.target = checkNotNull(target, "target");
    this.client = checkNotNull(client, "client for %s", target);
    this.retryer = checkNotNull(retryer, "retryer for %s", target);
    this.wire = checkNotNull(wire, "wire for %s", target);
    this.metadata = checkNotNull(metadata, "metadata for %s", target);
    this.buildTemplateFromArgs = checkNotNull(buildTemplateFromArgs, "metadata for %s", target);
    this.options = checkNotNull(options, "options for %s", target);
    this.errorDecoder = checkNotNull(errorDecoder, "errorDecoder for %s", target);
  }

  public Object invoke(Object[] argv) throws Throwable {
    RequestTemplate template = buildTemplateFromArgs.apply(argv);
    Retryer retryer = this.retryer.get();
    while (true) {
      try {
        return executeAndDecode(argv, template);
      } catch (RetryableException e) {
        retryer.continueOrPropagate(e);
        continue;
      }
    }
  }

  public Object executeAndDecode(Object[] argv, RequestTemplate template)
      throws Throwable {
    // create the request from a mutable copy of the input template.
    Request request = targetRequest(template);
    wire.wireRequest(target, request);
    Response response;
    try {
      response = client.execute(request, options);
    } catch (IOException e) {
      throw errorExecuting(request, e);
    }
    try {
      response = wire.wireAndRebufferResponse(target, response);
      if (response.status() >= 200 && response.status() < 300) {
        return decode(argv, response);
      } else {
        throw errorDecoder.decode(metadata.configKey(), response);
      }
    } catch (IOException e) {
      throw errorReading(request, response, e);
    } finally {
      ensureClosed(response.body());
    }
  }

  protected Request targetRequest(RequestTemplate template) {
    return target.apply(new RequestTemplate(template));
  }

  protected abstract Object decode(Object[] argv, Response response) throws Throwable;
}
