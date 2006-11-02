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

package org.apache.cxf.interceptor;

import java.io.UnsupportedEncodingException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.logging.Logger;

import javax.jws.WebParam;

import org.apache.cxf.common.i18n.BundleUtils;
import org.apache.cxf.common.util.CollectionUtils;
import org.apache.cxf.common.util.PrimitiveUtils;
import org.apache.cxf.common.util.StringUtils;
import org.apache.cxf.message.Message;
import org.apache.cxf.phase.Phase;
import org.apache.cxf.service.Service;
import org.apache.cxf.service.factory.MethodDispatcher;
import org.apache.cxf.service.model.BindingOperationInfo;
import org.apache.cxf.service.model.OperationInfo;
import org.apache.cxf.service.model.ServiceModelUtil;

public class URIMappingInterceptor extends AbstractInDatabindingInterceptor {
    
    private static final Logger LOG = Logger.getLogger(URIMappingInterceptor.class.getName());
    private static final ResourceBundle BUNDLE = BundleUtils.getBundle(URIMappingInterceptor.class);
    
    public URIMappingInterceptor() {
        super();
        setPhase(Phase.UNMARSHAL);
    }

    public void handleMessage(Message message) throws Fault {
        String method = (String)message.get(Message.HTTP_REQUEST_METHOD);
        LOG.info("Invoking HTTP method " + method);
        BindingOperationInfo op = message.getExchange().get(BindingOperationInfo.class);
        if (!isGET(message)) {
            LOG.info("URIMappingInterceptor can only handle HTTP GET, not HTTP " + method);
            return;
        }
        if (op != null) {
            return;
        }
        String opName = getOperationName(message);
        LOG.info("URIMappingInterceptor get operation: " + opName);
        op = ServiceModelUtil.getOperation(message.getExchange(), opName);
        
        if (op == null || opName == null || op.getName() == null
            || StringUtils.isEmpty(op.getName().getLocalPart())
            || !opName.equals(op.getName().getLocalPart())) {
            throw new Fault(new org.apache.cxf.common.i18n.Message("NO_OPERATION", BUNDLE, opName));
        }
        message.getExchange().put(BindingOperationInfo.class, op);
        message.setContent(List.class, getParameters(message, op));
    }

    private Method getMethod(Message message, BindingOperationInfo operation) {        
        MethodDispatcher md = (MethodDispatcher) message.getExchange().
            get(Service.class).get(MethodDispatcher.class.getName());
        return md.getMethod(operation);
    }
    
    private boolean isValidParameter(Annotation[][] parameterAnnotation, int index, String parameterName) {
        if (parameterAnnotation == null || parameterAnnotation.length < index) {
            return true;
        }
        Annotation[] annotations = parameterAnnotation[index];
        if (annotations == null || annotations.length < 1) {
            return true;
        }
        WebParam webParam = null;
        for (Annotation annotation : annotations) {
            if (annotation.annotationType() == WebParam.class) {
                webParam = (WebParam) annotation;
            }
        }
        if (webParam == null 
            || StringUtils.isEmpty(webParam.name()) 
            || webParam.name().equals(parameterName)) {
            return true;
        }
        LOG.warning("The parameter name [" + parameterName 
                    + "] is not match the one defined in the WebParam name [" + webParam.name() + "]");
        return false;
    }
    
    private boolean requireCheckParameterName(Message message) {
        // TODO add a configuration, if return false, then the parameter should be given by order.
        Boolean order = (Boolean) message.get("HTTP_GET_CHECK_PARAM_NAME");
        return order != null && order;
    }
    
