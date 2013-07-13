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

import java.io.Closeable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import javax.inject.Named;
import javax.inject.Singleton;
import javax.net.ssl.SSLSocketFactory;

import dagger.Lazy;
import dagger.ObjectGraph;
import dagger.Provides;
import feign.Request.Options;
import feign.Target.HardCodedTarget;
import feign.Wire.NoOpWire;
import feign.codec.BodyEncoder;
import feign.codec.Decoder;
import feign.codec.ErrorDecoder;
import feign.codec.FormEncoder;
import feign.codec.ObserverDecoder;

import static java.lang.Thread.MIN_PRIORITY;

/**
 * Feign's purpose is to ease development against http apis that feign
 * restfulness.
 * <br>
 * In implementation, Feign is a {@link Feign#newInstance factory} for
 * generating {@link Target targeted} http apis.
 */
public abstract class Feign implements Closeable {

  /**
   * Returns a new instance of an HTTP API, defined by annotations in the
   * {@link Feign Contract}, for the specified {@code target}. You should
   * cache this result.
   */
  public abstract <T> T newInstance(Target<T> target);

  public static <T> T create(Class<T> apiType, String url, Object... modules) {
    return create(new HardCodedTarget<T>(apiType, url), modules);
  }

  /**
   * Shortcut to {@link #newInstance(Target) create} a single {@code targeted}
   * http api using {@link ReflectiveFeign reflection}.
   */
  public static <T> T create(Target<T> target, Object... modules) {
    return create(modules).newInstance(target);
  }

  /**
   * Returns a {@link ReflectiveFeign reflective} factory for generating
   * {@link Target targeted} http apis.
   */
  public static Feign create(Object... modules) {
    return ObjectGraph.create(modulesForGraph(modules).toArray()).get(Feign.class);
  }


  /**
   * Returns an {@link ObjectGraph Dagger ObjectGraph} that can inject a
   * {@link ReflectiveFeign reflective} Feign.
   */
  public static ObjectGraph createObjectGraph(Object... modules) {
    return ObjectGraph.create(modulesForGraph(modules).toArray());
  }

  @dagger.Module(complete = false, injects = Feign.class, library = true)
  public static class Defaults {

    @Provides Contract contract() {
      return new Contract.Default(new TypeQuery.Default());
    }

    @Provides SSLSocketFactory sslSocketFactory() {
      return SSLSocketFactory.class.cast(SSLSocketFactory.getDefault());
    }

    @Provides Client httpClient(Client.Default client) {
      return client;
    }

    @Provides Retryer retryer() {
      return new Retryer.Default();
    }

    @Provides Wire noOp() {
      return new NoOpWire();
    }

    @Provides Map<String, Options> noOptions() {
      return Collections.emptyMap();
    }

    @Provides Map<String, BodyEncoder> noBodyEncoders() {
      return Collections.emptyMap();
    }

    @Provides Map<String, FormEncoder> noFormEncoders() {
      return Collections.emptyMap();
    }

    @Provides Map<String, Decoder> noDecoders() {
      return Collections.emptyMap();
    }

    @Provides Map<String, ObserverDecoder> noObserverDecoders() {
      return Collections.emptyMap();
    }

    @Provides Map<String, ErrorDecoder> noErrorDecoders() {
      return Collections.emptyMap();
    }

    /**
     * Used for both http invocation and decoding when observers are used.
     */
    @Provides @Singleton @Named("http") Executor httpExecutor() {
      return Executors.newCachedThreadPool(new ThreadFactory() {
        @Override public Thread newThread(final Runnable r) {
          return new Thread(new Runnable() {
            @Override public void run() {
              Thread.currentThread().setPriority(MIN_PRIORITY);
              r.run();
            }
          }, MethodHandler.IDLE_THREAD_NAME);
        }
      });
    }
  }

  /**
   * <br>
   * Configuration keys are formatted as unresolved <a href=
   * "http://docs.oracle.com/javase/6/docs/jdk/api/javadoc/doclet/com/sun/javadoc/SeeTag.html"
   * >see tags</a>.
   * <br>
   * For example.
   * <ul>
   * <li>{@code Route53}: would match a class such as
   * {@code denominator.route53.Route53}
   * <li>{@code Route53#list()}: would match a method such as
   * {@code denominator.route53.Route53#list()}
   * <li>{@code Route53#listAt(Marker)}: would match a method such as
   * {@code denominator.route53.Route53#listAt(denominator.route53.Marker)}
   * <li>{@code Route53#listByNameAndType(String, String)}: would match a
   * method such as {@code denominator.route53.Route53#listAt(String, String)}
   * </ul>
   * <br>
   * Note that there is no whitespace expected in a key!
   */
  public static String configKey(Method method) {
    StringBuilder builder = new StringBuilder();
    builder.append(method.getDeclaringClass().getSimpleName());
    builder.append('#').append(method.getName()).append('(');
    for (Class<?> param : method.getParameterTypes())
      builder.append(param.getSimpleName()).append(',');
    if (method.getParameterTypes().length > 0)
      builder.deleteCharAt(builder.length() - 1);
    return builder.append(')').toString();
  }

  private static List<Object> modulesForGraph(Object... modules) {
    List<Object> modulesForGraph = new ArrayList<Object>(3);
    modulesForGraph.add(new Defaults());
    modulesForGraph.add(new ReflectiveFeign.Module());
    if (modules != null)
      for (Object module : modules)
        modulesForGraph.add(module);
    return modulesForGraph;
  }

  private final Lazy<Executor> httpExecutor;

  Feign(Lazy<Executor> httpExecutor) {
    this.httpExecutor = httpExecutor;
  }

  @Override public void close() {
    Executor e = httpExecutor.get();
    if (e instanceof ExecutorService) {
      ExecutorService.class.cast(e).shutdownNow();
    }
  }
}
