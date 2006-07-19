package org.objectweb.celtix.bindings.soap2;

import java.net.URI;
import java.util.Collections;
import java.util.Set;

import javax.xml.namespace.QName;

import org.objectweb.celtix.phase.AbstractPhaseInterceptor;

public abstract class AbstractSoapInterceptor extends AbstractPhaseInterceptor<SoapMessage> 
    implements SoapInterceptor {

    @SuppressWarnings("unchecked")
    public Set<URI> getRoles() {
        return Collections.EMPTY_SET;
    }

    @SuppressWarnings("unchecked")
    public Set<QName> getUnderstoodHeaders() {
        return Collections.EMPTY_SET;
    }
}
