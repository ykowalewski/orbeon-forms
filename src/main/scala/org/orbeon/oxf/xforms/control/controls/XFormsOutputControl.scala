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
package org.orbeon.oxf.xforms.control.controls

import collection.JavaConverters._
import org.apache.commons.lang3.StringUtils
import org.dom4j.Element
import org.orbeon.oxf.externalcontext.ServletURLRewriter
import org.orbeon.oxf.externalcontext.URLRewriter
import org.orbeon.oxf.util.{Connection, NetUtils}
import org.orbeon.oxf.xforms.XFormsConstants._
import org.orbeon.oxf.xforms.XFormsError
import org.orbeon.oxf.xforms.XFormsUtils
import org.orbeon.oxf.xforms.analysis.controls.OutputControl
import org.orbeon.oxf.xforms.control._
import org.orbeon.oxf.xforms.model.DataModel
import org.orbeon.oxf.xforms.processor.XFormsResourceServer.proxyURI
import org.orbeon.oxf.xforms.submission.{SubmissionUtils, SubmissionHeaders}
import org.orbeon.oxf.xforms.xbl.XBLContainer
import org.xml.sax.helpers.AttributesImpl
import org.orbeon.exception.OrbeonFormatter
import scala.util.control.NonFatal

/**
 * Represents an xf:output control.
 */
