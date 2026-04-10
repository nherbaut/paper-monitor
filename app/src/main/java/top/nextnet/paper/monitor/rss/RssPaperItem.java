package top.nextnet.paper.monitor.rss;

import java.time.LocalDate;

public record RssPaperItem(
        String title,
        String link,
        String openAccessLink,
        String summary,
        String authors,
        String publisher,
        LocalDate publishedOn
) {
}
