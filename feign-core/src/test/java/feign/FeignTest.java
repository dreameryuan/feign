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

import com.google.common.collect.ImmutableMap;
import com.google.mockwebserver.MockResponse;
import com.google.mockwebserver.MockWebServer;
import com.google.mockwebserver.SocketPolicy;

import org.testng.annotations.Test;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.inject.Named;
import javax.inject.Singleton;
import javax.net.ssl.SSLSocketFactory;

import dagger.Lazy;
import dagger.Module;
import dagger.Provides;
import feign.codec.Decoder;
import feign.codec.ErrorDecoder;
import feign.codec.StringDecoder;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

@Test
public class FeignTest {

  @Test public void closeShutsdownExecutorService() throws IOException, InterruptedException {
    final ExecutorService service = Executors.newCachedThreadPool();
    new Feign(new Lazy<Executor>() {
      @Override public Executor get() {
        return service;
      }
    }) {
      @Override public <T> T newInstance(Target<T> target) {
        return null;
      }
    }.close();
    assertTrue(service.isShutdown());
  }

  interface TestInterface {
    @RequestLine("POST /") String post();

    @RequestLine("POST /")
    @Body("%7B\"customer_name\": \"{customer_name}\", \"user_name\": \"{user_name}\", \"password\": \"{password}\"%7D")
    void login(
        @Named("customer_name") String customer,
        @Named("user_name") String user, @Named("password") String password);

    @RequestLine("GET /{1}/{2}") Response uriParam(@Named("1") String one, URI endpoint, @Named("2") String two);

    @RequestLine("POST /") void observeVoid(Observer<Void> observer);

    @RequestLine("POST /") void observeString(Observer<String> observer);

    @RequestLine("POST /") void observeResponse(Observer<Response> observer);

    @dagger.Module(overrides = true, library = true)
    static class Module {
      // until dagger supports real map binding, we need to recreate the
      // entire map, as opposed to overriding a single entry.
      @Provides @Singleton Map<String, Decoder> decoders() {
        return ImmutableMap.<String, Decoder>of("TestInterface", new StringDecoder());
      }

      // just run synchronously
      @Provides @Singleton @Named("http") Executor httpExecutor() {
        return new Executor() {
          @Override public void execute(Runnable command) {
            command.run();
          }
        };
      }
    }
  }

  @Test
  public void observeVoid() throws IOException, InterruptedException {
    final MockWebServer server = new MockWebServer();
    server.enqueue(new MockResponse().setResponseCode(200).setBody("foo"));
    server.play();

    try {
      TestInterface api = Feign.create(TestInterface.class, server.getUrl("").toString(), new TestInterface.Module());

      final AtomicBoolean success = new AtomicBoolean();

      Observer<Void> observer = new Observer<Void>() {

        @Override public void onNext(Void element) {
          fail("on next isn't valid for void");
        }

        @Override public void onSuccess() {
          success.set(true);
        }

        @Override public void onFailure(Throwable cause) {
          fail(cause.getMessage());
        }
      };
      api.observeVoid(observer);

      assertTrue(success.get());
      assertEquals(server.getRequestCount(), 1);
    } finally {
      server.shutdown();
    }
  }

  @Test
  public void observeResponse() throws IOException, InterruptedException {
    final MockWebServer server = new MockWebServer();
    server.enqueue(new MockResponse().setResponseCode(200).setBody("foo"));
    server.play();

    try {
      TestInterface api = Feign.create(TestInterface.class, server.getUrl("").toString(), new TestInterface.Module());

      final AtomicBoolean success = new AtomicBoolean();

      Observer<Response> observer = new Observer<Response>() {

        @Override public void onNext(Response element) {
          assertEquals(element.status(), 200);
        }

        @Override public void onSuccess() {
          success.set(true);
        }

        @Override public void onFailure(Throwable cause) {
          fail(cause.getMessage());
        }
      };
      api.observeResponse(observer);

      assertTrue(success.get());
      assertEquals(server.getRequestCount(), 1);
    } finally {
      server.shutdown();
    }
  }

  @Test
  public void observeString() throws IOException, InterruptedException {
    final MockWebServer server = new MockWebServer();
    server.enqueue(new MockResponse().setResponseCode(200).setBody("foo"));
    server.play();

    try {
      TestInterface api = Feign.create(TestInterface.class, server.getUrl("").toString(), new TestInterface.Module());

      final AtomicBoolean success = new AtomicBoolean();

      Observer<String> observer = new Observer<String>() {

        @Override public void onNext(String element) {
          assertEquals(element, "foo");
        }

        @Override public void onSuccess() {
          success.set(true);
        }

        @Override public void onFailure(Throwable cause) {
          fail(cause.getMessage());
        }
      };
      api.observeString(observer);

      assertTrue(success.get());
      assertEquals(server.getRequestCount(), 1);
    } finally {
      server.shutdown();
    }
  }

