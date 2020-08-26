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

package com.rackspace.salus.event.handler.model;

import com.rackspace.salus.event.model.kapacitor.KapacitorEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.Data;

@Data
public class EventState {

  String id;
  String tenantId;
  String resourceId;
  UUID monitorId;
  UUID taskId;
  String currentState;
  Instant currentStateProcessedTimestamp;
  String previousState;
  Instant previousStateChangeTimestamp;

  Map<String/*zoneId*/, KapacitorEvent> eventsByZone = new HashMap();

  boolean warm = false;

  public List<KapacitorEvent> getEvents() {
    return new ArrayList<>(this.eventsByZone.values());
  }

  /**
   * The newest kapacitor event seen.
   *
   * @param event
   * @return True if it changed state, otherwise false.
   */
  public boolean insertEvent(KapacitorEvent event) {
    String zoneId = event.getZoneId();
    KapacitorEvent oldEvent = eventsByZone.get(zoneId);
    eventsByZone.put(zoneId, event);

    return oldEvent == null || !oldEvent.getLevel().equals(event.getLevel());
  }
}
