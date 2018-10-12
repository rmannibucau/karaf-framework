/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.karaf.framework;

import static java.util.Arrays.asList;
import static java.util.Comparator.comparing;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.function.Predicate;

import org.apache.karaf.framework.deployer.OSGiBundleLifecycle;
import org.apache.karaf.framework.scanner.StandaloneScanner;
import org.apache.karaf.framework.service.BundleRegistry;
import org.apache.karaf.framework.service.OSGiServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public interface ContextualFramework extends AutoCloseable {
    long getStartTime();

    ContextualFramework start();

    void stop();

    OSGiServices getServices();

    BundleRegistry getRegistry();

    @Override
    void close();

    class Configuration {
        private static final Collection<String> DEFAULT_EXCLUSIONS = asList( // todo: make it configurable
                "slf4j-",
                "xbean-",
                "org.osgi.",
                "opentest4j-"
        );

        private Predicate<String> jarFilter = it -> DEFAULT_EXCLUSIONS.stream().anyMatch(it::startsWith);

        public void setJarFilter(final Predicate<String> jarFilter) {
            this.jarFilter = jarFilter;
        }

        public Predicate<String> getJarFilter() {
            return jarFilter;
        }
    }


    class Impl implements ContextualFramework {
        private final static Logger LOGGER = LoggerFactory.getLogger(ContextualFramework.class);

        private final OSGiServices services = new OSGiServices();
        private final BundleRegistry registry = new BundleRegistry();

        private final Configuration configuration;

        private long startTime = -1;

        public Impl(final Configuration configuration) {
            this.configuration = configuration;
        }

        @Override
        public long getStartTime() {
            return startTime;
        }

        @Override
        public synchronized ContextualFramework start() {
            startTime = System.currentTimeMillis();
            LOGGER.info("Starting Apache Karaf Contextual Framework on {}",
                    LocalDateTime.ofInstant(Instant.ofEpochMilli(startTime), ZoneId.systemDefault()));
            new StandaloneScanner(configuration.getJarFilter())
                    .findOSGiBundles()
                    .stream()
                    .sorted(comparing(b -> b.getJar().getName()))
                    .map(it -> new OSGiBundleLifecycle(it.getManifest(), it.getJar(), services, registry))
                    .peek(OSGiBundleLifecycle::start)
                    .peek(it -> registry.getBundles().put(it.getBundle().getBundleId(), it))
                    .forEach(bundle -> LOGGER.debug("Bundle {}", bundle));
            return this;
        }

        @Override
        public synchronized void stop() {
            LOGGER.info("Stopping Apache Karaf Contextual Framework on {}", LocalDateTime.now());
            final Map<Long, OSGiBundleLifecycle> bundles = registry.getBundles();
            bundles.forEach((k, v) -> v.stop());
            bundles.clear();
        }

        @Override
        public OSGiServices getServices() {
            return services;
        }

        @Override
        public BundleRegistry getRegistry() {
            return registry;
        }

        @Override // for try with resource syntax
        public void close() {
            stop();
        }
    }

    static void main(final String[] args) {
        final CountDownLatch latch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread() {

            {
                setName(getClass().getName() + "-shutdown-hook");
            }

            @Override
            public void run() {
                latch.countDown();
            }
        });
        try (final ContextualFramework framework = new ContextualFramework.Impl(new Configuration()).start()) {
            try {
                latch.await();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
