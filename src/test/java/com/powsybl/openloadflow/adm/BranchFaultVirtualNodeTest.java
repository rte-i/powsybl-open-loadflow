/**
 * Copyright (c) 2025, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 * SPDX-License-Identifier: MPL-2.0
 */
package com.powsybl.openloadflow.adm;

import com.powsybl.iidm.network.Bus;
import com.powsybl.iidm.network.Country;
import com.powsybl.iidm.network.Line;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.Substation;
import com.powsybl.iidm.network.Terminal;
import com.powsybl.iidm.network.TopologyKind;
import com.powsybl.iidm.network.VoltageLevel;
import com.powsybl.ieeecdf.converter.IeeeCdfNetworkFactory;
import com.powsybl.math.matrix.DenseMatrixFactory;
import com.powsybl.openloadflow.equations.EquationSystem;
import com.powsybl.openloadflow.equations.VariableSet;
import com.powsybl.openloadflow.network.*;
import com.powsybl.openloadflow.network.TwoBusNetworkFactory;
import com.powsybl.openloadflow.network.impl.LfNetworkLoaderImpl;
import org.apache.commons.math3.complex.Complex;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class BranchFaultVirtualNodeTest {

    private static final double REFERENCE_SHUNT_B = 1e-6;
    private static final double[] ALPHA_SAMPLES = {0.001, 0.1, 0.5, 0.991};

    @Test
    void alphaBoundariesReusePhysicalBuses() {
        LfNetwork network = loadNetwork();
        BranchFaultVirtualNode nodeSide1 = BranchFaultVirtualNode.builder()
                .setFaultId("F0")
                .setBranchId("l12")
                .setAlpha(0.0)
                .setReferenceSide(com.powsybl.iidm.network.TwoSides.ONE)
                .build();
        BranchFaultVirtualNode nodeSide2 = BranchFaultVirtualNode.builder()
                .setFaultId("F1")
                .setBranchId("l12")
                .setAlpha(1.0)
                .setReferenceSide(com.powsybl.iidm.network.TwoSides.ONE)
                .build();
        AdmittanceVirtualNetwork.Builder builder = AdmittanceVirtualNetwork.builder(network);
        AdmittanceVirtualNetwork.BranchFaultVirtualNodeInstance instanceSide1 = builder.add(nodeSide1);
        AdmittanceVirtualNetwork.BranchFaultVirtualNodeInstance instanceSide2 = builder.add(nodeSide2);
        builder.build();

        assertEquals(network.getBusById("b1_vl_0"), instanceSide1.getBus());
        assertEquals(network.getBusById("b2_vl_0"), instanceSide2.getBus());
        assertTrue(instanceSide1.getSegments().isEmpty());
        assertTrue(instanceSide2.getSegments().isEmpty());
    }

    @Test
    void alphaBarelyAwayFromBusStillSplits() {
        LfNetwork network = loadNetwork();
        BranchFaultVirtualNode nearBus1 = BranchFaultVirtualNode.builder()
                .setFaultId("FN")
                .setBranchId("l12")
                .setAlpha(0.001)
                .setReferenceSide(com.powsybl.iidm.network.TwoSides.ONE)
                .build();
        AdmittanceVirtualNetwork.Builder builder = AdmittanceVirtualNetwork.builder(network);
        AdmittanceVirtualNetwork.BranchFaultVirtualNodeInstance instance = builder.add(nearBus1);
        builder.build();

        assertEquals(2, instance.getSegments().size());
        assertNotEquals(network.getBusById("b1_vl_0"), instance.getBus());
    }

    @Test
    void alphaBarelyAwayFromOppositeBusStillSplits() {
        LfNetwork network = loadNetwork();
        BranchFaultVirtualNode nearBus2 = BranchFaultVirtualNode.builder()
                .setFaultId("FP")
                .setBranchId("l12")
                .setAlpha(0.999)
                .setReferenceSide(com.powsybl.iidm.network.TwoSides.ONE)
                .build();
        AdmittanceVirtualNetwork.Builder builder = AdmittanceVirtualNetwork.builder(network);
        AdmittanceVirtualNetwork.BranchFaultVirtualNodeInstance instance = builder.add(nearBus2);
        builder.build();

        assertEquals(2, instance.getSegments().size());
        assertNotEquals(network.getBusById("b2_vl_0"), instance.getBus());
    }

    @Test
    void balancedMidpointSplitAndFaultImpedance() {
        LfNetwork network = loadNetwork();
        BranchFaultVirtualNode middle = BranchFaultVirtualNode.builder()
                .setFaultId("FM")
                .setBranchId("l12")
                .setAlpha(0.5)
                .setReferenceSide(com.powsybl.iidm.network.TwoSides.ONE)
                .build();
        AdmittanceVirtualNetwork.Builder builder = AdmittanceVirtualNetwork.builder(network);
        AdmittanceVirtualNetwork.BranchFaultVirtualNodeInstance instance = builder.add(middle);
        AdmittanceVirtualNetwork virtualNetwork = builder.build();
        LfBranch originalBranch = network.getBranchById("l12");

        assertEquals(2, instance.getSegments().size());
        LfBranch upstream = instance.getSegments().get(0);
        assertEquals(originalBranch.getPiModel().getX() * 0.5, upstream.getPiModel().getX(), 1e-6);
        assertEquals(originalBranch.getPiModel().getR() * 0.5, upstream.getPiModel().getR(), 1e-6);

        VariableSet<AdmittanceVariableType> variableSet = new VariableSet<>();
        AdmittanceEquationSystem system = AdmittanceEquationSystem.create(network, variableSet, virtualNetwork);
        LfBus slack = network.getBusById("b1_vl_0");
        LfBus faultBus = instance.getBus();
        groundReference(system, variableSet, slack);
        try (var matrix = AdmittanceMatrix.create(system, new DenseMatrixFactory())) {
            Complex zth = matrix.getZ(slack, faultBus);
            double faultCurrentNoRf = Complex.ONE.divide(zth).abs();
            Complex zf = new Complex(0.02, 0.01);
            double faultCurrentWithRf = Complex.ONE.divide(zth.add(zf)).abs();
            assertTrue(faultCurrentNoRf > faultCurrentWithRf);
        }
    }

    @Test
    void ieeeBalancedVirtualEqualsPhysicalSplit() {
        List<BalancedRow> rows = new ArrayList<>();
        for (double alpha : ALPHA_SAMPLES) {
            Complex virtualZ = computeIeeeVirtualBalancedZ(alpha);
            Complex physicalZ = computeIeeePhysicalBalancedZ(alpha);
            assertComplexEquals(physicalZ, virtualZ, 1e-6);
            double iNoRf = 1.0 / virtualZ.abs();
            double iWithRf = 1.0 / virtualZ.add(new Complex(0.05, 0.01)).abs();
            rows.add(new BalancedRow(alpha, virtualZ, physicalZ, iNoRf, iWithRf));
        }
        printBalancedTable(rows);
    }

    @Test
    void ieeeBalancedFaultsMatchLineImpedance() {
        LfNetwork network = loadIeee14Network();
        LfBranch branch = findBranchBetween(network, "VL2_0", "VL3_0");
        LfBus slack = network.getBusById("VL1_0");
        LfBus bus2 = branch.getBus1();
        LfBus bus3 = branch.getBus2();

        Complex zBus2 = theveninBetween(network, null, slack, bus2);
        Complex zBus3 = theveninBetween(network, null, slack, bus3);
        Complex zLine = new Complex(branch.getPiModel().getR(), branch.getPiModel().getX());

        BranchFaultVirtualNode nodeBus2 = BranchFaultVirtualNode.builder()
                .setFaultId("IEEE_NEG")
                .setBranchId(branch.getId())
                .setAlpha(0.0)
                .setReferenceSide(com.powsybl.iidm.network.TwoSides.ONE)
                .build();
        AdmittanceVirtualNetwork.Builder builderBus2 = AdmittanceVirtualNetwork.builder(network);
        AdmittanceVirtualNetwork.BranchFaultVirtualNodeInstance instanceBus2 = builderBus2.add(nodeBus2);
        Complex zAlpha0 = theveninBetween(network, builderBus2.build(), slack, instanceBus2.getBus());
        assertComplexEquals(zBus2, zAlpha0, 1e-6);

        BranchFaultVirtualNode nodeBus3 = BranchFaultVirtualNode.builder()
                .setFaultId("IEEE_POS")
                .setBranchId(branch.getId())
                .setAlpha(1.0)
                .setReferenceSide(com.powsybl.iidm.network.TwoSides.ONE)
                .build();
        AdmittanceVirtualNetwork.Builder builderBus3 = AdmittanceVirtualNetwork.builder(network);
        AdmittanceVirtualNetwork.BranchFaultVirtualNodeInstance instanceBus3 = builderBus3.add(nodeBus3);
        Complex zAlpha1 = theveninBetween(network, builderBus3.build(), slack, instanceBus3.getBus());
        assertComplexEquals(zBus3, zAlpha1, 1e-6);

        BranchFaultVirtualNode nodeMid = BranchFaultVirtualNode.builder()
                .setFaultId("IEEE_MID")
                .setBranchId(branch.getId())
                .setAlpha(0.5)
                .setReferenceSide(com.powsybl.iidm.network.TwoSides.ONE)
                .build();
        AdmittanceVirtualNetwork.Builder builderMid = AdmittanceVirtualNetwork.builder(network);
        AdmittanceVirtualNetwork.BranchFaultVirtualNodeInstance instanceMid = builderMid.add(nodeMid);
        Complex zMid = theveninBetween(network, builderMid.build(), slack, instanceMid.getBus());
        Complex expectedMid = computeIeeePhysicalBalancedZ(0.5);
        assertComplexEquals(expectedMid, zMid, 1e-6);
        double faultCurrentNoRf = 1.0 / zMid.abs();
        double faultCurrentWithRf = 1.0 / zMid.add(new Complex(0.05, 0.01)).abs();
        assertTrue(faultCurrentNoRf > faultCurrentWithRf);
    }

    @Test
    void unbalancedSequenceSplitAndRfImpact() {
        LfNetwork network = loadNetwork();
        LfBranch branch = network.getBranchById("l12");
        branch.setAsymLine(new LfAsymLine(
                new SimplePiModel().setR(0.03).setX(0.15),
                new SimplePiModel().setR(0.01).setX(0.10),
                new SimplePiModel().setR(0.012).setX(0.11),
                false, false, false));

        BranchFaultVirtualNode node = BranchFaultVirtualNode.builder()
                .setFaultId("AS")
                .setBranchId("l12")
                .setAlpha(0.4)
                .setReferenceSide(com.powsybl.iidm.network.TwoSides.ONE)
                .build();
        AdmittanceVirtualNetwork.Builder builder = AdmittanceVirtualNetwork.builder(network);
        AdmittanceVirtualNetwork.BranchFaultVirtualNodeInstance instance = builder.add(node);
        builder.build();
        LfBranch upstream = instance.getSegments().get(0);
        LfAsymLine asymLine = upstream.getAsymLine();
        assertNotNull(asymLine);
        Complex z1 = toComplex(asymLine.getPiPositiveComponent());
        Complex z2 = toComplex(asymLine.getPiNegativeComponent());
        Complex z0 = toComplex(asymLine.getPiZeroComponent());

        Complex noRf = Complex.ZERO;
        Complex zf = new Complex(0.02, 0.0);
        double singlePhaseNoRf = singlePhaseGroundCurrent(z1, z2, z0, noRf);
        double singlePhaseWithRf = singlePhaseGroundCurrent(z1, z2, z0, zf);
        assertTrue(singlePhaseNoRf > singlePhaseWithRf);

        double doublePhaseNoRf = doublePhaseGroundCurrent(z1, z2, z0, noRf);
        double doublePhaseWithRf = doublePhaseGroundCurrent(z1, z2, z0, zf);
        assertTrue(doublePhaseNoRf > doublePhaseWithRf);
    }

    @Test
    void ieeeUnbalancedFaultsReactToRf() {
        LfNetwork network = loadIeee14Network();
        LfBranch branch = findBranchBetween(network, "VL2_0", "VL3_0");
        branch.setAsymLine(new LfAsymLine(
                new SimplePiModel().setR(0.05).setX(0.25),
                new SimplePiModel().setR(branch.getPiModel().getR()).setX(branch.getPiModel().getX()),
                new SimplePiModel().setR(branch.getPiModel().getR() * 0.9).setX(branch.getPiModel().getX() * 1.1),
                false, false, false));

        BranchFaultVirtualNode node = BranchFaultVirtualNode.builder()
                .setFaultId("IEEE_ASYM")
                .setBranchId(branch.getId())
                .setAlpha(0.4)
                .setReferenceSide(com.powsybl.iidm.network.TwoSides.ONE)
                .build();
        AdmittanceVirtualNetwork.Builder builder = AdmittanceVirtualNetwork.builder(network);
        AdmittanceVirtualNetwork.BranchFaultVirtualNodeInstance instance = builder.add(node);
        builder.build();
        LfBranch upstream = instance.getSegments().get(0);
        LfBranch downstream = instance.getSegments().get(1);
        LfAsymLine upstreamAsym = upstream.getAsymLine();
        LfAsymLine downstreamAsym = downstream.getAsymLine();
        assertNotNull(upstreamAsym);
        assertNotNull(downstreamAsym);

        double alpha = node.getPortionFromBus1();
        LfAsymLine original = branch.getAsymLine();
        assertComplexEquals(toComplex(original.getPiPositiveComponent()).multiply(alpha), toComplex(upstreamAsym.getPiPositiveComponent()), 1e-6);
        assertComplexEquals(toComplex(original.getPiPositiveComponent()).multiply(1 - alpha), toComplex(downstreamAsym.getPiPositiveComponent()), 1e-6);

        Complex z1 = toComplex(upstreamAsym.getPiPositiveComponent());
        Complex z2 = toComplex(upstreamAsym.getPiNegativeComponent());
        Complex z0 = toComplex(upstreamAsym.getPiZeroComponent());

        Complex noRf = Complex.ZERO;
        Complex zf = new Complex(0.03, 0.02);
        double singlePhaseNoRf = singlePhaseGroundCurrent(z1, z2, z0, noRf);
        double singlePhaseWithRf = singlePhaseGroundCurrent(z1, z2, z0, zf);
        assertTrue(singlePhaseNoRf > singlePhaseWithRf);

        double doublePhaseNoRf = doublePhaseGroundCurrent(z1, z2, z0, noRf);
        double doublePhaseWithRf = doublePhaseGroundCurrent(z1, z2, z0, zf);
        assertTrue(doublePhaseNoRf > doublePhaseWithRf);
    }

    @Test
    void ieeeAsymmetricalFaultCurrentsTable() {
        List<AsymRow> rows = new ArrayList<>();
        for (double alpha : ALPHA_SAMPLES) {
            rows.add(computeIeeeAsymCurrents(alpha));
        }
        printAsymTable(rows);
    }

    private static LfNetwork loadNetwork() {
        Network network = TwoBusNetworkFactory.create();
        return LfNetwork.load(network, new LfNetworkLoaderImpl(), new FirstSlackBusSelector()).get(0);
    }

    private static LfNetwork loadIeee14Network() {
        Network network = IeeeCdfNetworkFactory.create14();
        return LfNetwork.load(network, new LfNetworkLoaderImpl(), new FirstSlackBusSelector()).get(0);
    }

    private static Complex computeIeeeVirtualBalancedZ(double alpha) {
        LfNetwork network = loadIeee14Network();
        LfBranch branch = findBranchBetween(network, "VL2_0", "VL3_0");
        BranchFaultVirtualNode node = BranchFaultVirtualNode.builder()
                .setFaultId("IEEE_VIRTUAL_" + alpha)
                .setBranchId(branch.getId())
                .setAlpha(alpha)
                .setReferenceSide(com.powsybl.iidm.network.TwoSides.ONE)
                .build();
        AdmittanceVirtualNetwork.Builder builder = AdmittanceVirtualNetwork.builder(network);
        AdmittanceVirtualNetwork.BranchFaultVirtualNodeInstance instance = builder.add(node);
        return theveninBetween(network, builder.build(), network.getBusById("VL1_0"), instance.getBus());
    }

    private static Complex computeIeeePhysicalBalancedZ(double alpha) {
        Network iidm = IeeeCdfNetworkFactory.create14();
        String suffix = String.format(Locale.ROOT, "%.3f", alpha).replace('.', '_');
        PhysicalSplitResult splitResult = physicallySplitLine(iidm, "VL2_0", "VL3_0", alpha, suffix);
        LfNetwork network = LfNetwork.load(iidm, new LfNetworkLoaderImpl(), new FirstSlackBusSelector()).get(0);
        LfBus slack = requireBus(network, "VL1_0");
        LfBus faultBus = requireBusInVoltageLevel(network, splitResult.voltageLevelId());
        return theveninBetween(network, null, slack, faultBus);
    }

    private static AsymRow computeIeeeAsymCurrents(double alpha) {
        LfNetwork network = loadIeee14Network();
        LfBranch branch = findBranchBetween(network, "VL2_0", "VL3_0");
        branch.setAsymLine(new LfAsymLine(
                new SimplePiModel().setR(0.05).setX(0.25),
                new SimplePiModel().setR(branch.getPiModel().getR()).setX(branch.getPiModel().getX()),
                new SimplePiModel().setR(branch.getPiModel().getR() * 0.9).setX(branch.getPiModel().getX() * 1.1),
                false, false, false));
        BranchFaultVirtualNode node = BranchFaultVirtualNode.builder()
                .setFaultId("IEEE_ASYM_TABLE_" + alpha)
                .setBranchId(branch.getId())
                .setAlpha(alpha)
                .setReferenceSide(com.powsybl.iidm.network.TwoSides.ONE)
                .build();
        AdmittanceVirtualNetwork.Builder builder = AdmittanceVirtualNetwork.builder(network);
        AdmittanceVirtualNetwork.BranchFaultVirtualNodeInstance instance = builder.add(node);
        builder.build();
        LfBranch upstream = instance.getSegments().get(0);
        LfAsymLine asymLine = upstream.getAsymLine();
        Complex z1 = toComplex(asymLine.getPiPositiveComponent());
        Complex z2 = toComplex(asymLine.getPiNegativeComponent());
        Complex z0 = toComplex(asymLine.getPiZeroComponent());
        Complex resistiveZf = new Complex(0.05, 0.02);
        double singleNoRf = singlePhaseGroundCurrent(z1, z2, z0, Complex.ZERO);
        double singleWithRf = singlePhaseGroundCurrent(z1, z2, z0, resistiveZf);
        double doubleNoRf = doublePhaseGroundCurrent(z1, z2, z0, Complex.ZERO);
        double doubleWithRf = doublePhaseGroundCurrent(z1, z2, z0, resistiveZf);
        return new AsymRow(alpha, singleNoRf, singleWithRf, doubleNoRf, doubleWithRf);
    }

    private static LfBus requireBus(LfNetwork network, String busId) {
        String normalizedTarget = normalizeId(busId);
        LfBus bus = network.getBusById(busId);
        if (bus == null) {
            bus = findBusByNormalizedId(network, normalizedTarget, false);
        }
        if (bus == null) {
            bus = findBusByNormalizedId(network, normalizedTarget, true);
        }
        if (bus == null) {
            throw new IllegalStateException("Bus " + busId + " not found. Candidates=" + describeBuses(network));
        }
        return bus;
    }

    private static LfBus requireBusInVoltageLevel(LfNetwork network, String voltageLevelId) {
        LfBus match = null;
        for (LfBus bus : network.getBuses()) {
            if (bus.getVoltageLevelId().equals(voltageLevelId)) {
                if (match != null && match != bus) {
                    throw new IllegalStateException("Voltage level " + voltageLevelId + " hosts multiple LF buses (" + match.getId() + ", " + bus.getId() + ")");
                }
                match = bus;
            }
        }
        if (match == null) {
            throw new IllegalStateException("No bus found in voltage level " + voltageLevelId + ". Candidates=" + describeBuses(network));
        }
        return match;
    }

    private static LfBus findBusByNormalizedId(LfNetwork network, String normalizedTarget, boolean allowContains) {
        for (LfBus candidate : network.getBuses()) {
            if (matches(normalizedTarget, candidate.getId(), allowContains)) {
                return candidate;
            }
            for (String originalId : candidate.getOriginalIds()) {
                if (matches(normalizedTarget, originalId, allowContains)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static boolean matches(String normalizedTarget, String rawId, boolean allowContains) {
        String normalized = normalizeId(rawId);
        if (normalized.equals(normalizedTarget)) {
            return true;
        }
        if (allowContains) {
            return normalized.contains(normalizedTarget) || normalizedTarget.contains(normalized);
        }
        return false;
    }

    private static String describeBuses(LfNetwork network) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (LfBus bus : network.getBuses()) {
            if (!first) {
                sb.append(", ");
            }
            first = false;
            sb.append(bus.getId());
            if (!bus.getOriginalIds().isEmpty()) {
                sb.append("{orig=").append(bus.getOriginalIds()).append("}");
            }
        }
        sb.append("]");
        return sb.toString();
    }

    private static String normalizeId(String id) {
        return id.replace('-', '_').toUpperCase(Locale.ROOT);
    }

    private static LfBranch findBranchBetween(LfNetwork network, String busId1, String busId2) {
        for (LfBranch branch : network.getBranches()) {
            LfBus b1 = branch.getBus1();
            LfBus b2 = branch.getBus2();
            if (b1 != null && b2 != null) {
                String id1 = b1.getId();
                String id2 = b2.getId();
                if (id1.equals(busId1) && id2.equals(busId2) || id1.equals(busId2) && id2.equals(busId1)) {
                    return branch;
                }
            }
        }
        throw new IllegalStateException("Branch between " + busId1 + " and " + busId2 + " not found");
    }

    private static Line findLineBetween(Network network, String busId1, String busId2) {
        LfNetwork lfNetwork = LfNetwork.load(network, new LfNetworkLoaderImpl(), new FirstSlackBusSelector()).get(0);
        LfBranch branch = findBranchBetween(lfNetwork, busId1, busId2);
        for (String originalId : branch.getOriginalIds()) {
            Line line = network.getLine(originalId);
            if (line != null) {
                return line;
            }
        }
        throw new IllegalStateException("Line between " + busId1 + " and " + busId2 + " not found");
    }

    private static PhysicalSplitResult physicallySplitLine(Network network, String busId1, String busId2, double alpha, String suffix) {
        Line line = findLineBetween(network, busId1, busId2);
        Terminal t1 = line.getTerminal1();
        Terminal t2 = line.getTerminal2();
        Bus bus1 = resolveBus(t1);
        Bus bus2 = resolveBus(t2);
        double portionFromBus1 = alpha;
        double portionFromBus2 = 1.0 - alpha;
        double r = line.getR();
        double x = line.getX();
        double g1 = line.getG1();
        double b1 = line.getB1();
        double g2 = line.getG2();
        double b2 = line.getB2();
        String lineId = line.getId();
        Substation refSubstation = t1.getVoltageLevel().getSubstation().orElse(null);
        Country country = refSubstation != null && refSubstation.getCountry().isPresent() ? refSubstation.getCountry().get() : Country.FR;
        Substation faultSubstation = network.newSubstation()
                .setId(lineId + "_FAULT_SUB_" + suffix)
                .setCountry(country)
                .add();
        VoltageLevel faultVl = faultSubstation.newVoltageLevel()
                .setId(lineId + "_FAULT_VL_" + suffix)
                .setNominalV(t1.getVoltageLevel().getNominalV())
                .setTopologyKind(TopologyKind.BUS_BREAKER)
                .add();
        Bus faultBus = faultVl.getBusBreakerView().newBus()
                .setId(lineId + "_FAULT_BUS_" + suffix)
                .add();

        line.remove();

        network.newLine()
                .setId(lineId + "_SEG_A_" + suffix)
                .setBus1(bus1.getId())
                .setConnectableBus1(bus1.getId())
                .setBus2(faultBus.getId())
                .setConnectableBus2(faultBus.getId())
                .setR(r * portionFromBus1)
                .setX(x * portionFromBus1)
                .setG1(g1)
                .setB1(b1)
                .setG2(0)
                .setB2(0)
                .add();

        network.newLine()
                .setId(lineId + "_SEG_B_" + suffix)
                .setBus1(faultBus.getId())
                .setConnectableBus1(faultBus.getId())
                .setBus2(bus2.getId())
                .setConnectableBus2(bus2.getId())
                .setR(r * portionFromBus2)
                .setX(x * portionFromBus2)
                .setG1(0)
                .setB1(0)
                .setG2(g2)
                .setB2(b2)
                .add();

        return new PhysicalSplitResult(faultBus.getId(), faultVl.getId());
    }

    private static Bus resolveBus(Terminal terminal) {
        Bus bus = terminal.getBusBreakerView().getBus();
        if (bus == null) {
            bus = terminal.getBusView().getBus();
        }
        return bus;
    }

    private record BalancedRow(double alpha, Complex virtualZ, Complex physicalZ, double iNoRf, double iWithRf) { }

    private record AsymRow(double alpha, double singleNoRf, double singleWithRf, double doubleNoRf, double doubleWithRf) { }

    private record PhysicalSplitResult(String busId, String voltageLevelId) { }

    private static void printBalancedTable(List<BalancedRow> rows) {
        System.out.println("IEEE 14 balanced 3-phase fault comparison (virtual vs physical split)");
        System.out.printf(Locale.ROOT, "%6s | %18s | %18s | %18s | %18s%n", "alpha", "|Z| virtual", "|Z| physical", "I (Rf=0)", "I (Rf=0.05+j0.01)");
        for (BalancedRow row : rows) {
            System.out.printf(Locale.ROOT, "%6.3f | %18.6f | %18.6f | %18.6f | %18.6f%n",
                    row.alpha(), row.virtualZ().abs(), row.physicalZ().abs(), row.iNoRf(), row.iWithRf());
        }
    }

    private static void printAsymTable(List<AsymRow> rows) {
        System.out.println("IEEE 14 asymmetrical fault currents (pu)");
        System.out.printf(Locale.ROOT, "%6s | %18s | %18s | %18s | %18s%n", "alpha", "I1φ (Rf=0)", "I1φ (Rf>0)", "I2φG (Rf=0)", "I2φG (Rf>0)");
        for (AsymRow row : rows) {
            System.out.printf(Locale.ROOT, "%6.3f | %18.6f | %18.6f | %18.6f | %18.6f%n",
                    row.alpha(), row.singleNoRf(), row.singleWithRf(), row.doubleNoRf(), row.doubleWithRf());
        }
    }

    private static void groundReference(AdmittanceEquationSystem system, VariableSet<AdmittanceVariableType> variables, LfBus slack) {
        EquationSystem<AdmittanceVariableType, AdmittanceEquationType> eq = system.getEquationSystem();
        eq.createEquation(slack.getNum(), AdmittanceEquationType.BUS_ADM_IX)
                .addTerm(new AdmittanceEquationTermShunt(slack, variables, 0, REFERENCE_SHUNT_B, true));
        eq.createEquation(slack.getNum(), AdmittanceEquationType.BUS_ADM_IY)
                .addTerm(new AdmittanceEquationTermShunt(slack, variables, 0, REFERENCE_SHUNT_B, false));
    }

    private static Complex toComplex(SimplePiModel piModel) {
        return new Complex(piModel.getR(), piModel.getX());
    }

    private static double singlePhaseGroundCurrent(Complex z1, Complex z2, Complex z0, Complex zf) {
        Complex denominator = z1.add(z2).add(z0).add(zf.multiply(3.0));
        Complex i1 = Complex.ONE.divide(denominator);
        return i1.abs() * 3.0;
    }

    private static double doublePhaseGroundCurrent(Complex z1, Complex z2, Complex z0, Complex zf) {
        Complex z0WithFault = z0.add(zf.multiply(3.0));
        Complex parallel = z2.multiply(z0WithFault).divide(z2.add(z0WithFault));
        Complex i1 = Complex.ONE.divide(z1.add(parallel));
        return i1.abs() * Math.sqrt(3.0);
    }

    private static Complex theveninBetween(LfNetwork network, AdmittanceVirtualNetwork virtualNetwork, LfBus referenceBus, LfBus faultBus) {
        VariableSet<AdmittanceVariableType> variableSet = new VariableSet<>();
        AdmittanceEquationSystem system = AdmittanceEquationSystem.create(network, variableSet, virtualNetwork);
        try (var matrix = AdmittanceMatrix.create(system, new DenseMatrixFactory())) {
            return matrix.getZ(referenceBus, faultBus);
        }
    }

    private static void assertComplexEquals(Complex expected, Complex actual, double tolerance) {
        assertEquals(expected.getReal(), actual.getReal(), tolerance);
        assertEquals(expected.getImaginary(), actual.getImaginary(), tolerance);
    }
}
