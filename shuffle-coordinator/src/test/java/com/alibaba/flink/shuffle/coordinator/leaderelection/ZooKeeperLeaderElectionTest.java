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

package com.alibaba.flink.shuffle.coordinator.leaderelection;

import com.alibaba.flink.shuffle.common.config.Configuration;
import com.alibaba.flink.shuffle.coordinator.highavailability.DefaultLeaderElectionService;
import com.alibaba.flink.shuffle.coordinator.highavailability.DefaultLeaderRetrievalService;
import com.alibaba.flink.shuffle.coordinator.highavailability.HaServices;
import com.alibaba.flink.shuffle.coordinator.highavailability.LeaderContender;
import com.alibaba.flink.shuffle.coordinator.highavailability.LeaderElectionDriver;
import com.alibaba.flink.shuffle.coordinator.highavailability.LeaderElectionService;
import com.alibaba.flink.shuffle.coordinator.highavailability.LeaderInformation;
import com.alibaba.flink.shuffle.coordinator.highavailability.LeaderRetrievalDriver;
import com.alibaba.flink.shuffle.coordinator.highavailability.zookeeper.ZooKeeperHaServices;
import com.alibaba.flink.shuffle.coordinator.highavailability.zookeeper.ZooKeeperLeaderElectionDriver;
import com.alibaba.flink.shuffle.coordinator.highavailability.zookeeper.ZooKeeperSingleLeaderRetrievalDriver;
import com.alibaba.flink.shuffle.coordinator.highavailability.zookeeper.ZooKeeperUtils;
import com.alibaba.flink.shuffle.coordinator.leaderretrieval.TestingLeaderRetrievalEventHandler;
import com.alibaba.flink.shuffle.core.config.ClusterOptions;
import com.alibaba.flink.shuffle.core.config.HighAvailabilityOptions;
import com.alibaba.flink.shuffle.core.utils.TestLogger;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.api.CreateBuilder;
import org.apache.curator.framework.recipes.cache.ChildData;
import org.apache.curator.framework.recipes.cache.NodeCache;
import org.apache.curator.framework.recipes.cache.NodeCacheListener;
import org.apache.curator.test.TestingServer;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Matchers;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * Tests for the {@link ZooKeeperLeaderElectionDriver} and the {@link
 * ZooKeeperSingleLeaderRetrievalDriver}. To directly test the {@link ZooKeeperLeaderElectionDriver}
 * and {@link ZooKeeperSingleLeaderRetrievalDriver}, some simple tests will use {@link
 * TestingLeaderElectionEventHandler} which will not write the leader information to ZooKeeper. For
 * the complicated tests(e.g. multiple leaders), we will use {@link DefaultLeaderElectionService}
 * with {@link TestingContender}.
 */
public class ZooKeeperLeaderElectionTest extends TestLogger {

    private static final Logger LOG = LoggerFactory.getLogger(ZooKeeperLeaderElectionTest.class);

    private TestingServer testingServer;

    private Configuration configuration;

    private CuratorFramework client;

    private static final String TEST_URL = "akka//user/shufflmanager";

    private static final LeaderInformation TEST_LEADER =
            new LeaderInformation(UUID.randomUUID(), TEST_URL);

    private static final long timeout = 200L * 1000L;

    @Before
    public void before() {
        // loop to avoid port conflict
        int maxRetries = 10;
        for (int retry = 0; retry < maxRetries; ++retry) {
            try {
                testingServer = new TestingServer();
                break;
            } catch (Throwable throwable) {
                if (retry == maxRetries - 1) {
                    throw new RuntimeException(
                            "Could not start ZooKeeper testing cluster.", throwable);
                }
            }
        }

        configuration = new Configuration();

        configuration.setString(
                HighAvailabilityOptions.HA_ZOOKEEPER_QUORUM, testingServer.getConnectString());
        configuration.setString(HighAvailabilityOptions.HA_MODE, "zookeeper");

        client = ZooKeeperUtils.startCuratorFramework(configuration);
    }

