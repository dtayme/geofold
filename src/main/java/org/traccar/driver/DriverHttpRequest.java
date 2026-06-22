// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 FOGNETX <Drew.Taylor@fognetx.com>

package org.traccar.driver;

import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.QueryStringDecoder;
import jakarta.json.Json;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonReader;

import java.io.StringReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public final class DriverHttpRequest {

    private final FullHttpRequest request;
    private final QueryStringDecoder queryDecoder;

    DriverHttpRequest(FullHttpRequest request) {
        this.request = request;
        this.queryDecoder = new QueryStringDecoder(request.uri());
    }

    public String method() {
        return request.method().name();
    }

    public String uri() {
        return request.uri();
    }

    public String path() {
        return queryDecoder.path();
    }

    public String header(String name) {
        return request.headers().get(name);
    }

    public String contentType() {
        return request.headers().get(HttpHeaderNames.CONTENT_TYPE);
    }

    public Map<String, List<String>> params() {
        return queryDecoder.parameters();
    }

    public List<String> params(String name) {
        return queryDecoder.parameters().getOrDefault(name, Collections.emptyList());
    }

    public String param(String name) {
        List<String> values = params(name);
        return values.isEmpty() ? null : values.getFirst();
    }

    public String content() {
        return content(StandardCharsets.UTF_8.name());
    }

    public String content(String charset) {
        return request.content().toString(Charset.forName(charset));
    }

    public byte[] bytes() {
        byte[] bytes = new byte[request.content().readableBytes()];
        request.content().getBytes(request.content().readerIndex(), bytes);
        return bytes;
    }

    public JsonObject jsonObject() {
        try (JsonReader reader = Json.createReader(new StringReader(content()))) {
            return reader.readObject();
        }
    }

    public JsonArray jsonArray() {
        try (JsonReader reader = Json.createReader(new StringReader(content()))) {
            return reader.readArray();
        }
    }

    /** Parses the request body as URL-encoded form parameters (for POST form submissions). */
    public Map<String, List<String>> bodyParams() {
        return new QueryStringDecoder(content(), false).parameters();
    }
}
