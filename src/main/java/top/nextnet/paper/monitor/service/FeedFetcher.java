package top.nextnet.paper.monitor.service;

import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

@ApplicationScoped
public class FeedFetcher {

    private final HttpClient httpClient = HttpClient.newHttpClient();

    public byte[] fetch(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .GET()
                .header("Accept", "application/rss+xml, application/xml, text/xml")
                .build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() >= 400) {
            throw new IOException("Feed returned HTTP " + response.statusCode());
        }
        return response.body();
    }
}
