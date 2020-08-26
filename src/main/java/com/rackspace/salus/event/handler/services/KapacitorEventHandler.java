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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rackspace.salus.event.handler.model.EventState;
import com.rackspace.salus.event.model.kapacitor.KapacitorEvent;
import com.rackspace.salus.telemetry.entities.EventStateChange;
import com.rackspace.salus.telemetry.entities.Monitor;
import com.rackspace.salus.telemetry.repositories.BoundMonitorRepository;
import com.rackspace.salus.telemetry.repositories.EventStateChangeRepository;
import com.rackspace.salus.telemetry.repositories.MonitorRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
@Slf4j
/**
 * TODO:
 *  1. What happens when tasks are disabled?
 *    * task should be removed from kapacitor, so not action needed here
 *    * Will we have an excludedResourceId for tasks?
 *  Possible Problems:
 *  https://github.com/influxdata/kapacitor/issues/2285
 *  https://github.com/influxdata/kapacitor/issues/2373 - currently a blocking issue
 */
public class KapacitorEventHandler {

  private final BoundMonitorRepository boundMonitorRepository;
  private final EventStateChangeRepository eventStateChangeRepository;
  private final MonitorRepository monitorRepository;

  private final EventProducer eventProducer;
  private final ObjectMapper objectMapper;
  private final TaskWarmupTracker taskWarmupTracker;
  private Map<String/*eventId*/, EventState> eventStates = new ConcurrentHashMap<>();
  private static final Duration DEFAULT_METRIC_WINDOW = Duration.ofMinutes(30);
  private static final double METRIC_WINDOW_MULTIPLIER = 1.5;

  @Autowired
  public KapacitorEventHandler(
      BoundMonitorRepository boundMonitorRepository,
      EventStateChangeRepository eventStateChangeRepository,
      MonitorRepository monitorRepository,
      EventProducer eventProducer,
      ObjectMapper objectMapper, TaskWarmupTracker taskWarmupTracker) {
    this.boundMonitorRepository = boundMonitorRepository;
    this.eventStateChangeRepository = eventStateChangeRepository;
    this.monitorRepository = monitorRepository;
    this.eventProducer = eventProducer;
    this.objectMapper = objectMapper;
    this.taskWarmupTracker = taskWarmupTracker;
  }

  void processKapacitorEvent(KapacitorEvent kapacitorEvent) {
    String alertGroup = kapacitorEvent.getAlertGroupId();

    EventState eventState = eventStates.getOrDefault(alertGroup, new EventState().setId(alertGroup));
    boolean stateChanged = eventState.insertEvent(kapacitorEvent);

    if (!stateChanged) {
      log.trace("No processing required since state is unchanged for task={}, state={}",
          alertGroup, kapacitorEvent.getLevel());
      return;
    }

    // retrieve only recent events for use in the final state evaluation
    List<KapacitorEvent> contributingEvents = getRelevantEvents(eventState);

    if (!isTaskWarm(eventState, contributingEvents)) {
      return;
    }

    String newState = determineNewEventState(contributingEvents);
    if (newState == null) {
      log.debug("Quorum was not met for task={}", alertGroup);
      return;
    }

    String oldState = getPreviousKnownState(eventState);
    if (newState.equals(oldState)) {
      log.debug("Task={} state={} is unchanged", alertGroup, newState);
      return;
    }
    log.info("Task={} changed state from {} to {}", alertGroup, oldState, newState);

    handleStateChange(eventState, kapacitorEvent, contributingEvents, newState, oldState);
  }

  private void handleStateChange(EventState eventState, KapacitorEvent triggerEvent,
      List<KapacitorEvent> contributingEvents, String newState, String oldState) {

    eventState.setPreviousState(oldState);
    eventState.setCurrentState(newState);
    eventState.setPreviousStateChangeTimestamp(eventState.getCurrentStateProcessedTimestamp());
    eventState.setCurrentStateProcessedTimestamp(Instant.now());
    eventStates.put(eventState.getId(), eventState); // TODO: is this put needed?

    String serializedEvents;
    try {
      serializedEvents = objectMapper.writeValueAsString(contributingEvents);
    } catch (JsonProcessingException e) {
      log.error("Failed to serialize list of contributing events={}", contributingEvents, e);
      serializedEvents = "Unable to parse events";
    }

    EventStateChange eventStateChange = new EventStateChange()
        .setTenantId(triggerEvent.getTenantId())
        .setResourceId(triggerEvent.getResourceId())
        .setMonitorId(triggerEvent.getMonitorId())
        .setTaskId(triggerEvent.getTaskId())
        .setState(newState)
        .setPreviousState(oldState)
        .setContributingEvents(serializedEvents)
        .setMetricTimestamp(triggerEvent.getTime().toInstant())
        .setEvaluationTimestamp(Instant.now());

    eventStateChange = eventStateChangeRepository.save(eventStateChange);

    // always send events to kafka.
    // in the case of a backlog / outage, downstream systems are responsible for handling/grouping old events
    eventProducer.sendStateChange(eventStateChange);
  }

  /**
   * Retrieve the last stored state for a task.
   *
   * @param eventState
   * @return The previous state if one exists, otherwise null.
   */
  private String getPreviousKnownState(EventState eventState) {
    if (eventState.getCurrentState() != null) {
      return eventState.getCurrentState();
    }

    return eventStateChangeRepository.findFirstByTenantIdAndResourceIdAndMonitorIdAndTaskId(
        eventState.getTenantId(),
        eventState.getResourceId(),
        eventState.getMonitorId(),
        eventState.getTaskId())
        .map(EventStateChange::getState).orElse(null);
  }