  @Test
  public void postTemplateParamsResolve() throws IOException, InterruptedException {
    final MockWebServer server = new MockWebServer();
    server.enqueue(new MockResponse().setResponseCode(200).setBody("foo"));
    server.play();

    try {
      TestInterface api = Feign.create(TestInterface.class, server.getUrl("").toString(), new TestInterface.Module());

      api.login("netflix", "denominator", "password");
      assertEquals(new String(server.takeRequest().getBody()),
          "{\"customer_name\": \"netflix\", \"user_name\": \"denominator\", \"password\": \"password\"}");
    } finally {
      server.shutdown();
    }
  }

  @Test public void toKeyMethodFormatsAsExpected() throws Exception {
    assertEquals(Feign.configKey(TestInterface.class.getDeclaredMethod("post")), "TestInterface#post()");
    assertEquals(Feign.configKey(TestInterface.class.getDeclaredMethod("uriParam", String.class, URI.class,
        String.class)), "TestInterface#uriParam(String,URI,String)");
  }

  @Test(expectedExceptions = IllegalArgumentException.class, expectedExceptionsMessageRegExp = "zone not found")
  public void canOverrideErrorDecoderOnMethod() throws IOException, InterruptedException {
    @dagger.Module(overrides = true, includes = TestInterface.Module.class) class Overrides {
      @Provides @Singleton Map<String, ErrorDecoder> decoders() {
        return ImmutableMap.<String, ErrorDecoder>of("TestInterface#post()", new ErrorDecoder() {

          @Override
          public Exception decode(String methodKey, Response response) {
            if (response.status() == 404)
              return new IllegalArgumentException("zone not found");
            return ErrorDecoder.DEFAULT.decode(methodKey, response);
          }

        });
      }
    }

    final MockWebServer server = new MockWebServer();
    server.enqueue(new MockResponse().setResponseCode(404).setBody("foo"));
    server.play();

    try {
      TestInterface api = Feign.create(TestInterface.class, server.getUrl("").toString(), new Overrides());

      api.post();
    } finally {
      server.shutdown();
    }
  }

  @Test public void retriesLostConnectionBeforeRead() throws IOException, InterruptedException {
    MockWebServer server = new MockWebServer();
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
    server.enqueue(new MockResponse().setResponseCode(200).setBody("success!".getBytes()));
    server.play();

    try {
      TestInterface api = Feign.create(TestInterface.class, server.getUrl("").toString(),
          new TestInterface.Module());

      api.post();
      assertEquals(server.getRequestCount(), 2);

    } finally {
      server.shutdown();
    }
  }

  @Test(expectedExceptions = FeignException.class, expectedExceptionsMessageRegExp = "error reading response POST http://.*")
  public void doesntRetryAfterResponseIsSent() throws IOException, InterruptedException {
    MockWebServer server = new MockWebServer();
    server.enqueue(new MockResponse().setResponseCode(200).setBody("success!".getBytes()));
    server.play();

    try {
      @dagger.Module(overrides = true) class Overrides {
        @Provides @Singleton Map<String, Decoder> decoders() {
          return ImmutableMap.<String, Decoder>of("TestInterface", new Decoder() {

            @Override
            public Object decode(Reader reader, Type type) throws IOException {
              throw new IOException("error reading response");
            }

          });
        }
      }
      TestInterface api = Feign.create(TestInterface.class, server.getUrl("").toString(), new Overrides());

      api.post();
    } finally {
      server.shutdown();
      assertEquals(server.getRequestCount(), 1);
    }
  }

  @Module(injects = Client.Default.class, overrides = true)
  static class TrustSSLSockets {
    @Provides SSLSocketFactory trustingSSLSocketFactory() {
      return TrustingSSLSocketFactory.get();
    }
  }

  @Test public void canOverrideSSLSocketFactory() throws IOException, InterruptedException {
    MockWebServer server = new MockWebServer();
    server.useHttps(TrustingSSLSocketFactory.get(), false);
    server.enqueue(new MockResponse().setResponseCode(200).setBody("success!".getBytes()));
    server.play();

    try {
      TestInterface api = Feign.create(TestInterface.class, server.getUrl("").toString(),
          new TestInterface.Module(), new TrustSSLSockets());
      api.post();
    } finally {
      server.shutdown();
    }
  }

  @Test public void retriesFailedHandshake() throws IOException, InterruptedException {
    MockWebServer server = new MockWebServer();
    server.useHttps(TrustingSSLSocketFactory.get(), false);
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.FAIL_HANDSHAKE));
    server.enqueue(new MockResponse().setResponseCode(200).setBody("success!".getBytes()));
    server.play();

    try {
      TestInterface api = Feign.create(TestInterface.class, server.getUrl("").toString(),
          new TestInterface.Module(), new TrustSSLSockets());
      api.post();
      assertEquals(server.getRequestCount(), 2);
    } finally {
      server.shutdown();
    }
  }
}
