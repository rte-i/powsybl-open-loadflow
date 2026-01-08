package com.powsybl.demo.model;

import java.util.List;

public record NetworkMetadata(List<BusMetadata> buses, List<BranchMetadata> branches) {
}
