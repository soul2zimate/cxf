package org.objectweb.celtix.transports.http;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import javax.wsdl.WSDLException;
import javax.xml.namespace.QName;
import javax.xml.ws.handler.MessageContext;

import junit.extensions.TestSetup;
import junit.framework.Test;
import junit.framework.TestCase;
import junit.framework.TestSuite;
import org.easymock.classextension.EasyMock;
import org.objectweb.celtix.Bus;
import org.objectweb.celtix.BusEvent;
import org.objectweb.celtix.BusException;
import org.objectweb.celtix.bindings.ClientBinding;
import org.objectweb.celtix.bus.configuration.ConfigurationEventFilter;
import org.objectweb.celtix.bus.configuration.spring.ConfigurationProviderImpl;
import org.objectweb.celtix.bus.workqueue.WorkQueueManagerImpl;
import org.objectweb.celtix.bus.wsdl.WSDLManagerImpl;
import org.objectweb.celtix.buslifecycle.BusLifeCycleManager;
import org.objectweb.celtix.configuration.CompoundName;
import org.objectweb.celtix.configuration.Configuration;
import org.objectweb.celtix.configuration.ConfigurationBuilder;
import org.objectweb.celtix.configuration.impl.ConfigurationBuilderImpl;
import org.objectweb.celtix.configuration.impl.TypeSchemaHelper;
import org.objectweb.celtix.context.GenericMessageContext;
import org.objectweb.celtix.context.InputStreamMessageContext;
import org.objectweb.celtix.context.OutputStreamMessageContext;
import org.objectweb.celtix.transports.ClientTransport;
import org.objectweb.celtix.transports.ServerTransport;
import org.objectweb.celtix.transports.ServerTransportCallback;
import org.objectweb.celtix.transports.TestResponseCallback;
import org.objectweb.celtix.transports.http.configuration.HTTPServerPolicy;
import org.objectweb.celtix.workqueue.WorkQueueManager;
import org.objectweb.celtix.ws.addressing.EndpointReferenceType;
import org.objectweb.celtix.wsdl.EndpointReferenceUtils;
import org.objectweb.celtix.wsdl.WSDLManager;

public class HTTPTransportTest extends TestCase {

    private static final QName SERVICE_NAME = new
        QName("http://objectweb.org/hello_world_soap_http", "SOAPService");
    private static final String PORT_NAME = "SoapPort";
    private static final String ADDRESS = "http://localhost:9000/SoapContext/SoapPort";
    private static final String DECOUPLED_ADDRESS = "http://localhost:9999/decoupled";
    private static final int DECOUPLED_PORT = 9999;

    private static final URL WSDL_URL = HTTPTransportTest.class.getResource("/wsdl/hello_world.wsdl");
    private static final String HTTP_SERVER_CONFIGURATION_URI =
        "http://celtix.objectweb.org/bus/transports/http/http-server-config";

    private static boolean first = true;

    Bus bus;
    private WSDLManager wsdlManager;
    private WorkQueueManager queueManager;
    private ExecutorService executorService;
    private TestResponseCallback responseCallback;
    private HTTPTransportFactory factory;
    private Lock partialResponseReceivedLock;
    private Condition partialResponseReceivedCondition;
    private boolean partialResponseReceivedNotified;
    private Lock invokerControlRegainedLock;
    private Condition invokerControlRegainedCondition;
    private boolean invokerControlRegainedNotified;
    private boolean invokerControlRegainedWaitTimedOut;
    private ClientBinding clientBinding;
    private ConfigurationBuilder configurationBuilder = new ConfigurationBuilderImpl();

    public HTTPTransportTest(String arg0) {
        super(arg0);
    }

    public static Test suite() throws Exception {
        TestSuite suite = new TestSuite(HTTPTransportTest.class);
        return new TestSetup(suite) {
            protected void tearDown() throws Exception {
                super.tearDown();
                JettyHTTPServerEngine.destroyForPort(9000);
            }
        };
    }


    public static void main(String[] args) {
        junit.textui.TestRunner.run(HTTPTransportTest.class);
    }

