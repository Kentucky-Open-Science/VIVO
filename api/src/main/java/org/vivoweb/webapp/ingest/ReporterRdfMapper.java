/* $This file is distributed under the terms of the license in LICENSE$ */

package org.vivoweb.webapp.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import edu.cornell.mannlib.vitro.webapp.dao.VitroVocabulary;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.vocabulary.RDF;

/**
 * Maps an aggregated NIH RePORTER v2 project record (JSON) to VIVO-ISF RDF.
 *
 * URIs are deterministic, derived from the core project number, so repeated
 * harvests are idempotent (merge semantics). The PI role pattern mirrors the
 * one VIVO's grant visualizations query:
 * grant vivo:relates role; role a vivo:PrincipalInvestigatorRole;
 * role obo:RO_0000052 person; person obo:RO_0000053 role.
 */
public final class ReporterRdfMapper {

    // Class URIs
    public static final String VIVO_GRANT = "http://vivoweb.org/ontology/core#Grant";
    public static final String VIVO_FUNDING_ORGANIZATION = "http://vivoweb.org/ontology/core#FundingOrganization";
    public static final String VIVO_PI_ROLE = "http://vivoweb.org/ontology/core#PrincipalInvestigatorRole";
    public static final String VIVO_DATETIME_INTERVAL_CLASS = "http://vivoweb.org/ontology/core#DateTimeInterval";
    public static final String FOAF_PERSON = "http://xmlns.com/foaf/0.1/Person";

    // Property URIs
    public static final String RDFS_LABEL = "http://www.w3.org/2000/01/rdf-schema#label";
    public static final String VIVO_SPONSOR_AWARD_ID = "http://vivoweb.org/ontology/core#sponsorAwardId";
    public static final String VIVO_TOTAL_AWARD_AMOUNT = "http://vivoweb.org/ontology/core#totalAwardAmount";
    public static final String VIVO_ASSIGNED_BY = "http://vivoweb.org/ontology/core#assignedBy";
    public static final String VIVO_ASSIGNS = "http://vivoweb.org/ontology/core#assigns";
    public static final String VIVO_RELATES = "http://vivoweb.org/ontology/core#relates";
    public static final String VIVO_RELATEDBY = "http://vivoweb.org/ontology/core#relatedBy";
    public static final String VIVO_DATETIME_INTERVAL = "http://vivoweb.org/ontology/core#dateTimeInterval";
    public static final String VIVO_INTERVAL_START = "http://vivoweb.org/ontology/core#start";
    public static final String VIVO_INTERVAL_END = "http://vivoweb.org/ontology/core#end";
    public static final String VIVO_DATETIME = "http://vivoweb.org/ontology/core#dateTime";
    public static final String VIVO_DATETIMEPRECISION = "http://vivoweb.org/ontology/core#dateTimePrecision";
    public static final String VIVO_DATETIMEVALUE_CLASS = "http://vivoweb.org/ontology/core#DateTimeValue";
    public static final String OBO_INHERES_IN = "http://purl.obolibrary.org/obo/RO_0000052";
    public static final String OBO_BEARER_OF = "http://purl.obolibrary.org/obo/RO_0000053";

    private ReporterRdfMapper() {
        // Static utility; never instantiated.
    }

    /**
     * Adds RDF for one aggregated RePORTER project to the model.
     *
     * @param model       target model (statements are accumulated for batch writing)
     * @param ns          the VIVO default namespace
     * @param coreNumber  the core project number (e.g. R01DA012345)
     * @param record      the RePORTER project JSON record (latest fiscal year)
     * @param totalAmount award amount summed across fiscal years, or 0 if unknown
     * @return the URI of the grant individual
     */
    public static String mapGrant(Model model, String ns, String coreNumber, JsonNode record, long totalAmount) {
        String grantUri = grantUri(ns, coreNumber);
        Resource grant = model.createResource(grantUri);
        grant.addProperty(RDF.type, model.getResource(VIVO_GRANT));
        grant.addProperty(model.createProperty(VIVO_SPONSOR_AWARD_ID), coreNumber);

        String title = text(record, "project_title");
        if (!isEmpty(title)) {
            grant.addProperty(model.createProperty(RDFS_LABEL), title.trim());
        }

        if (totalAmount > 0) {
            grant.addProperty(model.createProperty(VIVO_TOTAL_AWARD_AMOUNT), String.valueOf(totalAmount));
        }

        mapFunder(model, ns, grant, record);
        mapInterval(model, ns, coreNumber, grant, record);
        mapInvestigators(model, ns, coreNumber, grant, record);

        return grantUri;
    }

    /** Deterministic URI of the grant individual for a core project number. */
    public static String grantUri(String ns, String coreNumber) {
        return ns + "grant-" + PubmedRdfMapper.sanitizeForUri(coreNumber);
    }

