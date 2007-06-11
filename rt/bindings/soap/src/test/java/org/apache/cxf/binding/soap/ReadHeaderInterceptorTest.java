/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.cxf.binding.soap;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import javax.activation.DataHandler;
import javax.mail.util.ByteArrayDataSource;
import javax.xml.stream.XMLStreamReader;

import org.w3c.dom.Element;

import org.apache.cxf.BusFactory;
import org.apache.cxf.attachment.AttachmentImpl;
import org.apache.cxf.attachment.AttachmentUtil;
import org.apache.cxf.binding.soap.interceptor.ReadHeadersInterceptor;
import org.apache.cxf.headers.Header;
import org.apache.cxf.interceptor.StaxInInterceptor;
import org.apache.cxf.message.Attachment;

public class ReadHeaderInterceptorTest extends TestBase {

    private ReadHeadersInterceptor rhi;
    private StaxInInterceptor staxIntc = new StaxInInterceptor();

    public void setUp() throws Exception {
        super.setUp();

        rhi = new ReadHeadersInterceptor(BusFactory.getDefaultBus(), "phase1");
        chain.add(rhi);
    }

    public void testBadSOAPEnvelopeNamespace() throws Exception {
        soapMessage = TestUtil.createEmptySoapMessage(Soap12.getInstance(), chain);
        InputStream in = getClass().getResourceAsStream("test-bad-env.xml");
        assertNotNull(in);
        ByteArrayDataSource bads = new ByteArrayDataSource(in, "test/xml");
        soapMessage.setContent(InputStream.class, bads.getInputStream());

        ReadHeadersInterceptor r = new ReadHeadersInterceptor(BusFactory.getDefaultBus());
        try {
            r.handleMessage(soapMessage);
            fail("Did not throw exception");
        } catch (SoapFault f) {
            assertEquals(Soap11.getInstance().getVersionMismatch(), f.getFaultCode());
        }
    }

    public void testHandleHeader() {
        try {
            prepareSoapMessage("test-soap-header.xml");
        } catch (IOException ioe) {
            fail("Failed in creating soap message");
        }

        staxIntc.handleMessage(soapMessage);
        soapMessage.getInterceptorChain().doIntercept(soapMessage);
        // check the xmlReader should be placed on the first entry of the body element
        XMLStreamReader xmlReader = soapMessage.getContent(XMLStreamReader.class);
        assertEquals("check the first entry of body", "itinerary", xmlReader.getLocalName());
        
        List<Header> eleHeaders = soapMessage.getHeaders();
        
        List<Element> headerChilds = new ArrayList<Element>();
        Iterator<Header> iter = eleHeaders.iterator();
        while (iter.hasNext()) {
            Header hdr = iter.next();

            if (hdr.getObject() instanceof Element) {
                headerChilds.add((Element) hdr.getObject());
            }
        }
//        for (int i = 0; i < eleHeaders.getChildNodes().getLength(); i++) {
//            if (eleHeaders.getChildNodes().item(i) instanceof Element) {
//                Element element = (Element)eleHeaders.getChildNodes().item(i);
//                headerChilds.add(element);
//            }
//        }

        assertEquals(2, headerChilds.size());
        for (int i = 0; i < headerChilds.size(); i++) {
            Element ele = headerChilds.get(i);
            if (ele.getLocalName().equals("reservation")) {
                Element reservation = ele;
                List<Element> reservationChilds = new ArrayList<Element>();
                for (int j = 0; j < reservation.getChildNodes().getLength(); j++) {
                    if (reservation.getChildNodes().item(j) instanceof Element) {
                        Element element = (Element)reservation.getChildNodes().item(j);
                        reservationChilds.add(element);
                    }
                }
                assertEquals(2, reservationChilds.size());
                assertEquals("reference", reservationChilds.get(0).getLocalName());
                assertEquals("uuid:093a2da1-q345-739r-ba5d-pqff98fe8j7d", ((Element)reservationChilds.get(0))
                    .getTextContent());
                assertEquals("dateAndTime", ((Element)reservationChilds.get(1)).getLocalName());
                assertEquals("2001-11-29T13:20:00.000-05:00", ((Element)reservationChilds.get(1))
                    .getTextContent());

            }
            if (ele.getLocalName().equals("passenger")) {
                Element passenger = ele;
                assertNotNull(passenger);
                Element child = null;
                for (int j = 0; j < passenger.getChildNodes().getLength(); j++) {
                    if (passenger.getChildNodes().item(j) instanceof Element) {
                        child = (Element)passenger.getChildNodes().item(j);
                    }
                }
                assertNotNull("passenger should has child element", child);                
                assertEquals("name", child.getLocalName());
                assertEquals("Bob", child.getTextContent());
            }

        }
    }

    private void prepareSoapMessage(String message) throws IOException {

        soapMessage = TestUtil.createEmptySoapMessage(Soap12.getInstance(), chain);
        ByteArrayDataSource bads = new ByteArrayDataSource(this.getClass().getResourceAsStream(message),
                                                           "Application/xop+xml");
        String cid = AttachmentUtil.createContentID("http://cxf.apache.org");
        soapMessage.setContent(Attachment.class, new AttachmentImpl(cid, new DataHandler(bads)));
        soapMessage.setContent(InputStream.class, bads.getInputStream());

    }
}
