/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.emc.pravega.controller.task;

import com.emc.pravega.controller.store.task.Resource;
import com.emc.pravega.controller.store.task.TaggedResource;
import com.emc.pravega.controller.store.task.TaskMetadataStore;
import lombok.Data;

import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;

/**
 * TaskBase contains the following.
 * 1. Environment variables used by tasks.
 * 2. Wrapper method that has boilerplate code for locking, persisting task data and executing the task
 *
 * Actual tasks are implemented in sub-classes of TaskBase and annotated with @Task annotation.
 */
public class TaskBase implements Cloneable {

    public interface FutureOperation<T> {
        CompletableFuture<T> apply();
    }

    @Data
    public static class Context {
        private final String hostId;
        private final String oldHostId;
        private final String oldThreadId;
        private final Resource oldResource;

        public Context(String hostId) {
            this.hostId = hostId;
            this.oldHostId = null;
            this.oldThreadId = null;
            this.oldResource = null;
        }

        public Context(String hostId, String oldHost, String oldThreadId, Resource oldResource) {
            this.hostId = hostId;
            this.oldHostId = oldHost;
            this.oldThreadId = oldThreadId;
            this.oldResource = oldResource;
        }
    }

    protected final ScheduledExecutorService executor;

    private Context context;

    private final TaskMetadataStore taskMetadataStore;

    public TaskBase(TaskMetadataStore taskMetadataStore, ScheduledExecutorService executor, String hostId) {
        this.taskMetadataStore = taskMetadataStore;
        this.executor = executor;
        context = new Context(hostId);
    }

    @Override
    public TaskBase clone() throws CloneNotSupportedException {
        return (TaskBase) super.clone();
    }

    public void setContext(Context context) {
        this.context = context;
    }

    public Context getContext() {
        return this.context;
    }

    /**
     * Wrapper method that initially obtains lock then executes the passed method, and finally releases lock.
     *
     * @param resource resource to be updated by the task.
     * @param parameters method parameters.
     * @param operation lambda operation that is the actual task.
     * @param <T> type parameter of return value of operation to be executed.
     * @return return value of task execution.
     */
    public <T> CompletableFuture<T> execute(Resource resource, Serializable[] parameters, FutureOperation<T> operation) {
        final String threadId = UUID.randomUUID().toString();
        final TaskData taskData = getTaskData(parameters);
        final CompletableFuture<T> result = new CompletableFuture<>();
        final TaggedResource resourceTag = new TaggedResource(threadId, resource);

        // PutChild (HostId, resource)
        // Initially store the fact that I am about the update the resource.
        // Since multiple threads within this process could concurrently attempt to modify same resource,
        // we tag the resource name with a random GUID so as not to interfere with other thread's
        // creation or deletion of resource children under HostId node.
        taskMetadataStore.putChild(context.hostId, resourceTag)
                // After storing that fact, lock the resource, execute task and unlock the resource
                .thenComposeAsync(x -> executeTask(resource, taskData, threadId, operation), executor)
                // finally delete the resource child created under the controller's HostId
                .whenCompleteAsync((value, e) ->
                                taskMetadataStore.removeChild(context.hostId, resourceTag, true)
                                        .whenCompleteAsync((innerValue, innerE) -> {
                                            // ignore the result of removeChile operations, since it is an optimization
                                            if (e != null) {
                                                result.completeExceptionally(e);
                                            } else {
                                                result.complete(value);
                                            }
                                        }, executor),
                        executor
                );

        return result;
    }

    private <T> CompletableFuture<T> executeTask(Resource resource, TaskData taskData, String threadId, FutureOperation<T> operation) {
        final CompletableFuture<T> result = new CompletableFuture<>();

        final CompletableFuture<Void> lockResult = new CompletableFuture<>();

        taskMetadataStore
                .lock(resource, taskData, context.hostId, threadId, context.oldHostId, context.oldThreadId)

                // On acquiring lock, the following invariants hold
                // Invariant 1. No other thread within any controller process is running an update task on the resource
                // Invariant 2. We have denoted the fact that current controller's HostId is updating the resource. This
                // fact can be used in case current controller instance crashes.
                // Invariant 3. Any other controller that had created resource child under its HostId, can now be safely
                // deleted, since that information is redundant and is not useful during that HostId's fail over.
                .whenComplete((value, e) -> {
                    // Once I acquire the lock, safe to delete context.oldResource from oldHost, if available
                    if (e != null) {

                        lockResult.completeExceptionally(e);

                    } else {

                        removeOldHostChild().whenComplete((x, y) -> lockResult.complete(value));
                    }
                });

        lockResult
                // Exclusively execute the update task on the resource
                .thenCompose(y -> operation.apply())

                // If lock had been obtained, unlock it before completing the task.
                .whenComplete((T value, Throwable e) -> {
                    if (lockResult.isCompletedExceptionally()) {
                        // If lock was not obtained, complete the operation with error
                        result.completeExceptionally(e);

                    } else {
                        // If lock was obtained, irrespective of result of operation execution,
                        // release lock before completing operation.
                        taskMetadataStore.unlock(resource, context.hostId, threadId)
                                .whenComplete((innerValue, innerE) -> {
                                    // If lock was acquired above, unlock operation retries until it is released.
                                    // It throws exception only if non-lock holder tries to release it.
                                    // Hence ignore result of unlock operation and complete future with previous result.
                                    if (e != null) {
                                        result.completeExceptionally(e);
                                    } else {
                                        result.complete(value);
                                    }
                                });
                    }
                });
        return result;
    }

    private CompletableFuture<Void> removeOldHostChild() {
        if (context.oldHostId != null && !context.oldHostId.isEmpty()) {
            return taskMetadataStore.removeChild(
                    context.oldHostId,
                    new TaggedResource(context.oldThreadId, context.oldResource),
                    true);
        } else {
            return CompletableFuture.completedFuture(null);
        }
    }

    private TaskData getTaskData(Serializable[] parameters) {
        // Quirk of using stack trace shall be rendered redundant when Task Annotation's handler is coded up.
        TaskData taskData = new TaskData();
        StackTraceElement[] stacktrace = Thread.currentThread().getStackTrace();
        StackTraceElement e = stacktrace[3];
        Task annotation = getTaskAnnotation(e.getMethodName());
        taskData.setMethodName(annotation.name());
        taskData.setMethodVersion(annotation.version());
        taskData.setParameters(parameters);
        return taskData;
    }

    private Task getTaskAnnotation(String method) {
        for (Method m : this.getClass().getMethods()) {
            if (m.getName().equals(method)) {
                for (Annotation annotation : m.getDeclaredAnnotations()) {
                    if (annotation instanceof Task) {
                        return (Task) annotation;
                    }
                }
                break;
            }
        }
        throw new TaskAnnotationNotFoundException(method);
    }
}
