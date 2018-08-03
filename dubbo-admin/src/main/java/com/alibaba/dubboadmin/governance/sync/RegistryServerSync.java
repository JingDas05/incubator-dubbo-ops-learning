/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.dubboadmin.governance.sync;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.registry.NotifyListener;
import com.alibaba.dubbo.registry.RegistryService;
import com.alibaba.dubboadmin.web.pulltool.Tool;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class RegistryServerSync implements InitializingBean, DisposableBean, NotifyListener {

    private static final Logger logger = LoggerFactory.getLogger(RegistryServerSync.class);

    // admin://172.24.224.1?category=providers,consumers,routers,configurators&check=false&classifier=*&enabled=*&group=*&interface=*&version=*
    private static final URL SUBSCRIBE = new URL(Constants.ADMIN_PROTOCOL, NetUtils.getLocalHost(), 0, "",
            Constants.INTERFACE_KEY, Constants.ANY_VALUE,
            Constants.GROUP_KEY, Constants.ANY_VALUE,
            Constants.VERSION_KEY, Constants.ANY_VALUE,
            Constants.CLASSIFIER_KEY, Constants.ANY_VALUE,
            Constants.CATEGORY_KEY, Constants.PROVIDERS_CATEGORY + ","
            + Constants.CONSUMERS_CATEGORY + ","
            + Constants.ROUTERS_CATEGORY + ","
            + Constants.CONFIGURATORS_CATEGORY,
            Constants.ENABLED_KEY, Constants.ANY_VALUE,
            Constants.CHECK_KEY, String.valueOf(false));

    // 这个就是那个版本号
    private static final AtomicLong ID = new AtomicLong();

    /**
     * Make sure ID never changed when the same url notified many times
     */
    private final ConcurrentHashMap<String, Long> URL_IDS_MAPPER = new ConcurrentHashMap<String, Long>();

    // ConcurrentMap<category, ConcurrentMap<servicename, Map<Long, URL>>>
    private final ConcurrentMap<String, ConcurrentMap<String, Map<Long, URL>>>
            registryCache = new ConcurrentHashMap<String, ConcurrentMap<String, Map<Long, URL>>>();
    @Autowired
    private RegistryService registryService;

    public ConcurrentMap<String, ConcurrentMap<String, Map<Long, URL>>> getRegistryCache() {
        return registryCache;
    }

    // admin://172.24.224.1?category=providers,consumers,routers,configurators&check=false&classifier=*&enabled=*&group=*&interface=*&version=*
    // 相当于订阅了所有的服务
    public void afterPropertiesSet() throws Exception {
        logger.info("Init Dubbo Admin Sync Cache...");
        // 这步订阅很重要，会监听zookeeper所有的变动，之后更新本地缓存registryCache
        // 本地用的是 ZookeeperRegistry，实际调用 ZookeeperRegistry doSubscribe()方法，之后传入注册监听器，本例就是监听器
        registryService.subscribe(SUBSCRIBE, this);
    }

    public void destroy() throws Exception {
        registryService.unsubscribe(SUBSCRIBE, this);
    }

    // Notification of of any service with any type (override、subcribe、route、provider) is full.
    // 有新的注册信息
    // 对于 demoProvider
    // 如果服务注册，收到的通知消息如下：
//    dubbo://192.168.73.1:20880/com.alibaba.dubbo.demo.DemoService?accepts=0&anyhost=true&application=demoProvider&
//    buffer=8192&dispatcher=all&dubbo=2.0.0&generic=false&interface=com.alibaba.dubbo.demo.DemoService&iothreads=9&
//    methods=sayHello
//    &payload=88388608&pid=19296&register=true&serialization=hessian2&side=provider&threadpool=fixed&threads=100&timeout=30000&timestamp=1533088098886
//
//    dubbo://192.168.73.1:20880/com.alibaba.dubbo.demo.DemoService2?accepts=0&anyhost=true&application=demoProvider&
//    buffer=8192&dispatcher=all&dubbo=2.0.0&generic=false&interface=com.alibaba.dubbo.demo.DemoService2&iothreads=9&
//    methods=sayHello2,anotherSayHello2&
//    payload=88388608&pid=19296&register=true&serialization=hessian2&side=provider&threadpool=fixed&threads=100&timeout=30000&timestamp=1533088108861

      // 如果注册的服务停了，收到的通知消息如下：
//    empty://172.24.224.1/com.alibaba.dubbo.demo.DemoService?category=providers&
//    check=false&classifier=*&enabled=*&group=*&interface=com.alibaba.dubbo.demo.DemoService&version=*
//
//    empty://172.24.224.1/com.alibaba.dubbo.demo.DemoService2?category=providers&
//    check=false&classifier=*&enabled=*&group=*&interface=com.alibaba.dubbo.demo.DemoService2&version=*
    public void notify(List<URL> urls) {
        if (urls == null || urls.isEmpty()) {
            return;
        }
        // Map<category, Map<servicename, Map<Long, URL>>>
        final Map<String, Map<String, Map<Long, URL>>> categories = new HashMap<String, Map<String, Map<Long, URL>>>();
        String interfaceName = null;
        for (URL url : urls) {
            // 获取分类，默认是 providers
            String category = url.getParameter(Constants.CATEGORY_KEY, Constants.PROVIDERS_CATEGORY);
            // 如果是空协议，移除掉 service 服务，empty://开头的
            if (Constants.EMPTY_PROTOCOL.equalsIgnoreCase(url.getProtocol())) {
                // NOTE: group and version in empty protocol is *
                // 分类包括 providers consumers 等，默认是 providers
                ConcurrentMap<String, Map<Long, URL>> services = registryCache.get(category);
                if (services != null) {
                    String group = url.getParameter(Constants.GROUP_KEY);
                    String version = url.getParameter(Constants.VERSION_KEY);
                    // NOTE: group and version in empty protocol is *，如果 group 和 version都不等于 *，就移除
                    if (!Constants.ANY_VALUE.equals(group) && !Constants.ANY_VALUE.equals(version)) {
                        services.remove(url.getServiceKey());
                    } else {
                        // 如果是一个服务，移除掉（一致的标准是 group和version和Interface 相同）
                        for (Map.Entry<String, Map<Long, URL>> serviceEntry : services.entrySet()) {
                            String service = serviceEntry.getKey();
                            if (Tool.getInterface(service).equals(url.getServiceInterface())
                                    && (Constants.ANY_VALUE.equals(group) || StringUtils.isEquals(group, Tool.getGroup(service)))
                                    && (Constants.ANY_VALUE.equals(version) || StringUtils.isEquals(version, Tool.getVersion(service)))) {
                                services.remove(service);
                            }
                        }
                    }
                }
            } else {
                // 获取接口名字
                if (StringUtils.isEmpty(interfaceName)) {
                    interfaceName = url.getServiceInterface();
                }
                Map<String, Map<Long, URL>> services = categories.get(category);
                // 如果为空 初始化
                if (services == null) {
                    services = new HashMap<String, Map<Long, URL>>();
                    categories.put(category, services);
                }
                // 方法全路径名
                String service = url.getServiceKey();
                // 如果为空 初始化
                Map<Long, URL> ids = services.get(service);
                if (ids == null) {
                    ids = new HashMap<Long, URL>();
                    services.put(service, ids);
                }
                // Make sure we use the same ID for the same URL
                // 确保相同的url使用相同的ID
                // 核心代码
                if (URL_IDS_MAPPER.containsKey(url.toFullString())) {
                    ids.put(URL_IDS_MAPPER.get(url.toFullString()), url);
                } else {
                    long currentId = ID.incrementAndGet();
                    ids.put(currentId, url);
                    // 存储 url.toFullString() 与id 的对应关系
                    URL_IDS_MAPPER.putIfAbsent(url.toFullString(), currentId);
                }
            }
        }
        if (categories.size() == 0) {
            return;
        }
        // 添加到 registryCache 中
        for (Map.Entry<String, Map<String, Map<Long, URL>>> categoryEntry : categories.entrySet()) {
            String category = categoryEntry.getKey();
            // 根据分类获取缓存， key:      value:
            ConcurrentMap<String, Map<Long, URL>> services = registryCache.get(category);
            if (services == null) {
                services = new ConcurrentHashMap<String, Map<Long, URL>>();
                registryCache.put(category, services);
            } else {
                // Fix map can not be cleared when service is unregistered: when a unique “group/service:version” service is unregistered,
                // but we still have the same services with different version or group, so empty protocols can not be invoked.
                Set<String> keys = new HashSet<String>(services.keySet());
                for (String key : keys) {
                    if (Tool.getInterface(key).equals(interfaceName) && !categoryEntry.getValue().entrySet().contains(key)) {
                        services.remove(key);
                    }
                }
            }
            services.putAll(categoryEntry.getValue());
        }
    }
}
    
