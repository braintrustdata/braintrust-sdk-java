package dev.braintrust;

import dev.braintrust.api.BraintrustApiClient;
import dev.braintrust.openapi.model.Organization;
import dev.braintrust.openapi.model.Project;
import java.lang.invoke.MethodHandles;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nonnull;

public class BraintrustUtils {
    @Deprecated
    public static URI createProjectURI(
            String appUrl, BraintrustApiClient.OrganizationAndProjectInfo orgAndProject) {
        return createProjectURI(
                appUrl, orgAndProject.orgInfo().name(), orgAndProject.project().name());
    }

    /**
     * construct a URI to link to a specific braintrust project within an org, using generated types
     */
    public static URI createProjectURI(String appUrl, Organization org, Project project) {
        return createProjectURI(appUrl, org.getName(), project.getName());
    }

    /** construct a URI to link to a specific braintrust project within an org by name */
    public static URI createProjectURI(String appUrl, String orgName, String projectName) {
        try {
            var baseURI = new URI(appUrl);
            var path = "/app/%s/p/%s".formatted(orgName, projectName);
            return new URI(
                    baseURI.getScheme(),
                    baseURI.getUserInfo(),
                    baseURI.getHost(),
                    baseURI.getPort(),
                    path,
                    null,
                    null);
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    public static Parent parseParent(@Nonnull String parentStr) {
        String[] parts = parentStr.split(":");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid parent format: " + parentStr);
        }
        return new Parent(parts[0], parts[1]);
    }

    /** Represents a parsed parent with type and ID. */
    public record Parent(String type, String id) {
        public String toParentValue() {
            return type + ":" + id;
        }
    }

    public static List<String> parseCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }

