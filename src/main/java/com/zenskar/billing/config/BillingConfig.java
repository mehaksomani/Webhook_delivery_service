package com.zenskar.billing.config;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.zenskar.billing.domain.HealthPolicy;
import com.zenskar.billing.domain.RetryPolicy;
import com.zenskar.billing.http.BillingHttpClient;
import com.zenskar.billing.security.UrlPolicy;

/**
 * All Spring bean wiring for the dispatcher. Kept in one file because for a
 * service this size the four-bean separation we started with was ceremony
 * more than clarity: a reader can see Clock, async executor, HTTP client,
 * and domain policies in one scroll.
 */
@Configuration
@EnableConfigurationProperties(BillingProperties.class)
@EnableScheduling
@EnableAsync
public class BillingConfig {

    /** A single Clock bean so tests can swap it for a fixed clock if needed. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /** Runs attempt-recording callbacks and async event listeners. */
    @Bean(name = "taskExecutor")
    public TaskExecutor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(512);
        executor.setThreadNamePrefix("dispatch-cb-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /** JDK async HTTP client. Non-blocking I/O is what makes S4 possible. */
    @Bean
    public HttpClient httpClient() {
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "billing-http-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER)
                .executor(Executors.newCachedThreadPool(tf))
                .build();
    }

    /** SSRF guard for delivery targets, configured from {@code billing.security.*}. */
    @Bean
    public UrlPolicy urlPolicy(BillingProperties props) {
        BillingProperties.Security s = props.security();
        return new UrlPolicy(s.allowedSchemes(), s.blockPrivateAddresses());
    }

    @Bean
    public BillingHttpClient billingHttpClient(HttpClient httpClient, BillingProperties props, UrlPolicy urlPolicy) {
        return new BillingHttpClient(
                httpClient, Duration.ofSeconds(props.dispatcher().httpTimeoutSeconds()), urlPolicy);
    }

    @Bean
    public RetryPolicy retryPolicy(BillingProperties props) {
        BillingProperties.Retry r = props.retry();
        return new RetryPolicy(r.maxAttempts(), r.scheduleSeconds(), r.jitterRatio());
    }

    @Bean
    public HealthPolicy healthPolicy(BillingProperties props) {
        BillingProperties.Health h = props.health();
        return new HealthPolicy(h.windowSize(), h.failureThreshold(), h.consecutiveThreshold());
    }
}