    public void setUp() throws BusException {
        configurationBuilder = new ConfigurationBuilderImpl();

        ConfigurationProviderImpl.clearBeanFactoriesMap();
        TypeSchemaHelper.clearCache();

        
        bus = EasyMock.createMock(Bus.class);
        wsdlManager = new WSDLManagerImpl(null);
        partialResponseReceivedLock = new ReentrantLock();
        partialResponseReceivedCondition = partialResponseReceivedLock.newCondition();
        partialResponseReceivedNotified = false;
        invokerControlRegainedLock = new ReentrantLock();
        invokerControlRegainedCondition = invokerControlRegainedLock.newCondition();
        invokerControlRegainedNotified = false;
        invokerControlRegainedWaitTimedOut = false;
        responseCallback = new TestResponseCallback();
        clientBinding = EasyMock.createMock(ClientBinding.class);
    }

    public void tearDown() throws Exception {
        EasyMock.reset(bus);
        try {
            bus.removeListener(EasyMock.isA(JettyHTTPServerTransport.class));
        } catch (BusException e) {
            // nothing to do            
        }
        EasyMock.expectLastCall();
        checkBusRemovedEvent();        
        EasyMock.replay(bus);

        if (queueManager != null) {
            queueManager.shutdown(false);
        }
        if (executorService != null) {
            executorService.shutdownNow();
        }
        JettyHTTPServerEngine.destroyForPort(DECOUPLED_PORT);
        
        ConfigurationProviderImpl.clearBeanFactoriesMap();
        TypeSchemaHelper.clearCache();
    }

    int readBytes(byte bytes[], InputStream ins) throws IOException {
        int len = ins.read(bytes);
        int total = 0;
        while (len != -1) {
            total += len;
            len = ins.read(bytes, total, bytes.length - total);
        }
        return total;
    }


    public void testInvokeOneway() throws Exception {
        doTestInvokeOneway(false);
    }

    public void testInvokeOnewayDecoupled() throws Exception {
        doTestInvokeOneway(true);
    }

    public void testInvoke() throws Exception {
        doTestInvoke(false);
        doTestInvoke(false);
    }


    public void testInvokeDecoupled() throws Exception {
        doTestInvoke(false, true, ADDRESS);
    }

    public void testInvokeUsingAutomaticWorkQueue() throws Exception {
        doTestInvoke(true);
    }

    public void testInvokeDecoupledUsingAutomaticWorkQueue() throws Exception {
        doTestInvoke(true, true, ADDRESS);
    }

    public void testInvokeAsync() throws Exception {
        doTestInvokeAsync(false);
    }

    public void testInvokeAsyncDecoupled() throws Exception {
        doTestInvokeAsync(false, true);
    }

    public void testInvokeAsyncUsingAutomaticWorkQueue() throws Exception {
        doTestInvokeAsync(true);
    }

    public void testInvokeAsyncDecoupledUsingAutomaticWorkQueue() throws Exception {
        doTestInvokeAsync(true, true);
    }

    public void testInputStreamMessageContextCallable() throws Exception {
        factory = createTransportFactory();
        HTTPClientTransport.HTTPClientOutputStreamContext octx =
            EasyMock.createMock(HTTPClientTransport.HTTPClientOutputStreamContext.class);
        HTTPClientTransport.HTTPClientInputStreamContext ictx =
            EasyMock.createMock(HTTPClientTransport.HTTPClientInputStreamContext.class);
        octx.getCorrespondingInputStreamContext();
        EasyMock.expectLastCall().andReturn(ictx);
        EasyMock.replay(octx);
        HTTPClientTransport client = (HTTPClientTransport)
            createClientTransport(WSDL_URL, SERVICE_NAME, PORT_NAME, ADDRESS, false);

        Callable c = client.getInputStreamMessageContextCallable(octx);
        assertNotNull(c);
        InputStreamMessageContext result = (InputStreamMessageContext)c.call();
        assertEquals(result, ictx);
    }

