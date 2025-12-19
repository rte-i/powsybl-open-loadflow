/**
 * Copyright (c) 2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.adm;

import com.powsybl.commons.PowsyblException;
import com.powsybl.iidm.network.TwoSides;

import java.util.Objects;

/**
 * Definition of a virtual node used to split a branch when modelling a branch fault.
 */
public final class BranchFaultVirtualNode {

    private static final double EPS = 1e-9;

    private final String faultId;
    private final String branchId;
    private final double alpha;
    private final TwoSides referenceSide;

    private BranchFaultVirtualNode(Builder builder) {
        this.faultId = builder.faultId;
        this.branchId = builder.branchId;
        this.alpha = builder.alpha;
        this.referenceSide = builder.referenceSide;
    }

    public String getFaultId() {
        return faultId;
    }

    public String getBranchId() {
        return branchId;
    }

    public double getAlpha() {
        return alpha;
    }

    public TwoSides getReferenceSide() {
        return referenceSide;
    }

    double getPortionFromBus1() {
        if (referenceSide == TwoSides.ONE) {
            return alpha;
        }
        return 1.0 - alpha;
    }

    boolean isCloseToBus(double portion) {
        return Math.abs(portion) < EPS;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private String faultId;
        private String branchId;
        private double alpha;
        private TwoSides referenceSide = TwoSides.ONE;

        private Builder() {
        }

        public Builder setFaultId(String faultId) {
            this.faultId = Objects.requireNonNull(faultId);
            return this;
        }

        public Builder setBranchId(String branchId) {
            this.branchId = Objects.requireNonNull(branchId);
            return this;
        }

        public Builder setAlpha(double alpha) {
            this.alpha = alpha;
            return this;
        }

        public Builder setReferenceSide(TwoSides referenceSide) {
            this.referenceSide = Objects.requireNonNull(referenceSide);
            return this;
        }

        public BranchFaultVirtualNode build() {
            if (faultId == null || faultId.isEmpty()) {
                throw new PowsyblException("Fault id is not defined");
            }
            if (branchId == null || branchId.isEmpty()) {
                throw new PowsyblException("Branch id is not defined");
            }
            if (alpha < 0.0 || alpha > 1.0) {
                throw new PowsyblException("Alpha must belong to [0, 1]");
            }
            return new BranchFaultVirtualNode(this);
        }
    }
}
