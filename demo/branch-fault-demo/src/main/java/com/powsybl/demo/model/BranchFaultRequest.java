package com.powsybl.demo.model;

import com.powsybl.sc.extensions.BranchFaultSpec;
import com.powsybl.shortcircuit.BranchFault;
import com.powsybl.shortcircuit.Fault;

import java.util.Objects;

public record BranchFaultRequest(
        String id,
        String branchId,
        double alpha,
        double r,
        double x,
        Fault.ConnectionType connection,
        Fault.FaultType faultType,
        BranchFaultSpec.BranchSide referenceSide
) {

    public BranchFaultRequest {
        Objects.requireNonNull(branchId, "branchId is required");
        connection = connection == null ? Fault.ConnectionType.SERIES : connection;
        faultType = faultType == null ? Fault.FaultType.THREE_PHASE : faultType;
        referenceSide = referenceSide == null ? BranchFaultSpec.BranchSide.FROM : referenceSide;
        if (Double.isNaN(alpha)) {
            throw new IllegalArgumentException("Branch fault alpha cannot be NaN");
        }
        if (alpha < 0.0 || alpha > 1.0) {
            throw new IllegalArgumentException("Branch fault alpha must be within [0, 1]");
        }
    }

    public BranchFault toFault() {
        return new BranchFault(resolvedId(), branchId, r, x, connection, faultType, alpha);
    }

    public String resolvedId() {
        return (id == null || id.isBlank()) ? branchId + "_ALPHA_" + alpha : id;
    }
}
