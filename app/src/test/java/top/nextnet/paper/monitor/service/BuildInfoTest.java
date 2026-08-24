package top.nextnet.paper.monitor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BuildInfoTest {

    @Test
    void acceptsOnlyGitCommitIdentifiers() {
        assertEquals("7f2ceac4bcb87b4a6d8bb763bed2948353f58bc1",
                BuildInfo.normalizeCommit("7F2CEAC4BCB87B4A6D8BB763BED2948353F58BC1"));
        assertEquals("unknown", BuildInfo.normalizeCommit("@paper-monitor.build.commit@"));
        assertEquals("unknown", BuildInfo.normalizeCommit("not-a-commit"));
    }
}
