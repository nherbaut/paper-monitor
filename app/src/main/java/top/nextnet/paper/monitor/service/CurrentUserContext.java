package top.nextnet.paper.monitor.service;

import jakarta.enterprise.context.RequestScoped;
import top.nextnet.paper.monitor.model.AppUser;
import top.nextnet.paper.monitor.model.UserSession;

@RequestScoped
public class CurrentUserContext {

    private AppUser user;
    private UserSession session;

    public AppUser user() {
        return user;
    }

    public void setUser(AppUser user) {
        this.user = user;
    }

    public UserSession session() {
        return session;
    }

    public void setSession(UserSession session) {
        this.session = session;
    }

    public boolean isAuthenticated() {
        return user != null;
    }

    public boolean isAdmin() {
        return user != null && user.admin;
    }
}