class XFormsOutputControl(
  container : XBLContainer,
  parent    : XFormsControl,
  element   : Element,
  id        : String
) extends XFormsSingleNodeControl(container, parent, element, id)
  with XFormsValueControl
  with VisitableTrait
  with FileMetadata {

  override type Control <: OutputControl

  def supportedFileMetadata = Seq("mediatype", "filename") // could add "state"?

  // Optional format and mediatype
  private def format = Option(staticControl) flatMap (_.format)

  // Value attribute
  private val valueAttribute = element.attributeValue(VALUE_QNAME)
  // TODO: resolve statically
  private val urlNorewrite = XFormsUtils.resolveUrlNorewrite(element)

  override def evaluateImpl(relevant: Boolean, parentRelevant: Boolean): Unit = {
    super.evaluateImpl(relevant, parentRelevant)
    evaluateFileMetadata(relevant)
  }

  override def markDirtyImpl(): Unit ={
    super.markDirtyImpl()
    markFileMetadataDirty()
  }

  override def evaluateValue(): Unit = {
    val bc = bindingContext
    val value =
      if (valueAttribute eq null)
        // Get value from single-node binding
        Option(DataModel.getValue(bc.getSingleItem))
      else
        // Value comes from the XPath expression within the value attribute
        evaluateAsString(valueAttribute, bc.nodeset.asScala, bc.position)

    setValue(value getOrElse "")
  }

  override def evaluateExternalValue(): Unit = {
    assert(isRelevant)

    val internalValue = getValue
    assert(internalValue ne null)

    val updatedValue =
      if (getAppearances.contains(XXFORMS_DOWNLOAD_APPEARANCE_QNAME)) {
        // Download appearance
        // NOTE: Never put timestamp for downloads otherwise browsers may cache the file to download which is not
        proxyValueIfNeeded(internalValue, "", filename, fileMediatype orElse mediatype)
      } else if (mediatype exists (_.startsWith("image/"))) {
        // Image mediatype
        // Use dummy image as default value so that client always has something to load
        proxyValueIfNeeded(internalValue, DUMMY_IMAGE_URI, filename, mediatype)
      } else if (mediatype contains "text/html") {
        // HTML mediatype
        internalValue
      } else {
        // Other mediatypes
        if (valueAttribute eq null)
          // There is a single-node binding, so the format may be used
          getValueUseFormat(format) getOrElse internalValue
        else
          // There is a @value attribute, don't use format
          internalValue
      }

    setExternalValue(updatedValue)
  }

  // Keep public for unit tests
  def evaluatedHeaders: Map[String, List[String]] = {
    // TODO: pass BindingContext directly
    getContextStack.setBinding(bindingContext)
    val headersToForward = SubmissionUtils.clientHeadersToForward(containingDocument.getRequestHeaders, forwardClientHeaders = true)
    try SubmissionHeaders.evaluateHeaders(container, getContextStack, getEffectiveId, staticControl.element, headersToForward)
    catch {
      case NonFatal(t) ⇒
        XFormsError.handleNonFatalXPathError(container, t)
        Map()
    }
  }

  private def proxyValueIfNeeded(internalValue: String, defaultValue: String, filename: Option[String], mediatype: Option[String]): String =
    try {
      // If the value is a file: we make sure it is signed otherwise we return the default value
      def verifiedValueOrDefault(initial: String, value: ⇒ String, default: ⇒ String) =
        if ("file".equals(NetUtils.getProtocol(initial)) && ! XFormsUploadControl.verifyMAC(initial))
          default
        else
          value

      def doProxyURI(uri: String, lastModified: Long) =
        proxyURI(
          uri              = uri,
          filename         = filename,
          contentType      = mediatype,
          lastModified     = lastModified,
          customHeaders    = evaluatedHeaders,
          headersToForward = Connection.headersToForwardFromProperty,
          getHeader        = containingDocument.headersGetter
      )

      if (StringUtils.isNotBlank(internalValue)) {
        getBuiltinTypeName match {
          case null | "anyURI" ⇒
            val (maybeResolved, maybeProxied) =
              if (! urlNorewrite) {
                // Resolve xml:base and try to obtain a path which is an absolute path without the context

                // NOTE: We also proxy data: URLs, even though we could send them to the client directly in many
                // cases. One drawback is that they are are limited to 32 KB with IE8 (although IE8 support will
                // go away soon hopefully, and IE 8 doesn't support the image annotation component which was the
                // driver for data: URL support here), and in general make more sense for relatively short
                // values. So for now we keep the proxying for data: URLs.

                val rebasedURI      = XFormsUtils.resolveXMLBase(containingDocument, element, internalValue)
                val servletRewriter = new ServletURLRewriter(NetUtils.getExternalContext.getRequest)
                val resolvedURI     = servletRewriter.rewriteResourceURL(rebasedURI.toString, URLRewriter.REWRITE_MODE_ABSOLUTE_PATH_NO_CONTEXT)
                val lastModified    = NetUtils.getLastModifiedIfFast(resolvedURI)

                (resolvedURI, doProxyURI(resolvedURI, lastModified))
              } else {
                // Otherwise we leave the value as is
                (internalValue, internalValue)
              }

            verifiedValueOrDefault(
              maybeResolved,
              maybeProxied,
              defaultValue
            )
          case "base64Binary" ⇒
            // NOTE: -1 for lastModified will cause XFormsResourceServer to set Last-Modified and Expires properly to "now"
            doProxyURI(NetUtils.base64BinaryToAnyURI(internalValue, NetUtils.SESSION_SCOPE, logger.getLogger), -1)
          case _ ⇒
            defaultValue
        }
      } else {
        defaultValue
      }
    } catch {
      case NonFatal(t) ⇒
        // We don't want to fail if there is an issue proxying, for example if the resource is not found.
        // Ideally, this would indicate some condition on the control (not found? out of range?).
        warn(
          "exception while proxying value",
          Seq(
            "value"     → internalValue,
            "throwable" → OrbeonFormatter.format(t)
          )
        )

        defaultValue
    }

  override def getRelevantEscapedExternalValue =
    if (getAppearances.contains(XXFORMS_DOWNLOAD_APPEARANCE_QNAME) || (mediatype exists (_.startsWith("image/")))) {
      val externalValue = getExternalValue
      if (StringUtils.isNotBlank(externalValue)) {
        // External value is not blank, rewrite as absolute path. Two cases:
        // - URL is proxied:        /xforms-server/dynamic/27bf...  => [/context]/xforms-server/dynamic/27bf...
        // - URL is default value:  /ops/images/xforms/spacer.gif   => [/context][/version]/ops/images/xforms/spacer.gif
        XFormsUtils.resolveResourceURL(containingDocument, element, externalValue, URLRewriter.REWRITE_MODE_ABSOLUTE_PATH)
      } else
        // Empty value, return as is
        externalValue
    } else if (mediatype contains "text/html")
      // Rewrite the HTML value with resolved @href and @src attributes
      XFormsControl.getEscapedHTMLValue(getLocationData, getExternalValue)
    else
      // Return external value as is
      getExternalValue

  override def getNonRelevantEscapedExternalValue =
    if (mediatype exists (_.startsWith("image/")))
      // Return rewritten URL of dummy image URL
      XFormsUtils.resolveResourceURL(containingDocument, element, DUMMY_IMAGE_URI, URLRewriter.REWRITE_MODE_ABSOLUTE_PATH)
    else
      super.getNonRelevantEscapedExternalValue

  // If we have both @ref and @value, read the type
  // XForms doesn't specify this as of XForms 2.0, but we already read the other MIPs so it makes sense.
  override def valueType = super.valueType

  // It usually doesn't make sense to focus on xf:output, at least not in the sense "focus to enter data"
  override def focusableControls = Iterator.empty

  override def addAjaxExtensionAttributes(attributesImpl: AttributesImpl, isNewRepeatIteration: Boolean, other: XFormsControl) = {
    var added = super.addAjaxExtensionAttributes(attributesImpl, isNewRepeatIteration, other)
    added |= addFileMetadataAttributes(attributesImpl, isNewRepeatIteration, other.asInstanceOf[FileMetadata])
    added
  }

  override def compareExternalUseExternalValue(
    previousExternalValue : Option[String],
    previousControl       : Option[XFormsControl]
  ): Boolean =
    previousControl match {
      case Some(other: XFormsOutputControl) ⇒
        compareFileMetadata(other) &&
        super.compareExternalUseExternalValue(previousExternalValue, previousControl)
      case _ ⇒ false
    }

  override def getBackCopy: AnyRef = {
    val cloned = super.getBackCopy.asInstanceOf[XFormsOutputControl]
    updateFileMetadataCopy(cloned)
    cloned
  }
}

object XFormsOutputControl {

  def getExternalValueOrDefault(control: XFormsOutputControl, mediatypeValue: String) =
    if ((control ne null) && control.isRelevant)
      // Ask control
      control.getExternalValue
    else if ((mediatypeValue ne null) && mediatypeValue.startsWith("image/"))
      // Dummy image
      DUMMY_IMAGE_URI
    else
      // Default for other mediatypes
      null
}