package com.berrx.service.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * DTO классы для DEX Screener API
 */

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class DexScreenerResponse {
    private String schemaVersion;
    private List<DexPool> pairs;
}