    public void testServerTransportSetsContextProperties() throws Exception {

        final String expectedPathInfo = new URL(ADDRESS).getPath();
        final String expectedQuery = "foo=1&bar=2";
        
        factory = createTransportFactory();

        ServerTransport server =
            createServerTransport(WSDL_URL, SERVICE_NAME, PORT_NAME, ADDRESS);

        ServerTransportCallback callback = new TestServerTransportCallback(server, false, false, 
                                                                           null, false, false) {
            public void dispatch(InputStreamMessageContext ctx, ServerTransport transport) {
                String method = (String)ctx.get(MessageContext.HTTP_REQUEST_METHOD);
                String pathInfo = (String)ctx.get(MessageContext.PATH_INFO);
                String queryString = (String)ctx.get(MessageContext.QUERY_STRING); 
                
                assertEquals("incorrect HTTP method received", "POST", method);
                assertEquals("incorrect pathInfo", expectedPathInfo, pathInfo);
                assertEquals("incorrect query string", expectedQuery, queryString);
                super.dispatch(ctx, transport);
            }
            
        };
        
        activateServer(callback, server, true, false, null, false, false);
        //short request
        ClientTransport client =
            createClientTransport(WSDL_URL, SERVICE_NAME, PORT_NAME, ADDRESS + "?" + expectedQuery, false);
        doRequestResponse(client, "Hello World".getBytes(), true, false);

    }


    public void testStemMatchingOfURL() throws Exception {
        final String subaddr = "http://localhost:9000/containers/subcontext";
        final String topaddr = "http://localhost:9000/containers";
        final URL subaddrURL = new URL(subaddr);
        final URL topaddrURL = new URL(topaddr);
        final QName serviceName = new QName("http://com.iona/", "containers");
        factory = createTransportFactory();
        
        EasyMock.reset(bus);

        Configuration serverCfg = EasyMock.createMock(Configuration.class);
        ServerTransport server = helpSetupServerSide(topaddrURL, serviceName, serverCfg);
        
        ServerTransportCallback callback = new TestServerTransportCallback(server, false, false, 
                                                                           null, false, false) {
            public void dispatch(InputStreamMessageContext ctx, ServerTransport transport) {
                String pathInfo = (String)ctx.get(MessageContext.PATH_INFO);
                if (getCalls() == 0) {
                    // First callback, should be subcontext
                    assertEquals("incorrect pathInfo", subaddrURL.getPath(), pathInfo);
                } else {
                    if (getCalls() == 1) {
                        // Second callback, should be top context
                        assertEquals("incorrect pathInfo", 
                                     topaddrURL.getPath(), pathInfo);                        
                    } else {
                        fail("Unexpected number of calls to server transport callback: " 
                             + getCalls());
                    }
                }
                super.dispatch(ctx, transport);
            }            
        };                

        EasyMock.reset(serverCfg);
        serverCfg.getString("contextMatchStrategy");
        EasyMock.expectLastCall().andReturn("stem");
        EasyMock.replay(serverCfg);
        
        activateServer(callback, server, true, false, null, false, false);
        
        EasyMock.verify(serverCfg);
        
        // Both of these client invocations should go the same server
        // callback instance - the invocations are trivial, just touch
        // the endpoint, content is immaterial.
        
        // Request to the subcontext: /containers/subcontext
        try {
            subaddrURL.openStream().close();
        } catch (FileNotFoundException fnf) {
            fail("Subcontext not matched as expected.");
        }
        
        // Request to the top context: /containers        
        topaddrURL.openStream().close();

    }

    private ServerTransport helpSetupServerSide(
            final URL topaddrURL, final QName serviceName, Configuration serverCfg) 
        throws WSDLException, IOException {
        Configuration bc = EasyMock.createMock(Configuration.class);
        CompoundName bcn = new CompoundName("celtix");
        ConfigurationBuilder cb = EasyMock.createMock(ConfigurationBuilder.class);
        EasyMock.expect(bus.getConfiguration()).andReturn(bc);
        EasyMock.expect(bc.getId()).andReturn(bcn);
        EasyMock.expect(bus.getConfigurationBuilder()).andReturn(cb);
        EasyMock.expect(cb.getConfiguration(EasyMock.isA(String.class),
            EasyMock.isA(CompoundName.class))).andReturn(serverCfg);


        serverCfg.getObject(HTTPServerPolicy.class, "httpServer");
        EasyMock.expectLastCall().andReturn(null);        
      
        bus.getWSDLManager();
        EasyMock.expectLastCall().andReturn(wsdlManager);
        if (first) {
            //first call will configure the port listener
            EasyMock.expect(bus.getConfiguration()).andReturn(bc);
            EasyMock.expect(bc.getId()).andReturn(bcn);
            EasyMock.expect(bus.getConfigurationBuilder()).andReturn(configurationBuilder);
            first = false;
        }

        
        // check the bus configuration call for serviceMonitoring 
        checkServiceMonitoringConfiguration();
        
        try {
            bus.addListener(EasyMock.isA(JettyHTTPServerTransport.class), 
                            EasyMock.isA(ConfigurationEventFilter.class));
        } catch (BusException e) {
            // nothing to do            
        }
        EasyMock.expectLastCall();

        checkBusCreatedEvent();

        EasyMock.replay(bus);
        EasyMock.replay(bc);
        EasyMock.replay(cb);
        EasyMock.replay(serverCfg);
        
        EndpointReferenceType ref = 
            EndpointReferenceUtils.getEndpointReference(topaddrURL, serviceName, null);
        EndpointReferenceUtils.setAddress(ref, topaddrURL.toString());
        ServerTransport server = factory.createServerTransport(ref);

        EasyMock.verify(bus);
        EasyMock.verify(bc);
        EasyMock.verify(cb);
        EasyMock.verify(serverCfg);
        
        return server;
    }
    
