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

import org.testng.annotations.Test;

import java.lang.reflect.Type;
import java.util.List;

import static org.testng.Assert.assertEquals;

@Test
public class DefaultTypeQueryTest {
  private TypeQuery typeQuery = new TypeQuery.Default();

  interface FirstType {
    final List<String> LIST_STRING = null;
    final Observer<List<String>> OBSERVER_LIST_STRING = null;
    final Observer<? extends List<String>> OBSERVER_WILDCARD_LIST_STRING = null;
    final ParameterizedObserver<List<String>> PARAMETERIZED_OBSERVER_LIST_STRING = null;
    final ParameterizedObserver<?> PARAMETERIZED_OBSERVER_UNBOUND = null;
  }

  interface ParameterizedObserver<T extends List<String>> extends Observer<T> {
  }

  @Test public void firstTypeParameterWhenNotSubtype() throws Exception {
    Type context = FirstType.class.getDeclaredField("OBSERVER_LIST_STRING").getGenericType();
    Type listStringType = FirstType.class.getDeclaredField("LIST_STRING").getGenericType();
    Type first = typeQuery.firstParameterOfSupertype(context, Observer.class, Observer.class);
    assertEquals(first, listStringType);
  }

  @Test public void firstTypeParameterWhenWildcard() throws Exception {
    Type context = FirstType.class.getDeclaredField("OBSERVER_WILDCARD_LIST_STRING").getGenericType
        ();
    Type listStringType = FirstType.class.getDeclaredField("LIST_STRING").getGenericType();
    Type first = typeQuery.firstParameterOfSupertype(context, Observer.class, Observer.class);
    assertEquals(first, listStringType);
  }

  @Test public void firstTypeParameterWhenParameterizedSubtype() throws Exception {
    Type context = FirstType.class.getDeclaredField("PARAMETERIZED_OBSERVER_LIST_STRING").getGenericType
        ();
    Type listStringType = FirstType.class.getDeclaredField("LIST_STRING").getGenericType();
    Type first = typeQuery.firstParameterOfSupertype(context, ParameterizedObserver.class, Observer.class);
    assertEquals(first, listStringType);
  }

  @Test(expectedExceptions = IllegalArgumentException.class, expectedExceptionsMessageRegExp = "unbound type " +
      "parameter on .* not supported")
  public void unboundTypeParamUnsupported() throws Exception {
    Type context = FirstType.class.getDeclaredField("PARAMETERIZED_OBSERVER_UNBOUND").getGenericType();
    Type listStringType = FirstType.class.getDeclaredField("LIST_STRING").getGenericType();
    Type first = typeQuery.firstParameterOfSupertype(context, ParameterizedObserver.class, Observer.class);
    assertEquals(first, listStringType);
  }
}
