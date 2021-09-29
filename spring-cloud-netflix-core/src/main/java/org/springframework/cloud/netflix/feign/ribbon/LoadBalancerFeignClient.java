/*
 * Copyright 2015 the original author or authors.
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

package org.springframework.cloud.netflix.feign.ribbon;

import java.io.IOException;
import java.net.URI;

import org.springframework.cloud.netflix.ribbon.SpringClientFactory;

import com.netflix.client.ClientException;
import com.netflix.client.config.CommonClientConfigKey;
import com.netflix.client.config.DefaultClientConfigImpl;
import com.netflix.client.config.IClientConfig;

import feign.Client;
import feign.Request;
import feign.Response;

/**
 * Feign最核心的入口 构造一个FeignClient 里面包含了Encoder Decoder Logger Contract等其他重要组件
 * 负载均衡 底层和Ribbon结合使用
 *
 * @author Dave Syer
 */
public class LoadBalancerFeignClient implements Client {

	static final Request.Options DEFAULT_OPTIONS = new Request.Options();

	private final Client delegate;
	private CachingSpringLoadBalancerFactory lbClientFactory;
	private SpringClientFactory clientFactory;

	public LoadBalancerFeignClient(Client delegate,
								   CachingSpringLoadBalancerFactory lbClientFactory,
								   SpringClientFactory clientFactory) {
		this.delegate = delegate;
		this.lbClientFactory = lbClientFactory;
		this.clientFactory = clientFactory;
	}

	@Override
	public Response execute(Request request, Request.Options options) throws IOException {
		try {
			// 请求URL
			URI asUri = URI.create(request.url());
			// 从请求URL取出要访问的服务名称ServiceA
			String clientName = asUri.getHost();
			// 从请求URL剔除的服务名称ServiceA
			URI uriWithoutHost = cleanUrl(request.url(), clientName);
			// 适合Ribbon的请求对象
			FeignLoadBalancer.RibbonRequest ribbonRequest = new FeignLoadBalancer.RibbonRequest(
					this.delegate, request, uriWithoutHost);

			// Ribbon相关的一些配置 调用到Ribbon中
			// com.netflix.client.AbstractLoadBalancerAwareClient.executeWithLoadBalancer(S, com.netflix.client.config.IClientConfig)
			IClientConfig requestConfig = getClientConfig(options, clientName);
			// 调用Ribbon 默认使用的是ZoneAwareLoadBalancer 在 org.springframework.cloud.netflix.ribbon.RibbonClientConfiguration.ribbonLoadBalancer 注入spring容器
			// ServerList默认使用的是DomainExtractingServerList DomainExtractingServerList这个东西自己会去eureka的注册表里去抓取服务对应的注册表 server list 在 org.springframework.cloud.netflix.ribbon.eureka.EurekaRibbonClientConfiguration.ribbonServerList 注入spring容器
			// spring boot启动时 要去获取一个ribbon的ILoadBalancer的时候 会去获取到那个服务对应的一个独立的spring容器 再从这个容器里面去获取对应的独立的ZoneAwareLoadBalancer，ZoneAwareLoadBalancer内部就有DomainExtractingServerList
			return lbClient(clientName).executeWithLoadBalancer(ribbonRequest,
					requestConfig).toResponse();
		} catch (ClientException e) {
			IOException io = findIOException(e);
			if (io != null) {
				throw io;
			}
			throw new RuntimeException(e);
		}
	}

	IClientConfig getClientConfig(Request.Options options, String clientName) {
		IClientConfig requestConfig;
		if (options == DEFAULT_OPTIONS) {
			requestConfig = this.clientFactory.getClientConfig(clientName);
		} else {
			requestConfig = new FeignOptionsClientConfig(options);
		}
		return requestConfig;
	}

	protected IOException findIOException(Throwable t) {
		if (t == null) {
			return null;
		}
		if (t instanceof IOException) {
			return (IOException) t;
		}
		return findIOException(t.getCause());
	}

	public Client getDelegate() {
		return this.delegate;
	}

	static URI cleanUrl(String originalUrl, String host) {
		String newUrl = originalUrl.replaceFirst(host, "");
		StringBuffer buffer = new StringBuffer(newUrl);
		if((newUrl.startsWith("https://") && newUrl.length() == 8) ||
				(newUrl.startsWith("http://") && newUrl.length() == 7)) {
			buffer.append("/");
		}
		return URI.create(buffer.toString());
	}

	private FeignLoadBalancer lbClient(String clientName) {
		return this.lbClientFactory.create(clientName);
	}

	static class FeignOptionsClientConfig extends DefaultClientConfigImpl {

		public FeignOptionsClientConfig(Request.Options options) {
			setProperty(CommonClientConfigKey.ConnectTimeout,
					options.connectTimeoutMillis());
			setProperty(CommonClientConfigKey.ReadTimeout, options.readTimeoutMillis());
		}

		@Override
		public void loadProperties(String clientName) {

		}

		@Override
		public void loadDefaultValues() {

		}

	}
}
