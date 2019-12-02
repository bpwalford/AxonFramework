/*
 * Copyright (c) 2010-2019. Axon Framework
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

package org.axonframework.axonserver.connector.event.axon;

import org.axonframework.axonserver.connector.AxonServerConfiguration;
import org.axonframework.axonserver.connector.AxonServerConnectionManager;
import org.axonframework.axonserver.connector.event.StubServer;
import org.axonframework.eventhandling.GenericDomainEventMessage;
import org.axonframework.eventhandling.GenericEventMessage;
import org.axonframework.eventhandling.TrackingEventStream;
import org.axonframework.eventsourcing.eventstore.DomainEventStream;
import org.axonframework.eventsourcing.eventstore.EventStoreException;
import org.axonframework.messaging.Message;
import org.axonframework.messaging.unitofwork.DefaultUnitOfWork;
import org.axonframework.messaging.unitofwork.UnitOfWork;
import org.axonframework.serialization.json.JacksonSerializer;
import org.axonframework.serialization.upcasting.event.EventUpcaster;
import org.axonframework.serialization.upcasting.event.IntermediateEventRepresentation;
import org.axonframework.serialization.xml.XStreamSerializer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class AxonServerEventStoreTest {

    private StubServer server;
    private AxonServerEventStore testSubject;
    private EventUpcaster upcasterChain;

    @Before
    public void setUp() throws Exception {
        server = new StubServer(6123);
        server.start();
        upcasterChain = mock(EventUpcaster.class);
        when(upcasterChain.upcast(any())).thenAnswer(i -> i.getArgument(0));
        AxonServerConfiguration config = AxonServerConfiguration.builder()
                                                                .servers("localhost:6123")
                                                                .componentName("JUNIT")
                                                                .flowControl(2, 1, 1)
                                                                .build();
        AxonServerConnectionManager axonServerConnectionManager =
                AxonServerConnectionManager.builder()
                                           .axonServerConfiguration(config)
                                           .build();
        testSubject = AxonServerEventStore.builder()
                                          .configuration(config)
                                          .platformConnectionManager(axonServerConnectionManager)
                                          .upcasterChain(upcasterChain)
                                          .eventSerializer(JacksonSerializer.defaultSerializer())
                                          .snapshotSerializer(XStreamSerializer.defaultSerializer())
                                          .build();
    }

    @After
    public void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    public void testPublishAndConsumeEvents() throws Exception {
        UnitOfWork<Message<?>> uow = DefaultUnitOfWork.startAndGet(null);
        testSubject.publish(GenericEventMessage.asEventMessage("Test1"),
                            GenericEventMessage.asEventMessage("Test2"),
                            GenericEventMessage.asEventMessage("Test3"));
        uow.commit();

        TrackingEventStream stream = testSubject.openStream(null);

        List<String> received = new ArrayList<>();
        while (stream.hasNextAvailable(100, TimeUnit.MILLISECONDS)) {
            received.add(stream.nextAvailable().getPayload().toString());
        }
        stream.close();

        assertEquals(Arrays.asList("Test1", "Test2", "Test3"), received);
    }

    @Test
    public void testLoadEventsWithMultiUpcaster() {
        reset(upcasterChain);
        when(upcasterChain.upcast(any())).thenAnswer(invocation -> {
            Stream<IntermediateEventRepresentation> si = invocation.getArgument(0);
            return si.flatMap(i -> Stream.of(i, i));
        });
        testSubject.publish(new GenericDomainEventMessage<>("aggregateType", "aggregateId", 0, "Test1"),
                            new GenericDomainEventMessage<>("aggregateType", "aggregateId", 1, "Test2"),
                            new GenericDomainEventMessage<>("aggregateType", "aggregateId", 2, "Test3"));

        DomainEventStream actual = testSubject.readEvents("aggregateId");
        assertTrue(actual.hasNext());
        assertEquals("Test1", actual.next().getPayload());
        assertEquals("Test1", actual.next().getPayload());
        assertEquals("Test2", actual.next().getPayload());
        assertEquals("Test2", actual.next().getPayload());
        assertEquals("Test3", actual.next().getPayload());
        assertEquals("Test3", actual.next().getPayload());
        assertFalse(actual.hasNext());
    }

    @Test
    public void testLoadSnapshotAndEventsWithMultiUpcaster() {
        reset(upcasterChain);
        when(upcasterChain.upcast(any())).thenAnswer(invocation -> {
            Stream<IntermediateEventRepresentation> si = invocation.getArgument(0);
            return si.flatMap(i -> Stream.of(i, i));
        });
        testSubject.publish(new GenericDomainEventMessage<>("aggregateType", "aggregateId", 0, "Test1"),
                            new GenericDomainEventMessage<>("aggregateType", "aggregateId", 1, "Test2"),
                            new GenericDomainEventMessage<>("aggregateType", "aggregateId", 2, "Test3"));
        testSubject.storeSnapshot(new GenericDomainEventMessage<>("aggregateType", "aggregateId", 1, "Snapshot1"));

        DomainEventStream actual = testSubject.readEvents("aggregateId");
        assertTrue(actual.hasNext());
        assertEquals("Snapshot1", actual.next().getPayload());
        assertEquals("Snapshot1", actual.next().getPayload());
        assertEquals("Test3", actual.next().getPayload());
        assertEquals("Test3", actual.next().getPayload());
        assertFalse(actual.hasNext());
    }

    @Test(expected = EventStoreException.class)
    public void testLastSequenceNumberFor() {
        testSubject.lastSequenceNumberFor("Agg1");
    }

    @Test
    public void testCreateStreamableMessageSourceForContext() {
        assertNotNull(testSubject.createStreamableMessageSourceForContext("some-context"));
    }
}
