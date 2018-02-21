/*
 * Copyright 2018 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.particleframework.http.client.interceptor;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.reactivex.Flowable;
import org.particleframework.aop.MethodInterceptor;
import org.particleframework.aop.MethodInvocationContext;
import org.particleframework.context.BeanContext;
import org.particleframework.core.async.publisher.Publishers;
import org.particleframework.core.async.subscriber.CompletionAwareSubscriber;
import org.particleframework.core.beans.BeanMap;
import org.particleframework.core.convert.ConversionService;
import org.particleframework.core.naming.NameUtils;
import org.particleframework.core.reflect.ReflectionUtils;
import org.particleframework.core.type.Argument;
import org.particleframework.core.type.MutableArgumentValue;
import org.particleframework.core.type.ReturnType;
import org.particleframework.core.util.ArrayUtils;
import org.particleframework.core.util.StringUtils;
import org.particleframework.http.*;
import org.particleframework.http.annotation.Body;
import org.particleframework.http.annotation.Consumes;
import org.particleframework.http.annotation.Header;
import org.particleframework.http.annotation.HttpMethodMapping;
import org.particleframework.http.client.*;
import org.particleframework.http.client.exceptions.HttpClientException;
import org.particleframework.http.client.exceptions.HttpClientResponseException;
import org.particleframework.http.codec.MediaTypeCodec;
import org.particleframework.http.codec.MediaTypeCodecRegistry;
import org.particleframework.http.uri.UriMatchTemplate;
import org.particleframework.inject.MethodExecutionHandle;
import org.particleframework.inject.qualifiers.Qualifiers;
import org.particleframework.jackson.ObjectMapperFactory;
import org.particleframework.jackson.annotation.JacksonFeatures;
import org.particleframework.jackson.codec.JsonMediaTypeCodec;
import org.particleframework.runtime.ApplicationConfiguration;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PreDestroy;
import javax.inject.Singleton;
import java.io.Closeable;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/**
 * Introduction advice that implements the {@link Client} annotation
 *
 * @author graemerocher
 * @since 1.0
 */
