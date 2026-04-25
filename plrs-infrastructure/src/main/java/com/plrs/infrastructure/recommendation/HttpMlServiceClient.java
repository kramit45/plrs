package com.plrs.infrastructure.recommendation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.plrs.application.eval.EvalReport;
import com.plrs.application.recommendation.MlServiceClient;
import com.plrs.application.recommendation.MlServiceException;
import com.plrs.application.recommendation.RebuildResult;
import com.plrs.application.recommendation.SimNeighbour;
import com.plrs.domain.content.ContentId;
import java.io.IOException;
import java.time.Instant;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * HTTP adapter for {@link MlServiceClient} targeting the FastAPI
 * service at {@code plrs.ml.base-url}.
 *
 * <p>Resilience contract: every authenticated call has a 2-second
 * default timeout, exactly one retry on 5xx or {@link IOException},
 * a 100 ms back-off between attempts, then fail-fast with
 * {@link MlServiceException}. {@link #isReachable} uses a tighter 1
 * s timeout and never throws — callers test it on every request to
 * pick between the ML path and the in-process fallback.
 *
 * <p>Authentication: every call signs
 * {@code method.toUpperCase() + path + body} with HMAC-SHA256 using
 * {@code plrs.ml.hmac-secret}, hex-encoded into the
 * {@code X-PLRS-Signature} header. {@code /health} is the only
 * unauthenticated route on the ML side.
 */
@Component
@ConditionalOnProperty(name = "plrs.ml.base-url")
public class HttpMlServiceClient implements MlServiceClient {

    static final String SIGNATURE_HEADER = "X-PLRS-Signature";
    static final Duration RETRY_BACKOFF = Duration.ofMillis(100);
    static final Duration HEALTH_TIMEOUT = Duration.ofSeconds(1);

    private static final Logger log = LoggerFactory.getLogger(HttpMlServiceClient.class);

    private final String baseUrl;
    private final String hmacSecret;
    private final Duration timeout;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public HttpMlServiceClient(
            @Value("${plrs.ml.base-url:http://plrs-ml:8000}") String baseUrl,
            @Value("${plrs.ml.hmac-secret:dev-secret}") String hmacSecret,
            @Value("${plrs.ml.timeout-seconds:2}") int timeoutSeconds,
            ObjectMapper objectMapper) {
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.hmacSecret = hmacSecret;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.objectMapper = objectMapper;
        this.httpClient =
                HttpClient.newBuilder()
                        .connectTimeout(this.timeout)
                        .build();
    }

    @Override
    public List<SimNeighbour> cfSimilar(ContentId itemId, int k) {
        String path = "/cf/similar";
        String query = "?itemId=" + itemId.value() + "&k=" + k;
        HttpResponse<String> response = sendWithRetry("GET", path, query, null);
        return parseNeighbours(response.body());
    }

    @Override
    public List<SimNeighbour> cbSimilar(ContentId itemId, int k) {
        String path = "/cb/similar";
        String query = "?itemId=" + itemId.value() + "&k=" + k;
        HttpResponse<String> response = sendWithRetry("GET", path, query, null);
        return parseNeighbours(response.body());
    }

    @Override
    public RebuildResult rebuildFeatures() {
        HttpResponse<String> response =
                sendWithRetry("POST", "/features/rebuild", "", new byte[0]);
        return parseRebuildResult(response.body());
    }

    @Override
    public RebuildResult recomputeCf() {
        HttpResponse<String> response =
                sendWithRetry("POST", "/cf/recompute", "", new byte[0]);
        return parseRebuildResult(response.body());
    }

    @Override
    public EvalReport runEval(String variant, int k) {
        String query = "?variant=" + variant + "&k=" + k;
        HttpResponse<String> response =
                sendWithRetry("POST", "/eval/run", query, new byte[0]);
        return parseEvalReport(response.body());
    }

    @Override
    public boolean isReachable() {
        try {
            HttpRequest request =
                    HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + "/health"))
                            .timeout(HEALTH_TIMEOUT)
                            .GET()
                            .build();
            HttpResponse<Void> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    private HttpResponse<String> sendWithRetry(
            String method, String path, String query, byte[] body) {
        Throwable lastFailure = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                HttpResponse<String> response = sendOnce(method, path, query, body);
                int status = response.statusCode();
                if (status >= 500 && status <= 599) {
                    lastFailure =
                            new MlServiceException(
                                    "ml-service " + method + " " + path
                                            + " returned " + status);
                    if (attempt == 1) {
                        sleepBackoff();
                        continue;
                    }
                    throw (MlServiceException) lastFailure;
                }
                if (status >= 400) {
                    // 4xx is a client error and not retryable —
                    // surface immediately so the caller sees the
                    // root cause.
                    throw new MlServiceException(
                            "ml-service " + method + " " + path
                                    + " returned " + status
                                    + " body=" + safeTruncate(response.body()));
                }
                return response;
            } catch (IOException e) {
                lastFailure = e;
                if (attempt == 1) {
                    sleepBackoff();
                    continue;
                }
                throw new MlServiceException(
                        "ml-service " + method + " " + path + " transport error",
                        e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new MlServiceException(
                        "ml-service " + method + " " + path + " interrupted", e);
            }
        }
        throw new MlServiceException(
                "ml-service " + method + " " + path + " failed after retry",
                lastFailure);
    }

    private HttpResponse<String> sendOnce(
            String method, String path, String query, byte[] body)
            throws IOException, InterruptedException {
        byte[] effectiveBody = body == null ? new byte[0] : body;
        String signature = sign(method, path, effectiveBody);
        HttpRequest.Builder builder =
                HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + path + query))
                        .timeout(timeout)
                        .header(SIGNATURE_HEADER, signature)
                        .header("Content-Type", "application/json");
        if ("GET".equals(method)) {
            builder.GET();
        } else {
            builder.method(method, HttpRequest.BodyPublishers.ofByteArray(effectiveBody));
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String sign(String method, String path, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    hmacSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(method.toUpperCase().getBytes(StandardCharsets.UTF_8));
            mac.update(path.getBytes(StandardCharsets.UTF_8));
            mac.update(body);
            return toHex(mac.doFinal());
        } catch (Exception e) {
            throw new MlServiceException("HMAC signing failed", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private List<SimNeighbour> parseNeighbours(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode neighbours = root.path("neighbours");
            List<SimNeighbour> out = new ArrayList<>();
            if (neighbours.isArray()) {
                for (JsonNode node : neighbours) {
                    long id = node.path("contentId").asLong();
                    double sim = node.path("similarity").asDouble();
                    out.add(new SimNeighbour(id, sim));
                }
            }
            return out;
        } catch (Exception e) {
            throw new MlServiceException("malformed neighbours payload", e);
        }
    }

    private RebuildResult parseRebuildResult(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            String status = root.path("status").asText("UNKNOWN");
            return new RebuildResult(
                    status,
                    optInt(root, "items"),
                    optInt(root, "users"),
                    optInt(root, "vocab_size"),
                    optString(root, "reason"));
        } catch (Exception e) {
            throw new MlServiceException("malformed rebuild result payload", e);
        }
    }

    private EvalReport parseEvalReport(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            String status = root.path("status").asText("OK");
            String variant = root.path("variant").asText("");
            int k = root.path("k").asInt(0);
            String ranAtRaw = root.path("ran_at").asText("");
            Instant ranAt = ranAtRaw.isEmpty() ? Instant.now() : Instant.parse(ranAtRaw);
            return new EvalReport(
                    status,
                    variant,
                    k,
                    optDouble(root, "precision_at_k"),
                    optDouble(root, "ndcg_at_k"),
                    optDouble(root, "coverage"),
                    optInt(root, "n_users"),
                    ranAt,
                    optString(root, "reason"));
        } catch (Exception e) {
            throw new MlServiceException("malformed eval report payload", e);
        }
    }

    private static Optional<Double> optDouble(JsonNode root, String key) {
        JsonNode node = root.path(key);
        return node.isMissingNode() || node.isNull()
                ? Optional.empty()
                : Optional.of(node.asDouble());
    }

    private static Optional<Integer> optInt(JsonNode root, String key) {
        JsonNode node = root.path(key);
        return node.isMissingNode() || node.isNull()
                ? Optional.empty()
                : Optional.of(node.asInt());
    }

    private static Optional<String> optString(JsonNode root, String key) {
        JsonNode node = root.path(key);
        return node.isMissingNode() || node.isNull()
                ? Optional.empty()
                : Optional.of(node.asText());
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String safeTruncate(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    private void sleepBackoff() {
        try {
            Thread.sleep(RETRY_BACKOFF.toMillis());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
