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
package feign.codec;

import java.io.Reader;
import java.lang.reflect.Type;

import feign.Observer;

/**
 * Decodes an HTTP response iteratively. {@link Observer#onNext(Object)} is called on each successful parse of a
 * given {@code type}.  This is invoked when {@link feign.Response#status()} is in the 2xx range.
 * <br>
 * Ex.
 * <br>
 * <pre>
 * public class GsonObserverDecoder implements ObserverDecoder<Object> {
 *   private final Gson gson;
 *
 *   public GsonObserverDecoder(Gson gson) {
 *     this.gson = gson;
 *   }
 *
 *   &#064;Override public void decode(Reader reader, Type type, Observer<? super Object> observer) throws Exception {
 *     JsonReader jsonReader = new JsonReader(reader);
 *     jsonReader.beginArray();
 *     while (jsonReader.hasNext()) {
 *       observer.onNext(gson.fromJson(jsonReader, type));
 *     }
 *     jsonReader.endArray();
 *   }
 * }
 * </pre>
 * <br>
 * <br><br><b>Error handling</b><br>
 * <br>
 * Responses where {@link feign.Response#status()} is not in the 2xx range are
 * classified as errors, addressed by the {@link feign.codec.ErrorDecoder}. That said,
 * certain RPC apis return errors defined in the {@link feign.Response#body()} even on
 * a 200 status. For example, in the DynECT api, a job still running condition
 * is returned with a 200 status, encoded in json. When scenarios like this
 * occur, you should raise an application-specific exception (which may be
 * {@link feign.RetryableException retryable}).
 */
public interface ObserverDecoder<T> {

  /**
   * Implement this to decode a {@code Reader} iteratively into {@link Observer#onNext(Object)}.
   *
   * @param reader   no need to close this, as the caller manages resources.
   * @param type     type of {@link Observer#onNext}.
   * @param observer {@link Observer#onNext} is called each time an object of {@code type} is read from the response.
   * @throws java.io.IOException will be propagated safely to the caller.
   * @throws Exception           if the decoder threw a checked exception.
   */
  void decode(Reader reader, Type type, Observer<? super T> observer) throws Exception;
}
