package com.ainative.integration.tapd;

import com.ainative.integration.provider.ExternalPlatformConfig;
import com.ainative.integration.provider.ExternalPlatformException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.Credentials;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

@Component
public class TapdClient {
    public static final String API_URL = "https://api.tapd.cn";
    private final OkHttpClient writeHttp;
    private final OkHttpClient readHttp;
    private final ObjectMapper json;

    @Autowired
    public TapdClient(ObjectMapper json) {
        this(json, new OkHttpClient.Builder()
                .callTimeout(Duration.ofSeconds(15))
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .build());
    }

    TapdClient(ObjectMapper json, OkHttpClient http) {
        this.json = json;
        this.writeHttp = http;
        // Reads can reconnect when TAPD closes a pooled connection between syncs.
        this.readHttp = http.newBuilder().retryOnConnectionFailure(true).build();
    }

    JsonNode get(ExternalPlatformConfig config, String resource, Map<String, String> query) {
        HttpUrl.Builder url = HttpUrl.get(baseUrl(config)).newBuilder().addPathSegment(resource);
        query.forEach(url::addQueryParameter);
        return execute(request(config, url.build()).get().build(), false);
    }

    JsonNode addComment(ExternalPlatformConfig config, Map<String, String> fields) {
        FormBody.Builder form = new FormBody.Builder();
        fields.forEach(form::add);
        return execute(request(config, HttpUrl.get(baseUrl(config) + "/comments")).post(form.build()).build(), true);
    }

    // 合入说明:v1 固定用官方 API_URL;v2 允许绑定自带 baseUrl(私有化部署/联调网关),缺省仍是官方地址。
    private static String baseUrl(ExternalPlatformConfig config) {
        String base = config.baseUrl() == null || config.baseUrl().isBlank() ? API_URL : config.baseUrl();
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    private Request.Builder request(ExternalPlatformConfig config, HttpUrl url) {
        return new Request.Builder().url(url)
                .header("Accept", "application/json")
                .header("Authorization", Credentials.basic(config.clientKey(), config.accessSecret(),
                        StandardCharsets.UTF_8));
    }

    private JsonNode execute(Request request, boolean writes) {
        OkHttpClient client = writes ? writeHttp : readHttp;
        try (Response response = client.newCall(request).execute()) {
            int code = response.code();
            if (!response.isSuccessful()) {
                if (writes && code >= 500) {
                    throw new IOException("TAPD comment outcome unknown (HTTP " + code + ")");
                }
                throw new ExternalPlatformException("TAPD HTTP " + code, code < 500 && code != 429);
            }
            JsonNode body = response.body() == null ? null : json.readTree(response.body().byteStream());
            if (body == null || !body.path("status").canConvertToInt()) {
                throw new ExternalPlatformException("TAPD returned an invalid response envelope");
            }
            if (body.path("status").asInt() != 1) {
                throw new ExternalPlatformException("TAPD rejected the request (API status "
                        + body.path("status").asInt() + ")", true);
            }
            return body.path("data");
        } catch (IOException e) {
            // Do not retain raw upstream payloads or parser exceptions in request logs.
            throw new ExternalPlatformException("TAPD transport or response error", new IOException(e.getClass().getSimpleName()));
        }
    }
}
