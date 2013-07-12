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
package feign.jaxrs;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import org.testng.annotations.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Type;
import java.net.URI;
import java.util.List;

import javax.ws.rs.DELETE;
import javax.ws.rs.FormParam;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.HttpMethod;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;

import feign.Body;
import feign.MethodMetadata;
import feign.Observer;
import feign.Response;
import feign.TypeQuery;

import static feign.jaxrs.JAXRSModule.CONTENT_TYPE;
import static javax.ws.rs.HttpMethod.DELETE;
import static javax.ws.rs.HttpMethod.GET;
import static javax.ws.rs.HttpMethod.POST;
import static javax.ws.rs.HttpMethod.PUT;
import static javax.ws.rs.core.MediaType.APPLICATION_XML;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/**
 * Tests interfaces defined per {@link feign.jaxrs.JAXRSModule.JAXRSContract} are interpreted into expected {@link feign
 * .RequestTemplate template}
 * instances.
 */
@Test
public class JAXRSContractTest {
  JAXRSModule.JAXRSContract contract = new JAXRSModule.JAXRSContract(new TypeQuery.Default());

  interface Methods {
    @POST void post();

    @PUT void put();

    @GET void get();

    @DELETE void delete();
  }

  @Test public void httpMethods() throws Exception {
    assertEquals(contract.parseAndValidatateMetadata(Methods.class.getDeclaredMethod("post")).template().method(),
        POST);
    assertEquals(contract.parseAndValidatateMetadata(Methods.class.getDeclaredMethod("put")).template().method(), PUT);
    assertEquals(contract.parseAndValidatateMetadata(Methods.class.getDeclaredMethod("get")).template().method(), GET);
    assertEquals(contract.parseAndValidatateMetadata(Methods.class.getDeclaredMethod("delete")).template().method(), DELETE);
  }

  interface CustomMethodAndURIParam {
    @Target({ElementType.METHOD})
    @Retention(RetentionPolicy.RUNTIME)
    @HttpMethod("PATCH")
    public @interface PATCH {
    }

    @PATCH Response patch(URI nextLink);
  }

  @Test public void requestLineOnlyRequiresMethod() throws Exception {
    MethodMetadata md = contract.parseAndValidatateMetadata(CustomMethodAndURIParam.class.getDeclaredMethod("patch",
        URI.class));
    assertEquals(md.template().method(), "PATCH");
    assertEquals(md.template().url(), "");
    assertTrue(md.template().queries().isEmpty());
    assertTrue(md.template().headers().isEmpty());
    assertNull(md.template().body());
    assertNull(md.template().bodyTemplate());
    assertEquals(md.urlIndex(), Integer.valueOf(0));
  }

  interface WithQueryParamsInPath {
    @GET @Path("/") Response none();

    @GET @Path("/?Action=GetUser") Response one();

    @GET @Path("/?Action=GetUser&Version=2010-05-08") Response two();

    @GET @Path("/?Action=GetUser&Version=2010-05-08&limit=1") Response three();

    @GET @Path("/?flag&Action=GetUser&Version=2010-05-08") Response empty();
  }

