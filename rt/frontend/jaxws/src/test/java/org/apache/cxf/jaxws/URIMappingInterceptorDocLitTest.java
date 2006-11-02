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

package org.apache.cxf.jaxws;

import java.net.URL;
import java.util.List;

import javax.xml.namespace.QName;

import org.apache.cxf.binding.BindingFactoryManager;
import org.apache.cxf.binding.soap.SoapBindingFactory;
import org.apache.cxf.calculator.CalculatorImpl;
import org.apache.cxf.endpoint.Endpoint;
import org.apache.cxf.endpoint.EndpointImpl;
import org.apache.cxf.interceptor.URIMappingInterceptor;
import org.apache.cxf.jaxws.support.JaxWsServiceFactoryBean;
import org.apache.cxf.message.Exchange;
import org.apache.cxf.message.ExchangeImpl;
import org.apache.cxf.message.Message;
import org.apache.cxf.message.MessageImpl;
import org.apache.cxf.service.Service;
import org.apache.cxf.service.invoker.BeanInvoker;
import org.apache.cxf.service.model.BindingOperationInfo;
import org.apache.cxf.service.model.EndpointInfo;
import org.apache.cxf.test.AbstractCXFTest;

public class URIMappingInterceptorDocLitTest extends AbstractCXFTest {
    
    Message message;
    String ns = "http://apache.org/cxf/calculator";
    
    public void setUp() throws Exception {
        super.setUp();
        BindingFactoryManager bfm = getBus().getExtension(BindingFactoryManager.class);
        bfm.registerBindingFactory("http://schemas.xmlsoap.org/wsdl/soap/", 
                                   new SoapBindingFactory());
        
        message = new MessageImpl();
        message.put(Message.HTTP_REQUEST_METHOD, "GET");
        message.put(Message.BASE_PATH, "/CalculatorService/SoapPort");
        
        Exchange exchange = new ExchangeImpl();
        message.setExchange(exchange);        


        JaxWsServiceFactoryBean bean = new JaxWsServiceFactoryBean();
        URL resource = getClass().getResource("/wsdl/calculator.wsdl");
        assertNotNull(resource);
        bean.setWsdlURL(resource);
        bean.setBus(getBus());
        bean.setServiceClass(CalculatorImpl.class);
        CalculatorImpl calculator = new CalculatorImpl();
        BeanInvoker invoker = new BeanInvoker(calculator);
        bean.setInvoker(invoker);

        Service service = bean.create();
        
        EndpointInfo endpointInfo = service.getServiceInfo().getEndpoint(new QName(ns, "CalculatorPort"));
        Endpoint endpoint = new EndpointImpl(getBus(), service, endpointInfo);
        exchange.put(Service.class, service);
        exchange.put(Endpoint.class, endpoint);        
    }
    
    public void testGetAddFromPath() throws Exception {
        message.put(Message.PATH_INFO, "/CalculatorService/SoapPort/add/arg0/1/arg1/2");
        
        URIMappingInterceptor interceptor = new URIMappingInterceptor();
        interceptor.handleMessage(message);
        
        assertNull(message.getContent(Exception.class));
        
        Object parameters = message.getContent(List.class);
        assertNotNull(parameters);
        assertEquals(2, ((List)parameters).size());
         
        Integer value = (Integer) ((List)parameters).get(0);
        assertEquals(1, value.intValue());
        
        value = (Integer) ((List)parameters).get(1);        
        assertEquals(2, value.intValue());
        
        BindingOperationInfo boi = message.getExchange().get(BindingOperationInfo.class);
        assertNotNull(boi);
        assertEquals(new QName(ns, "add"), boi.getName());
    }
    
    public void testGetAddFromQuery() throws Exception {
        message.put(Message.PATH_INFO, "/CalculatorService/SoapPort/add");
        message.put(Message.QUERY_STRING, "arg0=1&arg1=2");
        
        URIMappingInterceptor interceptor = new URIMappingInterceptor();
        interceptor.handleMessage(message);
        
        assertNull(message.getContent(Exception.class));
        
        Object parameters = message.getContent(List.class);
        assertNotNull(parameters);
        assertEquals(2, ((List)parameters).size());
                 
        Integer value = (Integer) ((List)parameters).get(0);
        assertEquals(1, value.intValue());
        value = (Integer) ((List)parameters).get(1);
        assertEquals(2, value.intValue());
    }
    
    public void testGetAddFromQueryOrdered() throws Exception {
        message.put("HTTP_GET_CHECK_PARAM_NAME", Boolean.TRUE);
        message.put(Message.PATH_INFO, "/CalculatorService/SoapPort/add");
        message.put(Message.QUERY_STRING, "arg1=0&arg0=1");
        
        URIMappingInterceptor interceptor = new URIMappingInterceptor();
        interceptor.handleMessage(message);
        
        assertNull(message.getContent(Exception.class));
        
        Object parameters = message.getContent(List.class);
        assertNotNull(parameters);
        assertEquals(2, ((List)parameters).size());
                 
        Integer value = (Integer) ((List)parameters).get(0);       
        assertEquals(1, value.intValue());
        value = (Integer) ((List)parameters).get(1);
        assertEquals(0, value.intValue());
    }
    
    public void testGetAddFromQueryOrderedNull() throws Exception {
        message.put("HTTP_GET_CHECK_PARAM_NAME", Boolean.TRUE);
        message.put(Message.PATH_INFO, "/CalculatorService/SoapPort/add");
        message.put(Message.QUERY_STRING, "one=1&two=2");
        
        URIMappingInterceptor interceptor = new URIMappingInterceptor();
        interceptor.handleMessage(message);
        assertNull(message.getContent(Exception.class));
        Object parameters = message.getContent(List.class);
        assertNotNull(parameters);
        assertEquals(2, ((List)parameters).size());        
        
        assertNull(((List)parameters).get(0));
        assertNull(((List)parameters).get(1));        
    }    
}