    @After
    public void after() throws IOException {
        if (client != null) {
            client.close();
            client = null;
        }

        if (testingServer != null) {
            testingServer.stop();
            testingServer = null;
        }
    }

    /** Tests that the ZooKeeperLeaderElection/RetrievalService return both the correct URL. */
    @Test
    public void testZooKeeperLeaderElectionRetrieval() throws Exception {

        final TestingLeaderElectionEventHandler electionEventHandler =
                new TestingLeaderElectionEventHandler(TEST_LEADER);
        final TestingLeaderRetrievalEventHandler retrievalEventHandler =
                new TestingLeaderRetrievalEventHandler();
        LeaderElectionDriver leaderElectionDriver = null;
        LeaderRetrievalDriver leaderRetrievalDriver = null;
        try {

            leaderElectionDriver = createAndInitLeaderElectionDriver(client, electionEventHandler);
            leaderRetrievalDriver =
                    ZooKeeperUtils.createLeaderRetrievalDriverFactory(
                                    client,
                                    configuration,
                                    ZooKeeperHaServices.SHUFFLE_MANAGER_LEADER_RETRIEVAL_PATH,
                                    HaServices.LeaderReceptor.SHUFFLE_WORKER)
                            .createLeaderRetrievalDriver(
                                    retrievalEventHandler, retrievalEventHandler::handleError);

            electionEventHandler.waitForLeader(timeout);
            assertThat(electionEventHandler.getConfirmedLeaderInformation(), is(TEST_LEADER));

            retrievalEventHandler.waitForNewLeader(timeout);

            assertThat(
                    retrievalEventHandler.getLeaderSessionID(),
                    is(TEST_LEADER.getLeaderSessionID()));
            assertThat(retrievalEventHandler.getAddress(), is(TEST_LEADER.getLeaderAddress()));
        } finally {
            if (leaderElectionDriver != null) {
                leaderElectionDriver.close();
            }
            if (leaderRetrievalDriver != null) {
                leaderRetrievalDriver.close();
            }
        }
    }

    /**
     * Tests repeatedly the reelection of still available LeaderContender. After a contender has
     * been elected as the leader, it is removed. This forces the DefaultLeaderElectionService to
     * elect a new leader.
     */
    @Test
    public void testZooKeeperReelection() throws Exception {
        long deadlineTime = System.nanoTime() + 5L * 60 * 1000000000;
        int num = 10;

        DefaultLeaderElectionService[] leaderElectionService =
                new DefaultLeaderElectionService[num];
        TestingContender[] contenders = new TestingContender[num];
        DefaultLeaderRetrievalService leaderRetrievalService = null;

        TestingListener listener = new TestingListener();

        try {
            leaderRetrievalService =
                    ZooKeeperUtils.createLeaderRetrievalService(
                            client,
                            configuration,
                            ZooKeeperHaServices.SHUFFLE_MANAGER_LEADER_RETRIEVAL_PATH,
                            HaServices.LeaderReceptor.SHUFFLE_WORKER);

            LOG.debug("Start leader retrieval service for the TestingListener.");

            leaderRetrievalService.start(listener);

            for (int i = 0; i < num; i++) {
                leaderElectionService[i] =
                        ZooKeeperUtils.createLeaderElectionService(
                                client,
                                configuration,
                                ZooKeeperHaServices.SHUFFLE_MANAGER_LEADER_LATCH_PATH,
                                ZooKeeperHaServices.SHUFFLE_MANAGER_LEADER_RETRIEVAL_PATH);
                contenders[i] = new TestingContender(createAddress(i), leaderElectionService[i]);

                LOG.debug("Start leader election service for contender #{}.", i);

                leaderElectionService[i].start(contenders[i]);
            }

            String pattern = TEST_URL + "_" + "(\\d+)";
            Pattern regex = Pattern.compile(pattern);

            int numberSeenLeaders = 0;
            long timeLeft = deadlineTime - System.nanoTime();
            while (timeLeft > 0 && numberSeenLeaders < num) {
                LOG.debug("Wait for new leader #{}.", numberSeenLeaders);
                String address = listener.waitForNewLeader(timeLeft);

                Matcher m = regex.matcher(address);

                if (m.find()) {
                    int index = Integer.parseInt(m.group(1));

                    TestingContender contender = contenders[index];

                    // check that the retrieval service has retrieved the correct leader
                    if (address.equals(createAddress(index))
                            && listener.getLeaderSessionID()
                                    .equals(contender.getLeaderSessionID())) {
                        // kill the election service of the leader
                        LOG.debug(
                                "Stop leader election service of contender #{}.",
                                numberSeenLeaders);
                        leaderElectionService[index].stop();
                        leaderElectionService[index] = null;

                        numberSeenLeaders++;
                    }
                } else {
                    fail("Did not find the leader's index.");
                }
            }

            assertFalse(
                    "Did not complete the leader reelection in time.",
                    System.nanoTime() >= deadlineTime);
            assertEquals(num, numberSeenLeaders);

        } finally {
            if (leaderRetrievalService != null) {
                leaderRetrievalService.stop();
            }

            for (DefaultLeaderElectionService electionService : leaderElectionService) {
                if (electionService != null) {
                    electionService.stop();
                }
            }
        }
    }

