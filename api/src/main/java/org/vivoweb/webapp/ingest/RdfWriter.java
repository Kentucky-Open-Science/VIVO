/* $This file is distributed under the terms of the license in LICENSE$ */

package org.vivoweb.webapp.ingest;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import edu.cornell.mannlib.vitro.webapp.modelaccess.ModelNames;
import edu.cornell.mannlib.vitro.webapp.rdfservice.ChangeSet;
import edu.cornell.mannlib.vitro.webapp.rdfservice.RDFService;
import edu.cornell.mannlib.vitro.webapp.rdfservice.RDFServiceException;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.ResIterator;
import org.apache.jena.rdf.model.Resource;

/**
 * Writes batches of new triples into the VIVO ABox via the RDFService
 * ChangeSet mechanism, so registered listeners (search indexer, inference
 * engine) are notified of the additions. Mirrors the idiom used by
 * CreateAndLinkResourceController.writeChanges().
 */
public class RdfWriter {

    private final RDFService rdfService;
    private final String editorUri;

    public RdfWriter(RDFService rdfService, String editorUri) {
        this.rdfService = rdfService;
        this.editorUri = editorUri;
    }

    /**
     * Adds all statements in the model to the ABox assertions graph.
     *
     * @param addModel model of triples to add; no-op when null or empty
     */
    public void write(Model addModel) throws RDFServiceException {
        if (addModel == null || addModel.isEmpty()) {
            return;
        }

        InputStream addStream = makeN3InputStream(addModel);
        try {
            ChangeSet changeSet = rdfService.manufactureChangeSet();
            changeSet.addAddition(addStream, RDFService.ModelSerializationFormat.N3,
                    ModelNames.ABOX_ASSERTIONS, editorUri);
            rdfService.changeSetUpdate(changeSet);
        } finally {
            try {
                addStream.close();
            } catch (IOException e) {
                // ByteArrayInputStream close never fails; ignore.
            }
        }
    }

    /**
     * Replaces the store's statements about every subject in the model with
     * the model's statements: existing triples for those subjects are
     * retracted in the same ChangeSet that adds the new ones. Used by
     * re-process harvests so changed upstream values update instead of
     * accumulating duplicate literals.
     */
    public void replace(Model newModel) throws RDFServiceException {
        if (newModel == null || newModel.isEmpty()) {
            return;
        }

        StringBuilder values = new StringBuilder();
        ResIterator subjects = newModel.listSubjects();
        while (subjects.hasNext()) {
            Resource subject = subjects.next();
            if (subject.isURIResource()) {
                values.append('<').append(subject.getURI()).append("> ");
            }
        }

        Model existing = ModelFactory.createDefaultModel();
        if (values.length() > 0) {
            String constructQuery = "CONSTRUCT { ?s ?p ?o } WHERE { VALUES ?s { "
                    + values.toString().trim() + " } ?s ?p ?o }";
            try {
                InputStream in = rdfService.sparqlConstructQuery(constructQuery,
                        RDFService.ModelSerializationFormat.N3);
                try {
                    existing.read(in, null, "N3");
                } finally {
                    in.close();
                }
            } catch (IOException e) {
                throw new RDFServiceException("Could not read existing statements for replace", e);
            }
        }

        Model removeModel = existing.difference(newModel);
        Model addModel = newModel.difference(existing);
        if (removeModel.isEmpty() && addModel.isEmpty()) {
            return;
        }

        InputStream addStream = makeN3InputStream(addModel);
        InputStream removeStream = makeN3InputStream(removeModel);
        try {
            ChangeSet changeSet = rdfService.manufactureChangeSet();
            if (!removeModel.isEmpty()) {
                changeSet.addRemoval(removeStream, RDFService.ModelSerializationFormat.N3,
                        ModelNames.ABOX_ASSERTIONS, editorUri);
            }
            if (!addModel.isEmpty()) {
                changeSet.addAddition(addStream, RDFService.ModelSerializationFormat.N3,
                        ModelNames.ABOX_ASSERTIONS, editorUri);
            }
            rdfService.changeSetUpdate(changeSet);
        } finally {
            try {
                addStream.close();
                removeStream.close();
            } catch (IOException e) {
                // ByteArrayInputStream close never fails; ignore.
            }
        }
    }

    /** Runs a SPARQL ASK query against the store. */
    public boolean ask(String query) throws RDFServiceException {
        return rdfService.sparqlAskQuery(query);
    }

    private InputStream makeN3InputStream(Model m) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        m.write(out, "N3");
        return new ByteArrayInputStream(out.toByteArray());
    }
}
