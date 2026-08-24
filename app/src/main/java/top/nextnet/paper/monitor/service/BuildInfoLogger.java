package top.nextnet.paper.monitor.service;

import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

@ApplicationScoped
public class BuildInfoLogger {

    void logRevision(@Observes StartupEvent event) {
        Log.infof("Paper Monitor build revision: %s", BuildInfo.commit());
    }
}
