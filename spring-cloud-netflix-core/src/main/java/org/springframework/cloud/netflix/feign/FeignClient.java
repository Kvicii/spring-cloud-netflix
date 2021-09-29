/*
 * Copyright 2013-2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.netflix.feign;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.core.annotation.AliasFor;

/**
 * Annotation for interfaces declaring that a REST client with that interface should be
 * created (e.g. for autowiring into another component). If ribbon is available it will be
 * used to load balance the backend requests, and the load balancer can be configured
 * using a <code>@RibbonClient</code> with the same name (i.e. value) as the feign client.
 * <p>
 * 使用@FeignClient标注了一个接口 这个接口会被创建为一个REST Client(即发送RESTful请求的客户端)
 * 可以将这个REST Client注入其他组件
 * 如果启用了Ribbon 就会采用负载均衡的方式 进行HTTP请求的发送 可以用@RibbonClient标注一个配置类 在配置类中可以定义Ribbon的ILoadBalancer
 * <p>
 * 用@FeignClient标注一个接口 就是让Feign对这个接口创建一个对应的动态代理 这个动态代理就是REST Client(发送REST请求的客户端)
 *
 * @author Spencer Gibb
 * @author Venil Noronha
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface FeignClient {

	/**
	 * The name of the service with optional protocol prefix. Synonym for {@link #name()
	 * name}. A name must be specified for all clients, whether or not a url is provided.
	 * Can be specified as property key, eg: ${propertyKey}.
	 */
	@AliasFor("name")
	String value() default "";

	/**
	 * The service id with optional protocol prefix. Synonym for {@link #value() value}.
	 *
	 * @deprecated use {@link #name() name} instead
	 */
	@Deprecated
	String serviceId() default "";

	/**
	 * The service id with optional protocol prefix. Synonym for {@link #value() value}.
	 * <p>
	 * 和value指定的意义一致 都是服务名称
	 */
	@AliasFor("value")
	String name() default "";
	
	/**
	 * Sets the <code>@Qualifier</code> value for the feign client.
	 */
	String qualifier() default "";

	/**
	 * An absolute URL or resolvable hostname (the protocol is optional).
	 * <p>
	 * 如果不用ribbon的话 那么就没法做负载均衡了 可以就用url地址指定要请求的地址
	 */
	String url() default "";

	/**
	 * Whether 404s should be decoded instead of throwing FeignExceptions
	 * <p>
	 * 就是说用404替代抛出FeignException异常
	 */
	boolean decode404() default false;

	/**
	 * A custom <code>@Configuration</code> for the feign client. Can contain override
	 * <code>@Bean</code> definition for the pieces that make up the client, for instance
	 * {@link feign.codec.Decoder}, {@link feign.codec.Encoder}, {@link feign.Contract}.
	 *
	 * @see FeignClientsConfiguration for the defaults
	 *
	 * 指定一个配置类 可以自定义Encoder | Decoder | Contract
	 */
	Class<?>[] configuration() default {};

	/**
	 * Fallback class for the specified Feign client interface. The fallback class must
	 * implement the interface annotated by this annotation and be a valid spring bean.
	 */
	Class<?> fallback() default void.class;

	/**
	 * Define a fallback factory for the specified Feign client interface. The fallback
	 * factory must produce instances of fallback classes that implement the interface
	 * annotated by {@link FeignClient}. The fallback factory must be a valid spring
	 * bean.
	 *
	 * @see feign.hystrix.FallbackFactory for details.
	 */
	Class<?> fallbackFactory() default void.class;

	/**
	 * Path prefix to be used by all method-level mappings. Can be used with or without
	 * <code>@RibbonClient</code>.
	 */
	String path() default "";

	/**
	 * Whether to mark the feign proxy as a primary bean. Defaults to true.
	 */
	boolean primary() default true;

}
