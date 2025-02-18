/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"). You may not use this file except in compliance
 * with the License. A copy of the License is located at
 *
 * http://aws.amazon.com/apache2.0/
 *
 * or in the "license" file accompanying this file. This file is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
 * OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */
package com.amazonaws.serverless.proxy.struts;

import com.amazonaws.serverless.exceptions.ContainerInitializationException;
import com.amazonaws.serverless.proxy.AwsHttpApiV2SecurityContextWriter;
import com.amazonaws.serverless.proxy.AwsProxyExceptionHandler;
import com.amazonaws.serverless.proxy.AwsProxySecurityContextWriter;
import com.amazonaws.serverless.proxy.ExceptionHandler;
import com.amazonaws.serverless.proxy.RequestReader;
import com.amazonaws.serverless.proxy.ResponseWriter;
import com.amazonaws.serverless.proxy.SecurityContextWriter;
import com.amazonaws.serverless.proxy.internal.servlet.AwsHttpApiV2HttpServletRequestReader;
import com.amazonaws.serverless.proxy.internal.servlet.AwsHttpServletRequest;
import com.amazonaws.serverless.proxy.internal.servlet.AwsHttpServletResponse;
import com.amazonaws.serverless.proxy.internal.servlet.AwsLambdaServletContainerHandler;
import com.amazonaws.serverless.proxy.internal.servlet.AwsProxyHttpServletRequestReader;
import com.amazonaws.serverless.proxy.internal.servlet.AwsProxyHttpServletResponseWriter;
import com.amazonaws.serverless.proxy.internal.testutils.Timer;
import com.amazonaws.serverless.proxy.model.AwsProxyRequest;
import com.amazonaws.serverless.proxy.model.AwsProxyResponse;
import com.amazonaws.serverless.proxy.model.HttpApiV2ProxyRequest;
import com.amazonaws.services.lambda.runtime.Context;
import org.apache.struts2.dispatcher.filter.StrutsPrepareAndExecuteFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.DispatcherType;
import javax.servlet.FilterRegistration;
import javax.servlet.Servlet;
import javax.servlet.http.HttpServletRequest;
import java.util.EnumSet;
import java.util.concurrent.CountDownLatch;

/**
 * A Lambda handler to initialize the Struts filter and proxy the requests.
 *
 * @param <RequestType>  request type
 * @param <ResponseType> response type
 */
public class StrutsLambdaContainerHandler<RequestType, ResponseType> extends AwsLambdaServletContainerHandler<RequestType, ResponseType, HttpServletRequest, AwsHttpServletResponse> {

    private static final Logger log = LoggerFactory.getLogger(StrutsLambdaContainerHandler.class);

    public static final String HEADER_STRUTS_STATUS_CODE = "X-Struts-StatusCode";

    private static final String TIMER_STRUTS_CONTAINER_CONSTRUCTOR = "STRUTS_CONTAINER_CONSTRUCTOR";
    private static final String TIMER_STRUTS_HANDLE_REQUEST = "STRUTS_HANDLE_REQUEST";
    private static final String TIMER_STRUTS_COLD_START_INIT = "STRUTS_COLD_START_INIT";
    private static final String STRUTS_FILTER_NAME = "StrutsFilter";

    private boolean initialized;

    public static StrutsLambdaContainerHandler<AwsProxyRequest, AwsProxyResponse> getAwsProxyHandler() {
        return new StrutsLambdaContainerHandler(
                AwsProxyRequest.class,
                AwsProxyResponse.class,
                new AwsProxyHttpServletRequestReader(),
                new AwsProxyHttpServletResponseWriter(),
                new AwsProxySecurityContextWriter(),
                new AwsProxyExceptionHandler());
    }

    public static StrutsLambdaContainerHandler<HttpApiV2ProxyRequest, AwsProxyResponse> getHttpApiV2ProxyHandler() {
        return new StrutsLambdaContainerHandler(
                HttpApiV2ProxyRequest.class,
                AwsProxyResponse.class,
                new AwsHttpApiV2HttpServletRequestReader(),
                new AwsProxyHttpServletResponseWriter(true),
                new AwsHttpApiV2SecurityContextWriter(),
                new AwsProxyExceptionHandler());
    }

    public StrutsLambdaContainerHandler(Class<RequestType> requestTypeClass,
                                        Class<ResponseType> responseTypeClass,
                                        RequestReader<RequestType, HttpServletRequest> requestReader,
                                        ResponseWriter<AwsHttpServletResponse, ResponseType> responseWriter,
                                        SecurityContextWriter<RequestType> securityContextWriter,
                                        ExceptionHandler<ResponseType> exceptionHandler) {

        super(requestTypeClass, responseTypeClass, requestReader, responseWriter, securityContextWriter, exceptionHandler);
        Timer.start(TIMER_STRUTS_CONTAINER_CONSTRUCTOR);
        this.initialized = false;
        Timer.stop(TIMER_STRUTS_CONTAINER_CONSTRUCTOR);
    }

    @Override
    protected AwsHttpServletResponse getContainerResponse(HttpServletRequest request, CountDownLatch latch) {
        return new AwsHttpServletResponse(request, latch);
    }

    @Override
    protected void handleRequest(HttpServletRequest httpServletRequest,
                                 AwsHttpServletResponse httpServletResponse,
                                 Context lambdaContext) throws Exception {
        Timer.start(TIMER_STRUTS_HANDLE_REQUEST);
        if (!this.initialized) {
            initialize();
        }

        if (AwsHttpServletRequest.class.isAssignableFrom(httpServletRequest.getClass())) {
            ((AwsHttpServletRequest)httpServletRequest).setServletContext(this.getServletContext());
        }
        this.doFilter(httpServletRequest, httpServletResponse, null);
        String responseStatusCode = httpServletResponse.getHeader(HEADER_STRUTS_STATUS_CODE);
        if (responseStatusCode != null) {
            httpServletResponse.setStatus(Integer.parseInt(responseStatusCode));
        }
        Timer.stop(TIMER_STRUTS_HANDLE_REQUEST);
    }

    @Override
    public void initialize() throws ContainerInitializationException {
        log.info("Initialize Struts Lambda Application ...");
        Timer.start(TIMER_STRUTS_COLD_START_INIT);
        try {
            if (this.startupHandler != null) {
                this.startupHandler.onStartup(this.getServletContext());
            }
            StrutsPrepareAndExecuteFilter filter = new StrutsPrepareAndExecuteFilter();
            FilterRegistration.Dynamic filterRegistration = this.getServletContext()
                    .addFilter(STRUTS_FILTER_NAME, filter);
            filterRegistration.addMappingForUrlPatterns(
                    EnumSet.of(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.INCLUDE, DispatcherType.FORWARD),
                    true, "/*");
        } catch (Exception e) {
            throw new ContainerInitializationException("Could not initialize Struts container", e);
        }

        this.initialized = true;
        Timer.stop(TIMER_STRUTS_COLD_START_INIT);
        log.info("... initialize of Struts Lambda Application completed!");
    }

    public Servlet getServlet() {
        return null;
    }
}
