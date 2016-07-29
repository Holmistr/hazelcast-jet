/*
 * Copyright (c) 2008-2016, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.jet.impl.statemachine.jobmanager.processors;

import com.hazelcast.jet.impl.Dummy;
import com.hazelcast.jet.impl.container.JobManager;
import com.hazelcast.jet.impl.container.ContainerPayloadProcessor;
import com.hazelcast.jet.impl.container.ProcessingContainer;
import com.hazelcast.jet.impl.executor.Task;
import com.hazelcast.jet.impl.util.JetUtil;
import java.util.List;

public class DestroyJobProcessor implements ContainerPayloadProcessor<Dummy> {
    private final JobManager jobManager;
    private final List<Task> networkTasks;

    public DestroyJobProcessor(JobManager jobManager) {
        this.jobManager = jobManager;
        networkTasks = jobManager.getJobContext().getExecutorContext().getNetworkTasks();
    }

    @Override
    public void process(Dummy payload) throws Exception {
        Throwable error = null;

        try {
            for (ProcessingContainer container : jobManager.containers()) {
                try {
                    container.destroy();
                } catch (Throwable e) {
                    error = e;
                }
            }

            if (error != null) {
                throw JetUtil.reThrow(error);
            }
        } finally {
            networkTasks.forEach(Task::destroy);
        }
    }
}