package top.nextnet.paper.monitor.rss;

import jakarta.enterprise.context.ApplicationScoped;
import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.w3c.dom.Node;

@ApplicationScoped
public class RssParser {

    public List<RssPaperItem> parse(byte[] xmlBytes) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            var builder = factory.newDocumentBuilder();
            var document = builder.parse(new ByteArrayInputStream(xmlBytes));
            var items = document.getElementsByTagName("item");
            List<RssPaperItem> parsedItems = new ArrayList<>();
            for (int i = 0; i < items.getLength(); i++) {
                Node item = items.item(i);
                String title = childText(item, "title");
                String link = childText(item, "link");
                String description = firstNonBlank(childText(item, "content:encoded"), childText(item, "description"));
                String author = childText(item, "author");
                String pubDate = childText(item, "pubDate");
                parsedItems.add(new RssPaperItem(
                        normalize(title),
                        normalize(link),
                        extractOpenAccessLink(description),
                        extractSummary(description),
                        extractAuthors(author, description),
                        extractPublisher(author, description),
                        parsePublishedOn(pubDate)
                ));
            }
            return parsedItems;
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not parse RSS feed", e);
        }
    }

    private String childText(Node node, String tagName) {
        var children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (tagName.equals(child.getNodeName())) {
                return child.getTextContent();
            }
        }
        return null;
    }

    private String extractOpenAccessLink(String rawDescription) {
        if (rawDescription == null || rawDescription.isBlank()) {
            return null;
        }
        String normalized = Jsoup.parse(rawDescription).text().replaceAll("\\s+", " ").trim();
        if (normalized.contains("We didn't find an OA link")) {
            return null;
        }
        Element link = Jsoup.parse(rawDescription).selectFirst("a[href]");
        return link == null ? null : normalize(link.attr("href"));
    }

    private String extractSummary(String rawDescription) {
        if (rawDescription == null || rawDescription.isBlank()) {
            return null;
        }
        String plainText = Jsoup.parse(rawDescription).text().replaceAll("\\s+", " ").trim();
        int authorMarker = plainText.indexOf(" written by ");
        if (authorMarker > 0) {
            plainText = plainText.substring(0, authorMarker).trim();
        }
        plainText = plainText.replace("We didn't find an OA link, try to find a OA version on Google Scholar", "").trim();
        plainText = plainText.replace("We think we have found an OA link here: this site", "").trim();
        return plainText.isBlank() ? null : plainText;
    }

    private String extractAuthors(String authorField, String description) {
        String parsed = extractFromDescription(description, "written by ", " Published by");
        if (parsed != null) {
            return parsed;
        }
        if (authorField == null) {
            return null;
        }
        int start = authorField.indexOf('(');
        int end = authorField.lastIndexOf(')');
        if (start >= 0 && end > start) {
            return normalize(authorField.substring(start + 1, end));
        }
        return normalize(authorField);
    }

    private String extractPublisher(String authorField, String description) {
        String parsed = extractPublisherFromDescription(description);
        if (parsed != null) {
            return parsed;
        }
        if (authorField == null) {
            return null;
        }
        int start = authorField.indexOf('(');
        return start > 0 ? normalize(authorField.substring(0, start)) : normalize(authorField);
    }

    private String extractPublisherFromDescription(String description) {
        if (description == null) {
            return null;
        }
        String normalized = Jsoup.parse(description).text().replaceAll("\\s+", " ").trim();
        int start = normalized.indexOf("Published by ");
        if (start < 0) {
            return null;
        }
        String tail = normalized.substring(start + "Published by ".length());
        int oaMarker = tail.indexOf(" We ");
        String publisher = oaMarker >= 0 ? tail.substring(0, oaMarker) : tail;
        return normalize(publisher);
    }

    private String extractFromDescription(String description, String startMarker, String endMarker) {
        if (description == null) {
            return null;
        }
        String normalized = Jsoup.parse(description).text().replaceAll("\\s+", " ").trim();
        int start = normalized.indexOf(startMarker);
        if (start < 0) {
            return null;
        }
        String tail = normalized.substring(start + startMarker.length());
        int end = tail.indexOf(endMarker);
        String value = end >= 0 ? tail.substring(0, end) : tail;
        value = value.trim();
        return value.isBlank() ? null : value;
    }

    private LocalDate parsePublishedOn(String pubDate) {
        if (pubDate == null || pubDate.isBlank()) {
            return null;
        }
        return OffsetDateTime.parse(pubDate, DateTimeFormatter.RFC_1123_DATE_TIME).toLocalDate();
    }

    private String firstNonBlank(String first, String second) {
        return (first != null && !first.isBlank()) ? first : second;
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.isBlank() ? null : normalized;
    }
}
