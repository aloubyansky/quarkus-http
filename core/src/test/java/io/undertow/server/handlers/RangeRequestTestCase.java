/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2018 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.undertow.server.handlers;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Date;

import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.util.EntityUtils;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import io.netty.buffer.Unpooled;
import io.undertow.Handlers;
import io.undertow.httpcore.IoCallback;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.cache.DirectBufferCache;
import io.undertow.server.handlers.resource.CachingResourceManager;
import io.undertow.server.handlers.resource.PathResourceManager;
import io.undertow.server.handlers.resource.ResourceHandler;
import io.undertow.testutils.DefaultServer;
import io.undertow.testutils.TestHttpClient;
import io.undertow.util.DateUtils;
import io.undertow.httpcore.HttpHeaderNames;
import io.undertow.httpcore.StatusCodes;

/**
 * @author Stuart Douglas
 */
@RunWith(DefaultServer.class)
public class RangeRequestTestCase {

    @BeforeClass
    public static void setup() throws URISyntaxException {
        Path rootPath = Paths.get(RangeRequestTestCase.class.getResource("range.txt").toURI()).getParent();
        PathHandler path = Handlers.path();
        path.addPrefixPath("/path", new ByteRangeHandler(new HttpHandler() {
            @Override
            public void handleRequest(HttpServerExchange exchange) throws Exception {
                exchange.setResponseHeader(HttpHeaderNames.LAST_MODIFIED, DateUtils.toDateString(new Date(10000)));
                exchange.setResponseHeader(HttpHeaderNames.ETAG, "\"someetag\"");
                exchange.setResponseContentLength("0123456789".length());
                exchange.writeAsync(Unpooled.copiedBuffer("0123456789", StandardCharsets.UTF_8), true, IoCallback.END_EXCHANGE, null);
            }
        }, true));
        path.addPrefixPath("/resource",  new ResourceHandler( new PathResourceManager(rootPath, 10485760))
                .setDirectoryListingEnabled(true));
        path.addPrefixPath("/cachedresource",  new ResourceHandler(new CachingResourceManager(1000, 1000000, new DirectBufferCache(1000, 10, 10000), new PathResourceManager(rootPath, 10485760), -1))
                .setDirectoryListingEnabled(true));
        path.addPrefixPath("/resource-blocking",  new BlockingHandler(new ResourceHandler( new PathResourceManager(rootPath, 10485760))
                .setDirectoryListingEnabled(true)));
        path.addPrefixPath("/cachedresource-blocking",  new BlockingHandler(new ResourceHandler(new CachingResourceManager(1000, 1000000, new DirectBufferCache(1000, 10, 10000), new PathResourceManager(rootPath, 10485760), -1))
                .setDirectoryListingEnabled(true)));
        DefaultServer.setRootHandler(path);
    }

    @Test
    public void testGenericRangeHandler() throws IOException, InterruptedException {
        runTest("/path", true);
    }
    @Test
    public void testResourceHandler() throws IOException, InterruptedException {
        runTest("/resource/range.txt", false);
    }
    @Test
    public void testCachedResourceHandler() throws IOException, InterruptedException {
        runTest("/cachedresource/range.txt", false);
    }

