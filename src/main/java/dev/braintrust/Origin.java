package dev.braintrust;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Generic pointer to an object in braintrust */
public record Origin(
        /** origin type. e.g. dataset, playground_logs */
        @JsonProperty("object_type") String objectType,
        /** id of the object. e.g. dataset id */
        @JsonProperty("object_id") String objectId,
        /** id of the specific item within the origin. e.g. dataset row id */
        @JsonProperty("id") String id,
        /** origin xact id */
        @JsonProperty("_xact_id") String xactId,
        /** creation timestamp of the origin */
        @JsonProperty("created") String createdTimestamp) {}