    private static void mapFunder(Model model, String ns, Resource grant, JsonNode record) {
        JsonNode agency = record.get("agency_ic_admin");
        if (agency == null || agency.isNull()) {
            return;
        }

        String code = text(agency, "code");
        if (isEmpty(code)) {
            code = text(agency, "abbreviation");
        }
        if (isEmpty(code)) {
            return;
        }

        Resource funder = model.createResource(ns + "funder-" + PubmedRdfMapper.sanitizeForUri(code));
        funder.addProperty(RDF.type, model.getResource(VIVO_FUNDING_ORGANIZATION));

        String name = text(agency, "name");
        if (!isEmpty(name)) {
            funder.addProperty(model.createProperty(RDFS_LABEL), name.trim());
        } else {
            funder.addProperty(model.createProperty(RDFS_LABEL), code.trim());
        }

        grant.addProperty(model.createProperty(VIVO_ASSIGNED_BY), funder);
        funder.addProperty(model.createProperty(VIVO_ASSIGNS), grant);
    }

    private static void mapInterval(Model model, String ns, String coreNumber, Resource grant, JsonNode record) {
        String start = isoDate(text(record, "project_start_date"));
        String end = isoDate(text(record, "project_end_date"));
        if (start == null && end == null) {
            return;
        }

        String base = ns + "grant-" + PubmedRdfMapper.sanitizeForUri(coreNumber);
        Resource interval = model.createResource(base + "-interval");
        interval.addProperty(RDF.type, model.getResource(VIVO_DATETIME_INTERVAL_CLASS));
        grant.addProperty(model.createProperty(VIVO_DATETIME_INTERVAL), interval);

        if (start != null) {
            Resource startVal = model.createResource(base + "-start");
            startVal.addProperty(RDF.type, model.getResource(VIVO_DATETIMEVALUE_CLASS));
            startVal.addProperty(model.createProperty(VIVO_DATETIME),
                    ResourceFactory.createTypedLiteral(start, XSDDatatype.XSDdateTime));
            startVal.addProperty(model.createProperty(VIVO_DATETIMEPRECISION),
                    model.createResource(VitroVocabulary.Precision.DAY.uri()));
            interval.addProperty(model.createProperty(VIVO_INTERVAL_START), startVal);
        }

        if (end != null) {
            Resource endVal = model.createResource(base + "-end");
            endVal.addProperty(RDF.type, model.getResource(VIVO_DATETIMEVALUE_CLASS));
            endVal.addProperty(model.createProperty(VIVO_DATETIME),
                    ResourceFactory.createTypedLiteral(end, XSDDatatype.XSDdateTime));
            endVal.addProperty(model.createProperty(VIVO_DATETIMEPRECISION),
                    model.createResource(VitroVocabulary.Precision.DAY.uri()));
            interval.addProperty(model.createProperty(VIVO_INTERVAL_END), endVal);
        }
    }

    private static void mapInvestigators(Model model, String ns, String coreNumber, Resource grant, JsonNode record) {
        JsonNode pis = record.get("principal_investigators");
        if (pis == null || !pis.isArray()) {
            return;
        }

        for (JsonNode pi : pis) {
            String profileId = text(pi, "profile_id");
            if (isEmpty(profileId) || "0".equals(profileId)) {
                continue;
            }

            String last = text(pi, "last_name");
            String first = text(pi, "first_name");
            String label;
            if (!isEmpty(last) && !isEmpty(first)) {
                label = last.trim() + ", " + first.trim();
            } else {
                String full = text(pi, "full_name");
                label = isEmpty(full) ? null : full.trim();
            }

            Resource person = model.createResource(ns + "reporter-pi-" + PubmedRdfMapper.sanitizeForUri(profileId));
            person.addProperty(RDF.type, model.getResource(FOAF_PERSON));
            if (!isEmpty(label)) {
                person.addProperty(model.createProperty(RDFS_LABEL), label);
            }

            Resource role = model.createResource(ns + "grant-" + PubmedRdfMapper.sanitizeForUri(coreNumber)
                    + "-pi-" + PubmedRdfMapper.sanitizeForUri(profileId));
            role.addProperty(RDF.type, model.getResource(VIVO_PI_ROLE));
            role.addProperty(model.createProperty(OBO_INHERES_IN), person);
            person.addProperty(model.createProperty(OBO_BEARER_OF), role);
            role.addProperty(model.createProperty(VIVO_RELATEDBY), grant);
            grant.addProperty(model.createProperty(VIVO_RELATES), role);
        }
    }

    /** Normalizes a RePORTER date like "2020-04-01T12:04:00Z" or "2020-04-01" to an xsd:dateTime string. */
    private static String isoDate(String value) {
        if (value == null || value.trim().length() < 10) {
            return null;
        }
        String day = value.trim().substring(0, 10);
        if (!day.matches("\\d{4}-\\d{2}-\\d{2}")) {
            return null;
        }
        return day + "T00:00:00";
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