    @Nonnull
    private String createAddress(int i) {
        return TEST_URL + "_" + i;
    }

    /**
     * Tests the repeated reelection of {@link LeaderContender} once the current leader dies.
     * Furthermore, it tests that new LeaderElectionServices can be started later on and that they
     * successfully register at ZooKeeper and take part in the leader election.
     */
    @Test
    public void testZooKeeperReelectionWithReplacement() throws Exception {
        int num = 3;
        int numTries = 30;

        DefaultLeaderElectionService[] leaderElectionService =
                new DefaultLeaderElectionService[num];
        TestingContender[] contenders = new TestingContender[num];
        DefaultLeaderRetrievalService leaderRetrievalService = null;

        TestingListener listener = new TestingListener();

        try {
            leaderRetrievalService =
                    ZooKeeperUtils.createLeaderRetrievalService(
                            client,
                            configuration,
                            ZooKeeperHaServices.SHUFFLE_MANAGER_LEADER_RETRIEVAL_PATH,
                            HaServices.LeaderReceptor.SHUFFLE_WORKER);

            leaderRetrievalService.start(listener);

            for (int i = 0; i < num; i++) {
                leaderElectionService[i] =
                        ZooKeeperUtils.createLeaderElectionService(
                                client,
                                configuration,
                                ZooKeeperHaServices.SHUFFLE_MANAGER_LEADER_LATCH_PATH,
                                ZooKeeperHaServices.SHUFFLE_MANAGER_LEADER_RETRIEVAL_PATH);
                contenders[i] =
                        new TestingContender(TEST_URL + "_" + i + "_0", leaderElectionService[i]);

                leaderElectionService[i].start(contenders[i]);
            }

            String pattern = TEST_URL + "_" + "(\\d+)" + "_" + "(\\d+)";
            Pattern regex = Pattern.compile(pattern);

            for (int i = 0; i < numTries; i++) {
                listener.waitForNewLeader(timeout);

                String address = listener.getAddress();

                Matcher m = regex.matcher(address);

                if (m.find()) {
                    int index = Integer.parseInt(m.group(1));
                    int lastTry = Integer.parseInt(m.group(2));

                    assertEquals(
                            listener.getLeaderSessionID(), contenders[index].getLeaderSessionID());

                    // stop leader election service = revoke leadership
                    leaderElectionService[index].stop();
                    // create new leader election service which takes part in the leader election
                    leaderElectionService[index] =
                            ZooKeeperUtils.createLeaderElectionService(
                                    client,
                                    configuration,
                                    ZooKeeperHaServices.SHUFFLE_MANAGER_LEADER_LATCH_PATH,
                                    ZooKeeperHaServices.SHUFFLE_MANAGER_LEADER_RETRIEVAL_PATH);
                    contenders[index] =
                            new TestingContender(
                                    TEST_URL + "_" + index + "_" + (lastTry + 1),
                                    leaderElectionService[index]);

                    leaderElectionService[index].start(contenders[index]);
                } else {
                    throw new Exception("Did not find the leader's index.");
                }
            }

        } finally {
            if (leaderRetrievalService != null) {
                leaderRetrievalService.stop();
            }

            for (DefaultLeaderElectionService electionService : leaderElectionService) {
                if (electionService != null) {
                    electionService.stop();
                }
            }
        }
    }

