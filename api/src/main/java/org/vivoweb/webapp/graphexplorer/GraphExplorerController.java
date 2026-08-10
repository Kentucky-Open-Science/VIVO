/* $This file is distributed under the terms of the license in LICENSE$ */

package org.vivoweb.webapp.graphexplorer;

import java.util.HashMap;
import java.util.Map;

import javax.servlet.annotation.WebServlet;

import edu.cornell.mannlib.vitro.webapp.controller.VitroRequest;
import edu.cornell.mannlib.vitro.webapp.controller.freemarker.FreemarkerHttpServlet;
import edu.cornell.mannlib.vitro.webapp.controller.freemarker.responsevalues.ResponseValues;
import edu.cornell.mannlib.vitro.webapp.controller.freemarker.responsevalues.TemplateResponseValues;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Renders the interactive graph explorer page. This is a public page, so
 * requiredActions() is not overridden.
 */
@WebServlet(name = "GraphExplorerController", urlPatterns = {"/graphExplorer"})
public class GraphExplorerController extends FreemarkerHttpServlet {

    private static final Log log = LogFactory.getLog(GraphExplorerController.class);
    private static final String TEMPLATE_NAME = "graphExplorer.ftl";

    @Override
    protected String getTitle(String siteName, VitroRequest vreq) {
        return "Graph Explorer - " + siteName;
    }

    @Override
    protected ResponseValues processRequest(VitroRequest vreq) {
        Map<String, Object> body = new HashMap<String, Object>();
        if (log.isDebugEnabled()) {
            log.debug("Rendering graph explorer page");
        }
        return new TemplateResponseValues(TEMPLATE_NAME, body);
    }
}