    @Test
    public void testResourceHandlerBlocking() throws IOException, InterruptedException {
        runTest("/resource-blocking/range.txt", false);
    }
    @Test
    public void testCachedResourceHandlerBlocking() throws IOException, InterruptedException {
        runTest("/cachedresource-blocking/range.txt", false);
    }
    public void runTest(String path, boolean etag) throws IOException, InterruptedException {
        TestHttpClient client = new TestHttpClient();
        try {
            HttpGet get = new HttpGet(DefaultServer.getDefaultServerURL() + path);
            get.addHeader(HttpHeaderNames.RANGE, "bytes=2-3");
            HttpResponse result = client.execute(get);
            Assert.assertEquals(StatusCodes.PARTIAL_CONTENT, result.getStatusLine().getStatusCode());
            String response = EntityUtils.toString(result.getEntity());
            Assert.assertEquals("23", response);
            Assert.assertEquals( "bytes 2-3/10", result.getFirstHeader(HttpHeaderNames.CONTENT_RANGE).getValue());

            get = new HttpGet(DefaultServer.getDefaultServerURL() + path);
            get.addHeader(HttpHeaderNames.RANGE, "bytes=3-1000");
            result = client.execute(get);
            Assert.assertEquals(StatusCodes.PARTIAL_CONTENT, result.getStatusLine().getStatusCode());
            response = EntityUtils.toString(result.getEntity());
            Assert.assertEquals("3456789", response);
            Assert.assertEquals( "bytes 3-9/10", result.getFirstHeader(HttpHeaderNames.CONTENT_RANGE).getValue());

            get = new HttpGet(DefaultServer.getDefaultServerURL() + path);
            get.addHeader(HttpHeaderNames.RANGE, "bytes=3-9");
            result = client.execute(get);
            Assert.assertEquals(StatusCodes.PARTIAL_CONTENT, result.getStatusLine().getStatusCode());
            response = EntityUtils.toString(result.getEntity());
            Assert.assertEquals("3456789", response);
            Assert.assertEquals( "bytes 3-9/10", result.getFirstHeader(HttpHeaderNames.CONTENT_RANGE).getValue());
            get = new HttpGet(DefaultServer.getDefaultServerURL() + path);

            get.addHeader(HttpHeaderNames.RANGE, "bytes=0-0");
            result = client.execute(get);
            Assert.assertEquals(StatusCodes.PARTIAL_CONTENT, result.getStatusLine().getStatusCode());
            response = EntityUtils.toString(result.getEntity());
            Assert.assertEquals("0", response);
            Assert.assertEquals( "bytes 0-0/10", result.getFirstHeader(HttpHeaderNames.CONTENT_RANGE).getValue());

            get = new HttpGet(DefaultServer.getDefaultServerURL() + path);
            get.addHeader(HttpHeaderNames.RANGE, "bytes=1-");
            result = client.execute(get);
            Assert.assertEquals(StatusCodes.PARTIAL_CONTENT, result.getStatusLine().getStatusCode());
            response = EntityUtils.toString(result.getEntity());
            Assert.assertEquals("123456789", response);
            Assert.assertEquals( "bytes 1-9/10", result.getFirstHeader(HttpHeaderNames.CONTENT_RANGE).getValue());

            get = new HttpGet(DefaultServer.getDefaultServerURL() + path);
            get.addHeader(HttpHeaderNames.RANGE, "bytes=0-");
            result = client.execute(get);
            Assert.assertEquals(StatusCodes.PARTIAL_CONTENT, result.getStatusLine().getStatusCode());
            response = EntityUtils.toString(result.getEntity());
            Assert.assertEquals("0123456789", response);
            Assert.assertEquals("bytes 0-9/10", result.getFirstHeader(HttpHeaderNames.CONTENT_RANGE).getValue());

            get = new HttpGet(DefaultServer.getDefaultServerURL() + path);
            get.addHeader(HttpHeaderNames.RANGE, "bytes=9-");
            result = client.execute(get);
            Assert.assertEquals(StatusCodes.PARTIAL_CONTENT, result.getStatusLine().getStatusCode());
            response = EntityUtils.toString(result.getEntity());
            Assert.assertEquals("9", response);
            Assert.assertEquals("bytes 9-9/10", result.getFirstHeader(HttpHeaderNames.CONTENT_RANGE).getValue());

            get = new HttpGet(DefaultServer.getDefaultServerURL() + path);
            get.addHeader(HttpHeaderNames.RANGE, "bytes=-1");
            result = client.execute(get);
            Assert.assertEquals(StatusCodes.PARTIAL_CONTENT, result.getStatusLine().getStatusCode());
            response = EntityUtils.toString(result.getEntity());
            Assert.assertEquals("9", response);
            Assert.assertEquals("bytes 9-9/10", result.getFirstHeader(HttpHeaderNames.CONTENT_RANGE).getValue());

            get = new HttpGet(DefaultServer.getDefaultServerURL() + path);
            get.addHeader(HttpHeaderNames.RANGE, "bytes=99-100");
            result = client.execute(get);
            Assert.assertEquals(StatusCodes.REQUEST_RANGE_NOT_SATISFIABLE, result.getStatusLine().getStatusCode());
            response = EntityUtils.toString(result.getEntity());
            Assert.assertEquals("", response);
            Assert.assertEquals("bytes */10", result.getFirstHeader(HttpHeaderNames.CONTENT_RANGE).getValue());
            get = new HttpGet(DefaultServer.getDefaultServerURL() + path);

            get = new HttpGet(DefaultServer.getDefaultServerURL() + path);
            get.addHeader(HttpHeaderNames.RANGE, "bytes=2-1");
            result = client.execute(get);
            Assert.assertEquals(StatusCodes.REQUEST_RANGE_NOT_SATISFIABLE, result.getStatusLine().getStatusCode());
            response = EntityUtils.toString(result.getEntity());
            Assert.assertEquals("", response);
            Assert.assertEquals("bytes */10", result.getFirstHeader(HttpHeaderNames.CONTENT_RANGE).getValue());

            get = new HttpGet(DefaultServer.getDefaultServerURL() + path);
            get.addHeader(HttpHeaderNames.RANGE, "bytes=2-3");
            get.addHeader(HttpHeaderNames.IF_RANGE, DateUtils.toDateString(new Date(System.currentTimeMillis() + 1000)));
            result = client.execute(get);
            Assert.assertEquals(StatusCodes.PARTIAL_CONTENT, result.getStatusLine().getStatusCode());
            response = EntityUtils.toString(result.getEntity());
            Assert.assertEquals("23", response);
            Assert.assertEquals( "bytes 2-3/10", result.getFirstHeader(HttpHeaderNames.CONTENT_RANGE).getValue());

            get = new HttpGet(DefaultServer.getDefaultServerURL() + path);
            get.addHeader(HttpHeaderNames.RANGE, "bytes=2-3");
            get.addHeader(HttpHeaderNames.IF_RANGE, DateUtils.toDateString(new Date(0)));
            result = client.execute(get);
            Assert.assertEquals(StatusCodes.OK, result.getStatusLine().getStatusCode());
            response = EntityUtils.toString(result.getEntity());
            Assert.assertEquals("0123456789", response);
            Assert.assertNull(result.getFirstHeader(HttpHeaderNames.CONTENT_RANGE));

            if(etag) {

                get = new HttpGet(DefaultServer.getDefaultServerURL() + path);
                get.addHeader(HttpHeaderNames.RANGE, "bytes=2-3");
                get.addHeader(HttpHeaderNames.IF_RANGE, "\"someetag\"");
                result = client.execute(get);
                Assert.assertEquals(StatusCodes.PARTIAL_CONTENT, result.getStatusLine().getStatusCode());
                response = EntityUtils.toString(result.getEntity());
                Assert.assertEquals("23", response);
                Assert.assertEquals( "bytes 2-3/10", result.getFirstHeader(HttpHeaderNames.CONTENT_RANGE).getValue());

                get = new HttpGet(DefaultServer.getDefaultServerURL() + path);
                get.addHeader(HttpHeaderNames.RANGE, "bytes=2-3");
                get.addHeader(HttpHeaderNames.IF_RANGE, "\"otheretag\"");
                result = client.execute(get);
                Assert.assertEquals(StatusCodes.OK, result.getStatusLine().getStatusCode());
                response = EntityUtils.toString(result.getEntity());
                Assert.assertEquals("0123456789", response);
                Assert.assertNull(result.getFirstHeader(HttpHeaderNames.CONTENT_RANGE));
            }
        } finally {
            client.getConnectionManager().shutdown();
        }
    }

}