    public void doTestInvokeOneway(boolean decoupled) throws Exception {

        factory = createTransportFactory();

        ServerTransport server =
            createServerTransport(WSDL_URL, SERVICE_NAME, PORT_NAME, ADDRESS);
        byte[] buffer = new byte[64];
        activateServer(server, false, true, buffer, true, decoupled);

        ClientTransport client =
            createClientTransport(WSDL_URL, SERVICE_NAME, PORT_NAME, ADDRESS, decoupled);
        byte outBytes[] = "Hello World!!!".getBytes();

        OutputStreamMessageContext octx = doRequest(client, outBytes, true, decoupled);
        client.invokeOneway(octx);
        signalInvokerControlRegained();

        octx = doRequest(client, outBytes, false, decoupled);
        client.invokeOneway(octx);
        signalInvokerControlRegained();
        octx = doRequest(client, outBytes, false, decoupled);
        client.invokeOneway(octx);
        signalInvokerControlRegained();

        server.deactivate();
        EasyMock.reset(bus);
        checkBusRemovedEvent();
        EasyMock.replay(bus);
        client.shutdown();

        Thread.sleep(200);
    }

    public void doTestInvoke(final boolean useAutomaticWorkQueue) throws Exception {
        doTestInvoke(useAutomaticWorkQueue, false, ADDRESS);
    }

    public void doTestInvoke(final boolean useAutomaticWorkQueue,
                             final boolean decoupled,
                             final String address) throws Exception {

        factory = createTransportFactory();

        ServerTransport server =
            createServerTransport(WSDL_URL, SERVICE_NAME, PORT_NAME, address);

        activateServer(server, useAutomaticWorkQueue, false, null, false, decoupled);
        //short request
        ClientTransport client =
            createClientTransport(WSDL_URL, SERVICE_NAME, PORT_NAME, address, decoupled);
        doRequestResponse(client, "Hello World".getBytes(), true, decoupled);

        //long request
        byte outBytes[] = new byte[5000];
        for (int x = 0; x < outBytes.length; x++) {
            outBytes[x] = (byte)('a' + (x % 26));
        }
        client =
            createClientTransport(WSDL_URL, SERVICE_NAME, PORT_NAME, address, decoupled);
        doRequestResponse(client, outBytes, false, decoupled);

        server.deactivate();
        outBytes = "HelloWorld".getBytes();

        try {
            OutputStreamMessageContext octx = client.createOutputStreamContext(new GenericMessageContext());
            client.finalPrepareOutputStreamContext(octx);
            octx.getOutputStream().write(outBytes);
            octx.getOutputStream().close();
            InputStreamMessageContext ictx = client.invoke(octx);
            byte bytes[] = new byte[10000];
            int len = ictx.getInputStream().read(bytes);
            if (len != -1
                && new String(bytes, 0, len).indexOf("HTTP Status 503") == -1
                && new String(bytes, 0, len).indexOf("Error 404") == -1) {
                fail("was able to process a message after the servant was deactivated: " + len
                     + " - " + new String(bytes));
            }
        } catch (IOException ex) {
            //ignore - this is what we want
        }
        activateServer(server, useAutomaticWorkQueue, false, null, false, decoupled);
        doRequestResponse(client, "Hello World   3".getBytes(), false, decoupled);
        server.deactivate();
        activateServer(server, useAutomaticWorkQueue, false, null, false, decoupled);
        doRequestResponse(client, "Hello World   4".getBytes(), false, decoupled);
        server.deactivate();
        EasyMock.reset(bus);
        checkBusRemovedEvent();
        EasyMock.replay(bus);
        client.shutdown();
    }

