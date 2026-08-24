package top.nextnet.paper.monitor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PaperDataExtractorServiceTest {

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
}
