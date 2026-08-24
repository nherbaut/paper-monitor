package top.nextnet.paper.monitor.web;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;
import top.nextnet.paper.monitor.service.BuildInfo;

@Provider
public class BuildInfoResponseFilter implements ContainerResponseFilter {

    public static final String REVISION_HEADER = "X-Paper-Monitor-Revision";

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        responseContext.getHeaders().putSingle(REVISION_HEADER, BuildInfo.commit());
    }
}
