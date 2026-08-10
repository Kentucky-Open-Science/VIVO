/* $This file is distributed under the terms of the license in LICENSE$ */

package org.vivoweb.webapp.ingest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Fetches the article details that esummary does not provide: abstracts and
 * MeSH descriptors via PubMed efetch XML, and Methods-section text via PMC
 * full-text JATS XML (mirroring the UK Knowledge Graph PMC pipeline).
 */
public final class PubmedDetailFetcher {

    private static final Log log = LogFactory.getLog(PubmedDetailFetcher.class);

    /** Cap stored section text so one review article cannot bloat the store. */
    private static final int MAX_TEXT_LENGTH = 50000;

    /** Details for one article gathered from efetch / PMC. */
    public static class ArticleDetails {
        private String abstractText;
        private String pmcid;
        private String methodsText;
        private final List<String[]> meshTerms = new ArrayList<String[]>();

        public String getAbstractText() {
            return abstractText;
        }

        public String getPmcid() {
            return pmcid;
        }

        public String getMethodsText() {
            return methodsText;
        }

        public void setMethodsText(String methodsText) {
            this.methodsText = methodsText;
        }

        /** Pairs of [descriptorUI, descriptorName]. */
        public List<String[]> getMeshTerms() {
            return meshTerms;
        }
    }

    private PubmedDetailFetcher() {
        // Static utility; never instantiated.
    }

    /**
     * Parses a PubMed efetch (retmode=xml) response into per-PMID details:
     * abstract text, MeSH descriptors, and PMCID when the article is in PMC.
     */
    public static Map<String, ArticleDetails> parseEfetch(String xml) {
        Map<String, ArticleDetails> byPmid = new HashMap<String, ArticleDetails>();
        Document doc = parse(xml);
        if (doc == null) {
            return byPmid;
        }

        NodeList articles = doc.getElementsByTagName("PubmedArticle");
        for (int i = 0; i < articles.getLength(); i++) {
            Element article = (Element) articles.item(i);
            String pmid = firstText(article, "PMID");
            if (pmid == null || pmid.trim().isEmpty()) {
                continue;
            }

            ArticleDetails details = new ArticleDetails();

            // Abstract: concatenate all AbstractText nodes, keeping their
            // section labels (BACKGROUND, METHODS, ...) when present.
            StringBuilder abstractText = new StringBuilder();
            NodeList abstractNodes = article.getElementsByTagName("AbstractText");
            for (int j = 0; j < abstractNodes.getLength(); j++) {
                Element part = (Element) abstractNodes.item(j);
                String label = part.getAttribute("Label");
                String text = part.getTextContent();
                if (text == null || text.trim().isEmpty()) {
                    continue;
                }
                if (abstractText.length() > 0) {
                    abstractText.append('\n');
                }
                if (label != null && !label.trim().isEmpty()) {
                    abstractText.append(label.trim()).append(": ");
                }
                abstractText.append(text.trim());
            }
            if (abstractText.length() > 0) {
                details.abstractText = truncate(abstractText.toString());
            }

            // MeSH descriptors: UI + name.
            NodeList descriptors = article.getElementsByTagName("DescriptorName");
            for (int j = 0; j < descriptors.getLength(); j++) {
                Element descriptor = (Element) descriptors.item(j);
                String ui = descriptor.getAttribute("UI");
                String name = descriptor.getTextContent();
                if (ui != null && !ui.trim().isEmpty() && name != null && !name.trim().isEmpty()) {
                    details.meshTerms.add(new String[] {ui.trim(), name.trim()});
                }
            }

            // PMCID from the ArticleIdList (idtype "pmc").
            NodeList ids = article.getElementsByTagName("ArticleId");
            for (int j = 0; j < ids.getLength(); j++) {
                Element id = (Element) ids.item(j);
                if ("pmc".equalsIgnoreCase(id.getAttribute("IdType"))) {
                    details.pmcid = id.getTextContent() == null ? null : id.getTextContent().trim();
                }
            }

            byPmid.put(pmid.trim(), details);
        }
        return byPmid;
    }

    /**
     * Parses a PMC efetch (JATS XML) response into per-PMCID Methods-section
     * text. A section qualifies when its sec-type or title matches
     * methods/materials.
     */
    public static Map<String, String> parsePmcMethods(String xml) {
        Map<String, String> byPmcid = new HashMap<String, String>();
        Document doc = parse(xml);
        if (doc == null) {
            return byPmcid;
        }

        NodeList articles = doc.getElementsByTagName("article");
        for (int i = 0; i < articles.getLength(); i++) {
            Element article = (Element) articles.item(i);

            String pmcid = null;
            NodeList idNodes = article.getElementsByTagName("article-id");
            for (int j = 0; j < idNodes.getLength(); j++) {
                Element id = (Element) idNodes.item(j);
                if ("pmc".equalsIgnoreCase(id.getAttribute("pub-id-type"))) {
                    pmcid = id.getTextContent() == null ? null : id.getTextContent().trim();
                }
            }
            if (pmcid == null || pmcid.isEmpty()) {
                continue;
            }

            StringBuilder methods = new StringBuilder();
            NodeList secs = article.getElementsByTagName("sec");
            for (int j = 0; j < secs.getLength(); j++) {
                Element sec = (Element) secs.item(j);
                if (!isMethodsSection(sec)) {
                    continue;
                }
                appendParagraphText(sec, methods);
            }
            if (methods.length() > 0) {
                byPmcid.put(pmcid, truncate(methods.toString()));
            }
        }
        return byPmcid;
    }

    private static boolean isMethodsSection(Element sec) {
        String secType = sec.getAttribute("sec-type");
        if (secType != null && secType.toLowerCase().contains("method")) {
            return true;
        }
        // First child <title> only; nested section titles are covered by their parent.
        NodeList children = sec.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE && "title".equals(child.getNodeName())) {
                String title = child.getTextContent();
                if (title != null) {
                    String lower = title.toLowerCase();
                    return lower.contains("method") || lower.contains("materials");
                }
                return false;
            }
        }
        return false;
    }

    private static void appendParagraphText(Element sec, StringBuilder out) {
        NodeList paragraphs = sec.getElementsByTagName("p");
        for (int i = 0; i < paragraphs.getLength(); i++) {
            String text = paragraphs.item(i).getTextContent();
            if (text == null) {
                continue;
            }
            String cleaned = text.replaceAll("\\s+", " ").trim();
            if (cleaned.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(cleaned);
            if (out.length() >= MAX_TEXT_LENGTH) {
                return;
            }
        }
    }

    /** Text content of the first descendant element with the given tag, or null. */
    private static String firstText(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() == 0) {
            return null;
        }
        return nodes.item(0).getTextContent();
    }

    private static String truncate(String text) {
        if (text.length() <= MAX_TEXT_LENGTH) {
            return text;
        }
        return text.substring(0, MAX_TEXT_LENGTH);
    }

    /** Hardened DOM parse: no DTDs, no external entities (XXE-safe). */
    private static Document parse(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new ByteArrayInputStream(xml.getBytes("UTF-8")));
        } catch (ParserConfigurationException e) {
            log.error("XML parser configuration failed", e);
            return null;
        } catch (SAXException e) {
            log.error("Could not parse eUtils XML response", e);
            return null;
        } catch (IOException e) {
            log.error("Could not read eUtils XML response", e);
            return null;
        }
    }
}
