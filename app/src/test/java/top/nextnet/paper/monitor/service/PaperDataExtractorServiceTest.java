package top.nextnet.paper.monitor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PaperDataExtractorServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void extractsFastApiErrorDetail() {
        assertEquals("Create a derivation before creating revisions",
                PaperDataExtractorService.upstreamErrorMessage(
                        400, "{\"detail\":\"Create a derivation before creating revisions\"}"));
    }

    @Test
    void retainsPlainTextAndProvidesFallback() {
        assertEquals("Invalid review design",
                PaperDataExtractorService.upstreamErrorMessage(422, " Invalid review design "));
        assertEquals("Paper Data Extractor returned 502",
                PaperDataExtractorService.upstreamErrorMessage(502, ""));
    }

    @Test
    void postsJsonBodyOverHttp11() throws Exception {
        AtomicReference<String> protocol = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/review-designs/base/derivations", exchange -> {
            protocol.set(exchange.getProtocol());
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = ("{\"id\":\"derived\",\"review_design\":{},"
                    + "\"form_schema\":{},\"review_json_schema\":{},\"review_linkml_schema\":{}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();

        try {
            PaperDataExtractorService service = new PaperDataExtractorService(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "", "test-token");
            var detail = service.deriveReviewTemplate(
                    "base",
                    "My questions",
                    List.of(Map.of("question", "What changed?", "required", false)),
                    null);

            assertEquals("derived", detail.id());
            assertEquals("HTTP/1.1", protocol.get());
            assertEquals("application/json", contentType.get());
            assertEquals(Map.of(
                    "title", "My questions",
                    "research_questions", List.of(Map.of(
                            "question", "What changed?",
                            "required", false))), JsonCodec.parse(requestBody.get()));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void matchesRecoveredResearchQuestionsByTextAndRequiredFlag() {
        List<Map<String, Object>> requested = List.of(
                Map.of("question", " First question ", "required", true),
                Map.of("question", "Second question", "required", false));
        List<Map<String, Object>> matching = List.of(
                Map.of("key", "generated-1", "question", "First question", "required", true),
                Map.of("key", "generated-2", "question", "Second question", "required", false));

        assertTrue(PaperDataExtractorService.matchingResearchQuestions(requested, matching));
        assertFalse(PaperDataExtractorService.matchingResearchQuestions(
                requested,
                List.of(
                        matching.get(0),
                        Map.of("question", "Different question", "required", false))));
    }

    @Test
    void recoversMatchingOwnedDerivation() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/review-designs", exchange -> respondJson(exchange, """
                [{"id":"derived-r1","title":"My questions","derivation_id":"derivation-1",
                  "revision":1,"is_latest_revision":true,"owned_by_current_user":true,"can_write":true}]
                """));
        server.createContext("/api/review-designs/derived-r1", exchange -> respondJson(exchange, """
                {"id":"derived-r1","review_design":{"title":"My questions",
                  "derived_from_review_design_id":"base",
                  "research_questions":[{"key":"generated","question":"What changed?","required":false}]},
                  "form_schema":{},"review_json_schema":{},"review_linkml_schema":{}}
                """));
        server.start();

        try {
            PaperDataExtractorService service = new PaperDataExtractorService(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "", "test-token");
            var recovered = service.findMatchingDerivation(
                    "base",
                    "My questions",
                    List.of(Map.of("question", "What changed?", "required", false)),
                    null);

            assertTrue(recovered.isPresent());
            assertEquals("derived-r1", recovered.orElseThrow().id());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void postsPaperAnalysisAsAuthenticatedMultipartRequest() throws Exception {
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> forwardedUser = new AtomicReference<>();
        AtomicReference<String> forwardedKey = new AtomicReference<>();
        AtomicReference<byte[]> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/papers/analyze", exchange -> {
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            forwardedUser.set(exchange.getRequestHeaders().getFirst("X-Forwarded-User-Id"));
            forwardedKey.set(exchange.getRequestHeaders().getFirst("X-Forwarded-PDE-OpenAI-Api-Key"));
            requestBody.set(exchange.getRequestBody().readAllBytes());
            respondJson(exchange, """
                    {"structured_abstract_markdown":"METHODS: Tested.",
                     "review_values":{"rq_1":"Result"}}
                    """);
        });
        server.start();

        try {
            Path pdf = temporaryDirectory.resolve("sample.pdf");
            Files.write(pdf, "%PDF-test".getBytes(StandardCharsets.UTF_8));
            PaperDataExtractorService service = new PaperDataExtractorService(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "", "internal-token");

            var result = service.analyzePaper(
                    pdf,
                    "sample.pdf",
                    Map.of("title", "A paper"),
                    Map.of("title", "A review"),
                    Map.of("fields", List.of(Map.of("id", "rq_1"))),
                    new PaperDataExtractorService.OpenAiRequestContext(
                            42L, "alice", "Alice", "alice@example.test", false,
                            "personal-key", 2, 0));

            assertEquals("METHODS: Tested.", result.structuredAbstractMarkdown());
            assertEquals(Map.of("rq_1", "Result"), result.reviewValues());
            assertTrue(contentType.get().startsWith("multipart/form-data; boundary="));
            assertEquals("42", forwardedUser.get());
            assertEquals("personal-key", forwardedKey.get());
            String body = new String(requestBody.get(), StandardCharsets.ISO_8859_1);
            assertTrue(body.contains("name=\"paper_json\""));
            assertTrue(body.contains("A paper"));
            assertTrue(body.contains("%PDF-test"));
        } finally {
            server.stop(0);
        }
    }

    private static void respondJson(HttpExchange exchange, String body) throws IOException {
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        try (var output = exchange.getResponseBody()) {
            output.write(response);
        }
    }
}
