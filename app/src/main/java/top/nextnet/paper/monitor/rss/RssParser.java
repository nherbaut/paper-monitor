package top.nextnet.paper.monitor.rss;

import jakarta.enterprise.context.ApplicationScoped;
import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.w3c.dom.Node;

@ApplicationScoped
public class RssParser {

    private static final String DC_NAMESPACE = "http://purl.org/dc/elements/1.1/";
    private static final String CONTENT_NAMESPACE = "http://purl.org/rss/1.0/modules/content/";

    public List<RssPaperItem> parse(byte[] xmlBytes) {
        try {
            Document document = parseDocument(xmlBytes);
            RssProvider provider = detectProvider(document);
            NodeList items = provider == RssProvider.ARXIV && isAtomFeed(document)
                    ? document.getElementsByTagNameNS("*", "entry")
                    : document.getElementsByTagName("item");
            List<RssPaperItem> parsedItems = new ArrayList<>();
            for (int i = 0; i < items.getLength(); i++) {
                Node item = items.item(i);
                parsedItems.add(switch (provider) {
                    case ARXIV -> parseArxivItem(item);
                    case MIAGE_SCHOLAR -> parseMiageScholarItem(item);
                    case GENERIC -> parseGenericItem(item);
                });
            }
            return parsedItems;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not parse RSS feed", e);
        }
    }