  @Test public void queryParamsInPathExtract() throws Exception {
    {
      MethodMetadata md = contract.parseAndValidatateMetadata(WithQueryParamsInPath.class.getDeclaredMethod("none"));
      assertEquals(md.template().url(), "/");
      assertTrue(md.template().queries().isEmpty());
      assertEquals(md.template().toString(), "GET / HTTP/1.1\n");
    }
    {
      MethodMetadata md = contract.parseAndValidatateMetadata(WithQueryParamsInPath.class.getDeclaredMethod("one"));
      assertEquals(md.template().url(), "/");
      assertEquals(md.template().queries().get("Action"), ImmutableSet.of("GetUser"));
      assertEquals(md.template().toString(), "GET /?Action=GetUser HTTP/1.1\n");
    }
    {
      MethodMetadata md = contract.parseAndValidatateMetadata(WithQueryParamsInPath.class.getDeclaredMethod("two"));
      assertEquals(md.template().url(), "/");
      assertEquals(md.template().queries().get("Action"), ImmutableSet.of("GetUser"));
      assertEquals(md.template().queries().get("Version"), ImmutableSet.of("2010-05-08"));
      assertEquals(md.template().toString(), "GET /?Action=GetUser&Version=2010-05-08 HTTP/1.1\n");
    }
    {
      MethodMetadata md = contract.parseAndValidatateMetadata(WithQueryParamsInPath.class.getDeclaredMethod("three"));
      assertEquals(md.template().url(), "/");
      assertEquals(md.template().queries().get("Action"), ImmutableSet.of("GetUser"));
      assertEquals(md.template().queries().get("Version"), ImmutableSet.of("2010-05-08"));
      assertEquals(md.template().queries().get("limit"), ImmutableSet.of("1"));
      assertEquals(md.template().toString(), "GET /?Action=GetUser&Version=2010-05-08&limit=1 HTTP/1.1\n");
    }
    {
      MethodMetadata md = contract.parseAndValidatateMetadata(WithQueryParamsInPath.class.getDeclaredMethod("empty"));
      assertEquals(md.template().url(), "/");
      assertTrue(md.template().queries().containsKey("flag"));
      assertEquals(md.template().queries().get("Action"), ImmutableSet.of("GetUser"));
      assertEquals(md.template().queries().get("Version"), ImmutableSet.of("2010-05-08"));
      assertEquals(md.template().toString(), "GET /?flag&Action=GetUser&Version=2010-05-08 HTTP/1.1\n");
    }
  }

  interface BodyWithoutParameters {
    @POST @Produces(APPLICATION_XML) @Body("<v01:getAccountsListOfUser/>") Response post();
  }

  @Test public void bodyWithoutParameters() throws Exception {
    MethodMetadata md = contract.parseAndValidatateMetadata(BodyWithoutParameters.class.getDeclaredMethod("post"));
    assertEquals(md.template().body(), "<v01:getAccountsListOfUser/>");
    assertFalse(md.template().bodyTemplate() != null);
    assertTrue(md.formParams().isEmpty());
    assertTrue(md.indexToName().isEmpty());
  }

  @Test public void producesAddsContentTypeHeader() throws Exception {
    MethodMetadata md = contract.parseAndValidatateMetadata(BodyWithoutParameters.class.getDeclaredMethod("post"));
    assertEquals(md.template().headers().get(CONTENT_TYPE), ImmutableSet.of(APPLICATION_XML));
  }

  interface WithURIParam {
    @GET @Path("/{1}/{2}") Response uriParam(@PathParam("1") String one, URI endpoint, @PathParam("2") String two);
  }

  @Test public void methodCanHaveUriParam() throws Exception {
    MethodMetadata md = contract.parseAndValidatateMetadata(WithURIParam.class.getDeclaredMethod("uriParam", String.class,
        URI.class, String.class));
    assertEquals(md.urlIndex(), Integer.valueOf(1));
  }

  @Test public void pathParamsParseIntoIndexToName() throws Exception {
    MethodMetadata md = contract.parseAndValidatateMetadata(WithURIParam.class.getDeclaredMethod("uriParam", String.class,
        URI.class, String.class));
    assertEquals(md.template().url(), "/{1}/{2}");
    assertEquals(md.indexToName().get(0), ImmutableSet.of("1"));
    assertEquals(md.indexToName().get(2), ImmutableSet.of("2"));
  }

  interface WithPathAndQueryParams {
    @GET @Path("/domains/{domainId}/records")
    Response recordsByNameAndType(@PathParam("domainId") int id, @QueryParam("name") String nameFilter,
                                  @QueryParam("type") String typeFilter);
  }

  @Test public void mixedRequestLineParams() throws Exception {
    MethodMetadata md = contract.parseAndValidatateMetadata(WithPathAndQueryParams.class.getDeclaredMethod
        ("recordsByNameAndType", int.class, String.class, String.class));
    assertNull(md.template().body());
    assertNull(md.template().bodyTemplate());
    assertTrue(md.template().headers().isEmpty());
    assertEquals(md.template().url(), "/domains/{domainId}/records");
    assertEquals(md.template().queries().get("name"), ImmutableSet.of("{name}"));
    assertEquals(md.template().queries().get("type"), ImmutableSet.of("{type}"));
    assertEquals(md.indexToName().get(0), ImmutableSet.of("domainId"));
    assertEquals(md.indexToName().get(1), ImmutableSet.of("name"));
    assertEquals(md.indexToName().get(2), ImmutableSet.of("type"));
    assertEquals(md.template().toString(), "GET /domains/{domainId}/records?name={name}&type={type} HTTP/1.1\n");
  }

