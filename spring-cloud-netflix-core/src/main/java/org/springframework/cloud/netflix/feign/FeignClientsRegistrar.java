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

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionReaderUtils;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.ResourceLoaderAware;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.ClassMetadata;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.core.type.filter.AbstractClassTestingTypeFilter;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.core.type.filter.TypeFilter;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

/**
 * 负责FeignClient注册的一个组件
 * 负责扫描所有包下面的@FeignClient注解的接口 然后触发后面所有的处理流程
 *
 * @author Spencer Gibb
 * @author Jakub Narloch
 * @author Venil Noronha
 */
class FeignClientsRegistrar implements ImportBeanDefinitionRegistrar,
		ResourceLoaderAware, BeanClassLoaderAware, EnvironmentAware {

	// patterned after Spring Integration IntegrationComponentScanRegistrar
	// and RibbonClientsConfigurationRegistgrar

	private ResourceLoader resourceLoader;

	private ClassLoader classLoader;

	private Environment environment;

	public FeignClientsRegistrar() {
	}

	@Override
	public void setResourceLoader(ResourceLoader resourceLoader) {
		this.resourceLoader = resourceLoader;
	}

	@Override
	public void setBeanClassLoader(ClassLoader classLoader) {
		this.classLoader = classLoader;
	}

	/**
	 * 继承自spring-context项目ImportBeanDefinitionRegistrar#registerBeanDefinitions
	 * 在spring boot启动时 spring容器初始化的时候 一定会在某个时间点 会对实现这个接口的类的registerBeanDefinitions方法进行调用 来实例化一些bean 注册到spring容器里去。
	 * <p>
	 * 在spring boot启动时 FeignClientsRegistrar#registerBeanDefinitions将会作为扫描@FeignClient注解的入口
	 *
	 * @param metadata
	 * @param registry
	 */
	@Override
	public void registerBeanDefinitions(AnnotationMetadata metadata,
			BeanDefinitionRegistry registry) {
		registerDefaultConfiguration(metadata, registry);
		registerFeignClients(metadata, registry);
	}

	/**
	 * 解析相关的一些配置 注册为Spring的Bean
	 *
	 * @param metadata
	 * @param registry
	 */
	private void registerDefaultConfiguration(AnnotationMetadata metadata,
			BeanDefinitionRegistry registry) {
		// 获取@EnableFeignClients这个注解的所有属性的值
		Map<String, Object> defaultAttrs = metadata
				.getAnnotationAttributes(EnableFeignClients.class.getName(), true);

		// 获取Application启动类的全限定名
		if (defaultAttrs != null && defaultAttrs.containsKey("defaultConfiguration")) {
			String name;
			if (metadata.hasEnclosingClass()) {
				name = "default." + metadata.getEnclosingClassName();
			} else {
				name = "default." + metadata.getClassName();
			}
			registerClientConfiguration(registry, name,
					defaultAttrs.get("defaultConfiguration"));
		}
	}

	/**
	 * 扫描各个包下面的@FeignClient注解 然后生成@FeignClient的动态代理 注册这些@FeignClient
	 *
	 * 1.会对所有包进行扫描 扫描包含@FeignClient注解的接口类
	 *
	 * @param metadata
	 * @param registry
	 */
	public void registerFeignClients(AnnotationMetadata metadata,
			BeanDefinitionRegistry registry) {
		// 获取一个Scanner 在classpath中扫描候选组件的Provider类 负责在指定的路径中扫描指定条件的相关Bean
		ClassPathScanningCandidateComponentProvider scanner = getScanner();
		scanner.setResourceLoader(this.resourceLoader);

		Set<String> basePackages;

		// 获取了@EnableFeignClients里面的属性 因为这个属性里是可以配置basePackages(即要扫描的包路径)
		Map<String, Object> attrs = metadata
				.getAnnotationAttributes(EnableFeignClients.class.getName());
		// 根据注解的类型进行过滤的过滤器
		// 用于过滤出来@FeignClient注解类型的过滤器
		AnnotationTypeFilter annotationTypeFilter = new AnnotationTypeFilter(FeignClient.class);
		// clients属性一般是空的(一般不配置这个属性)
		final Class<?>[] clients = attrs == null ? null : (Class<?>[]) attrs.get("clients");
		if (clients == null || clients.length == 0) {
			// scanner添加一个专门过滤@FeignClient注解类型的过滤器
			scanner.addIncludeFilter(annotationTypeFilter);
			// @EnableFeignClients没有配置basePackages属性 自动生成需要的basePackages
			// 如果@EnableFeignClients没有配置需要扫描的包路径 那默认就是Spring Boot启动类所在的包
			basePackages = getBasePackages(metadata);
		} else {
			final Set<String> clientClasses = new HashSet<>();
			basePackages = new HashSet<>();
			for (Class<?> clazz : clients) {
				basePackages.add(ClassUtils.getPackageName(clazz));
				clientClasses.add(clazz.getCanonicalName());
			}
			AbstractClassTestingTypeFilter filter = new AbstractClassTestingTypeFilter() {
				@Override
				protected boolean match(ClassMetadata metadata) {
					String cleaned = metadata.getClassName().replaceAll("\\$", ".");
					return clientClasses.contains(cleaned);
				}
			};
			scanner.addIncludeFilter(new AllTypeFilter(Arrays.asList(filter, annotationTypeFilter)));
		}

		// 对所有包路径进行扫描
		for (String basePackage : basePackages) {
			// 使用了整合了@FeignClient过滤器的scanner扫描包路径中包含@FeignClient注解的接口 扫描出来符合条件的BeanDefinition
			// getScanner时会覆盖isCandidateComponent方法 在扫描类时 每扫到一个类 就会交给该方法去判断是否是需要的类(isCandidateComponent方法默认都返回true)
			Set<BeanDefinition> candidateComponents = scanner
					.findCandidateComponents(basePackage);
			for (BeanDefinition candidateComponent : candidateComponents) {
				// 扫描出来的Bean必须是被注解标注过的Bean
				if (candidateComponent instanceof AnnotatedBeanDefinition) {
					// verify annotated class is an interface
					AnnotatedBeanDefinition beanDefinition = (AnnotatedBeanDefinition) candidateComponent;
					AnnotationMetadata annotationMetadata = beanDefinition.getMetadata();
					// @FeignClient标注的必须是一个接口
					Assert.isTrue(annotationMetadata.isInterface(),
							"@FeignClient can only be specified on an interface");
					// 获取到@FeignClient注解中的元数据
					Map<String, Object> attributes = annotationMetadata
							.getAnnotationAttributes(FeignClient.class.getCanonicalName());
					// 服务名称
					String name = getClientName(attributes);
					// 将服务名称 + @FeignClient配置 在BeanDefinitionRegistry注册
					registerClientConfiguration(registry, name,
							attributes.get("configuration"));
					// 基于这个方法完成扫描出来的@FeignClient相关接口的注册
					registerFeignClient(registry, annotationMetadata, attributes);
				}
			}
		}
	}

	/**
	 * 完成扫描出来的@FeignClient相关接口的注册
	 *
	 * @param registry
	 * @param annotationMetadata
	 * @param attributes         @FeignClient注解中的属性
	 */
	private void registerFeignClient(BeanDefinitionRegistry registry,
			AnnotationMetadata annotationMetadata, Map<String, Object> attributes) {
		// 获取接口的全限定类名
		String className = annotationMetadata.getClassName();
		// 构造器对象 会将@FeignClient注解的属性以及备注解的接口相关的条件都放到BeanDefinition中
		// FeignClientFactoryBean是用来创建核心的FeignClient组件的工厂Bean
		BeanDefinitionBuilder definition = BeanDefinitionBuilder
				.genericBeanDefinition(FeignClientFactoryBean.class);
		validate(attributes);
		definition.addPropertyValue("url", getUrl(attributes));
		definition.addPropertyValue("path", getPath(attributes));
		// 服务名称
		String name = getName(attributes);
		definition.addPropertyValue("name", name);
		definition.addPropertyValue("type", className);
		definition.addPropertyValue("decode404", attributes.get("decode404"));
		definition.addPropertyValue("fallback", attributes.get("fallback"));
		definition.addPropertyValue("fallbackFactory", attributes.get("fallbackFactory"));
		definition.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_BY_TYPE);

		// 基于接口类名构造一个别名
		String alias = name + "FeignClient";
		AbstractBeanDefinition beanDefinition = definition.getBeanDefinition();

		boolean primary = (Boolean)attributes.get("primary"); // has a default, won't be null

		beanDefinition.setPrimary(primary);

		String qualifier = getQualifier(attributes);
		if (StringUtils.hasText(qualifier)) {
			alias = qualifier;
		}

		// 基于被@FeignClient标注的接口类名 别名和BeanDefinition 构造一个 BeanDefinitionHolder
		BeanDefinitionHolder holder = new BeanDefinitionHolder(beanDefinition, className,
				new String[] { alias });
		// 基于BeanDefinitionRegistry和BeanDefinitionHolder完成BeanDefinition的注册
		BeanDefinitionReaderUtils.registerBeanDefinition(holder, registry);
	}

	private void validate(Map<String, Object> attributes) {
		AnnotationAttributes annotation = AnnotationAttributes.fromMap(attributes);
		// This blows up if an aliased property is overspecified
		annotation.getAliasedString("name", FeignClient.class, null);
	}

	/* for testing */ String getName(Map<String, Object> attributes) {
		String name = (String) attributes.get("serviceId");
		if (!StringUtils.hasText(name)) {
			name = (String) attributes.get("name");
		}
		if (!StringUtils.hasText(name)) {
			name = (String) attributes.get("value");
		}
		name = resolve(name);
		if (!StringUtils.hasText(name)) {
			return "";
		}

		String host = null;
		try {
			String url;
			if (!name.startsWith("http://") && !name.startsWith("https://")) {
				url = "http://" + name;
			} else {
				url = name;
			}
			host = new URI(url).getHost();

		}
		catch (URISyntaxException e) {
		}
		Assert.state(host != null, "Service id not legal hostname (" + name + ")");
		return name;
	}

	private String resolve(String value) {
		if (StringUtils.hasText(value)) {
			return this.environment.resolvePlaceholders(value);
		}
		return value;
	}

	private String getUrl(Map<String, Object> attributes) {
		String url = resolve((String) attributes.get("url"));
		if (StringUtils.hasText(url) && !(url.startsWith("#{") && url.contains("}"))) {
			if (!url.contains("://")) {
				url = "http://" + url;
			}
			try {
				new URL(url);
			}
			catch (MalformedURLException e) {
				throw new IllegalArgumentException(url + " is malformed", e);
			}
		}
		return url;
	}

	private String getPath(Map<String, Object> attributes) {
		String path = resolve((String) attributes.get("path"));
		if (StringUtils.hasText(path)) {
			path = path.trim();
			if (!path.startsWith("/")) {
				path = "/" + path;
			}
			if (path.endsWith("/")) {
				path = path.substring(0, path.length() - 1);
			}
		}
		return path;
	}

	protected ClassPathScanningCandidateComponentProvider getScanner() {
		return new ClassPathScanningCandidateComponentProvider(false, this.environment) {

			@Override
			protected boolean isCandidateComponent(AnnotatedBeanDefinition beanDefinition) {
				// 传入的beanDefinition是被@FeignClient注解的接口类
				if (beanDefinition.getMetadata().isIndependent()) {
					// TODO until SPR-11711 will be resolved
					if (beanDefinition.getMetadata().isInterface()
							&& beanDefinition.getMetadata().getInterfaceNames().length == 1
							&& Annotation.class.getName().equals(beanDefinition.getMetadata().getInterfaceNames()[0])) {
						try {
							Class<?> target = ClassUtils.forName(
									beanDefinition.getMetadata().getClassName(), FeignClientsRegistrar.this.classLoader);
							return !target.isAnnotation();
						} catch (Exception ex) {
							this.logger.error(
									"Could not load target class: "
											+ beanDefinition.getMetadata().getClassName(), ex);

						}
					}
					return true;
				}
				return false;

			}
		};
	}

	/**
	 * 获取basePackages
	 *
	 * @param importingClassMetadata
	 * @return
	 */
	protected Set<String> getBasePackages(AnnotationMetadata importingClassMetadata) {
		Map<String, Object> attributes = importingClassMetadata
				.getAnnotationAttributes(EnableFeignClients.class.getCanonicalName());

		Set<String> basePackages = new HashSet<>();
		for (String pkg : (String[]) attributes.get("value")) {
			if (StringUtils.hasText(pkg)) {
				basePackages.add(pkg);
			}
		}
		for (String pkg : (String[]) attributes.get("basePackages")) {
			if (StringUtils.hasText(pkg)) {
				basePackages.add(pkg);
			}
		}
		for (Class<?> clazz : (Class[]) attributes.get("basePackageClasses")) {
			basePackages.add(ClassUtils.getPackageName(clazz));
		}

		if (basePackages.isEmpty()) {
			basePackages.add(
					ClassUtils.getPackageName(importingClassMetadata.getClassName()));
		}
		return basePackages;
	}
	
	private String getQualifier(Map<String, Object> client) {
		if (client == null) {
			return null;
		}
		String qualifier = (String) client.get("qualifier");
		if (StringUtils.hasText(qualifier)) {
			return qualifier;
		}
		return null;
	}

	private String getClientName(Map<String, Object> client) {
		if (client == null) {
			return null;
		}
		String value = (String) client.get("value");
		if (!StringUtils.hasText(value)) {
			value = (String) client.get("name");
		}
		if (!StringUtils.hasText(value)) {
			value = (String) client.get("serviceId");
		}
		if (StringUtils.hasText(value)) {
			return value;
		}

		throw new IllegalStateException("Either 'name' or 'value' must be provided in @"
				+ FeignClient.class.getSimpleName());
	}

	private void registerClientConfiguration(BeanDefinitionRegistry registry, Object name,
			Object configuration) {
		// 将启动类的全限定名的name 以及defaultConfiguration(空数组) 使用addConstructorArgValue进行构造
		// 将name和defaultConfiguration作为构造某个bean的构造函数的入参
		BeanDefinitionBuilder builder = BeanDefinitionBuilder
				.genericBeanDefinition(FeignClientSpecification.class);
		builder.addConstructorArgValue(name);
		builder.addConstructorArgValue(configuration);
		// FeignClientSpecification注册到BeanDefinitionRegistry
		registry.registerBeanDefinition(
				name + "." + FeignClientSpecification.class.getSimpleName(),
				builder.getBeanDefinition());
	}

	@Override
	public void setEnvironment(Environment environment) {
		this.environment = environment;
	}

	/**
	 * Helper class to create a {@link TypeFilter} that matches if all the delegates
	 * match.
	 *
	 * @author Oliver Gierke
	 */
	private static class AllTypeFilter implements TypeFilter {

		private final List<TypeFilter> delegates;

		/**
		 * Creates a new {@link AllTypeFilter} to match if all the given delegates match.
		 *
		 * @param delegates must not be {@literal null}.
		 */
		public AllTypeFilter(List<TypeFilter> delegates) {

			Assert.notNull(delegates);
			this.delegates = delegates;
		}

		@Override
		public boolean match(MetadataReader metadataReader,
				MetadataReaderFactory metadataReaderFactory) throws IOException {

			for (TypeFilter filter : this.delegates) {
				if (!filter.match(metadataReader, metadataReaderFactory)) {
					return false;
				}
			}

			return true;
		}
	}
}
