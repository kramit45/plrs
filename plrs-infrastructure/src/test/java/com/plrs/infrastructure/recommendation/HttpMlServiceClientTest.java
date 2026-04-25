package com.plrs.infrastructure.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.plrs.application.recommendation.MlServiceException;
import com.plrs.application.recommendation.RebuildResult;
import com.plrs.application.recommendation.SimNeighbour;
import com.plrs.domain.content.ContentId;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Unit-style tests for {@link HttpMlServiceClient} using the JDK's
 * built-in {@link HttpServer}. No Spring context, no testcontainers
 * — just an embedded HTTP server bound to a free port plus a
 * counted handler that lets us assert retry behaviour.
 */
class HttpMlServiceClientTest {

    private static final String SECRET = "test-secret";

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private HttpMlServiceClient client(int port) {
        return new HttpMlServiceClient(
                "http://localhost:" + port,
                SECRET,
                2,
                new ObjectMapper());
    }

    private int startServer(HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler);
        server.start();
        return server.getAddress().getPort();
    }

    @Test
    void cfSimilarReturnsParsedNeighbours() throws IOException {
        AtomicInteger seenSig = new AtomicInteger(0);
        int port =
                startServer(
                        exchange -> {
                            seenSig.set(
                                    exchange.getRequestHeaders().containsKey("X-PLRS-Signature")
                                            ? 1
                                            : 0);
                            String body =
                                    "{\"itemId\":42,\"neighbours\":["
                                            + "{\"contentId\":1,\"similarity\":0.9},"
                                            + "{\"contentId\":2,\"similarity\":0.5}]}";
                            respond(exchange, 200, body);
                        });

        List<SimNeighbour> out = client(port).cfSimilar(ContentId.of(42L), 10);

        assertThat(out).extracting(SimNeighbour::contentId).containsExactly(1L, 2L);
        assertThat(out).extracting(SimNeighbour::similarity).containsExactly(0.9, 0.5);
        assertThat(seenSig.get())
                .as("X-PLRS-Signature header was sent on the request")
                .isEqualTo(1);
    }

    @Test
    void serverReturning500OnceThenSuccessIsRetried() throws IOException {
        AtomicInteger calls = new AtomicInteger(0);
        int port =
                startServer(
                        exchange -> {
                            int n = calls.incrementAndGet();
                            if (n == 1) {
                                respond(exchange, 503, "{\"detail\":\"down\"}");
                            } else {
                                respond(
                                        exchange,
                                        200,
                                        "{\"itemId\":1,\"neighbours\":[]}");
                            }
                        });

        List<SimNeighbour> out = client(port).cfSimilar(ContentId.of(1L), 5);

        assertThat(out).isEmpty();
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void serverReturning500TwiceThrows() throws IOException {
        AtomicInteger calls = new AtomicInteger(0);
        int port =
                startServer(
                        exchange -> {
                            calls.incrementAndGet();
                            respond(exchange, 502, "{\"detail\":\"upstream\"}");
                        });

        HttpMlServiceClient client = client(port);
        assertThatThrownBy(() -> client.cfSimilar(ContentId.of(1L), 5))
                .isInstanceOf(MlServiceException.class)
                .hasMessageContaining("502");
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void connectRefusedThrowsAfterRetry() {
        // Bind to a port we know is closed: bind, get the port, close,
        // then try to talk to it.
        int closedPort;
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            closedPort = s.getLocalPort();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        HttpMlServiceClient client = client(closedPort);
        assertThatThrownBy(() -> client.cfSimilar(ContentId.of(1L), 5))
                .isInstanceOf(MlServiceException.class);
    }

    @Test
    void signatureHeaderMatchesExpectedHmac() throws Exception {
        AtomicInteger ok = new AtomicInteger(0);
        int port =
                startServer(
                        exchange -> {
                            String provided =
                                    exchange.getRequestHeaders()
                                            .getFirst("X-PLRS-Signature");
                            String expected =
                                    expectedHmac("GET", "/cf/similar", new byte[0]);
                            if (expected.equals(provided)) {
                                ok.set(1);
                                respond(
                                        exchange,
                                        200,
                                        "{\"itemId\":1,\"neighbours\":[]}");
                            } else {
                                respond(
                                        exchange,
                                        401,
                                        "{\"detail\":\"bad sig\"}");
                            }
                        });

        client(port).cfSimilar(ContentId.of(1L), 5);

        assertThat(ok.get()).as("server saw the expected signature").isEqualTo(1);
    }

    @Test
    void isReachableReturnsTrueOn200() throws IOException {
        int port =
                startServer(
                        exchange ->
                                respond(exchange, 200, "{\"status\":\"UP\"}"));

        assertThat(client(port).isReachable()).isTrue();
    }

    @Test
    void isReachableReturnsFalseOn500OrConnectError() throws IOException {
        int port = startServer(exchange -> respond(exchange, 500, "down"));
        assertThat(client(port).isReachable()).isFalse();

        // Closed port for the connect-refused case.
        int closedPort;
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            closedPort = s.getLocalPort();
        }
        assertThat(client(closedPort).isReachable()).isFalse();
    }

    @Test
    void rebuildFeaturesParsesOkPayload() throws IOException {
        int port =
                startServer(
                        exchange ->
                                respond(
                                        exchange,
                                        200,
                                        "{\"status\":\"OK\",\"items\":12,"
                                                + "\"vocab_size\":150}"));

        RebuildResult result = client(port).rebuildFeatures();

        assertThat(result.isOk()).isTrue();
        assertThat(result.items()).contains(12);
        assertThat(result.vocabSize()).contains(150);
    }

    @Test
    void recomputeCfParsesSkippedPayload() throws IOException {
        int port =
                startServer(
                        exchange ->
                                respond(
                                        exchange,
                                        200,
                                        "{\"status\":\"SKIPPED\","
                                                + "\"reason\":\"no interactions\"}"));

        RebuildResult result = client(port).recomputeCf();

        assertThat(result.isOk()).isFalse();
        assertThat(result.reason()).contains("no interactions");
    }

    private static void respond(HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String expectedHmac(String method, String path, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(
                    new SecretKeySpec(
                            SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(method.toUpperCase().getBytes(StandardCharsets.UTF_8));
            mac.update(path.getBytes(StandardCharsets.UTF_8));
            mac.update(body);
            StringBuilder sb = new StringBuilder();
            for (byte b : mac.doFinal()) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
