package com.koostack.cveil.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PurposeRegulationLink(
        String regulationName, String lawfulBasis, String onGranted, String onDenied) {}
