/**
 * Copyright © 2016-2023 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.monitoring.service;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.thingsboard.monitoring.client.TbClient;
import org.thingsboard.monitoring.client.WsClient;
import org.thingsboard.monitoring.client.WsClientFactory;
import org.thingsboard.monitoring.config.MonitoringConfig;
import org.thingsboard.monitoring.config.MonitoringTarget;
import org.thingsboard.monitoring.data.Latencies;
import org.thingsboard.monitoring.data.MonitoredServiceKey;
import org.thingsboard.monitoring.service.transport.TransportHealthChecker;
import org.thingsboard.monitoring.util.TbStopWatch;

import javax.annotation.PostConstruct;
import java.net.InetAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
public abstract class BaseMonitoringService<C extends MonitoringConfig<T>, T extends MonitoringTarget> {

    @Autowired(required = false)
    private List<C> configs;
    private final List<BaseHealthChecker<C, T>> healthCheckers = new LinkedList<>();
    private final List<UUID> devices = new LinkedList<>();

    @Autowired
    private TbClient tbClient;
    @Autowired
    private WsClientFactory wsClientFactory;
    @Autowired
    private TbStopWatch stopWatch;
    @Autowired
    private MonitoringReporter reporter;
    @Autowired
    protected ApplicationContext applicationContext;

    @PostConstruct
    private void init() {
        if (configs == null || configs.isEmpty()) {
            return;
        }
        tbClient.logIn();
        configs.forEach(config -> {
            config.getTargets().forEach(target -> {
                initHealthChecker(target, config);
                if (target.isCheckDomainIps()) {
                    initIpsHealthCheckers(target, config);
                }
            });
        });
    }

    private void initHealthChecker(T target, C config) {
        BaseHealthChecker<C, T> healthChecker = (BaseHealthChecker<C, T>) createHealthChecker(config, target);
        log.info("Initializing {} for {}", healthChecker.getClass().getSimpleName(), target.getBaseUrl());
        healthChecker.initialize(tbClient);
        devices.add(target.getDeviceId());
        healthCheckers.add(healthChecker);
    }

    @SneakyThrows
    private void initIpsHealthCheckers(T target, C config) {
        URI baseUrl = new URI(target.getBaseUrl());
        String domain = baseUrl.getHost();

        Set<String> ips = Arrays.stream(InetAddress.getAllByName(domain))
                .map(InetAddress::getHostAddress)
                .collect(Collectors.toSet());
        for (String ip : ips) {
            String url = new URI(baseUrl.getScheme(), null, ip, baseUrl.getPort(), "", null, null).toString();
            initHealthChecker(createTarget(url), config);
        }
    }

    public final void runChecks() {
        if (healthCheckers.isEmpty()) {
            return;
        }
        try {
            log.info("Starting {}", getName());
            stopWatch.start();
            String accessToken = tbClient.logIn();
            reporter.reportLatency(Latencies.LOG_IN, stopWatch.getTime());

            try (WsClient wsClient = wsClientFactory.createClient(accessToken)) {
                wsClient.subscribeForTelemetry(devices, TransportHealthChecker.TEST_TELEMETRY_KEY).waitForReply();

                for (BaseHealthChecker<C, T> healthChecker : healthCheckers) {
                    healthChecker.check(wsClient);
                }
            }
            reporter.reportLatencies(tbClient);
            log.debug("Finished {}", getName());
        } catch (Throwable error) {
            try {
                reporter.serviceFailure(MonitoredServiceKey.GENERAL, error);
            } catch (Throwable reportError) {
                log.error("Error occurred during service failure reporting", reportError);
            }
        }
    }

    protected abstract BaseHealthChecker<?, ?> createHealthChecker(C config, T target);

    protected abstract T createTarget(String baseUrl);

    protected abstract String getName();

}
