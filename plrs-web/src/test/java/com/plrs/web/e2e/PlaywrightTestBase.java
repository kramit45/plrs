package com.plrs.web.e2e;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for browser-level end-to-end tests. Starts a real
 * {@code @SpringBootTest} on a random port, a Postgres container, and a
 * Redis container (each once per JVM via static initialiser, mirroring
 * {@code PostgresTestBase} in plrs-infrastructure). Playwright +
 * Chromium are also JVM-scoped, but each test gets a fresh
 * {@link BrowserContext} and {@link Page} so cookies and state do not
 * leak between tests.
 *
 * <p>Playwright Java auto-downloads the Chromium browser on first use
 * (cached under {@code ~/.cache/ms-playwright}). First-run cost is a
 * ~150 MB download; subsequent runs start in a second or so.
 *
 * <p>Tests extending this class boot the full application: real JPA
 * against the Postgres container, real Redis for the refresh-token
 * allow-list, real Spring Security chains, real Thymeleaf rendering.
 * That's what makes these tests genuinely end-to-end — the browser
 * drives the same stack users would hit.
 *
 * <p>Traces to: §3.e (Testcontainers-backed integration tests), §2.c
 * (web UI FR).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "plrs.jwt.generate-if-missing=true",
            "plrs.cors.allowed-origins=http://localhost:8080"
        })
public abstract class PlaywrightTestBase {

    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:15-alpine")
                    .withDatabaseName("plrs")
                    .withUsername("plrs")
                    .withPassword("plrs");

    protected static final GenericContainer<?> REDIS =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    private static Playwright playwright;
    private static Browser browser;

    @LocalServerPort private int port;

    protected BrowserContext context;
    protected Page page;

    @BeforeAll
    static void launchBrowser() {
        playwright = Playwright.create();
        browser =
                playwright
                        .chromium()
                        .launch(new BrowserType.LaunchOptions().setHeadless(true));
    }

    @BeforeEach
    void openContext() {
        context = browser.newContext();
        page = context.newPage();
    }

    @AfterEach
    void closeContext() {
        if (page != null) {
            page.close();
        }
        if (context != null) {
            context.close();
        }
    }

    @AfterAll
    static void shutdownBrowser() {
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
    }

    protected String baseUrl() {
        return "http://localhost:" + port;
    }
}