    public void doTestInvokeAsync(final boolean useAutomaticWorkQueue) throws Exception {
        doTestInvokeAsync(useAutomaticWorkQueue, false);
    }

    public void doTestInvokeAsync(final boolean useAutomaticWorkQueue, boolean decoupled) throws Exception {

        Executor executor =  null;
        if (useAutomaticWorkQueue) {
            queueManager = new WorkQueueManagerImpl(bus);
            executor = queueManager.getAutomaticWorkQueue();
        } else {
            executorService = Executors.newFixedThreadPool(1);
            executor = executorService;
        }
        factory = createTransportFactory();

        ServerTransport server =
            createServerTransport(WSDL_URL, SERVICE_NAME, PORT_NAME, ADDRESS);
        activateServer(server, false, true, null, false, decoupled);

        ClientTransport client =
            createClientTransport(WSDL_URL, SERVICE_NAME, PORT_NAME, ADDRESS, decoupled);
        byte outBytes[] = "Hello World!!!".getBytes();

        // wait then read without blocking
        OutputStreamMessageContext octx = doRequest(client, outBytes, true, decoupled);
        Future<InputStreamMessageContext> f = client.invokeAsync(octx, executor);
        assertNotNull(f);
        signalInvokerControlRegained();
        int i = 0;
        while (i < 10) {
            Thread.sleep(100);
            if (f.isDone()) {
                break;
            }
            i++;
        }
        assertTrue(f.isDone());
        InputStreamMessageContext ictx = f.get();
        doResponse(client, ictx, outBytes, decoupled);

        // blocking read (on new thread)
        octx = doRequest(client, outBytes, false, decoupled);
        f = client.invokeAsync(octx, executor);
        signalInvokerControlRegained();
        ictx = f.get();
        assertTrue(f.isDone());
        doResponse(client, ictx, outBytes, decoupled);

        // blocking read times out
        boolean timeoutImplemented = false;
        if (timeoutImplemented) {
            octx = doRequest(client, outBytes, false, decoupled);
            f = client.invokeAsync(octx, executor);
            signalInvokerControlRegained();
            try {
                ictx = f.get(200, TimeUnit.MILLISECONDS);
                fail("Expected TimeoutException not thrown.");
            } catch (TimeoutException ex) {
                // ignore
            }
            assertTrue(!f.isDone());
        }
        server.deactivate();
    }
    
    public void testInvokeNoContext() throws Exception {
        boolean oldFirst = first;
        try {
            first = true;
            doTestInvoke(false, false, "http://localhost:9888");
        } finally {
            first = oldFirst;
            JettyHTTPServerEngine.destroyForPort(9888);
        }
    }
    
    private void checkServiceMonitoringConfiguration() {
        Configuration cfg = EasyMock.createMock(Configuration.class);        
        bus.getConfiguration();
        EasyMock.expectLastCall().andReturn(cfg);
        cfg.getBoolean("servicesMonitoring");
        EasyMock.expectLastCall().andReturn(true);
    }
    
    private void checkBusCreatedEvent() {
        
        //        bus.sendEvent(EasyMock.isA(ComponentCreatedEvent.class));
        bus.sendEvent(EasyMock.isA(BusEvent.class));

        EasyMock.expectLastCall();
    }

    private void checkBusRemovedEvent() { 

        //        bus.sendEvent(EasyMock.isA(ComponentRemovedEvent.class));
        bus.sendEvent(EasyMock.isA(BusEvent.class));

        EasyMock.expectLastCall();
    }


    private void activateServer(ServerTransportCallback callback, 
                                ServerTransport server,
                                final boolean useAutomaticWorkQueue,
                                final boolean async,
                                final byte[] buffer,
                                final boolean oneWay,
                                final boolean decoupled) throws Exception {
        EasyMock.reset(bus);
        Configuration bc = EasyMock.createMock(Configuration.class);
        bus.getConfiguration();
        EasyMock.expectLastCall().andReturn(bc);
        server.activate(callback);
    }
    
