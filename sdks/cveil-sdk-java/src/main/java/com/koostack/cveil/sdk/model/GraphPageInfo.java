package com.koostack.cveil.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GraphPageInfo(String nextCursor, boolean hasMore, int totalNodes, int totalEdges) {}
