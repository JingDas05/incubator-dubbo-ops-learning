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
package com.alibaba.dubbo.registry.simple;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.ConcurrentHashSet;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.common.utils.UrlUtils;
import com.alibaba.dubbo.registry.NotifyListener;
import com.alibaba.dubbo.registry.RegistryService;
import com.alibaba.dubbo.registry.support.AbstractRegistry;
import com.alibaba.dubbo.rpc.RpcContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * SimpleRegistryService
 */
public class SimpleRegistryService extends AbstractRegistry {

    private final static Logger logger = LoggerFactory.getLogger(SimpleRegistryService.class);
    private final ConcurrentMap<String, Set<URL>> remoteRegistered = new ConcurrentHashMap<String, Set<URL>>();
    private final ConcurrentMap<String, ConcurrentMap<URL, Set<NotifyListener>>> remoteSubscribed = new ConcurrentHashMap<String, ConcurrentMap<URL, Set<NotifyListener>>>();

    public SimpleRegistryService() {
        super(new URL("dubbo", NetUtils.getLocalHost(), 0, RegistryService.class.getName(), "file", "N/A"));
    }

    public boolean isAvailable() {
        return true;
    }

    // 寻找匹配的 已注册的服务列表
    public List<URL> lookup(URL url) {
        List<URL> urls = new ArrayList<URL>();
        for (URL u : getRegistered()) {
            if (UrlUtils.isMatch(url, u)) {
                urls.add(u);
            }
        }
        return urls;
    }

    // 注册服务
    public void register(URL url) {
        // client 为 host:post
        String client = RpcContext.getContext().getRemoteAddressString();
        Set<URL> urls = remoteRegistered.get(client);
        // 如果为空初始化，之后置入 url
        if (urls == null) {
            remoteRegistered.putIfAbsent(client, new ConcurrentHashSet<URL>());
            urls = remoteRegistered.get(client);
        }
        urls.add(url);
        super.register(url);
        // 通知订阅
        registered(url);
    }

    // 取消注册服务
    public void unregister(URL url) {
        String client = RpcContext.getContext().getRemoteAddressString();
        Set<URL> urls = remoteRegistered.get(client);
        if (urls != null && urls.size() > 0) {
            urls.remove(url);
        }
        super.unregister(url);
        unregistered(url);
    }

    // 订阅服务
    public void subscribe(URL url, NotifyListener listener) {
        if (getUrl().getPort() == 0) {
            URL registryUrl = RpcContext.getContext().getUrl();
            if (registryUrl != null && registryUrl.getPort() > 0
                    && RegistryService.class.getName().equals(registryUrl.getPath())) {
                super.setUrl(registryUrl);
                super.register(registryUrl);
            }
        }
        String client = RpcContext.getContext().getRemoteAddressString();
        ConcurrentMap<URL, Set<NotifyListener>> clientListeners = remoteSubscribed.get(client);
        if (clientListeners == null) {
            remoteSubscribed.putIfAbsent(client, new ConcurrentHashMap<URL, Set<NotifyListener>>());
            clientListeners = remoteSubscribed.get(client);
        }
        Set<NotifyListener> listeners = clientListeners.get(url);
        if (listeners == null) {
            clientListeners.putIfAbsent(url, new ConcurrentHashSet<NotifyListener>());
            listeners = clientListeners.get(url);
        }
        listeners.add(listener);
        super.subscribe(url, listener);
        subscribed(url, listener);
    }

    // 取消订阅，去除listener
    public void unsubscribe(URL url, NotifyListener listener) {
        if (!Constants.ANY_VALUE.equals(url.getServiceInterface())
                && url.getParameter(Constants.REGISTER_KEY, true)) {
            unregister(url);
        }
        String client = RpcContext.getContext().getRemoteAddressString();
        Map<URL, Set<NotifyListener>> clientListeners = remoteSubscribed.get(client);
        if (clientListeners != null && clientListeners.size() > 0) {
            Set<NotifyListener> listeners = clientListeners.get(url);
            if (listeners != null && listeners.size() > 0) {
                listeners.remove(listener);
            }
        }
    }

    // 注册 通知订阅
    protected void registered(URL url) {
        for (Map.Entry<URL, Set<NotifyListener>> entry : getSubscribed().entrySet()) {
            URL key = entry.getKey();
            if (UrlUtils.isMatch(key, url)) {
                // 获取已注册的和url提供相同服务的 列表
                List<URL> list = lookup(key);
                for (NotifyListener listener : entry.getValue()) {
                    listener.notify(list);
                }
            }
        }
    }

    // 取消注册 通知订阅
    protected void unregistered(URL url) {
        for (Map.Entry<URL, Set<NotifyListener>> entry : getSubscribed().entrySet()) {
            URL key = entry.getKey();
            if (UrlUtils.isMatch(key, url)) {
                List<URL> list = lookup(key);
                for (NotifyListener listener : entry.getValue()) {
                    listener.notify(list);
                }
            }
        }
    }


    protected void subscribed(final URL url, final NotifyListener listener) {
        // 开启新线程处理，如果 interface = *,UrlUtils.isMatch()会返回true,所以下面要根据接口，获取map
        if (Constants.ANY_VALUE.equals(url.getServiceInterface())) {
            new Thread(new Runnable() {
                public void run() {
                    // interface=com.alibaba.dubbo.demo.DemoService， key 是 interface
                    // 获取提供相同服务的所有接口
                    Map<String, List<URL>> map = new HashMap<String, List<URL>>();
                    for (URL u : getRegistered()) {
                        if (UrlUtils.isMatch(url, u)) {
                            String service = u.getServiceInterface();
                            List<URL> list = map.get(service);
                            // 等于空就初始化
                            if (list == null) {
                                list = new ArrayList<URL>();
                                map.put(service, list);
                            }
                            list.add(u);
                        }
                    }
                    // 对于所有的方法都进行通知
                    for (List<URL> list : map.values()) {
                        try {
                            listener.notify(list);
                        } catch (Throwable e) {
                            logger.warn("Discard to notify " + url.getServiceKey() + " to listener " + listener);
                        }
                    }
                }
            }, "DubboMonitorNotifier").start();
        } else {
            List<URL> list = lookup(url);
            try {
                listener.notify(list);
            } catch (Throwable e) {
                logger.warn("Discard to notify " + url.getServiceKey() + " to listener " + listener);
            }
        }
    }

    public void disconnect() {
        String client = RpcContext.getContext().getRemoteAddressString();
        if (logger.isInfoEnabled()) {
            logger.info("Disconnected " + client);
        }
        Set<URL> urls = remoteRegistered.get(client);
        if (urls != null && urls.size() > 0) {
            for (URL url : urls) {
                unregister(url);
            }
        }
        Map<URL, Set<NotifyListener>> listeners = remoteSubscribed.get(client);
        if (listeners != null && listeners.size() > 0) {
            for (Map.Entry<URL, Set<NotifyListener>> entry : listeners.entrySet()) {
                URL url = entry.getKey();
                for (NotifyListener listener : entry.getValue()) {
                    unsubscribe(url, listener);
                }
            }
        }
    }

}