/* $This file is distributed under the terms of the license in LICENSE$ */
package org.vivoweb.webapp.dashboard;

import java.util.HashMap;
import java.util.Map;

import javax.servlet.annotation.WebServlet;

import edu.cornell.mannlib.vitro.webapp.controller.VitroRequest;
import edu.cornell.mannlib.vitro.webapp.controller.freemarker.FreemarkerHttpServlet;
import edu.cornell.mannlib.vitro.webapp.controller.freemarker.responsevalues.ResponseValues;
import edu.cornell.mannlib.vitro.webapp.controller.freemarker.responsevalues.TemplateResponseValues;

/**
 * Renders the public statistics dashboard page shell. All data is loaded
 * asynchronously from DashboardAjaxController at /dashboardAjax.
 */
@WebServlet(name = "DashboardController", urlPatterns = {"/dashboard"})
public class DashboardController extends FreemarkerHttpServlet {

    private static final String TEMPLATE_NAME = "statsDashboard.ftl";

    @Override
    protected String getTitle(String siteName, VitroRequest vreq) {
        return "Statistics Dashboard";
    }

    @Override
    protected ResponseValues processRequest(VitroRequest vreq) {
        Map<String, Object> body = new HashMap<String, Object>();
        return new TemplateResponseValues(TEMPLATE_NAME, body);
    }
}
