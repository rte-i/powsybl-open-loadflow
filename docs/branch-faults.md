# Branch fault virtual nodes

The short-circuit implementations hosted in `powsybl-open-sc` now need to compute
admittance matrices at arbitrary points along a branch. Open-loadflow exposes
the following helper classes to support that workflow:

* `BranchFaultVirtualNode` – describes the fault context (fault id, branch id,
  alpha value in `[0, 1]`, and which side of the branch is used as reference).
* `AdmittanceVirtualNetwork` – decorates an `LfNetwork` with synthetic buses and
  branches derived from the above definitions. The virtual network implements
  `LfElementContainer` so it can be fed directly to
  `AdmittanceEquationSystem.create(...)`.

## Typical usage

```java
LfNetwork lfNetwork = ...;
BranchFaultVirtualNode node = BranchFaultVirtualNode.builder()
        .setFaultId("FAULT_42")
        .setBranchId("LINE_12")
        .setAlpha(0.4)               // 40% of the impedance from the FROM side
        .setReferenceSide(TwoSides.ONE)
        .build();

AdmittanceVirtualNetwork.Builder builder = AdmittanceVirtualNetwork.builder(lfNetwork);
AdmittanceVirtualNetwork.BranchFaultVirtualNodeInstance instance = builder.add(node);
AdmittanceVirtualNetwork virtualNetwork = builder.build();

// Instance exposes the synthetic bus where the solver should inject the fault
LfBus faultBus = instance.getBus();

var variableSet = new VariableSet<AdmittanceVariableType>();
AdmittanceEquationSystem ySystem = AdmittanceEquationSystem.create(lfNetwork, variableSet, virtualNetwork);
```

When `alpha` is `0` (resp. `1`) the helper maps the fault directly to the FROM
(resp. TO) bus. For `0 < alpha < 1`, a synthetic bus is created and the original
branch is replaced by two `PiModel` segments with impedances `alpha · Z` and
`(1 − alpha) · Z`. The builder keeps track of the generated branches through the
`BranchFaultVirtualNodeInstance` returned by `add(...)`.

### Limitations

* Only branches with a nominal tap ratio (`ρ = 1`) and no phase shift are
  supported for now.
* At most one virtual node can be created per branch while the builder is in
  use. Invoke a new builder for each fault scenario.
* Virtual nodes are meant to model one fault at a time. They are not persisted
  in the original `LfNetwork` and are only visible through the
  `AdmittanceVirtualNetwork` instance.

## Regression tests

The table below documents the regression coverage added in
`BranchFaultVirtualNodeTest`. These tests should be kept green when extending
the API so downstream short-circuit solvers can rely on the behavior.

| Test | Scenario | Key assertions |
| --- | --- | --- |
| `alphaBoundariesReusePhysicalBuses` / `alphaBarelyAwayFromBusStillSplits` / `alphaBarelyAwayFromOppositeBusStillSplits` | Two-bus toy network. Fault exactly at each end, plus alpha values a few ‰ away from the buses. | `α=0`/`α=1` reuse the physical buses, while `α≈0`/`α≈1` still create a synthetic node and split the line. |
| `balancedMidpointSplitAndFaultImpedance` | Two-bus network with a balanced line and a mid-span virtual node. | The two Pi segments scale by `α`/`1−α`; Thevenin impedance decreases the expected amount when adding fault impedance. |
| `ieeeBalancedFaultsMatchLineImpedance` | IEEE-14 line VL2↔VL3, α∈{0, 0.5, 1}. | `α=0/1` match the bus Thevenin impedance; `α=0.5` equals `Zbus + Zline/2`; higher Rf reduces current magnitude. |
| `ieeeBalancedVirtualEqualsPhysicalSplit` | IEEE-14 with a virtual node compared to a physically split IIDM line for α = 0.001, 0.1, 0.5, 0.991. | The virtual and physical Thevenin impedances match to numerical tolerance; helper prints a table of \|Z\| and 3Φ currents (Rf=0 and 0.05+j0.01). |
| `unbalancedSequenceSplitAndRfImpact` | Two-bus network with explicit sequence impedances. | Derived positive/negative/zero-sequence Pi models preserve the α split; resistive faults reduce single-/double-phase ground currents. |
| `ieeeUnbalancedFaultsReactToRf` | IEEE-14 with asymmetrical sequence data derived from the line. | Each sequence segment scales by α; Rf dampens the 1Φ and 2Φ-G current magnitudes. |
| `ieeeAsymmetricalFaultCurrentsTable` | IEEE-14 asymmetrical faults for the same α grid. | Emits a human-readable table summarizing 1Φ and 2Φ-G current magnitudes with/without fault impedance to validate solver outputs. |