    protected Map<String, String> keepInOrder(Map<String, String> params, 
                                              OperationInfo operation,
                                              List<String> order) {
        if (params == null || params.size() == 0) {
            return params;
        }
        
        if (order == null || order.size() == 0) {
            return params;
        }
                
        Map<String, String> orderedParameters = new LinkedHashMap<String, String>();
        for (String name : order) {
            orderedParameters.put(name, params.get(name));
        }
        
        if (order.size() != params.size()) {
            LOG.info(order.size()
                     + " parameters definded in WSDL but found " 
                     + params.size() + " in request!");            
            Collection rest = CollectionUtils.diff(order, params.keySet());
            if (rest != null && rest.size() > 0) {
                LOG.info("Set the following parameters to null: " + rest);
                for (Iterator iter = rest.iterator(); iter.hasNext();) {
                    String key = (String)iter.next();
                    orderedParameters.put(key, null);
                }
            }
        }
        
        return orderedParameters;
    }
    
    protected List<Object> getParameters(Message message, BindingOperationInfo operation) {
        List<Object> parameters = new ArrayList<Object>();
        int idx = parameters.size();

        Map<String, String> queries = getQueries(message);
        
        if (requireCheckParameterName(message)) {
            boolean emptyQueries = CollectionUtils.isEmpty(queries.keySet());
            List<String> names = ServiceModelUtil.getOperationInputPartNames(operation.getOperationInfo());
            queries = keepInOrder(queries, 
                                  operation.getOperationInfo(),
                                  names);
            if (!emptyQueries && CollectionUtils.isEmpty(queries.keySet())) {
                throw new Fault(new org.apache.cxf.common.i18n.Message("ORDERED_PARAM_REQUIRED", 
                                                                       BUNDLE, 
                                                                       names.toString()));
            }
        }
        
        Method method = getMethod(message, operation);        
        
        Class[] types = method.getParameterTypes();
        
        Annotation[][] parameterAnnotation = method.getParameterAnnotations();
        
        for (String key : queries.keySet()) {
            Class<?> type = types[idx];
            
            // Do we need to fail the processing if the parameter not match the WebParam?
            isValidParameter(parameterAnnotation, idx, key);
            
            if (type == null) {
                LOG.warning("URIMappingInterceptor MessagePartInfo NULL ");
                throw new Fault(new org.apache.cxf.common.i18n.Message("NO_PART_FOUND", BUNDLE, 
                                                                       "index: " + idx + " on key " + key));
            }

            // TODO check the parameter name here
            Object param = null;
                        
            if (type != null && type.isPrimitive() && queries.get(key) != null) {
                param = PrimitiveUtils.read(queries.get(key), type);
            } else {
                param = queries.get(key);
            }
            
            parameters.add(param);
            
            idx = parameters.size();
        }
        return parameters;
    }
    
    private String uriDecode(String query) {
        try {
            query = URLDecoder.decode(query, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            LOG.warning(query + " can not be decoded: " + e.getMessage());            
        }
        return query;
    }

    protected Map<String, String> getQueries(Message message) {
        Map<String, String> queries = new LinkedHashMap<String, String>();
        String query = (String)message.get(Message.QUERY_STRING);
        if (!StringUtils.isEmpty(query)) {            
            List<String> parts = Arrays.asList(query.split("&"));
            for (String part : parts) {
                String[] keyValue = part.split("=");
                queries.put(keyValue[0], uriDecode(keyValue[1]));
            }
            return queries;
        }

        String rest = getRest(message);
        List<String> parts = StringUtils.getParts(rest, "/");
        
        for (int i = 1; i < parts.size(); i += 2) {
            if (i + 1 > parts.size()) {
                queries.put(parts.get(i), null);
            } else {
                queries.put(parts.get(i), uriDecode(parts.get(i + 1)));
            }
        }
        return queries;
    }
    
    private String getBasePath(Message message) {
        return (String)message.get(Message.BASE_PATH);        
    }
    
    private String getRest(Message message) {
        String path = (String)message.get(Message.PATH_INFO);
        String basePath = getBasePath(message);        
        return StringUtils.diff(path, basePath);        
    }
    
    protected String getOperationName(Message message) {
        String rest = getRest(message);        
        String opName = StringUtils.getFirstNotEmpty(rest, "/");
        if (opName.indexOf("?") != -1) {
            opName = opName.split("\\?")[0];
        }

        return opName;
    }
}