  interface FormParams {
    @POST
    @Body("%7B\"customer_name\": \"{customer_name}\", \"user_name\": \"{user_name}\", \"password\": \"{password}\"%7D")
    void login(
        @FormParam("customer_name") String customer,
        @FormParam("user_name") String user, @FormParam("password") String password);
  }

  @Test public void formParamsParseIntoIndexToName() throws Exception {
    MethodMetadata md = contract.parseAndValidatateMetadata(FormParams.class.getDeclaredMethod("login", String.class,
        String.class, String.class));

    assertFalse(md.template().body() != null);
    assertEquals(md.template().bodyTemplate(),
        "%7B\"customer_name\": \"{customer_name}\", \"user_name\": \"{user_name}\", \"password\": \"{password}\"%7D");
    assertEquals(md.formParams(), ImmutableList.of("customer_name", "user_name", "password"));
    assertEquals(md.indexToName().get(0), ImmutableSet.of("customer_name"));
    assertEquals(md.indexToName().get(1), ImmutableSet.of("user_name"));
    assertEquals(md.indexToName().get(2), ImmutableSet.of("password"));
  }

  interface HeaderParams {
    @POST void logout(@HeaderParam("Auth-Token") String token);
  }

  @Test public void headerParamsParseIntoIndexToName() throws Exception {
    MethodMetadata md = contract.parseAndValidatateMetadata(HeaderParams.class.getDeclaredMethod("logout", String.class));

    assertEquals(md.template().headers().get("Auth-Token"), ImmutableSet.of("{Auth-Token}"));
    assertEquals(md.indexToName().get(0), ImmutableSet.of("Auth-Token"));
  }

  interface WithObserver {
    @GET @Path("/") void valid(Observer<List<String>> one);

    @GET @Path("/{path}") void badOrder(Observer<List<String>> one, @PathParam("path") String path);

    @GET @Path("/") Response returnType(Observer<List<String>> one);

    @GET @Path("/") void wildcardExtends(Observer<? extends List<String>> one);

    @GET @Path("/") void subtype(ParameterizedObserver<List<String>> one);
  }

  static final List<String> listString = null;

  interface ParameterizedObserver<T extends List<String>> extends Observer<T> {
  }

  @Test public void methodCanHaveObserverParam() throws Exception {
    contract.parseAndValidatateMetadata(WithObserver.class.getDeclaredMethod("valid", Observer.class));
  }

  @Test public void methodMetadataReturnTypeOnObservableMethodIsItsTypeParameter() throws Exception {
    Type listStringType = getClass().getDeclaredField("listString").getGenericType();
    MethodMetadata md = contract.parseAndValidatateMetadata(WithObserver.class.getDeclaredMethod("valid", Observer.class));
    assertEquals(md.decodeInto(), listStringType);
    md = contract.parseAndValidatateMetadata(WithObserver.class.getDeclaredMethod("wildcardExtends", Observer.class));
    assertEquals(md.decodeInto(), listStringType);
    md = contract.parseAndValidatateMetadata(WithObserver.class.getDeclaredMethod("subtype", ParameterizedObserver.class));
    assertEquals(md.decodeInto(), listStringType);
  }

  @Test(expectedExceptions = IllegalStateException.class, expectedExceptionsMessageRegExp = ".*the last parameter.*")
  public void observerParamMustBeLast() throws Exception {
    contract.parseAndValidatateMetadata(WithObserver.class.getDeclaredMethod("badOrder", Observer.class, String.class));
  }

  @Test(expectedExceptions = IllegalStateException.class, expectedExceptionsMessageRegExp = ".*must return void.*")
  public void observerMethodMustReturnVoid() throws Exception {
    contract.parseAndValidatateMetadata(WithObserver.class.getDeclaredMethod("returnType", Observer.class));
  }
}
