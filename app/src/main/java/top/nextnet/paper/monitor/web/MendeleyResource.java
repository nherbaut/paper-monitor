package top.nextnet.paper.monitor.web;

import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.jboss.resteasy.reactive.RestForm;
import top.nextnet.paper.monitor.model.AppUser;
import top.nextnet.paper.monitor.model.LogicalFeed;
import top.nextnet.paper.monitor.service.CurrentUserContext;
import top.nextnet.paper.monitor.service.LogicalFeedAccessService;
import top.nextnet.paper.monitor.service.MendeleyAuthService;
import top.nextnet.paper.monitor.service.MendeleySyncService;

@Path("")
public class MendeleyResource {
    private final CurrentUserContext currentUser;
    private final LogicalFeedAccessService access;
    private final MendeleyAuthService auth;
    private final MendeleySyncService sync;

    public MendeleyResource(CurrentUserContext currentUser, LogicalFeedAccessService access,
            MendeleyAuthService auth, MendeleySyncService sync) {
        this.currentUser = currentUser; this.access = access; this.auth = auth; this.sync = sync;
    }

    @GET @Path("/auth/mendeley/start")
    public Response start(@QueryParam("returnTo") String returnTo) {
        AppUser user = requireUser();
        try { return Response.seeOther(auth.start(user, returnTo)).build(); }
        catch (IOException e) { return adminError(e); }
    }

    @GET @Path("/auth/mendeley/callback")
    public Response callback(@QueryParam("state") String state, @QueryParam("code") String code,
            @QueryParam("error") String error) {
        AppUser user = requireUser();
        if (error != null) return adminError(new IOException("Mendeley authorization was declined: " + error));
        try { return Response.seeOther(URI.create(auth.finish(user, state, code))).build(); }
        catch (IOException e) { return adminError(e); }
    }

    @POST @Path("/api/mendeley/disconnect") @Transactional
    public Response disconnect() { auth.disconnect(requireUser()); return Response.noContent().build(); }

    @GET @Path("/api/mendeley/status") @Produces(MediaType.APPLICATION_JSON) @Transactional
    public Map<String, Object> status() {
        AppUser user = requireUser();
        Map<String, Object> result = new java.util.LinkedHashMap<>(sync.status(user,
                access.readableLogicalFeeds(user).stream().filter(feed -> access.canAdmin(feed, user)).toList()));
        result.put("serverEnabled", auth.isEnabled());
        result.put("requestedScopes", auth.requestedScopes());
        return result;
    }

    @GET @Path("/api/mendeley/folders") @Produces(MediaType.APPLICATION_JSON) @Transactional
    public Object folders() { try { return Map.of("folders", sync.folders(requireUser())); } catch (IOException e) { throw apiError(e); } }

    @POST @Path("/api/mendeley/feeds/{id}/configure") @Produces(MediaType.APPLICATION_JSON) @Transactional
    public Object configure(@PathParam("id") Long id, @RestForm("folderId") String folderId,
            @RestForm("folderName") String folderName) {
        AppUser user = requireUser(); LogicalFeed feed = access.requireAdminLogicalFeed(id, user);
        try { return sync.configure(user, feed, folderId, folderName); } catch (IOException e) { throw apiError(e); }
    }

    @POST @Path("/api/mendeley/feeds/{id}/preview") @Produces(MediaType.APPLICATION_JSON) @Transactional
    public Object preview(@PathParam("id") Long id) {
        AppUser user = requireUser(); LogicalFeed feed = access.requireAdminLogicalFeed(id, user);
        try { return sync.preview(user, feed); } catch (IOException e) { throw apiError(e); }
    }

    @POST @Path("/api/mendeley/feeds/{id}/apply") @Produces(MediaType.APPLICATION_JSON) @Transactional
    public Object apply(@PathParam("id") Long id) {
        AppUser user = requireUser(); LogicalFeed feed = access.requireAdminLogicalFeed(id, user);
        try { return sync.apply(user, feed); } catch (IOException e) { throw apiError(e); }
    }

    @POST @Path("/api/mendeley/feeds/{id}/conflicts/{linkId}/resolve") @Produces(MediaType.APPLICATION_JSON) @Transactional
    public Object resolve(@PathParam("id") Long id, @PathParam("linkId") Long linkId,
            @RestForm("resolution") String resolution) {
        AppUser user = requireUser(); LogicalFeed feed = access.requireAdminLogicalFeed(id, user);
        try { return sync.resolve(user, feed, linkId, resolution); } catch (IOException e) { throw apiError(e); }
    }

    private AppUser requireUser() {
        AppUser user = currentUser.user();
        if (user == null) throw new WebApplicationException("Authentication is required", Response.Status.UNAUTHORIZED);
        return user;
    }
    private WebApplicationException apiError(IOException error) {
        return new WebApplicationException(Response.status(Response.Status.BAD_GATEWAY)
                .type(MediaType.TEXT_PLAIN).entity(error.getMessage()).build());
    }
    private Response adminError(IOException error) {
        return Response.seeOther(URI.create("/admin?error=" + URLEncoder.encode(error.getMessage(), StandardCharsets.UTF_8) + "#mendeley")).build();
    }
}
