/* $This file is distributed under the terms of the license in LICENSE$ */

package org.vivoweb.webapp.ingest;

import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import com.fasterxml.jackson.databind.JsonNode;
import edu.cornell.mannlib.vitro.webapp.dao.VitroVocabulary;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.vocabulary.RDF;

/**
 * Maps a PubMed eUtils esummary record (JSON) to VIVO-ISF RDF in a Jena model.
 *
 * URIs are deterministic, derived from the PMID, so repeated harvests are
 * idempotent (merge semantics). Property and class URIs are copied from
 * org.vivoweb.webapp.controller.freemarker.CreateAndLinkResourceController.
 */
public final class PubmedRdfMapper {

    // Class URIs
    public static final String BIBO_ACADEMIC_ARTICLE = "http://purl.org/ontology/bibo/AcademicArticle";
    public static final String BIBO_JOURNAL = "http://purl.org/ontology/bibo/Journal";
    public static final String VCARD_INDIVIDUAL = "http://www.w3.org/2006/vcard/ns#Individual";
    public static final String VCARD_NAME = "http://www.w3.org/2006/vcard/ns#Name";
    public static final String VIVO_AUTHORSHIP = "http://vivoweb.org/ontology/core#Authorship";
    public static final String VIVO_DATETIMEVALUE_CLASS = "http://vivoweb.org/ontology/core#DateTimeValue";

    // Property URIs
    public static final String BIBO_DOI = "http://purl.org/ontology/bibo/doi";
    public static final String BIBO_ISSN = "http://purl.org/ontology/bibo/issn";
    public static final String BIBO_ISSUE = "http://purl.org/ontology/bibo/issue";
    public static final String BIBO_PAGE_END = "http://purl.org/ontology/bibo/pageEnd";
    public static final String BIBO_PAGE_START = "http://purl.org/ontology/bibo/pageStart";
    public static final String BIBO_PMID = "http://purl.org/ontology/bibo/pmid";
    public static final String BIBO_VOLUME = "http://purl.org/ontology/bibo/volume";
    public static final String RDFS_LABEL = "http://www.w3.org/2000/01/rdf-schema#label";
    public static final String VCARD_FAMILYNAME = "http://www.w3.org/2006/vcard/ns#familyName";
    public static final String VCARD_GIVENNAME = "http://www.w3.org/2006/vcard/ns#givenName";
    public static final String VCARD_HAS_NAME = "http://www.w3.org/2006/vcard/ns#hasName";
    public static final String VIVO_DATETIME = "http://vivoweb.org/ontology/core#dateTime";
    public static final String VIVO_DATETIMEPRECISION = "http://vivoweb.org/ontology/core#dateTimePrecision";
    public static final String VIVO_DATETIMEVALUE = "http://vivoweb.org/ontology/core#dateTimeValue";
    public static final String VIVO_HASPUBLICATIONVENUE = "http://vivoweb.org/ontology/core#hasPublicationVenue";
    public static final String VIVO_PUBLICATIONVENUEFOR = "http://vivoweb.org/ontology/core#publicationVenueFor";
    public static final String VIVO_RANK = "http://vivoweb.org/ontology/core#rank";
    public static final String VIVO_RELATEDBY = "http://vivoweb.org/ontology/core#relatedBy";
    public static final String VIVO_RELATES = "http://vivoweb.org/ontology/core#relates";

    private PubmedRdfMapper() {
        // Static utility; never instantiated.
    }

    /**
     * Adds RDF for one esummary article record to the model.
     *
     * @param model   target model (statements are accumulated for batch writing)
     * @param ns      the VIVO default namespace, e.g. http://vivo.mydomain.edu/individual/
     * @param pmid    the PubMed identifier of the record
     * @param summary the esummary JSON node for this article
     * @return the URI of the article individual
     */
    public static String mapArticle(Model model, String ns, String pmid, JsonNode summary) {
        String articleUri = articleUri(ns, pmid);
        Resource article = model.createResource(articleUri);
        article.addProperty(RDF.type, model.getResource(BIBO_ACADEMIC_ARTICLE));
        article.addProperty(model.createProperty(BIBO_PMID), pmid);

        String title = text(summary, "title");
        if (!isEmpty(title)) {
            article.addProperty(model.createProperty(RDFS_LABEL), title.trim());
        }

        String doi = findArticleId(summary, "doi");
        if (!isEmpty(doi)) {
            article.addProperty(model.createProperty(BIBO_DOI), doi.trim());
        }

        String volume = text(summary, "volume");
        if (!isEmpty(volume)) {
            article.addProperty(model.createProperty(BIBO_VOLUME), volume);
        }

        String issue = text(summary, "issue");
        if (!isEmpty(issue)) {
            article.addProperty(model.createProperty(BIBO_ISSUE), issue);
        }

        String pages = text(summary, "pages");
        if (!isEmpty(pages)) {
            int hyphen = pages.indexOf('-');
            if (hyphen > 0) {
                article.addProperty(model.createProperty(BIBO_PAGE_START), pages.substring(0, hyphen).trim());
                String pageEnd = pages.substring(hyphen + 1).trim();
                if (!pageEnd.isEmpty()) {
                    article.addProperty(model.createProperty(BIBO_PAGE_END), pageEnd);
                }
            } else {
                article.addProperty(model.createProperty(BIBO_PAGE_START), pages.trim());
            }
        }

        mapJournal(model, ns, article, summary);
        mapAuthors(model, ns, pmid, article, summary);
        mapDate(model, ns, pmid, article, summary);

        return articleUri;
    }

