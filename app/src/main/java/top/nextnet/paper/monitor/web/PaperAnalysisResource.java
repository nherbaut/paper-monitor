package top.nextnet.paper.monitor.web;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import top.nextnet.paper.monitor.model.AppUser;
import top.nextnet.paper.monitor.service.CurrentUserContext;
import top.nextnet.paper.monitor.service.PaperAnalysisService;

@Path("/")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class PaperAnalysisResource {
    private final CurrentUserContext currentUser;
    private final PaperAnalysisService analysis;

    public PaperAnalysisResource(CurrentUserContext currentUser, PaperAnalysisService analysis) {
        this.currentUser = currentUser;
        this.analysis = analysis;
    }

    @POST
    @Path("/api/papers/{paperId}/analysis")
    public Response start(
            @PathParam("paperId") Long paperId,
            @QueryParam("reviewId") Long reviewId,
            @QueryParam("trigger") String trigger
    ) {
        Map<String, Object> job = analysis.start(requireUser(), paperId, reviewId, trigger);
        return Response.accepted(job).build();
    }

    @GET
    @Path("/api/paper-analyses/{jobId}")
    public Map<String, Object> status(@PathParam("jobId") Long jobId) {
        return analysis.status(requireUser(), jobId);
    }

    @GET
    @Path("/api/papers/{paperId}/analysis/latest")
    public Map<String, Object> latest(@PathParam("paperId") Long paperId) {
        return analysis.latest(requireUser(), paperId);
    }

    private AppUser requireUser() {
        AppUser user = currentUser.user();
        if (user == null) {
            throw new WebApplicationException("Authentication is required", Response.Status.UNAUTHORIZED);
        }
        return user;
    }
}
