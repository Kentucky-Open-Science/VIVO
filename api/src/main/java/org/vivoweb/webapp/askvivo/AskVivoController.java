/* $This file is distributed under the terms of the license in LICENSE$ */

package org.vivoweb.webapp.askvivo;

import java.util.HashMap;
import java.util.Map;

import javax.servlet.annotation.WebServlet;

import edu.cornell.mannlib.vitro.webapp.config.ConfigurationProperties;
import edu.cornell.mannlib.vitro.webapp.controller.VitroRequest;
import edu.cornell.mannlib.vitro.webapp.controller.freemarker.FreemarkerHttpServlet;
import edu.cornell.mannlib.vitro.webapp.controller.freemarker.responsevalues.ResponseValues;
import edu.cornell.mannlib.vitro.webapp.controller.freemarker.responsevalues.TemplateResponseValues;

/**
 * Renders the public "Ask VIVO" page: a chat interface for natural-language search over the VIVO
 * knowledge graph, backed by an OpenAI-compatible LLM (see AskVivoAjaxController).
 *
 * If llm.baseUrl or llm.model is missing from runtime.properties, the page shows a setup notice
 * instead of the chat interface.
 */
@WebServlet(name = "AskVivoController", urlPatterns = {"/askVivo"})
public class AskVivoController extends FreemarkerHttpServlet {

    private static final String TEMPLATE_NAME = "askVivo.ftl";

    @Override
    protected String getTitle(String siteName, VitroRequest vreq) {
        return "Ask VIVO - " + siteName;
    }

    @Override
    protected ResponseValues processRequest(VitroRequest vreq) {
        Map<String, Object> body = new HashMap<String, Object>();
        body.put("title", "Ask VIVO");
        body.put("configured", isConfigured(vreq));
        return new TemplateResponseValues(TEMPLATE_NAME, body);
    }

    private boolean isConfigured(VitroRequest vreq) {
        ConfigurationProperties props = ConfigurationProperties.getBean(vreq);
        String baseUrl = props.getProperty("llm.baseUrl");
        String model = props.getProperty("llm.model");
        return baseUrl != null && !baseUrl.trim().isEmpty()
                && model != null && !model.trim().isEmpty();
    }
}