        return Arrays.stream(csv.split("\\s*,\\s*")).toList();
    }

    public static <T> List<T> append(List<T> list, T value) {
        List<T> result = new ArrayList<>(list);
        result.add(value);
        return result;
    }

    /**
     * An executor that runs at most {@code maxSize} tasks concurrently and backpressures whoever
     * submits to it once that limit is reached.
     *
     * <p>NOTE: On Java 21+ each task gets its own virtual thread; on older JVMs this falls back to
     * a bounded pool of platform daemon threads.
     */
    public static ExecutorService newExecutor(int maxSize, String threadNamePrefix) {
        if (maxSize < 1) {
            throw new IllegalArgumentException("max size must be 1 or more: " + maxSize);
        }
        var virtual = VirtualThreads.newExecutor(threadNamePrefix);
        if (virtual != null) {
            return new BoundedExecutorService(virtual, maxSize, threadNamePrefix);
        }
        return newPlatformThreadExecutor(maxSize, threadNamePrefix);
    }

    /** A pool of at most {@code maxSize} daemon platform threads. Used on Java < 21. */
    private static ExecutorService newPlatformThreadExecutor(int maxSize, String threadNamePrefix) {
        var counter = new AtomicInteger();
        return new ThreadPoolExecutor(
                0,
                maxSize,
                60,
                TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                r -> {
                    var thread = new Thread(r, threadNamePrefix + counter.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                (task, pool) -> {
                    try {
                        while (!pool.isShutdown()) {
                            if (pool.getQueue().offer(task, 100, TimeUnit.MILLISECONDS)) {
                                return;
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RejectedExecutionException(
                                "interrupted while waiting to submit to " + threadNamePrefix, e);
                    }
                    throw new RejectedExecutionException(
                            "executor is shut down: " + threadNamePrefix);
                });
    }

    /**
     * Reflective access to the Java 21 virtual thread APIs.
     *
     * <p>The SDK compiles against Java 17 so {@code Thread.ofVirtual()} and {@code
     * Executors.newThreadPerTaskExecutor} aren't on the compile classpath. We look them up once at
     * class-init time and fall back to platform threads if anything is missing.
     */
    private static final class VirtualThreads {
        /**
         * {@code Executors.newThreadPerTaskExecutor(ThreadFactory)}, or null if unavailable. Java
         * 21 is the first release where virtual threads are a final (non-preview) feature.
         */
        private static final java.lang.invoke.MethodHandle NEW_THREAD_PER_TASK_EXECUTOR;

        /** {@code Thread.ofVirtual()}, or null if unavailable. */
        private static final java.lang.invoke.MethodHandle OF_VIRTUAL;

        /** {@code Thread.Builder.OfVirtual.name(String prefix, long start)}. */
        private static final java.lang.invoke.MethodHandle BUILDER_NAME;

        /** {@code Thread.Builder.factory()}. */
        private static final java.lang.invoke.MethodHandle BUILDER_FACTORY;

        static {
            java.lang.invoke.MethodHandle newExecutor = null;
            java.lang.invoke.MethodHandle ofVirtual = null;
            java.lang.invoke.MethodHandle name = null;
            java.lang.invoke.MethodHandle factory = null;
            if (Runtime.version().feature() >= 21) {
                try {
                    var lookup = MethodHandles.publicLookup();
                    var builder = Class.forName("java.lang.Thread$Builder");
                    var ofVirtualType = Class.forName("java.lang.Thread$Builder$OfVirtual");

                    newExecutor =
                            lookup.findStatic(
                                    java.util.concurrent.Executors.class,
                                    "newThreadPerTaskExecutor",
                                    java.lang.invoke.MethodType.methodType(
                                            ExecutorService.class, ThreadFactory.class));
                    ofVirtual =
                            lookup.findStatic(
                                    Thread.class,
                                    "ofVirtual",
                                    java.lang.invoke.MethodType.methodType(ofVirtualType));
                    name =
                            lookup.findVirtual(
                                    ofVirtualType,
                                    "name",
                                    java.lang.invoke.MethodType.methodType(
                                            ofVirtualType, String.class, long.class));
                    factory =
                            lookup.findVirtual(
                                    builder,
                                    "factory",
                                    java.lang.invoke.MethodType.methodType(ThreadFactory.class));
                } catch (ReflectiveOperationException | RuntimeException e) {
                    // Not a JVM we can use virtual threads on; fall back to platform threads.
                    newExecutor = null;
                    ofVirtual = null;
                    name = null;
                    factory = null;
                }
            }
            NEW_THREAD_PER_TASK_EXECUTOR = newExecutor;
            OF_VIRTUAL = ofVirtual;
            BUILDER_NAME = name;
            BUILDER_FACTORY = factory;
        }

        /**
         * A thread-per-task executor backed by virtual threads named {@code <prefix>0}, {@code
         * <prefix>1}, ..., or null when virtual threads aren't available on this JVM.
         */
        static ExecutorService newExecutor(String threadNamePrefix) {
            if (NEW_THREAD_PER_TASK_EXECUTOR == null) {
                return null;
            }
            try {
                var builder = OF_VIRTUAL.invoke();
                builder = BUILDER_NAME.invoke(builder, threadNamePrefix, 0L);
                var factory = (ThreadFactory) BUILDER_FACTORY.invoke(builder);
                return (ExecutorService) NEW_THREAD_PER_TASK_EXECUTOR.invoke(factory);
            } catch (Throwable t) {
                return null;
            }
        }

        private VirtualThreads() {}
    }

    /**
     * Caps a thread-per-task executor at {@code maxSize} concurrent tasks, blocking the submitter
     * while the limit is saturated.
     *
     * <p>Deliberately a semaphore over a thread-per-task executor rather than a pool: virtual
     * threads are meant to be created per task, not pooled and reused.
     */
    private static final class BoundedExecutorService extends AbstractExecutorService {
        private final ExecutorService delegate;
        private final Semaphore permits;
        private final String threadNamePrefix;

        BoundedExecutorService(ExecutorService delegate, int maxSize, String threadNamePrefix) {
            this.delegate = delegate;
            this.permits = new Semaphore(maxSize);
            this.threadNamePrefix = threadNamePrefix;
        }

        @Override
        public void execute(Runnable command) {
            if (delegate.isShutdown()) {
                throw new RejectedExecutionException("executor is shut down: " + threadNamePrefix);
            }
            try {
                permits.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RejectedExecutionException(
                        "interrupted while waiting to submit to " + threadNamePrefix, e);
            }
            try {
                delegate.execute(
                        () -> {
                            try {
                                command.run();
                            } finally {
                                permits.release();
                            }
                        });
            } catch (RuntimeException | Error e) {
                permits.release();
                throw e;
            }
        }

        @Override
        public void shutdown() {
            delegate.shutdown();
        }

        @Override
        public List<Runnable> shutdownNow() {
            return delegate.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return delegate.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return delegate.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }
    }
}