    private void activateServer(ServerTransport server,
                                final boolean useAutomaticWorkQueue,
                                final boolean async,
                                final byte[] buffer,
                                final boolean oneWay,
                                final boolean decoupled) throws Exception {
        ServerTransportCallback callback = new TestServerTransportCallback(server,
                                                                           useAutomaticWorkQueue,
                                                                           async,
                                                                           buffer,
                                                                           oneWay,
                                                                           decoupled);
        
        activateServer(callback, server, useAutomaticWorkQueue, async, buffer, oneWay, decoupled);
    }

    private void doRequestResponse(ClientTransport client,
                                   byte outBytes[],
                                   boolean initial,
                                   boolean decoupled)
        throws Exception {
        OutputStreamMessageContext octx = doRequest(client, outBytes, initial, decoupled);
        InputStreamMessageContext ictx = client.invoke(octx);
        doResponse(client, ictx, outBytes, decoupled);
    }

    private OutputStreamMessageContext doRequest(ClientTransport client,
                                                 byte outBytes[],
                                                 boolean initial,
                                                 boolean decoupled) throws Exception {
        if (decoupled) {
            if (initial) {
                assertFalse(((HTTPClientTransport)client).hasDecoupledEndpoint());
                EasyMock.reset(bus);
                Configuration bc = EasyMock.createMock(Configuration.class);
                CompoundName bcn = new CompoundName("celtix");
                
                EasyMock.expect(bus.getConfiguration()).andReturn(bc);
                EasyMock.expect(bc.getId()).andReturn(bcn);
                EasyMock.expect(bus.getConfigurationBuilder()).andReturn(configurationBuilder);

                EasyMock.replay(bus);
                EasyMock.replay(bc);
                EasyMock.reset(clientBinding);
                clientBinding.createResponseCallback();
                EasyMock.expectLastCall().andReturn(responseCallback);
                EasyMock.replay(clientBinding);
            }
            
            EndpointReferenceType decoupledEndpoint = client.getDecoupledEndpoint();
            assertNotNull(decoupledEndpoint);
            assertNotNull(decoupledEndpoint.getAddress());
            assertEquals(decoupledEndpoint.getAddress().getValue(), DECOUPLED_ADDRESS);
            assertTrue(((HTTPClientTransport)client).hasDecoupledEndpoint());
            assertSame(responseCallback, client.getResponseCallback());

            if (initial) {
                EasyMock.verify(bus);
                EasyMock.verify(clientBinding);
            }
        }
        OutputStreamMessageContext octx = client.createOutputStreamContext(new GenericMessageContext());
        client.finalPrepareOutputStreamContext(octx);
        octx.getOutputStream().write(outBytes);
        return octx;
    }

    private void doResponse(ClientTransport client,
                            InputStreamMessageContext ictx,
                            byte outBytes[],
                            boolean decoupled) throws Exception {
        if (decoupled) {
            signalPartialResponseReceived();
            doResponse(client, responseCallback.waitForNextResponse(), outBytes);
        } else {
            doResponse(client, ictx, outBytes);
        }
    }

    private void doResponse(ClientTransport client,
        InputStreamMessageContext ictx, byte outBytes[]) throws Exception {
        byte bytes[] = new byte[10000];
        int len = readBytes(bytes, ictx.getInputStream());
        assertTrue("Did not read anything " + len, len > 0);
        assertEquals(new String(outBytes), new String(bytes, 0, len));
    }

    private void awaitPartialResponseReceived() throws Exception {
        partialResponseReceivedLock.lock();
        try {
            while (!partialResponseReceivedNotified) {
                partialResponseReceivedCondition.await();
            }
        } finally {
            partialResponseReceivedNotified = false;
            partialResponseReceivedLock.unlock();
        }
    }

    private void signalPartialResponseReceived() throws Exception {
        partialResponseReceivedLock.lock();
        try {
            partialResponseReceivedNotified = true;
            partialResponseReceivedCondition.signal();
        } finally {
            partialResponseReceivedLock.unlock();
        }
    }
    
