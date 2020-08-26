/*
 * Copyright 2020 Rackspace US, Inc.
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

package com.rackspace.salus.event.handler.services;

import static com.rackspace.salus.telemetry.messaging.KafkaMessageKeyBuilder.buildMessageKey;

import com.rackspace.salus.common.messaging.KafkaTopicProperties;
import com.rackspace.salus.telemetry.entities.EventStateChange;
import com.rackspace.salus.telemetry.messaging.StateChangeEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/**
 * Kafka event producing operations
 */
@Service
@Slf4j
public class EventProducer {

  private final KafkaTemplate<String,Object> kafkaTemplate;
  private final KafkaTopicProperties kafkaTopics;

  @Autowired
  public EventProducer(KafkaTemplate<String,Object> kafkaTemplate, KafkaTopicProperties kafkaTopics) {
    this.kafkaTemplate = kafkaTemplate;
    this.kafkaTopics = kafkaTopics;
  }

  void sendStateChange(EventStateChange eventStateChange) {
    final String topic = kafkaTopics.getStateChangeEvents();

    StateChangeEvent event = new StateChangeEvent()
        .setTenantId(eventStateChange.getTenantId())
        .setStateChangeId(eventStateChange.getId());

    log.info("Sending state change event={} to kafka", event);
    kafkaTemplate.send(topic, buildMessageKey(event), event);
  }
}
