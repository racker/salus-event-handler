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

import java.io.Serializable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Service;

@Service
public class TaskWarmupTracker implements Serializable {

  // Use these to cheaply avoid overflow in extreme situations
  private static final int RESET_AT = Integer.MAX_VALUE / 2;
  private static final int RESET_TO = 50;

  private final Map<String, AtomicInteger> warmingTasks;

  public TaskWarmupTracker() {
    this.warmingTasks = new ConcurrentHashMap<>();
  }

  // TODO: do we need to reset task warmth when they change or can we get away with saying its still warm?
  // should probably consume task events for any updates to reset on changes and disables
  // probably not for disabled tasks?  they should have been removed from kapacitor.

  /**
   * Get the number of events that have been seen for this task while it "warms up".
   *
   * If the number of contributing events in {@link KapacitorEventHandler} do not match the
   * number of zones configured, this will be called to keep track of how many events have been seen
   * over time.
   *
   * If the number of events grows larger than the number of zones it is likely that at least one
   * zone is experiencing issues.  A choice can be made to class the task as warm despite
   * missing events or to continue to ignore any potential state changes until events from all
   * zones have been seen.
   *
   * @param key The tenant:resource:monitor:task combo key
   * @return The number of events that have been tracked while the task warms up.
   */
  public int getTaskWarmth(String key) {
    AtomicInteger warmth = warmingTasks.putIfAbsent(key, new AtomicInteger(1));

    if (warmth != null) {
      int value = warmth.incrementAndGet();

      // Once the value reaches RESET_AT, we reset it to RESET_TO
      if (value >= RESET_AT) {
        // Noting that retrieval operations do not block and can overlap with update operations
        warmingTasks.get(key).set(RESET_TO);
      }

      // We never return more than RESET_TO
      return Math.min(value, RESET_TO);
    } else {
      return 1;
    }
  }

}
