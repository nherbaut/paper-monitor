package top.nextnet.paper.monitor.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import org.junit.jupiter.api.Test;

class AppUserAvatarTest {

    @Test
    void selectedAvatarUsesBundledAsset() {
        AppUser user = new AppUser();
        user.avatarFileName = AppUser.normalizeAvatarFileName("miage-student-07.png");

        assertEquals("/assets/student-avatar/miage-student-07.png", user.avatarUrl());
    }

    @Test
    void missingAvatarGetsStableBundledFallback() {
        AppUser user = new AppUser();
        user.id = 42L;
        user.username = "student";

        String first = user.avatarUrl();
        String second = user.avatarUrl();

        assertEquals(first, second);
        assertTrue(first.matches("/assets/student-avatar/miage-student-(0[1-9]|1[0-9]|2[0-4])\\.png"));
    }

    @Test
    void avatarOptionsAreUniqueExistingAssets() {
        var options = AppUser.avatarOptions();

        assertEquals(24, options.size());
        assertEquals(24, new HashSet<>(options.stream().map(AppUser.AvatarOption::fileName).toList()).size());
        options.forEach(option -> assertNotNull(
                getClass().getClassLoader().getResource("META-INF/resources" + option.url()),
                option.fileName()));
    }

    @Test
    void arbitraryAvatarPathsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> AppUser.normalizeAvatarFileName("../../private.png"));
        assertThrows(IllegalArgumentException.class,
                () -> AppUser.normalizeAvatarFileName("miage-student-25.png"));
    }
}
