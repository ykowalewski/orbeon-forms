/**
 *  Copyright (C) 2005 Orbeon, Inc.
 *
 *  This program is free software; you can redistribute it and/or modify it under the terms of the
 *  GNU Lesser General Public License as published by the Free Software Foundation; either version
 *  2.1 of the License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 *  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *  See the GNU Lesser General Public License for more details.
 *
 *  The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.processor.xinclude;

import orbeon.apache.xml.utils.NamespaceSupport2;
import org.apache.log4j.Logger;
import org.orbeon.oxf.common.OXFException;
import org.orbeon.oxf.common.ValidationException;
import org.orbeon.oxf.pipeline.api.PipelineContext;
import org.orbeon.oxf.processor.*;
import org.orbeon.oxf.processor.transformer.TransformerURIResolver;
import org.orbeon.oxf.xml.ContentHandlerHelper;
import org.orbeon.oxf.xml.ForwardingContentHandler;
import org.orbeon.oxf.xml.XMLConstants;
import org.orbeon.oxf.xml.dom4j.LocationData;
import org.xml.sax.*;
import org.xml.sax.helpers.AttributesImpl;

import javax.xml.transform.sax.SAXSource;
import java.util.Enumeration;

/**
 * XInclude processor.
 *
 * This processor reads a document on its "config" input that may contain XInclude directives. It
 * produces on its output a resulting document with the XInclude directives processed.
 *
 * For now, this processor only supports <xi:include href="..." parse="xml"/> with no xpointer,
 * encoding, accept, or accept-language attribute. <xi:fallback> is not supported.
 *
 * TODO: Merge caching with URL generator, possibly XSLT transformer.
 */
public class XIncludeProcessor extends ProcessorImpl {

    private static Logger logger = Logger.getLogger(XIncludeProcessor.class);

    public XIncludeProcessor() {
        addInputInfo(new ProcessorInputOutputInfo(INPUT_CONFIG));
        addOutputInfo(new ProcessorInputOutputInfo(OUTPUT_DATA));
    }

    public ProcessorOutput createOutput(final String name) {
        final ProcessorOutput output = new URIProcessorOutputImpl(getClass(), name, INPUT_CONFIG) {
            public void readImpl(final PipelineContext pipelineContext, final ContentHandler contentHandler) {
//                final ContentHandler debugContentHandler = new SAXDebuggerProcessor.DebugContentHandler(contentHandler);
                final TransformerURIResolver uriResolver = new TransformerURIResolver(XIncludeProcessor.this, pipelineContext, INPUT_CONFIG, false);

                // Try to cache URI references
                final boolean[] wasRead = { false };
                readCacheInputAsObject(pipelineContext, getInputByName(INPUT_CONFIG), new CacheableInputReader() {
                    public Object read(PipelineContext context, ProcessorInput input) {
                        final URIReferences uriReferences = new URIReferences();
                        readInputAsSAX(pipelineContext, INPUT_CONFIG, new XIncludeContentHandler(pipelineContext, contentHandler, uriReferences, uriResolver));
                        wasRead[0] = true;
                        return uriReferences;
                    }
                });

                // Read if not already read
                if (!wasRead[0]) {
                    readInputAsSAX(pipelineContext, INPUT_CONFIG, new XIncludeContentHandler(pipelineContext, contentHandler, null, uriResolver));
                }
            }
        };
        addOutput(name, output);
        return output;
    }

    private class XIncludeContentHandler extends ForwardingContentHandler {

        private PipelineContext pipelineContext;
        private URIReferences uriReferences;
        private boolean topLevelContentHandler;
        private String xmlBase;
        private NamespaceSupport2 paremtNamespaceSupport;

        private Locator locator;
        private TransformerURIResolver uriResolver;
        private NamespaceSupport2 namespaceSupport = new NamespaceSupport2();

        private int level;
        private boolean inInclude;
        private int includeLevel;

        public XIncludeContentHandler(PipelineContext pipelineContext, ContentHandler contentHandler, URIReferences uriReferences, TransformerURIResolver uriResolver) {
            this(pipelineContext, contentHandler, uriReferences, uriResolver, true, null, null);
        }

        public XIncludeContentHandler(PipelineContext pipelineContext, ContentHandler contentHandler, URIReferences uriReferences, TransformerURIResolver uriResolver, String xmlBase, NamespaceSupport2 paremtNamespaceSupport) {
            this(pipelineContext, contentHandler, uriReferences, uriResolver, false, xmlBase, paremtNamespaceSupport);
        }

        private XIncludeContentHandler(PipelineContext pipelineContext, ContentHandler contentHandler, URIReferences uriReferences, TransformerURIResolver uriResolver, boolean topLevelContentHandler, String xmlBase, NamespaceSupport2 paremtNamespaceSupport) {
            super(contentHandler);
            this.pipelineContext = pipelineContext;
            this.uriReferences = uriReferences;
            this.uriResolver = uriResolver;
            this.topLevelContentHandler = topLevelContentHandler;
            this.xmlBase = xmlBase;
            this.paremtNamespaceSupport = paremtNamespaceSupport;
        }