    /**
     * Tests that the current leader is notified when his leader connection information in ZooKeeper
     * are overwritten. The leader must re-establish the correct leader connection information in
     * ZooKeeper.
     */
    @Test
    public void testLeaderShouldBeCorrectedWhenOverwritten() throws Exception {
        final String faultyContenderUrl = "faultyContender";
        final String leaderPath =
                configuration.getString(ClusterOptions.REMOTE_SHUFFLE_CLUSTER_ID)
                        + ZooKeeperHaServices.SHUFFLE_MANAGER_LEADER_RETRIEVAL_PATH;

        final TestingLeaderElectionEventHandler electionEventHandler =
                new TestingLeaderElectionEventHandler(TEST_LEADER);
        final TestingLeaderRetrievalEventHandler retrievalEventHandler =
                new TestingLeaderRetrievalEventHandler();

        LeaderElectionDriver leaderElectionDriver = null;
        LeaderRetrievalDriver leaderRetrievalDriver = null;

        CuratorFramework anotherClient = null;

        try {

            leaderElectionDriver = createAndInitLeaderElectionDriver(client, electionEventHandler);

            electionEventHandler.waitForLeader(timeout);
            assertThat(electionEventHandler.getConfirmedLeaderInformation(), is(TEST_LEADER));

            anotherClient = ZooKeeperUtils.startCuratorFramework(configuration);

            LeaderInformation leaderInfo =
                    new LeaderInformation(UUID.randomUUID(), faultyContenderUrl);
            // overwrite the current leader address, the leader should notice that
            boolean dataWritten = false;

            while (!dataWritten) {
                anotherClient.delete().forPath(leaderPath);

                try {
                    anotherClient.create().forPath(leaderPath, leaderInfo.toByteArray());

                    dataWritten = true;
                } catch (KeeperException.NodeExistsException e) {
                    // this can happen if the leader election service was faster
                }
            }

            // The faulty leader should be corrected on ZooKeeper
            leaderRetrievalDriver =
                    ZooKeeperUtils.createLeaderRetrievalDriverFactory(
                                    client,
                                    configuration,
                                    ZooKeeperHaServices.SHUFFLE_MANAGER_LEADER_RETRIEVAL_PATH,
                                    HaServices.LeaderReceptor.SHUFFLE_WORKER)
                            .createLeaderRetrievalDriver(
                                    retrievalEventHandler, retrievalEventHandler::handleError);

            if (retrievalEventHandler.waitForNewLeader(timeout).equals(faultyContenderUrl)) {
                retrievalEventHandler.waitForNewLeader(timeout);
            }

            assertThat(
                    retrievalEventHandler.getLeaderSessionID(),
                    is(TEST_LEADER.getLeaderSessionID()));
            assertThat(retrievalEventHandler.getAddress(), is(TEST_LEADER.getLeaderAddress()));
        } finally {
            if (leaderElectionDriver != null) {
                leaderElectionDriver.close();
            }
            if (leaderRetrievalDriver != null) {
                leaderRetrievalDriver.close();
            }
            if (anotherClient != null) {
                anotherClient.close();
            }
        }
    }

