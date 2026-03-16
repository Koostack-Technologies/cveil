package com.koostack.cveil.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ConsentGraphPage(
        List<GraphNode> nodes, List<GraphEdge> edges, GraphPageInfo pageInfo) {}
