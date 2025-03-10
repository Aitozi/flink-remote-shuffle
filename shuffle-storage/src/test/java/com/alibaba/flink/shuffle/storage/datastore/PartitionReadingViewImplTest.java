/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.flink.shuffle.storage.datastore;

import com.alibaba.flink.shuffle.common.exception.ShuffleException;
import com.alibaba.flink.shuffle.core.storage.DataPartitionReader;

import org.junit.Test;

/** Tests for {@link PartitionReadingViewImpl}. */
public class PartitionReadingViewImplTest {

    private final DataPartitionReader partitionReader = new NoOpDataPartitionReader();

    @Test(expected = IllegalStateException.class)
    public void testReadAfterError() throws Exception {
        PartitionReadingViewImpl readingView = new PartitionReadingViewImpl(partitionReader);
        readingView.onError(new ShuffleException("Test exception."));
        readingView.nextBuffer();
    }

    @Test(expected = IllegalStateException.class)
    public void testOnErrorAfterError() {
        PartitionReadingViewImpl readingView = new PartitionReadingViewImpl(partitionReader);
        readingView.onError(new ShuffleException("Test exception."));
        readingView.onError(new ShuffleException("Test exception."));
    }

    @Test(expected = IllegalStateException.class)
    public void testCheckFinishAfterError() {
        PartitionReadingViewImpl readingView = new PartitionReadingViewImpl(partitionReader);
        readingView.onError(new ShuffleException("Test exception."));
        readingView.isFinished();
    }
}
