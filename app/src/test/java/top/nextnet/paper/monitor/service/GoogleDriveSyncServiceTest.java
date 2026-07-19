package top.nextnet.paper.monitor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import top.nextnet.paper.monitor.model.LogicalFeed;

class GoogleDriveSyncServiceTest {

    @Test
    void extractsFolderIdFromCommonDriveUrls() {
        assertEquals("abcDEF_123-xyz", GoogleDriveSyncService.extractFolderId(
                "https://drive.google.com/drive/folders/abcDEF_123-xyz"));
        assertEquals("abcDEF_123-xyz", GoogleDriveSyncService.extractFolderId(
                "https://drive.google.com/open?id=abcDEF_123-xyz"));
        assertEquals("abcDEF_123-xyz", GoogleDriveSyncService.extractFolderId(
                "https://drive.google.com/drive/u/0/folders/abcDEF_123-xyz?usp=sharing"));
    }

    @Test
    void acceptsPlainFolderIdAndBlankInput() {
        assertEquals("abcDEF_123-xyz", GoogleDriveSyncService.extractFolderId("abcDEF_123-xyz"));
        assertNull(GoogleDriveSyncService.extractFolderId(" "));
    }

    @Test
    void rejectsNonDriveText() {
        assertThrows(IllegalArgumentException.class, () -> GoogleDriveSyncService.extractFolderId("not a folder"));
    }

    @Test
    void detectsFullDriveScope() {
        assertEquals(true, GoogleDriveSyncService.hasFullDriveScope(
                "openid email https://www.googleapis.com/auth/drive profile"));
        assertEquals(false, GoogleDriveSyncService.hasFullDriveScope(
                "openid email https://www.googleapis.com/auth/drive.file profile"));
    }

    @Test
    void usesPaperFeedNameForFolder() {
        LogicalFeed logicalFeed = new LogicalFeed();
        logicalFeed.name = "  Review 2026  ";

        assertEquals("Review 2026", GoogleDriveSyncService.paperFeedFolderName(logicalFeed));
    }

    @Test
    void escapesDriveQueryStrings() {
        assertEquals("Bob\\'s \\\\ Feed", GoogleDriveSyncService.driveQueryEscape("Bob's \\ Feed"));
    }
}