    private Document parseDocument(byte[] xmlBytes) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        var builder = factory.newDocumentBuilder();
        return builder.parse(new ByteArrayInputStream(xmlBytes));
    }

    private RssProvider detectProvider(Document document) {
        Node root = document.getDocumentElement();
        Node channel = null;
        if (root != null && "rss".equals(root.getNodeName())) {
            var children = root.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                if ("channel".equals(children.item(i).getNodeName())) {
                    channel = children.item(i);
                    break;
                }
            }
        }
        if (channel == null && !isAtomFeed(document)) {
            throw new IllegalArgumentException("Unsupported feed format. Expected RSS with a channel or an arXiv Atom feed.");
        }
        String title = normalize(childText(channel, "title"));
        String link = normalize(childText(channel, "link"));
        String description = normalize(childText(channel, "description"));

        if (channel == null && isAtomFeed(document)) {
            Node feed = document.getDocumentElement();
            title = normalize(childText(feed, "title"));
            link = normalize(atomAlternateLink(feed));
            description = normalize(childText(feed, "subtitle"));
        }

        if ((title != null && title.toLowerCase().contains("arxiv.org"))
                || (link != null && link.toLowerCase().contains("arxiv.org"))
                || isAtomFeed(document) && title != null && title.toLowerCase().startsWith("arxiv query:")) {
            return RssProvider.ARXIV;
        }

        if (channel != null && ((title != null && title.toLowerCase().contains("scholar"))
                || (link != null && link.toLowerCase().contains("scholar.miage.dev"))
                || (description != null && description.contains("OA link"))
                || looksLikeMiageScholarItem(document))) {
            return RssProvider.MIAGE_SCHOLAR;
        }

        if (channel != null) {
            return RssProvider.GENERIC;
        }
        throw new IllegalArgumentException("Unsupported Atom provider. Only arXiv Atom feeds are supported.");
    }

    private boolean isAtomFeed(Document document) {
        Node root = document.getDocumentElement();
        return root != null && matchesTag(root, "feed");
    }

    private boolean looksLikeMiageScholarItem(Document document) {
        var items = document.getElementsByTagName("item");
        for (int i = 0; i < items.getLength(); i++) {
            Node item = items.item(i);
            String description = firstNonBlank(namespacedChildText(item, CONTENT_NAMESPACE, "encoded"), childText(item, "description"));
            if (description == null || description.isBlank()) {
                continue;
            }
            String normalized = Jsoup.parse(description).text().replaceAll("\\s+", " ").trim();
            if (normalized.contains(" written by ")
                    || normalized.contains("Published by ")
                    || normalized.contains("We didn't find an OA link")
                    || normalized.contains("We think we have found an OA link")) {
                return true;
            }
        }
        return false;
    }

    private RssPaperItem parseMiageScholarItem(Node item) {
        String title = childText(item, "title");
        String link = childText(item, "link");
        String description = firstNonBlank(namespacedChildText(item, CONTENT_NAMESPACE, "encoded"), childText(item, "description"));
        String author = childText(item, "author");
        String pubDate = childText(item, "pubDate");
        return new RssPaperItem(
                normalize(title),
                normalize(link),
                extractMiageOpenAccessLink(description),
                extractMiageSummary(description),
                extractMiageAuthors(author, description),
                extractMiagePublisher(author, description),
                parsePublishedOn(pubDate)
        );
    }

    private RssPaperItem parseArxivItem(Node item) {
        String title = normalize(childText(item, "title"));
        String link = normalize(firstNonBlank(atomAlternateLink(item), childText(item, "link"), childText(item, "id")));
        String openAccessLink = normalize(firstNonBlank(atomPdfLink(item), childText(item, "link")));
        String description = normalize(firstNonBlank(childText(item, "summary"), childText(item, "description")));
        String pubDate = firstNonBlank(childText(item, "published"), childText(item, "pubDate"), childText(item, "updated"));
        String authors = normalize(firstNonBlank(joinAuthorNames(item), namespacedChildText(item, DC_NAMESPACE, "creator"), childText(item, "author")));
        String summary = extractArxivSummary(description);
        return new RssPaperItem(
                title,
                link,
                openAccessLink,
                summary,
                authors,
                "arXiv",
                parsePublishedOn(pubDate)
        );
    }

    private String childText(Node node, String tagName) {
        if (node == null) {
            return null;
        }
        var children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (matchesTag(child, tagName)) {
                return child.getTextContent();
            }
        }
        return null;
    }

    private boolean matchesTag(Node node, String tagName) {
        if (node == null || tagName == null) {
            return false;
        }
        return tagName.equals(node.getNodeName()) || tagName.equals(node.getLocalName());
    }

    private String namespacedChildText(Node node, String namespace, String localName) {
        List<String> values = new ArrayList<>();
        var children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (namespace.equals(child.getNamespaceURI()) && localName.equals(child.getLocalName())) {
                String value = normalize(child.getTextContent());
                if (value != null) {
                    values.add(value);
                }
            }
        }
        return values.isEmpty() ? null : String.join(", ", values);
    }

    private RssPaperItem parseGenericItem(Node item) {
        String description = firstNonBlank(
                namespacedChildText(item, CONTENT_NAMESPACE, "encoded"), childText(item, "description"));
        return new RssPaperItem(
                normalize(childText(item, "title")),
                normalize(childText(item, "link")),
                null,
                description == null ? null : normalize(Jsoup.parse(description).text()),
                normalize(firstNonBlank(namespacedChildText(item, DC_NAMESPACE, "creator"), childText(item, "author"))),
                namespacedChildText(item, DC_NAMESPACE, "publisher"),
                parsePublishedOn(childText(item, "pubDate")));
    }

    private String joinAuthorNames(Node item) {
        List<String> names = new ArrayList<>();
        var children = item.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!matchesTag(child, "author")) {
                continue;
            }
            String name = normalize(childText(child, "name"));
            if (name != null) {
                names.add(name);
            }
        }
        return names.isEmpty() ? null : String.join(", ", names);
    }

    private String atomAlternateLink(Node node) {
        return atomLink(node, "alternate", "text/html");
    }

    private String atomPdfLink(Node node) {
        return firstNonBlank(
                atomLink(node, "related", "application/pdf"),
                atomLinkByTitle(node, "pdf"));
    }

    private String atomLink(Node node, String rel, String type) {
        if (!(node instanceof org.w3c.dom.Element element)) {
            return null;
        }
        var children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof org.w3c.dom.Element linkElement) || !matchesTag(child, "link")) {
                continue;
            }
            String childRel = normalize(linkElement.getAttribute("rel"));
            String childType = normalize(linkElement.getAttribute("type"));
            String href = normalize(linkElement.getAttribute("href"));
            if (href == null) {
                continue;
            }
            boolean relMatches = rel == null || rel.equalsIgnoreCase(childRel);
            boolean typeMatches = type == null || type.equalsIgnoreCase(childType);
            if (relMatches && typeMatches) {
                return href;
            }
        }
        return null;
    }

    private String atomLinkByTitle(Node node, String title) {
        if (!(node instanceof org.w3c.dom.Element element)) {
            return null;
        }
        var children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!(child instanceof org.w3c.dom.Element linkElement) || !matchesTag(child, "link")) {
                continue;
            }
            String childTitle = normalize(linkElement.getAttribute("title"));
            String href = normalize(linkElement.getAttribute("href"));
            if (href != null && title.equalsIgnoreCase(childTitle)) {
                return href;
            }
        }
        return null;
    }

    private String extractMiageOpenAccessLink(String rawDescription) {
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

    private String extractMiageSummary(String rawDescription) {
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

    private String extractMiageAuthors(String authorField, String description) {
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

    private String extractMiagePublisher(String authorField, String description) {
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

    private String extractArxivSummary(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        String summary = description.replaceAll("\\s+", " ").trim();
        int abstractIndex = summary.indexOf("Abstract:");
        if (abstractIndex >= 0) {
            summary = summary.substring(abstractIndex + "Abstract:".length()).trim();
        }
        return summary.isBlank() ? null : summary;
    }

    private LocalDate parsePublishedOn(String pubDate) {
        if (pubDate == null || pubDate.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(pubDate.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toLocalDate();
        } catch (Exception ignored) {
            try {
                return ZonedDateTime.parse(pubDate.trim()).toLocalDate();
            } catch (Exception ignoredIsoDate) {
                return null;
            }
        }
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.isBlank() ? null : normalized;
    }

    private enum RssProvider {
        ARXIV,
        MIAGE_SCHOLAR,
        GENERIC
    }
}
