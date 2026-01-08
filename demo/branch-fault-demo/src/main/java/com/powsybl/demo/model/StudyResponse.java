package com.powsybl.demo.model;

import java.util.List;

public record StudyResponse(
        List<LoadFlowBusResult> loadFlow,
        List<ShortCircuitFaultResultDto> shortCircuit,
        List<String> diagnostics
) {
}
