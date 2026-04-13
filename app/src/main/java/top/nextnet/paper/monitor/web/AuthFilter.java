package top.nextnet.paper.monitor.web;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import top.nextnet.paper.monitor.model.AppUser;
import top.nextnet.paper.monitor.model.UserSession;
import top.nextnet.paper.monitor.service.AuthService;
import top.nextnet.paper.monitor.service.CurrentUserContext;

@Provider
@Priority(Priorities.AUTHENTICATION)
@ApplicationScoped
public class AuthFilter implements ContainerRequestFilter {

    private static final List<String> PUBLIC_PATH_PREFIXES = List.of(
            "login",
            "auth/oidc/start",
            "auth/oidc/callback",
            "logout");

    @Inject
    AuthService authService;

    @Inject
    CurrentUserContext currentUserContext;

    @Override
    @Transactional
    public void filter(ContainerRequestContext requestContext) {
        String path = normalizePath(requestContext.getUriInfo().getPath());
        if (isPublic(path)) {
            return;
        }

        Cookie cookie = requestContext.getCookies().get(authService.sessionCookieName());
        Optional<UserSession> session = authService.findActiveSession(cookie == null ? null : cookie.getValue());
        if (session.isEmpty()) {
            abortUnauthenticated(requestContext);
            return;
        }

        UserSession authenticatedSession = session.get();
        AppUser user = authenticatedSession.user;
        // Touch template-facing fields while the filter transaction is still open so
        // later request handling does not carry a detached lazy proxy into Qute.
        user.id.longValue();
        user.admin = user.isAdmin();
        user.displayName = user.displayName;
        user.username = user.username;
        user.email = user.email;

        currentUserContext.setSession(authenticatedSession);
        currentUserContext.setUser(user);

        if (requiresAdmin(path, requestContext.getMethod()) && !currentUserContext.isAdmin()) {
            requestContext.abortWith(Response.status(Response.Status.FORBIDDEN)
                    .type(MediaType.TEXT_PLAIN)
                    .entity("Admin access is required")
                    .build());
        }
    }

    private boolean isPublic(String path) {
        for (String prefix : PUBLIC_PATH_PREFIXES) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }

    private boolean requiresAdmin(String path, String method) {
        if ("GET".equalsIgnoreCase(method) && !path.startsWith("admin/export")) {
            return false;
        }
        return path.equals("admin/export")
                || path.equals("admin/import")
                || path.equals("admin/local-users")
                || path.startsWith("admin/users/");
    }

    private void abortUnauthenticated(ContainerRequestContext requestContext) {
        String method = requestContext.getMethod();
        if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)) {
            String returnTo = requestContext.getUriInfo().getRequestUri().getRawPath();
            String query = requestContext.getUriInfo().getRequestUri().getRawQuery();
            if (query != null && !query.isBlank()) {
                returnTo += "?" + query;
            }
            requestContext.abortWith(Response.status(Response.Status.SEE_OTHER)
                    .header(HttpHeaders.LOCATION, "/login?returnTo=" + URLEncoder.encode(returnTo, StandardCharsets.UTF_8))
                    .build());
            return;
        }

        requestContext.abortWith(Response.status(Response.Status.UNAUTHORIZED)
                .type(MediaType.TEXT_PLAIN)
                .entity("Authentication is required")
                .build());
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank() || "/".equals(path)) {
            return "";
        }
        String normalized = path;
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