@Singleton
public class HttpClientIntroductionAdvice implements MethodInterceptor<Object, Object>, Closeable, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(DefaultHttpClient.class);

    public static final MediaType[] DEFAULT_ACCEPT_TYPES = {MediaType.APPLICATION_JSON_TYPE};
    private final BeanContext beanContext;
    private final Map<Integer, ClientRegistration> clients = new ConcurrentHashMap<>();
    private final ReactiveClientResultTransformer[] transformers;
    private final LoadBalancerResolver loadBalancerResolver;


    public HttpClientIntroductionAdvice(
            BeanContext beanContext,
            LoadBalancerResolver loadBalancerResolver,
            ReactiveClientResultTransformer...transformers) {
        this.beanContext = beanContext;
        this.loadBalancerResolver = loadBalancerResolver;
        this.transformers = transformers != null ? transformers : new ReactiveClientResultTransformer[0];
    }

    @Override
    public Object intercept(MethodInvocationContext<Object, Object> context) {
        Client clientAnnotation = context.getAnnotation(Client.class);
        if(clientAnnotation == null) {
            throw new IllegalStateException("Client advice called from type that is not annotated with @Client: " + context);
        }


        ClientRegistration reg = getClient(context, clientAnnotation);
        Optional<Class<? extends Annotation>> httpMethodMapping = context.getAnnotationTypeByStereotype(HttpMethodMapping.class);
        if(httpMethodMapping.isPresent()) {
            String uri = context.getValue(HttpMethodMapping.class, String.class).orElse( "");
            Class<? extends Annotation> annotationType = httpMethodMapping.get();

            HttpMethod httpMethod = HttpMethod.valueOf(annotationType.getSimpleName().toUpperCase());

            ReturnType returnType = context.getReturnType();
            Class<?> javaReturnType = returnType.getType();


            String contextPath = reg.contextPath;
            UriMatchTemplate uriTemplate = UriMatchTemplate.of(contextPath != null ? contextPath : "/");
            if(!(uri.length() == 1 && uri.charAt(0) == '/')) {
                uriTemplate = uriTemplate.nest(uri);
            }

            Map<String, Object> paramMap = context.getParameterValueMap();
            List<String> uriVariables = uriTemplate.getVariables();

            boolean variableSatisfied = uriVariables.isEmpty() || uriVariables.containsAll(paramMap.keySet());
            MutableHttpRequest<Object> request;
            Object body = null;
            Map<String, MutableArgumentValue<?>> parameters = context.getParameters();
            Argument[] arguments = context.getArguments();
            Map<String,String> headers = new LinkedHashMap<>(3);
            List<Argument> bodyArguments = new ArrayList<>();
            for (Argument argument : arguments) {
                String argumentName = argument.getName();
                if(argument.isAnnotationPresent(Body.class)) {
                    body = parameters.get(argumentName).getValue();
                    break;
                }
                else if(argument.isAnnotationPresent(Header.class)) {

                    String headerName = argument.getAnnotation(Header.class).value();
                    if(StringUtils.isEmpty(headerName)) {
                        headerName = NameUtils.hyphenate(argumentName);
                    }
                    MutableArgumentValue<?> value = parameters.get(argumentName);
                    String finalHeaderName = headerName;
                    ConversionService.SHARED.convert(value.getValue(), String.class)
                            .ifPresent(o -> headers.put(finalHeaderName, o));
                }
                else if(!uriVariables.contains(argumentName)){
                    bodyArguments.add(argument);
                }
            }
            if(HttpMethod.permitsRequestBody(httpMethod)) {

                if(body == null && !bodyArguments.isEmpty()) {
                    Map<String,Object> bodyMap = new LinkedHashMap<>();

                    for (Argument bodyArgument : bodyArguments) {
                        String argumentName = bodyArgument.getName();
                        MutableArgumentValue<?> value = parameters.get(argumentName);
                        bodyMap.put(argumentName, value.getValue());
                    }

                    body = bodyMap;
                }

                if(body != null) {
                    if(!variableSatisfied) {

                        if(body instanceof Map) {
                            paramMap.putAll((Map)body);
                            uri = uriTemplate.expand(paramMap);
                            request = HttpRequest.create(httpMethod, uri);
                        }
                        else{
                            BeanMap<Object> beanMap = BeanMap.of(body);
                            for (Map.Entry<String, Object> entry : beanMap.entrySet()) {
                                String k = entry.getKey();
                                Object v = entry.getValue();
                                if(v != null) {
                                    paramMap.put( k, v );
                                }
                            }
                            uri = uriTemplate.expand(paramMap);
                            request = HttpRequest.create(httpMethod, uri);
                        }
                    }
                    else {
                        uri = uriTemplate.expand(paramMap);
                        request = HttpRequest.create(httpMethod, uri);
                    }
                    request.body(body);
                }
                else {
                    uri = uriTemplate.expand(paramMap);
                    request = HttpRequest.create(httpMethod, uri);
                }
            }
            else {
                uri = uriTemplate.expand(paramMap);
                request = HttpRequest.create(httpMethod, uri);
            }

            if(!headers.isEmpty()) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    request.header(entry.getKey(), entry.getValue());
                }
            }
            HttpClient httpClient = reg.httpClient;

            boolean isFuture = CompletableFuture.class.isAssignableFrom(javaReturnType);
            final Class<Object> methodDeclaringType = context.getDeclaringType();
            if(Publishers.isPublisher(javaReturnType) || isFuture) {
                Argument<?> publisherArgument = returnType.asArgument().getFirstTypeVariable().orElse(Argument.OBJECT_ARGUMENT);
                Class<?> argumentType = publisherArgument.getType();
                Publisher<?> publisher;
                if(HttpResponse.class.isAssignableFrom(argumentType)) {
                    request.accept( context.getValue(Consumes.class,MediaType[].class).orElse(DEFAULT_ACCEPT_TYPES));
                    publisher = httpClient.exchange(
                            request, returnType.asArgument().getFirstTypeVariable().orElse(Argument.OBJECT_ARGUMENT)
                    );
                }
                else if(Void.class.isAssignableFrom(argumentType)) {
                    publisher = httpClient.exchange(
                            request
                    );
                }
                else {
                    MediaType[] acceptTypes = context.getValue(Consumes.class, MediaType[].class).orElse(DEFAULT_ACCEPT_TYPES);
                    request.accept(acceptTypes);
                    publisher = httpClient.retrieve(
                            request, publisherArgument
                    );
                }

                if(isFuture) {
                    CompletableFuture<Object> future = new CompletableFuture<>();
                    publisher.subscribe(new CompletionAwareSubscriber<Object>() {
                        AtomicReference<Object> reference = new AtomicReference<>();
                        @Override
                        protected void doOnSubscribe(Subscription subscription) {
                            subscription.request(1);
                        }

                        @Override
                        protected void doOnNext(Object message) {
                            if(!Void.class.isAssignableFrom(argumentType)) {
                                reference.set(message);
                            }
                        }

                        @Override
                        protected void doOnError(Throwable t) {
                            if(t instanceof HttpClientResponseException) {
                                HttpClientResponseException e = (HttpClientResponseException) t;
                                if( e.getStatus() == HttpStatus.NOT_FOUND) {
                                    future.complete(null);
                                    return;
                                }
                            }
                            if(LOG.isErrorEnabled()) {
                                LOG.error("Client ["+ methodDeclaringType.getName()+"] received HTTP error response: " + t.getMessage(), t);
                            }

                            Optional<MethodExecutionHandle<Object>> fallbackMethod = findFallbackMethod(methodDeclaringType, context);
                            if(fallbackMethod.isPresent()) {

                                if(LOG.isDebugEnabled()) {
                                    LOG.debug("Client [{}] resolved fallback: {}", methodDeclaringType.getName(), fallbackMethod.get() );
                                }

                                try {
                                    CompletableFuture<Object> resultingFuture = (CompletableFuture) fallbackMethod.get()
                                                                                                        .invoke(context.getParameterValues());
                                    resultingFuture.whenComplete((o, throwable) -> {
                                        if(throwable == null) {
                                            future.complete(o);
                                        }
                                        else {
                                            future.completeExceptionally(throwable);
                                        }
                                    });
                                } catch (Exception e) {
                                    future.completeExceptionally(new HttpClientException("Error invoking fallback for type ["+ methodDeclaringType +"]: " + e.getMessage() ,e));
                                }

                            }
                            else {
                                future.completeExceptionally(t);
                            }
                        }

                        @Override
                        protected void doOnComplete() {
                            future.complete(reference.get());
                        }
                    });
                    return future;
                }
                else {
                    Object finalPublisher = ConversionService.SHARED.convert(publisher, javaReturnType).orElseThrow(() ->
                            new HttpClientException("Cannot convert response publisher to Reactive type (Unsupported Reactive type): " + javaReturnType)
                    );
                    for (ReactiveClientResultTransformer transformer : transformers) {
                        finalPublisher = transformer.transform(finalPublisher, ()-> findFallbackMethod(methodDeclaringType, context), context.getParameterValues());
                    }
                    return finalPublisher;
                }
            }

            else {
                BlockingHttpClient blockingHttpClient = httpClient.toBlocking();
                if(HttpResponse.class.isAssignableFrom(javaReturnType)) {
                    return blockingHttpClient.exchange(
                            request, returnType.asArgument().getFirstTypeVariable().orElse(Argument.OBJECT_ARGUMENT)
                    );
                }
                else if(void.class == javaReturnType) {
                    try {
                        blockingHttpClient.exchange(request);
                        return null;
                    } catch (HttpClientException e) {
                        return handleFallback(context, methodDeclaringType, e);
                    }
                }
                else {
                    try {
                        return blockingHttpClient.retrieve(
                                request, returnType.asArgument()
                        );
                    } catch (HttpClientException t) {
                        if( t instanceof HttpClientResponseException && ((HttpClientResponseException)t).getStatus() == HttpStatus.NOT_FOUND) {
                            if(javaReturnType == Optional.class) {
                                return Optional.empty();
                            }
                            return null;
                        }
                        return handleFallback(context, methodDeclaringType, t);
                    }
                }
            }
        }
        // try other introduction advice
        return context.proceed();
    }

    protected Object handleFallback(MethodInvocationContext<Object, Object> context, Class<Object> methodDeclaringType, HttpClientException t) throws HttpClientException {
        if(LOG.isErrorEnabled()) {
            LOG.error("Client ["+ methodDeclaringType.getName()+"] received HTTP error response: " + t.getMessage(), t);
        }

        Optional<MethodExecutionHandle<Object>> fallback = findFallbackMethod(methodDeclaringType, context);
        if(fallback.isPresent())  {
            MethodExecutionHandle<Object> fallbackMethod = fallback.get();
            try {
                if(LOG.isDebugEnabled()) {
                    LOG.debug("Client [{}] resolved fallback: {}", methodDeclaringType.getName(), fallbackMethod );
                }
                return fallbackMethod.invoke(context.getParameterValues());
            } catch (Exception e) {
                throw new HttpClientException("Error invoking fallback for type ["+ methodDeclaringType +"]: " + e.getMessage() ,e);
            }
        }
        else {
            throw t;
        }
    }

    protected Optional<MethodExecutionHandle<Object>> findFallbackMethod(Class<Object> declaringType, MethodInvocationContext<Object, Object> context) {
        Optional<MethodExecutionHandle<Object>> result = beanContext
                .findExecutionHandle(declaringType, Qualifiers.byStereotype(Fallback.class), context.getMethodName(), context.getArgumentTypes());
        if(!result.isPresent()) {
            Set<Class> allInterfaces = ReflectionUtils.getAllInterfaces(declaringType);
            for (Class i : allInterfaces) {
                result = beanContext
                            .findExecutionHandle(i, Qualifiers.byStereotype(Fallback.class), context.getMethodName(), context.getArgumentTypes());
                if(result.isPresent()) {
                    return result;
                }
            }
        }
        return result;
    }

    private ClientRegistration getClient(MethodInvocationContext<Object, Object> context, Client clientAnn) {
        String[] clientId = clientAnn.value();

        return clients.computeIfAbsent(Arrays.hashCode(clientId), integer -> {
            LoadBalancer loadBalancer = loadBalancerResolver.resolve(clientId)
                                                                  .orElseThrow(()->
                                                                          new HttpClientException("Invalid service reference ["+ArrayUtils.toString((Object[]) clientId)+"] specified to @Client")
                                                                  );
            String contextPath = "";
            String path = clientAnn.path();
            if(StringUtils.isNotEmpty(path)) {
                contextPath = path;
            }
            else if(ArrayUtils.isNotEmpty(clientId) && clientId[0].startsWith("/")) {
                contextPath = clientId[0];
            }
            HttpClientConfiguration configuration = beanContext.getBean(clientAnn.configuration());
            HttpClient client = beanContext.createBean(HttpClient.class, loadBalancer, configuration);
            if(client instanceof DefaultHttpClient) {
                DefaultHttpClient defaultClient = (DefaultHttpClient) client;
                defaultClient.setClientIdentifiers(clientId);
                JacksonFeatures jacksonFeatures = context.getAnnotation(JacksonFeatures.class);

                if(jacksonFeatures != null) {
                    Optional<MediaTypeCodec> existingCodec = defaultClient.getMediaTypeCodecRegistry().findCodec(MediaType.APPLICATION_JSON_TYPE);
                    ObjectMapper objectMapper = null;
                    if(existingCodec.isPresent()) {
                        MediaTypeCodec existing = existingCodec.get();
                        if(existing instanceof JsonMediaTypeCodec) {
                            objectMapper = ((JsonMediaTypeCodec) existing).getObjectMapper().copy();
                        }
                    }
                    if(objectMapper == null) {
                        objectMapper = new ObjectMapperFactory().objectMapper(Optional.empty(), Optional.empty());
                    }

                    for (SerializationFeature serializationFeature : jacksonFeatures.enabledSerializationFeatures()) {
                        objectMapper.configure(serializationFeature, true);
                    }

                    for (DeserializationFeature serializationFeature : jacksonFeatures.enabledDeserializationFeatures()) {
                        objectMapper.configure(serializationFeature, true);
                    }

                    for (SerializationFeature serializationFeature : jacksonFeatures.disabledSerializationFeatures()) {
                        objectMapper.configure(serializationFeature, false);
                    }

                    for (DeserializationFeature feature : jacksonFeatures.disabledDeserializationFeatures()) {
                        objectMapper.configure(feature, false);
                    }

                    defaultClient.setMediaTypeCodecRegistry(MediaTypeCodecRegistry.of(new JsonMediaTypeCodec(objectMapper, beanContext.getBean(ApplicationConfiguration.class))));
                }
            }
            return new ClientRegistration(client, contextPath);
        });
    }


    @Override
    @PreDestroy
    public void close() throws IOException {
        for (ClientRegistration registration : clients.values()) {
            HttpClient httpClient = registration.httpClient;
            httpClient.close();
        }
    }

    class ClientRegistration {
        final HttpClient httpClient;
        final String contextPath;

        public ClientRegistration(HttpClient httpClient, String contextPath) {
            this.httpClient = httpClient;
            this.contextPath = contextPath;
        }
    }
}