    private void awaitInvokerControlRegained() throws Exception {
        invokerControlRegainedLock.lock();
        try {
            long timeout = 10 * 1000000;
            while (!invokerControlRegainedNotified) {
                if (timeout > 0L) {
                    timeout =
                        invokerControlRegainedCondition.awaitNanos(timeout);
                } else {
                    invokerControlRegainedWaitTimedOut = true;
                    break;
                }
            }
        } finally {
            invokerControlRegainedNotified = false;
            invokerControlRegainedLock.unlock();
        }
    }

    private void signalInvokerControlRegained() throws Exception {
        invokerControlRegainedLock.lock();
        assertFalse("invoker control regained wait timed out",
                    invokerControlRegainedWaitTimedOut);
        try {
            invokerControlRegainedNotified = true;
            invokerControlRegainedCondition.signal();
        } finally {
            invokerControlRegainedLock.unlock();
        }
    }

    private HTTPTransportFactory createTransportFactory() throws BusException {

        EasyMock.reset(bus);
        Configuration bc = EasyMock.createMock(Configuration.class);

        bus.getWSDLManager();
        EasyMock.expectLastCall().andReturn(wsdlManager);
        bus.getWSDLManager();
        EasyMock.expectLastCall().andReturn(wsdlManager);
        bus.getWSDLManager();
        EasyMock.expectLastCall().andReturn(wsdlManager);

        BusLifeCycleManager lifecycleManager = EasyMock.createNiceMock(BusLifeCycleManager.class);
        bus.getLifeCycleManager();
        EasyMock.expectLastCall().andReturn(lifecycleManager);

        // check the transportFactoryManager create event
        checkBusCreatedEvent();
        EasyMock.replay(bus);
        EasyMock.replay(bc);

        HTTPTransportFactory tfm = new HTTPTransportFactory();
        tfm.init(bus);
        return tfm;
        
    }

    private ClientTransport createClientTransport(URL wsdlUrl,
                                                  QName serviceName,
                                                  String portName,
                                                  String address,
                                                  boolean decoupled)
        throws WSDLException, IOException {
        EasyMock.reset(bus);

        Configuration bc = EasyMock.createMock(Configuration.class);
        CompoundName bcn = new CompoundName("celtix");
        ConfigurationBuilder cb = EasyMock.createMock(ConfigurationBuilder.class);
        Configuration pc = EasyMock.createMock(Configuration.class);

        EasyMock.expect(bus.getConfiguration()).andReturn(bc);
        EasyMock.expect(bc.getId()).andReturn(bcn);
        EasyMock.expect(bus.getConfigurationBuilder()).andReturn(cb);
        EasyMock.expect(cb.getConfiguration(EasyMock.isA(String.class),
            EasyMock.isA(CompoundName.class))).andReturn(pc);
       
        EasyMock.expect(pc.getId()).andReturn(bcn);
        EasyMock.expect(bus.getConfigurationBuilder()).andReturn(configurationBuilder);
         
        bus.getWSDLManager();
        EasyMock.expectLastCall().andReturn(wsdlManager);
        pc.getString("address");
        EasyMock.expectLastCall().andReturn(address);

        checkBusCreatedEvent();

        EasyMock.replay(bus);
        EasyMock.replay(bc);
        EasyMock.replay(cb);
        EasyMock.replay(pc);

        EndpointReferenceType ref = EndpointReferenceUtils
            .getEndpointReference(wsdlUrl, serviceName, portName);
        ClientTransport transport = factory.createClientTransport(ref, clientBinding);
        if (decoupled) {
            ((HTTPClientTransport)transport).policy.setDecoupledEndpoint(DECOUPLED_ADDRESS);
        }

        EasyMock.verify(bus);
        EasyMock.verify(bc);
        EasyMock.verify(cb);
        EasyMock.verify(pc);
        return transport;

    }
    
