/**
 * Copyright (C) 2010 Orbeon, Inc.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free Software Foundation; either version
 * 2.1 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.pipeline;

import org.dom4j.Document;
import org.orbeon.oxf.pipeline.api.ExternalContext;
import org.orbeon.oxf.pipeline.api.PipelineContext;
import org.orbeon.oxf.xforms.XFormsUtils;
import org.orbeon.saxon.om.NodeInfo;
import org.orbeon.saxon.sxpath.XPathEvaluator;

import java.util.*;

public class StaticExternalContext {
    private static Map<Thread, List<StaticContext>> staticContext = Collections.synchronizedMap(new HashMap<Thread, List<StaticContext>>());

    public static class StaticContext {
        private ExternalContext externalContext;
        private PipelineContext pipelineContext;

        public StaticContext(ExternalContext externalContext, PipelineContext pipelineContext) {
            this.externalContext = externalContext;
            this.pipelineContext = pipelineContext;
        }

        public ExternalContext getExternalContext() {
            return externalContext;
        }

        public PipelineContext getPipelineContext() {
            return pipelineContext;
        }
    }

    public static void setStaticContext(StaticContext staticContext) {
        List<StaticContext> current = StaticExternalContext.staticContext.get(Thread.currentThread());
        if (current == null)
            current = new ArrayList<StaticContext>();
        current.add(staticContext);
        StaticExternalContext.staticContext.put(Thread.currentThread(), current);
    }

    public static void removeStaticContext() {
        final List<StaticContext> current = staticContext.get(Thread.currentThread());
        current.remove(current.size() - 1);
        if (current.size() == 0)
            staticContext.remove(Thread.currentThread());
    }

    public static StaticContext getStaticContext() {
        final List<StaticContext> current = staticContext.get(Thread.currentThread());
        return (current != null) ? current.get(current.size() - 1) : null;
    }

    public static String rewriteActionURL(String urlString) {
        return getStaticContext().getExternalContext().getResponse().rewriteActionURL(urlString);
    }

    public static String rewriteRenderURL(String urlString) {
        return getStaticContext().getExternalContext().getResponse().rewriteRenderURL(urlString);
    }

    public static String rewriteResourceURL(String urlString) {
        return rewriteResourceURL(urlString, false);
    }

    public static String rewriteResourceURL(String urlString, boolean forceAbsolute) {
        return getStaticContext().getExternalContext().getResponse().rewriteResourceURL(urlString, forceAbsolute);
    }

    public static String rewriteServiceURL(String urlString, boolean forceAbsolute) {
        return getStaticContext().getExternalContext().rewriteServiceURL(urlString, forceAbsolute);
    }

    public static String setTitle(String title) {
        getStaticContext().getExternalContext().getResponse().setTitle(title);
        return "";
    }

    public static XPathEvaluator newEvaluator(NodeInfo context) {
        return new XPathEvaluator(context.getConfiguration());
    }

    public static String encodeXML(org.w3c.dom.Node node) {
        return XFormsUtils.encodeXMLAsDOM(getStaticContext().getPipelineContext(), node);
    }

    public static Document decodeXML(String encodedXML) {
        return XFormsUtils.decodeXML(getStaticContext().getPipelineContext(), encodedXML);
    }
}
