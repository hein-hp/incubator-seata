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
package org.apache.seata.discovery.loadbalance;

import org.apache.seata.common.rpc.RpcStatus;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Created by guoyao on 2019/2/14.
 */
public class LoadBalanceTest {

    private static final String XID = "XID";

    /**
     * Test random load balance select.
     *
     * @param addresses the addresses
     */
    @ParameterizedTest
    @MethodSource("addressProvider")
    public void testRandomLoadBalance_select(List<InetSocketAddress> addresses) {
        int runs = 10000;
        Map<InetSocketAddress, AtomicLong> counter = getSelectedCounter(runs, addresses, new RandomLoadBalance());
        for (InetSocketAddress address : counter.keySet()) {
            Long count = counter.get(address).get();
            Assertions.assertTrue(count > 0, "selecte one time at last");
        }
    }

    /**
     * Test round robin load balance select.
     *
     * @param addresses the addresses
     */
    @ParameterizedTest
    @MethodSource("addressProvider")
    public void testRoundRobinLoadBalance_select(List<InetSocketAddress> addresses) {
        int runs = 10000;
        Map<InetSocketAddress, AtomicLong> counter = getSelectedCounter(runs, addresses, new RoundRobinLoadBalance());
        for (InetSocketAddress address : counter.keySet()) {
            Long count = counter.get(address).get();
            Assertions.assertTrue(Math.abs(count - runs / (0f + addresses.size())) < 1f, "abs diff shoud < 1");
        }
    }

    /**
     * Test xid load load balance select.
     *
     * @param addresses the addresses
     */
    @ParameterizedTest
    @MethodSource("addressProvider")
    public void testXIDLoadBalance_select(List<InetSocketAddress> addresses) throws Exception {
        XIDLoadBalance loadBalance = new XIDLoadBalance();
        // ipv4
        InetSocketAddress inetSocketAddress = loadBalance.select(addresses, "127.0.0.1:8092:123456");
        Assertions.assertNotNull(inetSocketAddress);
        // ipv6
        inetSocketAddress = loadBalance.select(addresses, "2000:0000:0000:0000:0001:2345:6789:abcd:8092:123456");
        Assertions.assertNotNull(inetSocketAddress);
        // test not found tc channel
        inetSocketAddress = loadBalance.select(addresses, "127.0.0.1:8199:123456");
        Assertions.assertNotEquals(inetSocketAddress.getPort(), 8199);
    }

    /**
     * Test consistent hash load load balance select.
     *
     * @param addresses the addresses
     */
    @ParameterizedTest
    @MethodSource("addressProvider")
    public void testConsistentHashLoadBalance_select(List<InetSocketAddress> addresses) {
        int runs = 10000;
        int selected = 0;
        ConsistentHashLoadBalance loadBalance = new ConsistentHashLoadBalance();
        Map<InetSocketAddress, AtomicLong> counter = getSelectedCounter(runs, addresses, loadBalance);
        for (InetSocketAddress address : counter.keySet()) {
            if (counter.get(address).get() > 0) {
                selected++;
            }
        }
        Assertions.assertEquals(1, selected, "selected must be equal to 1");
    }

    /**
     * Test cached consistent hash load balance select.
     *
     * @param addresses the addresses
     */
    @ParameterizedTest
    @MethodSource("addressProvider")
    public void testCachedConsistentHashLoadBalance_select(List<InetSocketAddress> addresses) throws Exception {
        ConsistentHashLoadBalance loadBalance = new ConsistentHashLoadBalance();

        List<InetSocketAddress> addresses1 = new ArrayList<>(addresses);
        loadBalance.select(addresses1, XID);
        Object o1 = getConsistentHashSelectorByReflect(loadBalance);
        List<InetSocketAddress> addresses2 = new ArrayList<>(addresses);
        loadBalance.select(addresses2, XID);
        Object o2 = getConsistentHashSelectorByReflect(loadBalance);
        Assertions.assertEquals(o1, o2);

        List<InetSocketAddress> addresses3 = new ArrayList<>(addresses);
        addresses3.remove(ThreadLocalRandom.current().nextInt(addresses.size()));
        loadBalance.select(addresses3, XID);
        Object o3 = getConsistentHashSelectorByReflect(loadBalance);
        Assertions.assertNotEquals(o1, o3);
    }

