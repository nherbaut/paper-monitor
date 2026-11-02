package top.nextnet.paper.monitor.rss;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class RssParserTest {

    private final RssParser parser = new RssParser();

    @Test
    void parsesIntelligenceAnalysisAndMetadataWithoutUsingReviewLinkAsOpenAccess() throws Exception {
        try (var stream = getClass().getResourceAsStream("/rss/intelligence.xml")) {
            var items = parser.parse(stream.readAllBytes());
            assertEquals(1, items.size());
            var item = items.getFirst();
            assertEquals("An example research article", item.title());
            assertEquals("https://example.org/articles/123", item.link());
            assertEquals(LocalDate.of(2026, 11, 2), item.publishedOn());
            assertEquals("Alex Example, Sam Sample", item.authors());
            assertEquals("Example Journal", item.publisher());
            assertEquals("Analysis Evidence & findings. Review this article", item.summary());
            assertNull(item.openAccessLink());
        }
    }

    @Test
    void matchesNamespaceUrisWithAlternatePrefixes() {
        var item = parseItem("""
                <item xmlns:metadata="http://purl.org/dc/elements/1.1/"
                      xmlns:body="http://purl.org/rss/1.0/modules/content/">
                  <metadata:creator>Alex Example</metadata:creator>
                  <metadata:publisher>Example Press</metadata:publisher>
                  <author>Fallback author</author>
                  <body:encoded><![CDATA[<p>Full <b>analysis</b>.</p>]]></body:encoded>
                  <description>Teaser</description>
                </item>
                """);
        assertEquals("Alex Example", item.authors());
        assertEquals("Example Press", item.publisher());
        assertEquals("Full analysis.", item.summary());
    }

    @Test
    void fallsBackToRssAuthorAndDescription() {
        var item = parseItem("""
                <item><author> writer@example.org (Example Writer) </author>
                  <description><![CDATA[<p>A <b>summary</b>.</p><a href="https://example.org/review">Review this article</a>]]></description>
                </item>
                """);
        assertEquals("writer@example.org (Example Writer)", item.authors());
        assertEquals("A summary. Review this article", item.summary());
        assertNull(item.publisher());
        assertNull(item.openAccessLink());
    }

    @Test
    void leavesMissingMetadataUnknown() {
        assertEquals(new RssPaperItem(null, null, null, null, null, null, null), parseItem("<item/>"));
    }

    @Test
    void ignoresMalformedDateAndContinuesParsing() {
        var items = parse("""
                <rss version="2.0"><channel>
                  <item><link>https://example.org/1</link><pubDate>not a date</pubDate></item>
                  <item><link>https://example.org/2</link><pubDate>2026-11-02T09:30:00Z</pubDate></item>
                </channel></rss>
                """);
        assertEquals(2, items.size());
        assertNull(items.getFirst().publishedOn());
        assertEquals(LocalDate.of(2026, 11, 2), items.get(1).publishedOn());
    }

    @Test
    void acceptsEmptyRssFeed() {
        assertTrue(parse("<rss version=\"2.0\"><channel><title>Empty feed</title></channel></rss>").isEmpty());
    }

    @Test
    void ignoresElementsInUnrelatedNamespaces() {
        var item = parseItem("""
                <item xmlns:dc="https://example.org/unrelated" xmlns:content="https://example.org/unrelated">
                  <dc:creator>Wrong author</dc:creator><dc:publisher>Wrong publisher</dc:publisher>
                  <content:encoded>Wrong summary</content:encoded>
                  <description>Actual summary</description>
                </item>
                """);
        assertNull(item.authors());
        assertNull(item.publisher());
        assertEquals("Actual summary", item.summary());
    }

    @Test
    void preservesArxivRssParsing() {
        var item = parse("""
                <rss version="2.0" xmlns:dc="http://purl.org/dc/elements/1.1/"><channel>
                  <title>cs.SE updates on arXiv.org</title>
                  <item><title>Example paper</title><link>https://arxiv.org/abs/1234.56789</link>
                    <description>Abstract: An example abstract.</description><dc:creator>Example Author</dc:creator>
                    <pubDate>Mon, 02 Nov 2026 09:30:00 GMT</pubDate>
                  </item>
                </channel></rss>
                """).getFirst();
        assertEquals("An example abstract.", item.summary());
        assertEquals("Example Author", item.authors());
        assertEquals("arXiv", item.publisher());
        assertEquals(item.link(), item.openAccessLink());
        assertEquals(LocalDate.of(2026, 11, 2), item.publishedOn());
    }

    @Test
    void preservesArxivAtomParsing() {
        var item = parse("""
                <feed xmlns="http://www.w3.org/2005/Atom"><title>arXiv Query: example</title>
                  <entry><title>Example paper</title><id>https://arxiv.org/abs/1234.56789</id>
                    <link rel="alternate" type="text/html" href="https://arxiv.org/abs/1234.56789"/>
                    <link rel="related" type="application/pdf" href="https://arxiv.org/pdf/1234.56789"/>
                    <summary>Example abstract.</summary><author><name>Example Author</name></author>
                    <published>2026-11-02T09:30:00Z</published>
                  </entry>
                </feed>
                """).getFirst();
        assertEquals("Example abstract.", item.summary());
        assertEquals("Example Author", item.authors());
        assertEquals("arXiv", item.publisher());
        assertEquals("https://arxiv.org/abs/1234.56789", item.link());
        assertEquals("https://arxiv.org/pdf/1234.56789", item.openAccessLink());
        assertEquals(LocalDate.of(2026, 11, 2), item.publishedOn());
    }

    @Test
    void preservesScholarParsingWithAlternateContentPrefix() {
        var item = parseItem("""
                <item xmlns:body="http://purl.org/rss/1.0/modules/content/">
                  <title>Example paper</title><link>https://example.org/article</link>
                  <body:encoded><![CDATA[<p>Example abstract written by Alex Example Published by Example Press</p>
                    <p>We think we have found an OA link here: <a href="https://example.org/paper.pdf">this site</a></p>]]></body:encoded>
                </item>
                """);
        assertEquals("Example abstract", item.summary());
        assertEquals("Alex Example", item.authors());
        assertEquals("Example Press", item.publisher());
        assertEquals("https://example.org/paper.pdf", item.openAccessLink());
    }

    @Test
    void preservesScholarWithoutOpenAccessLink() {
        var item = parse("""
                <rss version="2.0"><channel><title>MIAGE Scholar</title>
                  <item><author>Example Press (Alex Example)</author>
                    <description><![CDATA[<p>Example abstract</p><p>We didn't find an OA link, try to find a OA version on
                      <a href="https://scholar.google.com/">Google Scholar</a></p>]]></description>
                  </item>
                </channel></rss>
                """).getFirst();
        assertEquals("Example abstract", item.summary());
        assertEquals("Alex Example", item.authors());
        assertEquals("Example Press", item.publisher());
        assertNull(item.openAccessLink());
    }

    @Test
    void rejectsHtmlUnsupportedXmlAndGenericAtom() {
        for (String xml : new String[] {
                "<html><body><channel><title>arxiv.org</title></channel></body></html>",
                "<rss version=\"2.0\"/>",
                "<document><channel/></document>",
                "<feed xmlns=\"http://www.w3.org/2005/Atom\"><title>Example</title></feed>"
        }) {
            assertThrows(IllegalArgumentException.class, () -> parse(xml));
        }
    }

    @Test
    void rejectsExternalEntitiesAndExternalDtds(@TempDir Path directory) throws Exception {
        Path entity = directory.resolve("entity.txt");
        Files.writeString(entity, "External content must never be imported");
        String xml = """
                <!DOCTYPE rss [<!ENTITY external SYSTEM "%s">]>
                <rss version="2.0"><channel><item><description>&external;</description></item></channel></rss>
                """.formatted(entity.toUri());
        assertThrows(IllegalArgumentException.class, () -> parse(xml));

        Path dtd = directory.resolve("feed.dtd");
        Files.writeString(dtd, "<!ENTITY external 'External DTD content'>");
        String externalDtd = """
                <!DOCTYPE rss SYSTEM "%s">
                <rss version="2.0"><channel><item><description>&external;</description></item></channel></rss>
                """.formatted(dtd.toUri());
        assertThrows(IllegalArgumentException.class, () -> parse(externalDtd));
    }

    private RssPaperItem parseItem(String itemXml) {
        return parse("<rss version=\"2.0\"><channel>" + itemXml + "</channel></rss>").getFirst();
    }

    private java.util.List<RssPaperItem> parse(String xml) {
        return parser.parse(xml.getBytes(StandardCharsets.UTF_8));
    }
}
