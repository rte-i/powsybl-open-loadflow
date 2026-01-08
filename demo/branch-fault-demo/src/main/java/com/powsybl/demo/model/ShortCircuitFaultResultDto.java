package com.powsybl.demo.model;

import com.powsybl.shortcircuit.Fault;
import com.powsybl.shortcircuit.FaultResult;

public record ShortCircuitFaultResultDto(
        String faultId,
        String elementId,
        Fault.Type elementType,
        Fault.FaultType faultType,
        FaultResult.Status status,
        double currentKa,
        double voltageKv,
        String diagnostic
) {
}
