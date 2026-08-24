package top.nextnet.paper.monitor.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ReviewResourceTest {

    @Test
    void requiresNonBlankDerivationIdentifier() {
        assertFalse(ReviewResource.hasDerivationId(Map.of()));
        assertFalse(ReviewResource.hasDerivationId(Map.of("derivation_id", "  ")));
        assertTrue(ReviewResource.hasDerivationId(Map.of("derivation_id", "derived-123")));
    }
}
