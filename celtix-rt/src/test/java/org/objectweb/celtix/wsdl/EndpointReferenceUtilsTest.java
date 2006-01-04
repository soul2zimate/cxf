package org.objectweb.celtix.wsdl;


import javax.wsdl.Definition;
import javax.wsdl.Port;

import junit.framework.TestCase;

import org.objectweb.celtix.Bus;
import org.objectweb.celtix.ws.addressing.EndpointReferenceType;
import org.objectweb.hello_world_soap_http.AnnotatedGreeterImpl;
import org.objectweb.hello_world_soap_http.DerivedGreeterImpl;

public class EndpointReferenceUtilsTest extends TestCase {

    public EndpointReferenceUtilsTest(String arg0) {
        super(arg0);
    }


    public void testGetWSDLDefinitionFromImplementation() throws Exception {
        Bus bus = Bus.init();
        
        // This implementor is not derived from an SEI and does not implement
        // the Remote interface. It i however annotated with a WebService annotation
        // in which the wsdl location attribute is not set.
        
        Object implementor = new AnnotatedGreeterImpl();
        WSDLManager manager = bus.getWSDLManager();
        EndpointReferenceType ref = 
            EndpointReferenceUtils.getEndpointReference(manager, implementor);
        
        Definition def = EndpointReferenceUtils.getWSDLDefinition(manager, ref);
        assertNotNull("Could not generate wsdl", def);
        Port port = EndpointReferenceUtils.getPort(bus.getWSDLManager(), ref);
        assertNotNull("Could not find port", port);

        // This implementor is annotated with a WebService annotation that has no
        // wsdl location specified but it is derived from an interface that is
        // annotated with a WebService annotation in which the attribute IS set -
        // to a url that can be resolved because the interface was generated as part 
        // of the test build.
        
        implementor = new DerivedGreeterImpl();
        EndpointReferenceUtils.getWSDLDefinition(manager, ref);
        def = EndpointReferenceUtils.getWSDLDefinition(manager, ref);
        assertNotNull("Could not load wsdl", def);
        port = EndpointReferenceUtils.getPort(bus.getWSDLManager(), ref);
        assertNotNull("Could not find port", port);
    }

}
