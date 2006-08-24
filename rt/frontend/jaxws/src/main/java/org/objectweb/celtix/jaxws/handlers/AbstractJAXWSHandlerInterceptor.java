package org.apache.cxf.jaxws.handlers;

import javax.xml.ws.Binding;

import org.apache.cxf.message.Message;
import org.apache.cxf.phase.AbstractPhaseInterceptor;

public abstract class AbstractJAXWSHandlerInterceptor<T extends Message> extends AbstractPhaseInterceptor<T> {

    public static final String HANDLER_CHAIN_INVOKER = "org.apache.cxf.jaxws.handlers.invoker";
    
    private Binding binding;
    
    protected AbstractJAXWSHandlerInterceptor(Binding b) {
        binding = b;
    }
    
    boolean isOneway(T message) {
        return true;
    }
    
    boolean isOutbound(T message) {
        return message == message.getExchange().getOutMessage();
    }
    
    boolean isRequestor(T message) {
        return true;
    }
    
    protected HandlerChainInvoker getInvoker(T message) {
        HandlerChainInvoker invoker = 
            (HandlerChainInvoker)message.getExchange().get(HANDLER_CHAIN_INVOKER);
        if (null == invoker) {
            invoker = new HandlerChainInvoker(binding.getHandlerChain());
            message.getExchange().put(HANDLER_CHAIN_INVOKER, invoker);
        }
        return invoker;
    }
    
    protected Binding getBinding() {
        return binding;
    }
}
