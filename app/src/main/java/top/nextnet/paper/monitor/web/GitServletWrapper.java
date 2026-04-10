package top.nextnet.paper.monitor.web;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebInitParam;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import org.eclipse.jgit.http.server.GitServlet;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.ReceivePack;
import org.eclipse.jgit.transport.UploadPack;
import org.eclipse.jgit.transport.resolver.FileResolver;
import org.eclipse.jgit.transport.resolver.RepositoryResolver;
import org.eclipse.jgit.transport.resolver.ServiceNotAuthorizedException;
import org.eclipse.jgit.transport.resolver.ServiceNotEnabledException;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@WebServlet(urlPatterns = "/git/*", name = "GitServlet", loadOnStartup = 1)
public class GitServletWrapper extends HttpServlet {

    @Inject
    @ConfigProperty(name = "paper-monitor.git.root", defaultValue = "git-remotes")
    String gitRoot;

    private GitServlet gitServlet;

    @Override
    public void init(ServletConfig config) throws ServletException {
        super.init(config);
        gitServlet = new GitServlet();
        File root = new File(gitRoot).getAbsoluteFile();
        if (!root.exists()) {
            root.mkdirs();
        }
        gitServlet.setRepositoryResolver(new FileResolver<>(root, true));
        
        // Enable anonymous push/pull
        gitServlet.setUploadPackFactory((req, db) -> {
            UploadPack up = new UploadPack(db);
            return up;
        });
        gitServlet.setReceivePackFactory((req, db) -> {
            ReceivePack rp = new ReceivePack(db);
            return rp;
        });

        gitServlet.init(config);
    }

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        gitServlet.service(req, resp);
    }
}