    private ServerTransport createServerTransport(URL wsdlUrl,
                                                  QName serviceName,
                                                  String portName,
                                                  String address)
        throws WSDLException, IOException {

        URL url = new URL(address);
        
        EasyMock.reset(bus);

        Configuration bc = EasyMock.createMock(Configuration.class);
        CompoundName bcn = new CompoundName("celtix");
        Configuration ec = EasyMock.createMock(Configuration.class);

        EasyMock.expect(bus.getConfiguration()).andReturn(bc);
        EasyMock.expect(bc.getId()).andReturn(bcn);
        EasyMock.expect(bus.getConfigurationBuilder()).andReturn(configurationBuilder);
        
        bus.getWSDLManager();
        EasyMock.expectLastCall().andReturn(wsdlManager);
        if (first) {
            //first call will configure the port listener
            EasyMock.expect(bus.getConfiguration()).andReturn(bc);
            EasyMock.expect(bc.getId()).andReturn(bcn);
            EasyMock.expect(bus.getConfigurationBuilder()).andReturn(configurationBuilder);
            first = false;
        }
        // check the bus configuration call for serviceMonitoring 
        checkServiceMonitoringConfiguration();
        
        try {
            bus.addListener(EasyMock.isA(JettyHTTPServerTransport.class), 
                            EasyMock.isA(ConfigurationEventFilter.class));
        } catch (BusException e) {
            // nothing to do            
        }
        EasyMock.expectLastCall();

        checkBusCreatedEvent();

        EasyMock.replay(bus);
        EasyMock.replay(bc);
        EasyMock.replay(ec);

        EndpointReferenceType ref = EndpointReferenceUtils.getEndpointReference(wsdlUrl, serviceName,
                                                                                portName);
        EndpointReferenceUtils.setAddress(ref, address);
        ServerTransport transport = factory.createServerTransport(ref);

        EasyMock.verify(bus);
        EasyMock.verify(bc);
        EasyMock.verify(ec);

        return transport;

    }

    private class TestServerTransportCallback implements ServerTransportCallback {
        private ServerTransport server;
        private boolean useAutomaticWorkQueue;
        private boolean async;
        private byte[] buffer;
        private boolean oneWay;
        private boolean decoupled;
        private int calls;

        TestServerTransportCallback(ServerTransport s,
                                    boolean uaq,
                                    boolean a,
                                    byte[] b,
                                    boolean ow,
                                    boolean dc) {
            server = s;
            useAutomaticWorkQueue = uaq;
            async = a;
            buffer = b;
            oneWay = ow;
            decoupled = dc;
            calls = 0;
        }

        public void dispatch(InputStreamMessageContext ctx, ServerTransport transport) {
            ++calls;
            try {
                byte[] bytes = buffer;
                if (null == bytes) {
                    bytes = new byte[10000];
                }
                int total = readBytes(bytes, ctx.getInputStream());

                OutputStreamMessageContext octx = null;
                if (decoupled) {
                    EndpointReferenceType ref = new EndpointReferenceType();
                    EndpointReferenceUtils.setAddress(ref, DECOUPLED_ADDRESS);
                    octx = server.rebase(ctx, ref);
                    server.finalPrepareOutputStreamContext(octx);
                    octx.getOutputStream().flush();
                    octx.getOutputStream().close();
                    assertEquals(ctx.get(HTTPServerInputStreamContext.HTTP_RESPONSE), ref);
                    if (!oneWay) {
                        awaitPartialResponseReceived();
                    }
                }

                if (oneWay) {
                    octx = transport.createOutputStreamContext(ctx);
                    octx.setOneWay(oneWay);
                    transport.finalPrepareOutputStreamContext(octx);
                    octx.getOutputStream().close();
                    transport.postDispatch(ctx, octx);
                }

                // simulate implementor call - this shouldn't block control being
                // returned to the oneway/async client
                if (async) {
                    awaitInvokerControlRegained();
                }

                if (!oneWay) {
                    octx = transport.createOutputStreamContext(ctx);
                    octx.setOneWay(oneWay);
                    transport.finalPrepareOutputStreamContext(octx);
                    octx.getOutputStream().write(bytes, 0, total);
                    octx.getOutputStream().flush();
                    octx.getOutputStream().close();
                    transport.postDispatch(ctx, octx);
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
        public synchronized Executor getExecutor() {
            EasyMock.reset(bus);
            checkBusCreatedEvent();
            EasyMock.replay(bus);
            if (useAutomaticWorkQueue) {
                if (queueManager == null) {
                    queueManager = new WorkQueueManagerImpl(bus);
                }
                return queueManager.getAutomaticWorkQueue();
            } else {
                return null;
            }
        }

        
        public int getCalls() {
            return calls;
        }
    }
}