  /**
   * Returns a filtered list of events that are within the allowed time window.
   * Any "old" events will be disregarded as they are no longer relevant to the state change
   * calculations.
   * @param eventState
   * @return A filtered list of seen events.  At most one per zone.
   */
  private List<KapacitorEvent> getRelevantEvents(EventState eventState) {
    Instant window = getTimeWindowForStateCalculation(eventState);
    return eventState.getEvents().stream()
        .filter(e -> isRelevant(e, window))
        .collect(Collectors.toList());
  }

  /**
   * Calculate the quorum state from a list of events.
   *
   * @param states A list of KapacitorEvents to be considered in the quorum calculation.
   * @return A state value if quorum is met, otherwise false.
   */
  private String determineNewEventState(List<KapacitorEvent> states) {
    int quorumCount =  (int) Math.floor((double) states.size() / 2) + 1;

    return states.stream()
        // convert list to a map of state counts
        .collect(Collectors.groupingBy(KapacitorEvent::getLevel, Collectors.counting()))
        // then select any item that meets quorum
        .entrySet().stream().filter(count -> count.getValue() >= quorumCount)
        .findFirst().map(Entry::getKey).orElse(null /*TODO should this be an indeterminate status?*/);
  }

  /**
   * Determine if an event is within the required time window to be used as part of the
   * final alert state calculation.
   *
   * @param event A kapacitor event
   * @param window The oldest cutoff timestamp for events to be taken into consideration.
   * @return True if the event's timestamp is greater than the window, otherwise false.
   */
  private boolean isRelevant(KapacitorEvent event, Instant window) {
    return event.getTime().toInstant().isAfter(window);
  }

  /**
   * Get the time period that can be used to determine which events should contribute towards
   * a state change calculation.
   *
   * This is based on the time of the latest event seen and the period of the corresponding monitor.
   *
   * @param eventState
   * @return
   */
  private Instant getTimeWindowForStateCalculation(EventState eventState) {
    Instant newestDate = eventState.getEvents().stream()
        .map(KapacitorEvent::getTime)
        .max(Date::compareTo)
        .get()
        .toInstant();

    Duration interval = getMonitorInterval(eventState.getTenantId(), eventState.getMonitorId());
    long windowSeconds = (long) (interval.toSeconds() * METRIC_WINDOW_MULTIPLIER);

    return newestDate.minusSeconds(windowSeconds);
  }

  /**
   * Validates enough events have been received to confidently evaluate a valid state.
   * For remote monitor types this relates to the number of zones configured, for agent monitors
   * only a single event must be seen.
   *
   * This is required to handle new tasks that are still "warming up" and for any service restarts
   * where previous state has been lost.
   *
   * Once an EventState is classed as warm it will remain that way until a service restart occurs.
   *
   * @param eventState The EventState that is being evaluated.
   * @param contributingEvents The "recent" observed events for each zone relating to this task event.
   * @return True if the enough events have been seen, otherwise false.
   */
  private boolean isTaskWarm(EventState eventState, List<KapacitorEvent> contributingEvents) {
    if (eventState.isWarm()) {
      log.trace("Task was previous set as warm, task={}", eventState.getId());
      return true;
    }

    int warmth;
    int totalZonesConfigured = boundMonitorRepository.countAllByResourceIdAndMonitor_IdAndMonitor_TenantId(
        eventState.getResourceId(),
        eventState.getMonitorId(),
        eventState.getTenantId());

    if (contributingEvents.size() == totalZonesConfigured) {
      log.debug("Setting task={} as warm", eventState.getId());
      eventState.setWarm(true);
      return true;
    } else if (contributingEvents.size() > totalZonesConfigured) {
      log.warn("Too many contributing events seen. "
              + "Monitor configured in {} zones but saw {} events for task={}",
          totalZonesConfigured, contributingEvents.size(), eventState.getId());
      eventState.setWarm(true);
      return true;
    } else if ((warmth = taskWarmupTracker.getTaskWarmth(eventState.getId())) > totalZonesConfigured) {
      // This helps account for problematic zones without having to query / rely on their stored state.
      log.warn("Alarm warmth {} greater than configures zones. "
              + "Monitor configured in {} zones but saw {} events for task={}",
          warmth, totalZonesConfigured, contributingEvents.size(), eventState.getId());
      eventState.setWarm(true);
      return true;
    } else {
      log.debug("Alarm warmth {} is too low. Monitor configured in {} zones but saw {} events for task={}",
          warmth, totalZonesConfigured, contributingEvents.size(), eventState.getId());
      return false;
    }

    // TODO: is this link worthwhile?
    // Attached is a diff with a proposed code change to propagate the expected zone count into remote, bound monitors. The change also includes that as an extra label sent down to the agent to include with metrics:
    // https://jira.rax.io/secure/attachment/291447/291447_Introduce_expected_zone_count_field.patch
    // this would prevent the need for a db lookup on bound monitors
  }

  private void isTaskDisabled() {
    // if the task is disabled we should skip any alerting
    // this would only happen due to race-condition - delayed events getting analyzed after it has
    // already been disabled

    // TODO: whenever a task is disabled we should probably remove it from kapacitor and set the stored state to DISABLED?
  }

  private Duration getMonitorInterval(String tenantId, UUID monitorId) {
    return monitorRepository.findByIdAndTenantId(monitorId, tenantId)
        .map(Monitor::getInterval)
        .orElse(DEFAULT_METRIC_WINDOW);
  }
}
