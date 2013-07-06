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

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;

import static feign.Util.checkArgument;

/**
 * Query methods necessary to derive type parameters in support of features such as {@link Observer}.
 */
public interface TypeQuery {
  /**
   * Returns the first type parameter from {@code toResolve} using the given context.
   * <br>
   * <br>
   * ex.
   * {@code firstParameterOfSupertype(ListObserver<String>, ListObserver.class, Observer.class)}
   * should result in {@code List<String>}.
   *
   * @throws IllegalArgumentException if {@code toResolve} is not assignable from {@code rawType}.
   */
  Type firstParameterOfSupertype(Type context, Class<?> rawType, Class<?> toResolve);

  /**
   * Naive derivative of {@code retrofit.Types} that only works when the type to resolve is an interface such as
   * {@link Observer}. Unbounded type params are not supported.
   */
  public static class Default implements TypeQuery {

    @Override
    public Type firstParameterOfSupertype(Type context, Class<?> rawType, Class<?> toResolve) {
      checkArgument(rawType == toResolve || toResolve.isAssignableFrom(rawType),
          "%s should be assignable from %s",
          toResolve, rawType);
      ParameterizedType type = firstGenericSuperInterface(context, rawType, toResolve);
      Type arg = firstUpperBoundIfWildcard(context, type.getActualTypeArguments()[0]);
      // if variable was unresolved (ex. T), try to get the first type of the context instead
      if (arg instanceof TypeVariable && context instanceof ParameterizedType) {
        arg = firstUpperBoundIfWildcard(context, ParameterizedType.class.cast(context).getActualTypeArguments()[0]);
      }
      checkArgument(!(arg instanceof TypeVariable), "cannot resolve type arg %s on %s from %s", arg, toResolve, context);
      return arg;
    }

    /**
     * bump ? extends Foo to just Foo
     */
    private Type firstUpperBoundIfWildcard(Type context, Type arg) {
      if (arg instanceof WildcardType) {
        arg = WildcardType.class.cast(arg).getUpperBounds()[0];
        checkArgument(Object.class != arg, "unbound type parameter on %s not supported", context);
      }
      return arg;
    }

    /**
     * Returns the first parameterized type corresponding to {@code superInterface}.
     */
    private static ParameterizedType firstGenericSuperInterface(Type context, Class<?> rawType, Class<?> superInterface) {
      if (superInterface == rawType) return ParameterizedType.class.cast(context);
      Class<?>[] interfaces = rawType.getInterfaces();
      for (int i = 0, length = interfaces.length; i < length; i++) {
        if (interfaces[i] == superInterface) {
          return ParameterizedType.class.cast(rawType.getGenericInterfaces()[i]);
        } else if (superInterface.isAssignableFrom(interfaces[i])) {
          return firstGenericSuperInterface(rawType.getGenericInterfaces()[i], interfaces[i], superInterface);
        }
      }
      throw new IllegalStateException();
    }
  }
}
