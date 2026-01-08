package com.powsybl.demo.model;

import com.powsybl.shortcircuit.BusFault;
import com.powsybl.shortcircuit.Fault;

import java.util.Objects;

public record BusFaultRequest(
        String id,
        String busId,
        double r,
        double x,
        Fault.ConnectionType connection,
        Fault.FaultType faultType
) {

    public BusFaultRequest {
        Objects.requireNonNull(busId, "busId is required");
        connection = connection == null ? Fault.ConnectionType.SERIES : connection;
        faultType = faultType == null ? Fault.FaultType.THREE_PHASE : faultType;
    }

    public BusFault toFault() {
        return new BusFault(resolvedId(), busId, r, x, connection, faultType);
    }

    public String resolvedId() {
        return (id == null || id.isBlank()) ? "BUS_FAULT_" + busId : id;
    }
}