    /**
     * Test that errors in the {@link LeaderElectionService} are correctly forwarded to the {@link
     * LeaderContender}.
     */
    @Test
    public void testExceptionForwarding() throws Exception {
        LeaderElectionDriver leaderElectionDriver = null;
        final TestingLeaderElectionEventHandler electionEventHandler =
                new TestingLeaderElectionEventHandler(TEST_LEADER);

        CuratorFramework client = null;
        final CreateBuilder mockCreateBuilder =
                mock(CreateBuilder.class, Mockito.RETURNS_DEEP_STUBS);
        final String exMsg = "Test exception";
        final Exception testException = new Exception(exMsg);

        try {
            client = spy(ZooKeeperUtils.startCuratorFramework(configuration));

            doAnswer(invocation -> mockCreateBuilder).when(client).create();

            when(mockCreateBuilder
                            .creatingParentsIfNeeded()
                            .withMode(Matchers.any(CreateMode.class))
                            .forPath(anyString(), any(byte[].class)))
                    .thenThrow(testException);

            leaderElectionDriver = createAndInitLeaderElectionDriver(client, electionEventHandler);

            electionEventHandler.waitForError(timeout);

            assertNotNull(electionEventHandler.getError());
            assertTrue(electionEventHandler.getError().getCause().getMessage().contains(exMsg));
        } finally {
            if (leaderElectionDriver != null) {
                leaderElectionDriver.close();
            }

            if (client != null) {
                client.close();
            }
        }
    }

    /**
     * Tests that there is no information left in the ZooKeeper cluster after the ZooKeeper client
     * has terminated. In other words, checks that the ZooKeeperLeaderElection service uses
     * ephemeral nodes.
     */
    @Test
    public void testEphemeralZooKeeperNodes() throws Exception {
        LeaderElectionDriver leaderElectionDriver = null;
        LeaderRetrievalDriver leaderRetrievalDriver = null;
        final TestingLeaderElectionEventHandler electionEventHandler =
                new TestingLeaderElectionEventHandler(TEST_LEADER);
        final TestingLeaderRetrievalEventHandler retrievalEventHandler =
                new TestingLeaderRetrievalEventHandler();

        CuratorFramework client = null;
        CuratorFramework client2 = null;
        NodeCache cache = null;

        try {
            client = ZooKeeperUtils.startCuratorFramework(configuration);
            client2 = ZooKeeperUtils.startCuratorFramework(configuration);

            leaderElectionDriver = createAndInitLeaderElectionDriver(client, electionEventHandler);
            leaderRetrievalDriver =
                    ZooKeeperUtils.createLeaderRetrievalDriverFactory(
                                    client2,
                                    configuration,
                                    ZooKeeperHaServices.SHUFFLE_MANAGER_LEADER_RETRIEVAL_PATH,
                                    HaServices.LeaderReceptor.SHUFFLE_WORKER)
                            .createLeaderRetrievalDriver(
                                    retrievalEventHandler, retrievalEventHandler::handleError);

            final String leaderPath =
                    configuration.getString(ClusterOptions.REMOTE_SHUFFLE_CLUSTER_ID)
                            + ZooKeeperHaServices.SHUFFLE_MANAGER_LEADER_RETRIEVAL_PATH;
            cache = new NodeCache(client2, leaderPath);

            ExistsCacheListener existsListener = new ExistsCacheListener(cache);
            DeletedCacheListener deletedCacheListener = new DeletedCacheListener(cache);

            cache.getListenable().addListener(existsListener);
            cache.start();

            electionEventHandler.waitForLeader(timeout);

            retrievalEventHandler.waitForNewLeader(timeout);

            Future<Boolean> existsFuture = existsListener.nodeExists();

            existsFuture.get(timeout, TimeUnit.MILLISECONDS);

            cache.getListenable().addListener(deletedCacheListener);

            leaderElectionDriver.close();

            // now stop the underlying client
            client.close();

            Future<Boolean> deletedFuture = deletedCacheListener.nodeDeleted();

            // make sure that the leader node has been deleted
            deletedFuture.get(timeout, TimeUnit.MILLISECONDS);

            try {
                retrievalEventHandler.waitForNewLeader(1000L);

                fail(
                        "TimeoutException was expected because there is no leader registered and "
                                + "thus there shouldn't be any leader information in ZooKeeper.");
            } catch (TimeoutException e) {
                // that was expected
            }
        } finally {
            if (leaderRetrievalDriver != null) {
                leaderRetrievalDriver.close();
            }

            if (cache != null) {
                cache.close();
            }

            if (client2 != null) {
                client2.close();
            }
        }
    }

