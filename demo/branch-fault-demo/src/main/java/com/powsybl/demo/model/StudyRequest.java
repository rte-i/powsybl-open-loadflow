package com.powsybl.demo.model;

import java.util.Collections;
import java.util.List;

public record StudyRequest(
        List<BranchFaultRequest> branchFaults,
        List<BusFaultRequest> busFaults,
        boolean includeAllBuses
) {

    public StudyRequest {
        branchFaults = branchFaults == null ? Collections.emptyList() : List.copyOf(branchFaults);
        busFaults = busFaults == null ? Collections.emptyList() : List.copyOf(busFaults);
    }
}
