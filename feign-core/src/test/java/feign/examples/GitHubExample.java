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
package feign.examples;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import com.google.gson.Gson;
import com.google.gson.stream.JsonReader;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

import javax.inject.Named;
import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;
import feign.Feign;
import feign.Observer;
import feign.RequestLine;
import feign.codec.Decoder;
import feign.codec.ObserverDecoder;

import static com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY;
import static com.fasterxml.jackson.annotation.PropertyAccessor.FIELD;
import static com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES;

/**
 * adapted from {@code com.example.retrofit.GitHubClient}
 */
public class GitHubExample {

  interface GitHub {
    @RequestLine("GET /repos/{owner}/{repo}/contributors")
    List<Contributor> contributors(@Named("owner") String owner, @Named("repo") String repo);

    @RequestLine("GET /repos/{owner}/{repo}/contributors")
    void contributors(@Named("owner") String owner, @Named("repo") String repo, Observer<Contributor> contributors);
  }

  static class Contributor {
    String login;
    int contributions;
  }

  public static void main(String... args) {
    GitHub github = Feign.create(GitHub.class, "https://api.github.com", new GsonModule());

    // Fetch and print a list of the contributors to this library.
    List<Contributor> contributors = github.contributors("netflix", "feign");
    for (Contributor contributor : contributors) {
      System.out.println(contributor.login + " (" + contributor.contributions + ")");
    }

    // example of async processing
    Observer<Contributor> printlnObserver = new Observer<Contributor>() {

      public int count;

      @Override public void onNext(Contributor element) {
        count++;
      }

      @Override public void onSuccess() {
        System.out.println("found " + count + " contributors");
      }

      @Override public void onFailure(Throwable cause) {
        cause.printStackTrace();
      }
    };
    github.contributors("netflix", "feign", printlnObserver);
  }

  /**
   * Here's how to wire gson deserialization.
   */
  @Module(overrides = true, library = true)
  static class GsonModule {
    @Provides @Singleton Map<String, Decoder> decoders() {
      return ImmutableMap.of("GitHub", jsonDecoder);
    }

    final Decoder jsonDecoder = new Decoder() {
      Gson gson = new Gson();

      @Override public Object decode(Reader reader, Type type) {
        return gson.fromJson(reader, type);
      }
    };

    @Provides @Singleton Map<String, ObserverDecoder> observerDecoders() {
      return ImmutableMap.of("GitHub", jsonObserverDecoder);
    }

    final ObserverDecoder jsonObserverDecoder = new ObserverDecoder<Object>() {
      Gson gson = new Gson();

      @Override public void decode(Reader reader, Type type, Observer<? super Object> observer) throws IOException {
        JsonReader jsonReader = new JsonReader(reader);
        jsonReader.beginArray();
        while (jsonReader.hasNext()) {
          observer.onNext(gson.fromJson(jsonReader, type));
        }
        jsonReader.endArray();
      }
    };
  }

  /**
   * Here's how to wire jackson deserialization.
   */
  @Module(overrides = true, library = true)
  static class JacksonModule {
    @Provides @Singleton Map<String, Decoder> decoders() {
      return ImmutableMap.of("GitHub", jsonDecoder);
    }

    final Decoder jsonDecoder = new Decoder() {
      ObjectMapper mapper = new ObjectMapper().disable(FAIL_ON_UNKNOWN_PROPERTIES).setVisibility(FIELD, ANY);

      @Override public Object decode(Reader reader, Type type) throws IOException {
        return mapper.readValue(reader, mapper.constructType(type));
      }
    };
  }
}
