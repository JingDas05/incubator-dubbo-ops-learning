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
package com.alibaba.dubbo.monitor.simple;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.container.Main;

public class MonitorStarter {
    public static void main(String[] args) {

        System.setProperty(Constants.DUBBO_PROPERTIES_KEY, "conf/dubbo.properties");
        // 手动设置usr.home，这个会在配置文件中用到 dubbo.jetty.directory=${user.home}/monitor
        // 没有图表的原因：程序不会自动创建monitor文件夹，需要手动创建
        System.setProperty("usr.home", "D:\\wusi");
        Main.main(args);
    }
}
