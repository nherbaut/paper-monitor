package top.nextnet.paper.monitor.web;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.RestForm;
import top.nextnet.paper.monitor.model.AppUser;
import top.nextnet.paper.monitor.model.LogicalFeed;
import top.nextnet.paper.monitor.service.ArxivPdfRetrievalService;
import top.nextnet.paper.monitor.service.CurrentUserContext;
import top.nextnet.paper.monitor.service.LogicalFeedAccessService;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
public class ArxivPdfRetrievalResource {
    private final CurrentUserContext currentUser;
    private final LogicalFeedAccessService access;
    private final ArxivPdfRetrievalService retrievals;

    public ArxivPdfRetrievalResource(CurrentUserContext currentUser, LogicalFeedAccessService access,
            ArxivPdfRetrievalService retrievals) {
        this.currentUser = currentUser;
        this.access = access;
        this.retrievals = retrievals;
    }

    @GET
    @Path("/api/logical-feeds/{id}/arxiv-pdf-retrievals/preview")
    public Object preview(@PathParam("id") Long id, @QueryParam("state") String state) {
        AppUser user = requireUser();
        LogicalFeed feed = access.requireAdminLogicalFeed(id, user);
        return retrievals.preview(feed, state);
    }

    @POST
    @Path("/api/logical-feeds/{id}/arxiv-pdf-retrievals")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response start(@PathParam("id") Long id, @RestForm("state") String state) {
        AppUser user = requireUser();
        LogicalFeed feed = access.requireAdminLogicalFeed(id, user);
        return Response.accepted(retrievals.start(user, feed, state)).build();
    }

    @GET
    @Path("/api/logical-feeds/{id}/arxiv-pdf-retrievals/latest")
    public Object latest(@PathParam("id") Long id) {
        AppUser user = requireUser();
        access.requireAdminLogicalFeed(id, user);
        return retrievals.latest(id);
    }

    @GET
    @Path("/api/arxiv-pdf-retrievals/{jobId}")
    public Object status(@PathParam("jobId") Long jobId) {
        AppUser user = requireUser();
        Long feedId = retrievals.feedId(jobId);
        access.requireAdminLogicalFeed(feedId, user);
        return retrievals.status(feedId, jobId);
    }

    @POST
    @Path("/api/arxiv-pdf-retrievals/{jobId}/retry")
    public Response retry(@PathParam("jobId") Long jobId) {
        AppUser user = requireUser();
        Long feedId = retrievals.feedId(jobId);
        LogicalFeed feed = access.requireAdminLogicalFeed(feedId, user);
        return Response.accepted(retrievals.retry(user, feed, jobId)).build();
    }

    private AppUser requireUser() {
        AppUser user = currentUser.user();
        if (user == null) throw new WebApplicationException("Authentication is required", Response.Status.UNAUTHORIZED);
        return user;
    }
}
