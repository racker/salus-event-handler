This module is part of the Salus system. More information can be found in the 
[bundle repository](https://github.com/racker/salus-telemetry-bundle).

1. Receive kapacitor state change events from kafka
1. Retrieve prior stateful information related to this event from memory
    1. This includes the last event seen for each zone for this tenant:resource:monitor:task combo
1. Insert the new event into that memory store
1. Determine whether a state change occurred for this specific zone
1. If it did not, no action is needed
1. If it did, determine the list of "recent" events that will be used to perform the quorum calculation
1. Calculate the quorum state
1. If new state differs from the previous stored state in MySQL
    1. Store the new state change in MySQL
    1. Send a state change event to Kafka
