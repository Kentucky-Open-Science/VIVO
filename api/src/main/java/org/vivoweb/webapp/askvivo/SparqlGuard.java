/* $This file is distributed under the terms of the license in LICENSE$ */

package org.vivoweb.webapp.askvivo;

import java.util.regex.Pattern;

import org.apache.jena.query.Query;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.Syntax;

/**
 * Validates LLM-generated SPARQL before it is executed against the VIVO triple store.
 *
 * Only SELECT queries are allowed. SERVICE, LOAD, INSERT and DELETE are rejected outright. A LIMIT
 * is forced onto every query: 50 if none is present, clamped to 200 if a larger one is requested.
 */
public final class SparqlGuard {

    private static final Pattern FORBIDDEN = Pattern.compile("(?i)\\b(SERVICE|LOAD|INSERT|DELETE)\\b");
    private static final long DEFAULT_LIMIT = 50;
    private static final long MAX_LIMIT = 200;

    private SparqlGuard() {
    }

    /** Thrown when a candidate query is rejected. The message is safe to feed back to the LLM. */
    public static class ValidationException extends Exception {
        public ValidationException(String message) {
            super(message);
        }

        public ValidationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * Validate a raw LLM response as a SPARQL SELECT query.
     *
     * @return the serialized, validated query with its LIMIT applied.
     */
    public static String validate(String rawQuery) throws ValidationException {
        if (rawQuery == null || rawQuery.trim().isEmpty()) {
            throw new ValidationException("The generated query was empty.");
        }
        String cleaned = stripFences(rawQuery);
        if (cleaned.isEmpty()) {
            throw new ValidationException("The generated query was empty.");
        }
        if (FORBIDDEN.matcher(cleaned).find()) {
            throw new ValidationException(
                    "The query contains a forbidden keyword (SERVICE, LOAD, INSERT or DELETE).");
        }

        Query query;
        try {
            query = QueryFactory.create(cleaned, Syntax.syntaxARQ);
        } catch (RuntimeException e) {
            throw new ValidationException("The query could not be parsed: " + e.getMessage(), e);
        }
        if (!query.isSelectType()) {
            throw new ValidationException("Only SELECT queries are allowed.");
        }

        if (!query.hasLimit()) {
            query.setLimit(DEFAULT_LIMIT);
        } else if (query.getLimit() > MAX_LIMIT) {
            query.setLimit(MAX_LIMIT);
        }
        return query.serialize();
    }

    /** Remove markdown code fences (```sparql ... ```) that LLMs sometimes wrap around queries. */
    private static String stripFences(String raw) {
        String text = raw.trim();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            text = (firstNewline >= 0) ? text.substring(firstNewline + 1) : "";
        }
        text = text.trim();
        if (text.endsWith("```")) {
            text = text.substring(0, text.length() - 3);
        }
        return text.trim();
    }
}
