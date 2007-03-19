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

package org.apache.cxf.binding.soap.saaj;


import java.io.IOException;
import java.io.OutputStream;
import java.util.ResourceBundle;

import javax.xml.soap.MessageFactory;
import javax.xml.soap.SOAPConstants;
import javax.xml.soap.SOAPException;
import javax.xml.soap.SOAPMessage;
import javax.xml.soap.SOAPPart;
import javax.xml.stream.XMLStreamWriter;

import org.apache.cxf.binding.soap.SoapFault;
import org.apache.cxf.binding.soap.SoapMessage;
import org.apache.cxf.binding.soap.SoapVersion;
import org.apache.cxf.binding.soap.interceptor.AbstractSoapInterceptor;
import org.apache.cxf.common.i18n.BundleUtils;
import org.apache.cxf.common.i18n.Message;
import org.apache.cxf.interceptor.Fault;
import org.apache.cxf.phase.Phase;
import org.apache.cxf.staxutils.W3CDOMStreamWriter;

/**
 * Sets up the outgoing chain to build a SAAJ tree instead of writing
 * directly to the output stream. First it will replace the XMLStreamWriter
 * with one which writes to a SOAPMessage. Then it will add an interceptor
 * at the end of the chain in the SEND phase which writes the resulting
 * SOAPMessage.
 */
public class SAAJOutInterceptor extends AbstractSoapInterceptor {
    private static final ResourceBundle BUNDLE = BundleUtils.getBundle(SAAJOutInterceptor.class);

    public SAAJOutInterceptor() {
        setPhase(Phase.PRE_PROTOCOL);
    }
    
    public void handleMessage(SoapMessage cxfMessage) throws Fault {
        SoapVersion version = cxfMessage.getVersion();
        try {
            MessageFactory factory = null;
            if (version.getVersion() == 1.1) {
                factory = MessageFactory.newInstance();
            } else {
                factory = MessageFactory.newInstance(SOAPConstants.SOAP_1_2_PROTOCOL);
            }
            SOAPMessage message = factory.createMessage();

            SOAPPart soapPart = message.getSOAPPart();
            W3CDOMStreamWriter writer = new W3CDOMStreamWriter(soapPart);
            // Replace stax writer with DomStreamWriter
            cxfMessage.setContent(XMLStreamWriter.class, writer);
            cxfMessage.setContent(SOAPMessage.class, message);
        } catch (SOAPException e) {
            throw new SoapFault(new Message("SOAPEXCEPTION", BUNDLE), e, version.getSender());
        }
        
        // Add a final interceptor to write the message
        cxfMessage.getInterceptorChain().add(new AbstractSoapInterceptor() {

            @Override
            public String getPhase() {
                return Phase.SEND;
            }

            public void handleMessage(SoapMessage message) throws Fault {
                SOAPMessage soapMessage = message.getContent(SOAPMessage.class);
                
                if (soapMessage != null) {
                    OutputStream os = message.getContent(OutputStream.class);
                    try {
                        soapMessage.writeTo(os);
                        os.flush();
                    } catch (IOException e) {
                        throw new SoapFault(new Message("SOAPEXCEPTION", BUNDLE), e, 
                                            message.getVersion().getSender());
                    } catch (SOAPException e) {
                        throw new SoapFault(new Message("SOAPEXCEPTION", BUNDLE), e, 
                                            message.getVersion().getSender());
                    }
                }
            }
            
        });
       
    }

}
