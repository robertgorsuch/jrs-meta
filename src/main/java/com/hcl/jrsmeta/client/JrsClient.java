package com.hcl.jrsmeta.client;

import com.hcl.jrsmeta.config.ConnectorConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/** Read-only HTTP transport for JRS REST v2. Knows nothing about repository artifacts. */
public final class JrsClient {
    private static final Logger log = LoggerFactory.getLogger(JrsClient.class);
    private static final Set<Integer> RETRY_STATUS = Set.of(429, 502, 503, 504);

    public record Response(int status, HttpHeaders headers, byte[] body) {
        public Optional<String> header(String name) { return headers.firstValue(name); }
        public String text() { return new String(body, StandardCharsets.UTF_8); }
    }

    @FunctionalInterface interface Sleeper { void sleep(long millis) throws InterruptedException; }

    private final HttpClient http;
    private final String restBase;
    private final String authHeader;
    private final RateLimiter limiter;
    private final AuditLog audit;
    private final int maxRetries;
    private final Duration timeout;
    private final Sleeper sleeper;

    public JrsClient(ConnectorConfig cfg, AuditLog audit) { this(cfg, audit, Thread::sleep); }

    JrsClient(ConnectorConfig cfg, AuditLog audit, Sleeper sleeper) {
        this.restBase = cfg.restBase();
        this.audit = audit;
        this.sleeper = sleeper;
        this.maxRetries = cfg.maxRetries();
        this.timeout = Duration.ofSeconds(cfg.requestTimeoutSeconds());
        this.limiter = new RateLimiter(cfg.rateLimitPerSecond());
        String creds = cfg.username() + ":" + new String(cfg.password());
        this.authHeader = "Basic " + Base64.getEncoder().encodeToString(creds.getBytes(StandardCharsets.UTF_8));
        // Cookie manager keeps the JSESSIONID so Basic auth does not open a new server session per request (spec G6).
        this.http = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /** Percent-encode each path segment, keeping '/'. Repository URIs may contain spaces and '&'. */
    public static String encodePath(String uri) {
        return Arrays.stream(uri.split("/", -1))
                .map(s -> URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20"))
                .collect(Collectors.joining("/"));
    }

    public Response get(String pathAndQuery, String accept) throws IOException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(restBase + pathAndQuery)).header("Accept", accept).GET();
        return send(b, "GET", pathAndQuery);
    }

    public Response post(String path, String contentType, byte[] body, String accept) throws IOException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(restBase + path)).header("Accept", accept)
                .header("Content-Type", contentType).POST(HttpRequest.BodyPublishers.ofByteArray(body));
        return send(b, "POST", path);
    }

    private Response send(HttpRequest.Builder b, String method, String path) throws IOException {
        HttpRequest req = b.header("Authorization", authHeader).timeout(timeout).build();
        IOException last = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            acquirePermit();
            long t0 = System.nanoTime();
            try {
                HttpResponse<byte[]> r = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
                audit.record(method, path, r.statusCode(), (System.nanoTime() - t0) / 1_000_000);
                int s = r.statusCode();
                if (s == 401 || s == 403) throw new JrsClientException(s, method + " " + path + " -> HTTP " + s
                        + ": authentication/authorization failed (check JRS_META_USER / JRS_META_PASSWORD and repository read permission)");
                if (RETRY_STATUS.contains(s)) {
                    last = new JrsClientException(s, method + " " + path + " -> HTTP " + s);
                    if (attempt < maxRetries) { backoff(attempt, s); continue; }
                    throw last;
                }
                if (s >= 400 && s != 404) throw new JrsClientException(s, method + " " + path + " -> HTTP " + s + " " + truncate(r.body()));
                return new Response(s, r.headers(), r.body());
            } catch (JrsClientException e) {
                throw e;
            } catch (IOException e) {
                audit.record(method, path, -1, (System.nanoTime() - t0) / 1_000_000);
                last = e;
                if (attempt < maxRetries) backoff(attempt, -1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted while calling " + path, e);
            }
        }
        throw new JrsClientException(-1, "giving up after " + (maxRetries + 1) + " attempts: " + method + " " + path, last);
    }

    private void acquirePermit() throws IOException {
        try { limiter.acquire(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("interrupted", e); }
    }

    private void backoff(int attempt, int status) throws IOException {
        long ms = (long) (500 * Math.pow(2, attempt)) + ThreadLocalRandom.current().nextInt(250);
        log.warn("retrying after {} ms (attempt {} of {}, status {})", ms, attempt + 1, maxRetries, status);
        try { sleeper.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("interrupted during backoff", e); }
    }

    private static String truncate(byte[] body) {
        String s = new String(body, StandardCharsets.UTF_8);
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