    /**
     * Test least active load balance select.
     *
     * @param addresses the addresses
     */
    @ParameterizedTest
    @MethodSource("addressProvider")
    public void testLeastActiveLoadBalance_select(List<InetSocketAddress> addresses) throws Exception {
        int runs = 10000;
        int size = addresses.size();
        for (int i = 0; i < size - 1; i++) {
            RpcStatus.beginCount(addresses.get(i).toString());
        }
        InetSocketAddress socketAddress = addresses.get(size - 1);
        LoadBalance loadBalance = new LeastActiveLoadBalance();
        for (int i = 0; i < runs; i++) {
            InetSocketAddress selectAddress = loadBalance.select(addresses, XID);
            Assertions.assertEquals(selectAddress, socketAddress);
        }
        RpcStatus.beginCount(socketAddress.toString());
        RpcStatus.beginCount(socketAddress.toString());
        Map<InetSocketAddress, AtomicLong> counter = getSelectedCounter(runs, addresses, loadBalance);
        for (InetSocketAddress address : counter.keySet()) {
            Long count = counter.get(address).get();
            if (address == socketAddress) {
                Assertions.assertEquals(count, 0);
            } else {
                Assertions.assertTrue(count > 0);
            }
        }
    }

    /**
     * Gets selected counter.
     *
     * @param runs        the runs
     * @param addresses   the addresses
     * @param loadBalance the load balance
     * @return the selected counter
     */
    public Map<InetSocketAddress, AtomicLong> getSelectedCounter(int runs, List<InetSocketAddress> addresses,
                                                                 LoadBalance loadBalance) {
        Assertions.assertNotNull(loadBalance);
        Map<InetSocketAddress, AtomicLong> counter = new ConcurrentHashMap<>();
        for (InetSocketAddress address : addresses) {
            counter.put(address, new AtomicLong(0));
        }
        try {
            for (int i = 0; i < runs; i++) {
                InetSocketAddress selectAddress = loadBalance.select(addresses, XID);
                counter.get(selectAddress).incrementAndGet();
            }
        } catch (Exception e) {
            //do nothing
        }
        return counter;
    }

    /**
     * Gets ConsistentHashSelector Instance By Reflect
     *
     * @param loadBalance the loadBalance
     * @return the ConsistentHashSelector
     */
    public Object getConsistentHashSelectorByReflect(ConsistentHashLoadBalance loadBalance) throws Exception {
        Field selectorWrapperField = ConsistentHashLoadBalance.class.getDeclaredField("selectorWrapper");
        selectorWrapperField.setAccessible(true);
        Object selectWrapper = selectorWrapperField.get(loadBalance);
        Assertions.assertNotNull(selectWrapper);
        Field selectorField = selectWrapper.getClass().getDeclaredField("selector");
        selectorField.setAccessible(true);
        return selectorField.get(selectWrapper);
    }

    /**
     * Address provider object [ ] [ ].
     *
     * @return Stream<List < InetSocketAddress>>
     */
    static Stream<List<InetSocketAddress>> addressProvider() {
        return Stream.of(
                Arrays.asList(new InetSocketAddress("127.0.0.1", 8091),
                        new InetSocketAddress("127.0.0.1", 8092),
                        new InetSocketAddress("127.0.0.1", 8093),
                        new InetSocketAddress("127.0.0.1", 8094),
                        new InetSocketAddress("127.0.0.1", 8095),
                        new InetSocketAddress("2000:0000:0000:0000:0001:2345:6789:abcd", 8092))
        );
    }
}
