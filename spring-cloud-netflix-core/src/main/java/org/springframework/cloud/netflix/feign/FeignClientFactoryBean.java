/*
 * Copyright 2013-2017 the original author or authors.
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

import java.util.Map;
import java.util.Objects;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.cloud.netflix.feign.ribbon.LoadBalancerFeignClient;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import feign.Client;
import feign.Contract;
import feign.Feign;
import feign.Logger;
import feign.Request;
import feign.RequestInterceptor;
import feign.Retryer;
import feign.Target.HardCodedTarget;
import feign.codec.Decoder;
import feign.codec.Encoder;
import feign.codec.ErrorDecoder;

/**
 * 保存了@FeignClient所有属性的值 因为要根据这些属性的值完成Feign动态代理的构造
 * <p>
 * 在Spring容器初始化的某个过程中 调用这个工厂Bean的某个方法 创建和获取到@FeignClient注解过的接口类对应的动态代理 放入Spring容器中 接口调用方注入的是该接口接口对应的动态代理
 *
 * @author Spencer Gibb
 * @author Venil Noronha
 * @author Eko Kurniawan Khannedy
 * @author Gregor Zurowski
 */
class FeignClientFactoryBean implements FactoryBean<Object>, InitializingBean,
		ApplicationContextAware {
	/***********************************
	 * WARNING! Nothing in this class should be @Autowired. It causes NPEs because of some lifecycles race condition.
	 ***********************************/

	private Class<?> type;

	private String name;

	private String url;

	private String path;

	private boolean decode404;

	private ApplicationContext applicationContext;

	private Class<?> fallback = void.class;

	private Class<?> fallbackFactory = void.class;

	@Override
	public void afterPropertiesSet() {
		Assert.hasText(this.name, "Name must be set");
	}

	@Override
	public void setApplicationContext(ApplicationContext context) throws BeansException {
		this.applicationContext = context;
	}

	/**
	 * 被 {@link #getObject()} 调用
	 *
	 * @param context
	 * @return
	 */
	protected Feign.Builder feign(FeignContext context) {
		// 根据服务名称(ServiceA)去FeignContext获取对应的spring容器 再从spring容器中获取对应的类型为FeignLoggerFactory的Bean(使用的是默认的DefaultFeignLoggerFactory)
		FeignLoggerFactory loggerFactory = get(context, FeignLoggerFactory.class);
		// 通过FeignLoggerFactory创建了一个Logger对象(Feign关联的日志记录组件 默认是Slf4j的Logger)
		Logger logger = loggerFactory.create(this.type);

		// @formatter:off
		// 构造Builder对象
		// 在生产环境 我们一般都是启用 feign.hystrix.enabled 的(即feign一定是跟hystrix整合起来用的)
		// 但在默认的情况下 Feign.Builder用的就是feign自己原生的这个Feign.Builder(FeignClientsConfiguration.feignBuilder 方法构造) 是不跟hystrix有关系的
		Feign.Builder builder = get(context, Feign.Builder.class)
				// required values
				// 构建复杂对象 赋值Logger对象
				.logger(logger)
				.encoder(get(context, Encoder.class))
				.decoder(get(context, Decoder.class))
				.contract(get(context, Contract.class));
		// @formatter:on
		// 基于参数对Feign.Builder进行配置
		configureFeign(context, builder);

		return builder;
	}

	/**
	 * 对Feign.Builder进行配置
	 * 被 {@link #feign(FeignContext)} 调用
	 *
	 * @param context
	 * @param builder
	 */
	protected void configureFeign(FeignContext context, Feign.Builder builder) {
		// 读取application.yml中的feign.client打头的一些参数 包括了connectionTimeout readTimeout之类的参数
		FeignClientProperties properties = applicationContext.getBean(FeignClientProperties.class);
		if (properties != null) {
			if (properties.isDefaultToProperties()) {
				// (如Logger.Level | Retryer | ErrorDecoder | Request.Options | RequestInterceptors)
				// 读取业务代码中编写的Configuration中的一些配置(优先级最低)
				configureUsingConfiguration(context, builder);
				// 读取application.yml中feign.client打头的default参数配置(优先级中等)
				configureUsingProperties(properties.getConfig().get(properties.getDefaultConfig()), builder);
				// 读取application.yml中feign.client打头的服务相关参数配置(优先级最高)
				configureUsingProperties(properties.getConfig().get(this.name), builder);
			} else {
				configureUsingProperties(properties.getConfig().get(properties.getDefaultConfig()), builder);
				configureUsingProperties(properties.getConfig().get(this.name), builder);
				configureUsingConfiguration(context, builder);
			}
		} else {
			configureUsingConfiguration(context, builder);
		}
	}

	/**
	 *  对Feign.Builder进行配置
	 *  被 {@link #configureFeign(FeignContext, Feign.Builder)} 调用
	 *
	 * @param context
	 * @param builder
	 */
	protected void configureUsingConfiguration(FeignContext context, Feign.Builder builder) {
		Logger.Level level = getOptional(context, Logger.Level.class);
		if (level != null) {
			builder.logLevel(level);
		}
		Retryer retryer = getOptional(context, Retryer.class);
		if (retryer != null) {
			builder.retryer(retryer);
		}
		ErrorDecoder errorDecoder = getOptional(context, ErrorDecoder.class);
		if (errorDecoder != null) {
			builder.errorDecoder(errorDecoder);
		}
		Request.Options options = getOptional(context, Request.Options.class);
		if (options != null) {
			builder.options(options);
		}
		Map<String, RequestInterceptor> requestInterceptors = context.getInstances(
				this.name, RequestInterceptor.class);
		if (requestInterceptors != null) {
			builder.requestInterceptors(requestInterceptors.values());
		}

		if (decode404) {
			builder.decode404();
		}
	}

	/**
	 *  对Feign.Builder进行配置
	 *  被 {@link #configureFeign(FeignContext, Feign.Builder)} 调用
	 *
	 * @param config
	 * @param builder
	 */
	protected void configureUsingProperties(FeignClientProperties.FeignClientConfiguration config, Feign.Builder builder) {
		if (config == null) {
			return;
		}

		if (config.getLoggerLevel() != null) {
			builder.logLevel(config.getLoggerLevel());
		}

		if (config.getConnectTimeout() != null && config.getReadTimeout() != null) {
			builder.options(new Request.Options(config.getConnectTimeout(), config.getReadTimeout()));
		}

		if (config.getRetryer() != null) {
			Retryer retryer = getOrInstantiate(config.getRetryer());
			builder.retryer(retryer);
		}

		if (config.getErrorDecoder() != null) {
			ErrorDecoder errorDecoder = getOrInstantiate(config.getErrorDecoder());
			builder.errorDecoder(errorDecoder);
		}

		if (config.getRequestInterceptors() != null && !config.getRequestInterceptors().isEmpty()) {
			// this will add request interceptor to builder, not replace existing
			for (Class<RequestInterceptor> bean : config.getRequestInterceptors()) {
				RequestInterceptor interceptor = getOrInstantiate(bean);
				builder.requestInterceptor(interceptor);
			}
		}

		if (config.getDecode404() != null) {
			if (config.getDecode404()) {
				builder.decode404();
			}
		}
	}

	/**
	 * 被 {@link #configureUsingProperties(FeignClientProperties.FeignClientConfiguration, Feign.Builder)} 调用
	 *
	 * @param tClass
	 * @param <T>
	 * @return
	 */
	private <T> T getOrInstantiate(Class<T> tClass) {
		try {
			return applicationContext.getBean(tClass);
		} catch (NoSuchBeanDefinitionException e) {
			return BeanUtils.instantiateClass(tClass);
		}
	}

	protected <T> T get(FeignContext context, Class<T> type) {
		T instance = context.getInstance(this.name, type);
		if (instance == null) {
			throw new IllegalStateException("No bean found of type " + type + " for "
					+ this.name);
		}
		return instance;
	}

	protected <T> T getOptional(FeignContext context, Class<T> type) {
		return context.getInstance(this.name, type);
	}

	protected <T> T loadBalance(Feign.Builder builder, FeignContext context,
			HardCodedTarget<T> target) {
		// 根据服务名称(ServiceA)去FeignContext获取对应的spring容器 再从spring容器中获取类型为Client的Bean
		// Client类型的Bean是在 DefaultFeignLoadBalancedConfiguration.feignClient 方法中进行构造的
		Client client = getOptional(context, Client.class);
		if (client != null) {
			// 将Client设置到Feign.Builder中
			builder.client(client);
			// 负责生成动态代理的组件
			// Target组件是在 FeignAutoConfiguration 类中进行构造的(HystrixFeign在Feign的源码中):
			// 		1. 如果有 feign.hystrix.HystrixFeign 类的话 调用的是 FeignAutoConfiguration.HystrixFeignTargeterConfiguration.feignTargeter 获取Targeter组件(默认使用HystrixTargeter)
			// 		2. 如果没有 feign.hystrix.HystrixFeign 类的话 调用的是 FeignAutoConfiguration.DefaultFeignTargeterConfiguration.feignTargeter 获取Targeter组件
			// HystrixTargeter 是用来跟feign和hystrix整合使用的 在发送请求的时候基于hystrix可以实现熔断 限流 降级
			Targeter targeter = get(context, Targeter.class);
			return targeter.target(this, builder, context, target);
		}

		throw new IllegalStateException(
				"No Feign Client for loadBalancing defined. Did you forget to include spring-cloud-starter-netflix-ribbon?");
	}

	/**
	 * Override注解 一般是实现的接口或者父类定义的抽象方法 这种@Override的方法 一般是作为调用方提供出去的
	 * 所以 FeignClientFactoryBean 的入口方法极有可能是 getObject方法
	 *
	 * @return
	 */
	@Override
	public Object getObject() {
		// Ribbon里面有个一SpringClientFactory
		// 就是对每个服务的调用 都有一个独立的ILoadBalancer ILoadBalancer里面的IRule | IPing都是独立的组件
		// 也就是说当时Ribbon用了一个SpringClientFactory 每个服务都对应一个独立的spring容器 从独立的spring容器中 可以取出这个服务关联的属于自己的LoadBalancer之类的东西
		// Feign也是相同的道理:
		// 如果想要调用一个服务(ServiceA)的话 那么该服务(ServiceA)就会关联一个独立的spring容器 FeignContext代表了一个独立的容器 关联着一些独立组件(如Logger | Decoder | Encoder)
		// FeignContext内部(其实是父类NamedContextFactory)维护一个map 负责对每个服务都维护一个对应的spring容器的(就是一个服务对应一个spring容器)
		// FeignContext 实际上是在FeignAutoConfiguration中注入的
		FeignContext context = applicationContext.getBean(FeignContext.class);
		Feign.Builder builder = feign(context);

		// 如果在@FeignClient没有配置url属性 就会自动和Ribbon关联起来 使用Ribbon进行负载均衡
		if (!StringUtils.hasText(this.url)) {
			// 为Ribbon构造url地址 http://ServiceA
			String url;
			if (!this.name.startsWith("http")) {
				url = "http://" + this.name;
			}
			else {
				url = this.name;
			}
			// 如果访问的是ServiceA的某一类接口 @FeignClient(value = "ServiceA", path = "/user") 在拼接请求url地址的时候 就会拼接成 http://ServiceA/user
			url += cleanPath();
			// 使用Ribbon支持负载均衡的一个组件 生成一个动态代理对象
			// 构造了一个HardCodedTarget(即硬编码的Target) 里面包含了接口类型(ServiceAClient) 服务名称(ServiceA) url地址(http://ServiceA)
			// 再将这个Target和Feign.Builder FeignContext 一起作为参数 调用loadBalance方法
			return loadBalance(builder, context, new HardCodedTarget<>(this.type, this.name, url));
		}
		if (StringUtils.hasText(this.url) && !this.url.startsWith("http")) {
			this.url = "http://" + this.url;
		}
		String url = this.url + cleanPath();
		Client client = getOptional(context, Client.class);
		if (client != null) {
			if (client instanceof LoadBalancerFeignClient) {
				// not lod balancing because we have a url,
				// but ribbon is on the classpath, so unwrap
				client = ((LoadBalancerFeignClient)client).getDelegate();
			}
			builder.client(client);
		}
		// 动态代理
		// 就是在spring容器初始化的时候 被作为入口来调用 然后创建了一个ServiceAClient的动态代理 返回给spring容器 并注册到spring容器
		Targeter targeter = get(context, Targeter.class);
		return targeter.target(this, builder, context, new HardCodedTarget<>(
				this.type, this.name, url));
	}

	private String cleanPath() {
		String path = this.path.trim();
		if (StringUtils.hasLength(path)) {
			if (!path.startsWith("/")) {
				path = "/" + path;
			}
			if (path.endsWith("/")) {
				path = path.substring(0, path.length() - 1);
			}
		}
		return path;
	}

	@Override
	public Class<?> getObjectType() {
		return this.type;
	}

	@Override
	public boolean isSingleton() {
		return true;
	}

	public Class<?> getType() {
		return type;
	}

	public void setType(Class<?> type) {
		this.type = type;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getUrl() {
		return url;
	}

	public void setUrl(String url) {
		this.url = url;
	}

	public String getPath() {
		return path;
	}

	public void setPath(String path) {
		this.path = path;
	}

	public boolean isDecode404() {
		return decode404;
	}

	public void setDecode404(boolean decode404) {
		this.decode404 = decode404;
	}

	public ApplicationContext getApplicationContext() {
		return applicationContext;
	}

	public Class<?> getFallback() {
		return fallback;
	}

	public void setFallback(Class<?> fallback) {
		this.fallback = fallback;
	}

	public Class<?> getFallbackFactory() {
		return fallbackFactory;
	}

	public void setFallbackFactory(Class<?> fallbackFactory) {
		this.fallbackFactory = fallbackFactory;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		FeignClientFactoryBean that = (FeignClientFactoryBean) o;
		return Objects.equals(applicationContext, that.applicationContext) &&
				decode404 == that.decode404 &&
				Objects.equals(fallback, that.fallback) &&
				Objects.equals(fallbackFactory, that.fallbackFactory) &&
				Objects.equals(name, that.name) &&
				Objects.equals(path, that.path) &&
				Objects.equals(type, that.type) &&
				Objects.equals(url, that.url);
	}

	@Override
	public int hashCode() {
		return Objects.hash(applicationContext, decode404, fallback, fallbackFactory,
				name, path, type, url);
	}

	@Override
	public String toString() {
		return new StringBuilder("FeignClientFactoryBean{")
				.append("type=").append(type).append(", ")
				.append("name='").append(name).append("', ")
				.append("url='").append(url).append("', ")
				.append("path='").append(path).append("', ")
				.append("decode404=").append(decode404).append(", ")
				.append("applicationContext=").append(applicationContext).append(", ")
				.append("fallback=").append(fallback).append(", ")
				.append("fallbackFactory=").append(fallbackFactory)
				.append("}").toString();
	}

}