    /** Deterministic URI of the article individual for a PMID. */
    public static String articleUri(String ns, String pmid) {
        return ns + "pubmed-" + pmid;
    }

    private static void mapJournal(Model model, String ns, Resource article, JsonNode summary) {
        String issn = text(summary, "issn");
        if (isEmpty(issn)) {
            issn = text(summary, "eissn");
        }
        String journalName = text(summary, "fulljournalname");
        if (isEmpty(journalName)) {
            journalName = text(summary, "source");
        }

        String journalUri;
        if (!isEmpty(issn)) {
            journalUri = ns + "journal-issn-" + sanitizeForUri(issn);
        } else if (!isEmpty(journalName)) {
            journalUri = ns + "journal-" + md5Hex(journalName.trim().toLowerCase());
        } else {
            return;
        }

        Resource journal = model.createResource(journalUri);
        journal.addProperty(RDF.type, model.getResource(BIBO_JOURNAL));
        if (!isEmpty(journalName)) {
            journal.addProperty(model.createProperty(RDFS_LABEL), journalName.trim());
        }
        if (!isEmpty(issn)) {
            journal.addProperty(model.createProperty(BIBO_ISSN), issn.trim());
        }

        article.addProperty(model.createProperty(VIVO_HASPUBLICATIONVENUE), journal);
        journal.addProperty(model.createProperty(VIVO_PUBLICATIONVENUEFOR), article);
    }

    private static void mapAuthors(Model model, String ns, String pmid, Resource article, JsonNode summary) {
        JsonNode authors = summary.get("authors");
        if (authors == null || !authors.isArray()) {
            return;
        }

        int rank = 0;
        for (JsonNode author : authors) {
            String name = text(author, "name");
            if (isEmpty(name)) {
                continue;
            }
            rank++;

            String authType = text(author, "authtype");
            String family;
            String given = null;

            int lastSpace = name.lastIndexOf(' ');
            if ("CollectiveName".equalsIgnoreCase(authType) || lastSpace <= 0) {
                family = name.trim();
            } else {
                // esummary format is "Last FM" - family name(s), space, initials
                family = name.substring(0, lastSpace).trim();
                given = name.substring(lastSpace + 1).trim();
            }

            Resource vcard = model.createResource(ns + "pubmed-" + pmid + "-author-" + rank);
            vcard.addProperty(RDF.type, model.getResource(VCARD_INDIVIDUAL));

            Resource nameRes = model.createResource(ns + "pubmed-" + pmid + "-authorname-" + rank);
            nameRes.addProperty(RDF.type, model.getResource(VCARD_NAME));
            vcard.addProperty(model.createProperty(VCARD_HAS_NAME), nameRes);
            if (!isEmpty(family)) {
                nameRes.addProperty(model.createProperty(VCARD_FAMILYNAME), family);
            }
            if (!isEmpty(given)) {
                nameRes.addProperty(model.createProperty(VCARD_GIVENNAME), given);
            }

            Resource authorship = model.createResource(ns + "pubmed-" + pmid + "-authorship-" + rank);
            authorship.addProperty(RDF.type, model.getResource(VIVO_AUTHORSHIP));
            authorship.addProperty(model.createProperty(VIVO_RELATES), article);
            authorship.addProperty(model.createProperty(VIVO_RELATES), vcard);
            authorship.addLiteral(model.createProperty(VIVO_RANK), rank);

            article.addProperty(model.createProperty(VIVO_RELATEDBY), authorship);
            vcard.addProperty(model.createProperty(VIVO_RELATEDBY), authorship);
        }
    }

    private static void mapDate(Model model, String ns, String pmid, Resource article, JsonNode summary) {
        String pubdate = text(summary, "pubdate");
        if (isEmpty(pubdate) || pubdate.trim().length() < 4) {
            return;
        }

        int year;
        try {
            year = Integer.parseInt(pubdate.trim().substring(0, 4), 10);
        } catch (NumberFormatException e) {
            return;
        }

        Resource dateRes = model.createResource(ns + "pubmed-" + pmid + "-date");
        dateRes.addProperty(RDF.type, model.getResource(VIVO_DATETIMEVALUE_CLASS));
        dateRes.addProperty(model.createProperty(VIVO_DATETIME),
                ResourceFactory.createTypedLiteral(String.format("%04d-01-01T00:00:00", year),
                        XSDDatatype.XSDdateTime));
        dateRes.addProperty(model.createProperty(VIVO_DATETIMEPRECISION),
                model.createResource(VitroVocabulary.Precision.YEAR.uri()));

        article.addProperty(model.createProperty(VIVO_DATETIMEVALUE), dateRes);
    }

    private static String findArticleId(JsonNode summary, String idType) {
        JsonNode articleIds = summary.get("articleids");
        if (articleIds == null || !articleIds.isArray()) {
            return null;
        }
        for (JsonNode idNode : articleIds) {
            if (idType.equalsIgnoreCase(text(idNode, "idtype"))) {
                return text(idNode, "value");
            }
        }
        return null;
    }

    /** Keeps letters, digits, dot and hyphen; replaces everything else with a hyphen. */
    static String sanitizeForUri(String value) {
        return value.trim().replaceAll("[^A-Za-z0-9.\\-]", "-");
    }

    static String md5Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(value.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 is required by the JVM specification", e);
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 is required by the JVM specification", e);
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.asText();
    }

    private static boolean isEmpty(String value) {
        return value == null || value.trim().isEmpty();
    }
}