    @Test
    public void testNotLeaderShouldNotCleanUpTheLeaderInformation() throws Exception {

        final TestingLeaderElectionEventHandler electionEventHandler =
                new TestingLeaderElectionEventHandler(TEST_LEADER);
        final TestingLeaderRetrievalEventHandler retrievalEventHandler =
                new TestingLeaderRetrievalEventHandler();
        ZooKeeperLeaderElectionDriver leaderElectionDriver = null;
        LeaderRetrievalDriver leaderRetrievalDriver = null;

        try {
            leaderElectionDriver = createAndInitLeaderElectionDriver(client, electionEventHandler);

            electionEventHandler.waitForLeader(timeout);
            assertThat(electionEventHandler.getConfirmedLeaderInformation(), is(TEST_LEADER));

            // Leader is revoked
            leaderElectionDriver.notLeader();
            electionEventHandler.waitForRevokeLeader(timeout);
            assertThat(
                    electionEventHandler.getConfirmedLeaderInformation(),
                    is(LeaderInformation.empty()));
            // The data on ZooKeeper it not be cleared
            leaderRetrievalDriver =
                    ZooKeeperUtils.createLeaderRetrievalDriverFactory(
                                    client,
                                    configuration,
                                    ZooKeeperHaServices.SHUFFLE_MANAGER_LEADER_RETRIEVAL_PATH,
                                    HaServices.LeaderReceptor.SHUFFLE_WORKER)
                            .createLeaderRetrievalDriver(
                                    retrievalEventHandler, retrievalEventHandler::handleError);

            retrievalEventHandler.waitForNewLeader(timeout);

            assertThat(
                    retrievalEventHandler.getLeaderSessionID(),
                    is(TEST_LEADER.getLeaderSessionID()));
            assertThat(retrievalEventHandler.getAddress(), is(TEST_LEADER.getLeaderAddress()));
        } finally {
            if (leaderElectionDriver != null) {
                leaderElectionDriver.close();
            }
            if (leaderRetrievalDriver != null) {
                leaderRetrievalDriver.close();
            }
        }
    }

    private static class ExistsCacheListener implements NodeCacheListener {

        final CompletableFuture<Boolean> existsPromise = new CompletableFuture<>();

        final NodeCache cache;

        public ExistsCacheListener(final NodeCache cache) {
            this.cache = cache;
        }

        public Future<Boolean> nodeExists() {
            return existsPromise;
        }

        @Override
        public void nodeChanged() throws Exception {
            ChildData data = cache.getCurrentData();

            if (data != null && !existsPromise.isDone()) {
                existsPromise.complete(true);
                cache.getListenable().removeListener(this);
            }
        }
    }

    private static class DeletedCacheListener implements NodeCacheListener {

        final CompletableFuture<Boolean> deletedPromise = new CompletableFuture<>();

        final NodeCache cache;

        public DeletedCacheListener(final NodeCache cache) {
            this.cache = cache;
        }

        public Future<Boolean> nodeDeleted() {
            return deletedPromise;
        }

        @Override
        public void nodeChanged() throws Exception {
            ChildData data = cache.getCurrentData();

            if (data == null && !deletedPromise.isDone()) {
                deletedPromise.complete(true);
                cache.getListenable().removeListener(this);
            }
        }
    }

    private ZooKeeperLeaderElectionDriver createAndInitLeaderElectionDriver(
            CuratorFramework client, TestingLeaderElectionEventHandler electionEventHandler)
            throws Exception {

        final ZooKeeperLeaderElectionDriver leaderElectionDriver =
                ZooKeeperUtils.createLeaderElectionDriverFactory(
                                client,
                                configuration,
                                ZooKeeperHaServices.SHUFFLE_MANAGER_LEADER_LATCH_PATH,
                                ZooKeeperHaServices.SHUFFLE_MANAGER_LEADER_RETRIEVAL_PATH)
                        .createLeaderElectionDriver(
                                electionEventHandler, electionEventHandler::handleError, TEST_URL);
        electionEventHandler.init(leaderElectionDriver);
        return leaderElectionDriver;
    }
}
