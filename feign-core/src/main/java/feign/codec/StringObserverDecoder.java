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
 * Adapted from {@code com.google.common.io.CharStreams.toString()}.
 */
public class StringObserverDecoder implements ObserverDecoder<String> {
  private static final StringDecoder STRING_DECODER = new StringDecoder();

  @Override public void decode(Reader reader, Type type, Observer<? super String> observer) throws Exception {
    observer.onNext((String) STRING_DECODER.decode(reader, type));
  }
}