        public void startDocument() throws SAXException {
            if (topLevelContentHandler)
                super.startDocument();
        }

        public void endDocument() throws SAXException {
            if (topLevelContentHandler)
                super.endDocument();
        }

        public void startElement(String uri, String localname, String qName, Attributes attributes) throws SAXException {

            namespaceSupport.pushContext();

            if (!topLevelContentHandler && level == 0) {
                // Clean-up namespace mappings
                sendStartPrefixMappings();
                // Add or replace xml:base attribute
                final AttributesImpl newAttributes = new AttributesImpl();
                for (int i = 0; i < attributes.getLength(); i++) {
                    final String attributeURI = attributes.getURI(i);
                    final String attributeValue = attributes.getValue(i);
                    final String attributeType = attributes.getType(i);
                    final String attributeQName = attributes.getQName(i);
                    final String attributeLocalname = attributes.getLocalName(i);

                    if (!(XMLConstants.XML_URI.equals(attributeURI) && "base".equals(attributeLocalname)))
                        newAttributes.addAttribute(attributeURI, attributeLocalname, attributeQName, attributeType, attributeValue);
                }
                newAttributes.addAttribute(XMLConstants.XML_URI, "base", "xml:base", ContentHandlerHelper.CDATA, xmlBase);
                attributes = newAttributes;
            }

            if (XMLConstants.XINCLUDE_URI.equals(uri) || XMLConstants.OLD_XINCLUDE_URI.equals(uri)) {
                // Found XInclude namespace

                // Warn upon obsolete namespace URI
                if (XMLConstants.OLD_XINCLUDE_URI.equals(uri))
                    logger.warn("Using incorrect XInclude namespace URI: '" + uri + "'; should use '" + XMLConstants.XINCLUDE_URI + "' at " + new LocationData(locator).toString());

                if ("include".equals(localname)) {
                    // Start inclusion

                    inInclude = true;
                    includeLevel = level;

                    final String href = attributes.getValue("href");
                    final String parse = attributes.getValue("parse");

                    if (parse != null && !parse.equals("xml"))
                        throw new ValidationException("Invalid 'parse' attribute value: " + parse, new LocationData(locator));

                    try {
                        // Get SAXSource
                        final String base = locator.getSystemId();
                        final SAXSource source = (SAXSource) uriResolver.resolve(href, base);
                        final XMLReader xmlReader = source.getXMLReader();
                        xmlReader.setContentHandler(new XIncludeContentHandler(pipelineContext, getContentHandler(), uriReferences, uriResolver, source.getSystemId(), namespaceSupport));

                        // Keep URI reference
                        if (uriReferences != null)
                            uriReferences.addReference(base, href);

                        // Read document
                        xmlReader.parse(new InputSource()); // Yeah, the SAX API doesn't make much sense

                    } catch (Exception e) {
                        // Resource error, must go to fallback if possible
                        throw new OXFException(e);
                    }

                } else if ("fallback".equals(localname)) {
                    // TODO
                } else {
                    throw new ValidationException("Invalid XInclude element: " + localname, new LocationData(locator));
                }

            } else {
                super.startElement(uri, localname, qName, attributes);
            }

            level++;
        }

        private void sendStartPrefixMappings() throws SAXException {
            for (Enumeration e = paremtNamespaceSupport.getPrefixes(); e.hasMoreElements();) {
                final String namespacePrefix = (String) e.nextElement();
                if (!namespacePrefix.startsWith("xml"))
                    super.startPrefixMapping(namespacePrefix, "");
            }

            final String defaultNS = paremtNamespaceSupport.getURI("");
            if (defaultNS != null && defaultNS.length() > 0)
                super.startPrefixMapping("", "");
        }

        private void sendEndPrefixMappings() throws SAXException {
            for (Enumeration e = paremtNamespaceSupport.getPrefixes(); e.hasMoreElements();) {
                final String namespacePrefix = (String) e.nextElement();
                if (!namespacePrefix.startsWith("xml"))
                    super.endPrefixMapping(namespacePrefix);
            }

            final String defaultNS = paremtNamespaceSupport.getURI("");
            if (defaultNS != null && defaultNS.length() > 0)
                super.endPrefixMapping("");
        }

        public void endElement(String uri, String localname, String qName) throws SAXException {

            level--;

            namespaceSupport.popContext();

            if (XMLConstants.XINCLUDE_URI.equals(uri) || XMLConstants.OLD_XINCLUDE_URI.equals(uri)) {

            } else {
                super.endElement(uri, localname, qName);
            }

            if (!topLevelContentHandler && level == 0) {
                sendEndPrefixMappings();
            }
        }

        public void startPrefixMapping(String prefix, String uri) throws SAXException {
            namespaceSupport.declarePrefix(prefix, uri);
            super.startPrefixMapping(prefix, uri);
        }

        public void setDocumentLocator(Locator locator) {
            this.locator = locator;
            super.setDocumentLocator(locator);
        }
    }
}
