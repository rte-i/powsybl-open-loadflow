package com.powsybl.demo.service;

import com.powsybl.computation.ComputationManager;
import com.powsybl.computation.local.LocalComputationManager;
import com.powsybl.demo.model.*;
import com.powsybl.ieeecdf.converter.IeeeCdfNetworkFactory;
import com.powsybl.iidm.network.Bus;
import com.powsybl.iidm.network.Line;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.Terminal;
import com.powsybl.loadflow.LoadFlow;
import com.powsybl.loadflow.LoadFlowParameters;
import com.powsybl.math.matrix.DenseMatrixFactory;
import com.powsybl.openloadflow.OpenLoadFlowProvider;
import com.powsybl.sc.extensions.BranchFaultSpec;
import com.powsybl.sc.extensions.ShortCircuitFaultSpecExtensionAdder;
import com.powsybl.sc.implementation.FaultProcessingDiagnostic;
import com.powsybl.sc.implementation.ShortCircuitStudyReport;
import com.powsybl.shortcircuit.*;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class StudyService {

    private final DenseMatrixFactory matrixFactory = new DenseMatrixFactory();
    private final LoadFlow.Runner loadFlowRunner = new LoadFlow.Runner(new OpenLoadFlowProvider(matrixFactory));
    private final ShortCircuitAnalysisProvider shortCircuitProvider = new OpenShortCircuitProvider(matrixFactory);
    private final ComputationManager computationManager = LocalComputationManager.getDefault();

    public NetworkMetadata describeNetwork() {
        Network network = createIeee14Network();
        List<BusMetadata> buses = network.getBusBreakerView()
                .getBuses()
                .stream()
                .filter(Objects::nonNull)
                .map(bus -> new BusMetadata(bus.getId(), bus.getName(), bus.getVoltageLevel().getNominalV()))
                .sorted(Comparator.comparing(BusMetadata::id))
                .toList();

        List<BranchMetadata> branches = network.getLines().stream()
                .map(line -> new BranchMetadata(line.getId(), line.getName(),
                        resolveBusId(line.getTerminal1()), resolveBusId(line.getTerminal2())))
                .sorted(Comparator.comparing(BranchMetadata::id))
                .toList();
        return new NetworkMetadata(buses, branches);
    }

    public StudyResponse runStudy(StudyRequest request) {
        Network network = createIeee14Network();
        LoadFlowParameters loadFlowParameters = LoadFlowParameters.load();
        loadFlowParameters.setTwtSplitShuntAdmittance(true);
        loadFlowRunner.run(network, loadFlowParameters);

        List<LoadFlowBusResult> loadFlowResults = network.getBusView().getBusStream()
                .map(bus -> new LoadFlowBusResult(bus.getId(), bus.getV(), bus.getAngle()))
                .sorted(Comparator.comparing(LoadFlowBusResult::busId))
                .toList();

        List<Fault> faults = new ArrayList<>();
        if (!request.branchFaults().isEmpty()) {
            ShortCircuitFaultSpecExtensionAdder adder = network.newExtension(ShortCircuitFaultSpecExtensionAdder.class);
            for (BranchFaultRequest branchFault : request.branchFaults()) {
                adder.withBranchFault(branchFault.resolvedId(), branchFault.branchId(),
                        branchFault.alpha(), branchFault.referenceSide());
                faults.add(branchFault.toFault());
            }
            adder.add();
        }

        for (BusFaultRequest busFault : request.busFaults()) {
            faults.add(busFault.toFault());
        }

        if (request.includeAllBuses()) {
            network.getBusBreakerView().getBuses().stream()
                    .filter(Objects::nonNull)
                    .forEach(bus -> faults.add(new BusFault("AUTO_BUS_" + bus.getId(), bus.getId())));
        }

        if (faults.isEmpty()) {
            return new StudyResponse(loadFlowResults, Collections.emptyList(), Collections.emptyList());
        }

        ShortCircuitParameters shortCircuitParameters = new ShortCircuitParameters();
        ShortCircuitAnalysisResult result = shortCircuitProvider.run(network, faults, shortCircuitParameters,
                computationManager, Collections.emptyList()).join();

        List<ShortCircuitFaultResultDto> faultResults = result.getFaultResults().stream()
                .map(this::mapFaultResult)
                .sorted(Comparator.comparing(ShortCircuitFaultResultDto::faultId))
                .collect(Collectors.toList());

        List<String> diagnostics = Optional.ofNullable(result.getExtension(ShortCircuitStudyReport.class))
                .map(ShortCircuitStudyReport::getDiagnostics)
                .orElse(List.of());

        return new StudyResponse(loadFlowResults, faultResults, diagnostics);
    }

    private ShortCircuitFaultResultDto mapFaultResult(FaultResult faultResult) {
        Fault fault = faultResult.getFault();
        double current = Double.NaN;
        double voltage = Double.NaN;
        if (faultResult instanceof MagnitudeFaultResult magnitudeFaultResult) {
            current = magnitudeFaultResult.getCurrent();
            voltage = magnitudeFaultResult.getVoltage();
        }
        String diagnostic = Optional.ofNullable(faultResult.getExtension(FaultProcessingDiagnostic.class))
                .map(FaultProcessingDiagnostic::getMessage)
                .orElse(null);
        return new ShortCircuitFaultResultDto(fault.getId(), fault.getElementId(), fault.getType(),
                fault.getFaultType(), faultResult.getStatus(), current, voltage, diagnostic);
    }

    private Network createIeee14Network() {
        return IeeeCdfNetworkFactory.create14();
    }

    private String resolveBusId(Terminal terminal) {
        if (terminal == null) {
            return null;
        }
        Bus bus = terminal.getBusBreakerView().getBus();
        return bus != null ? bus.getId() : null;
    }
}
