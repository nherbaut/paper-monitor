package top.nextnet.paper.monitor.web;

import io.quarkus.vertx.http.Compressed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

@Path("/app-assets")
public class HomeAssetResource {

    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "home.css", "text/css; charset=UTF-8",
            "home.js", "application/javascript; charset=UTF-8");

    @GET
    @Path("/{name}")
    @Compressed
    public Response asset(@PathParam("name") String name) {
        String contentType = CONTENT_TYPES.get(name);
        if (contentType == null) throw new NotFoundException();
        String resource = "META-INF/resources/assets/app/" + name;
        try (InputStream input = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            if (input == null) throw new NotFoundException();
            return Response.ok(input.readAllBytes(), contentType)
                    .header("Cache-Control", "public, max-age=31536000, immutable")
                    .build();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read application asset " + name, exception);
        }
    }
}
