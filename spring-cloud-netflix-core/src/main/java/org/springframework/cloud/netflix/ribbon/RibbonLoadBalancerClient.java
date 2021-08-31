/*
 * Copyright 2013-2015 the original author or authors.
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

package org.springframework.cloud.netflix.ribbon;

import java.io.IOException;
import java.net.URI;
import java.util.Collections;
import java.util.Map;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.cloud.client.loadbalancer.LoadBalancerRequest;
import org.springframework.util.Assert;
import org.springframework.util.ReflectionUtils;
import com.netflix.client.config.IClientConfig;
import com.netflix.loadbalancer.ILoadBalancer;
import com.netflix.loadbalancer.Server;

/**
 * @author Spencer Gibb
 * @author Dave Syer
 * @author Ryan Baxter
 */
public class RibbonLoadBalancerClient implements LoadBalancerClient {

	private SpringClientFactory clientFactory;

	public RibbonLoadBalancerClient(SpringClientFactory clientFactory) {
		this.clientFactory = clientFactory;
	}

	@Override
	public URI reconstructURI(ServiceInstance instance, URI original) {
		Assert.notNull(instance, "instance can not be null");
		String serviceId = instance.getServiceId();
		RibbonLoadBalancerContext context = this.clientFactory
				.getLoadBalancerContext(serviceId);
		Server server = new Server(instance.getHost(), instance.getPort());
		IClientConfig clientConfig = clientFactory.getClientConfig(serviceId);
		ServerIntrospector serverIntrospector = serverIntrospector(serviceId);
		URI uri = RibbonUtils.updateToHttpsIfNeeded(original, clientConfig,
				serverIntrospector, server);
		// 拼接URL 将 http://ServiceA/sayHello/leo 进行重构替换 http://localhost:9090/sayHello/leo
		// 将ServiceA替换成实际选择出来的Server对应的hostname:port
		return context.reconstructURIWithServer(server, uri);
	}

	@Override
	public ServiceInstance choose(String serviceId) {
		Server server = getServer(serviceId);
		if (server == null) {
			return null;
		}
		return new RibbonServer(serviceId, server, isSecure(server, serviceId),
				serverIntrospector(serviceId).getMetadata(server));
	}

	/**
	 * spring-cloud-commons包下的LoadBalancerInterceptor拦截器(Ribbon拦截器)最终会执行到该方法
	 *
	 * @param serviceId
	 * @param request
	 * @param <T>
	 * @return
	 * @throws IOException
	 */
	@Override
	public <T> T execute(String serviceId, LoadBalancerRequest<T> request) throws IOException {
		// 每个Service对应着一个Spring的ApplicationContext上下文容器(Map缓存) 获取的时候直接从相应Service的容器中获取对应的LoadBalancer
		// 如果没有获取到 就创建一个放入 获取到就直接返回
		ILoadBalancer loadBalancer = getLoadBalancer(serviceId);
		// 通过LoadBalancer从一个服务对应的ServerList中选择一个Server出来
		// 保持负载均衡 将请求均匀的达到各个服务实例
		Server server = getServer(loadBalancer);
		if (server == null) {
			throw new IllegalStateException("No instances available for " + serviceId);
		}
		RibbonServer ribbonServer = new RibbonServer(serviceId, server, isSecure(server,
				serviceId), serverIntrospector(serviceId).getMetadata(server));

		return execute(serviceId, ribbonServer, request);
	}

	@Override
	public <T> T execute(String serviceId, ServiceInstance serviceInstance, LoadBalancerRequest<T> request) throws IOException {
		Server server = null;
		if(serviceInstance instanceof RibbonServer) {
			server = ((RibbonServer)serviceInstance).getServer();
		}
		if (server == null) {
			throw new IllegalStateException("No instances available for " + serviceId);
		}

		// 从SpringClientFactory中获取ServiceA对应的ApplicationContext容器 然后再从这个容器中获取对应的RibbonLoadBalancerContext的Bean
		RibbonLoadBalancerContext context = this.clientFactory.getLoadBalancerContext(serviceId);
		RibbonStatsRecorder statsRecorder = new RibbonStatsRecorder(context, server);

		try {
			/**
			 * 调用到一个匿名内部类的apply方法
			 * {@link org.springframework.cloud.client.loadbalancer.LoadBalancerRequest#apply(ServiceInstance)}}
			 */
			T returnVal = request.apply(serviceInstance);
			statsRecorder.recordStats(returnVal);
			return returnVal;
		}
		// catch IOException and rethrow so RestTemplate behaves correctly
		catch (IOException ex) {
			statsRecorder.recordStats(ex);
			throw ex;
		} catch (Exception ex) {
			statsRecorder.recordStats(ex);
			ReflectionUtils.rethrowRuntimeException(ex);
		}
		return null;
	}

	private ServerIntrospector serverIntrospector(String serviceId) {
		ServerIntrospector serverIntrospector = this.clientFactory.getInstance(serviceId,
				ServerIntrospector.class);
		if (serverIntrospector == null) {
			serverIntrospector = new DefaultServerIntrospector();
		}
		return serverIntrospector;
	}

	private boolean isSecure(Server server, String serviceId) {
		IClientConfig config = this.clientFactory.getClientConfig(serviceId);
		ServerIntrospector serverIntrospector = serverIntrospector(serviceId);
		return RibbonUtils.isSecure(config, serverIntrospector, server);
	}

	protected Server getServer(String serviceId) {
		return getServer(getLoadBalancer(serviceId));
	}

	protected Server getServer(ILoadBalancer loadBalancer) {
		if (loadBalancer == null) {
			return null;
		}
		return loadBalancer.chooseServer("default"); // TODO: better handling of key
	}

	protected ILoadBalancer getLoadBalancer(String serviceId) {
		// 通过SpringClientFactory获取对应的LoadBalancer
		return this.clientFactory.getLoadBalancer(serviceId);
	}

	public static class RibbonServer implements ServiceInstance {
		private final String serviceId;
		private final Server server;
		private final boolean secure;
		private Map<String, String> metadata;

		public RibbonServer(String serviceId, Server server) {
			this(serviceId, server, false, Collections.emptyMap());
		}

		public RibbonServer(String serviceId, Server server, boolean secure,
				Map<String, String> metadata) {
			this.serviceId = serviceId;
			this.server = server;
			this.secure = secure;
			this.metadata = metadata;
		}

		@Override
		public String getServiceId() {
			return this.serviceId;
		}

		@Override
		public String getHost() {
			return this.server.getHost();
		}

		@Override
		public int getPort() {
			return this.server.getPort();
		}

		@Override
		public boolean isSecure() {
			return this.secure;
		}

		@Override
		public URI getUri() {
			return DefaultServiceInstance.getUri(this);
		}

		@Override
		public Map<String, String> getMetadata() {
			return this.metadata;
		}

		public Server getServer() {
			return this.server;
		}

		@Override
		public String toString() {
			final StringBuffer sb = new StringBuffer("RibbonServer{");
			sb.append("serviceId='").append(serviceId).append('\'');
			sb.append(", server=").append(server);
			sb.append(", secure=").append(secure);
			sb.append(", metadata=").append(metadata);
			sb.append('}');
			return sb.toString();
		}
	}

}
