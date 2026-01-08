const state = {
    network: null
};

document.addEventListener('DOMContentLoaded', () => {
    const alphaInput = document.getElementById('alpha-input');
    const alphaValue = document.getElementById('alpha-value');
    alphaInput.addEventListener('input', () => {
        alphaValue.textContent = Number(alphaInput.value).toFixed(2);
    });
    document.getElementById('study-form').addEventListener('submit', handleStudySubmit);
    loadNetwork();
});

async function loadNetwork() {
    setStatus('Loading IEEE 14 network…');
    try {
        const response = await fetch('/api/network');
        if (!response.ok) {
            throw new Error('Unable to fetch network metadata');
        }
        const data = await response.json();
        state.network = data;
        populateBranchSelect(data.branches);
        setStatus('Ready to run studies');
    } catch (error) {
        console.error(error);
        setStatus(`Network load failed: ${error.message}`);
    }
}

function populateBranchSelect(branches) {
    const select = document.getElementById('branch-select');
    select.innerHTML = '';
    branches.forEach((branch, index) => {
        const option = document.createElement('option');
        option.value = branch.id;
        const from = branch.fromBusId ?? '?';
        const to = branch.toBusId ?? '?';
        option.textContent = `${branch.id} (${from} → ${to})`;
        if (index === 0) {
            option.selected = true;
        }
        select.appendChild(option);
    });
}

async function handleStudySubmit(event) {
    event.preventDefault();
    setStatus('Running loadflow + short-circuit…');
    try {
        const payload = buildStudyRequest();
        const response = await fetch('/api/studies', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify(payload)
        });
        const text = await response.text();
        if (!response.ok) {
            throw new Error(text || 'Study failed');
        }
        const data = JSON.parse(text);
        renderLoadFlow(data.loadFlow);
        renderShortCircuit(data.shortCircuit);
        renderDiagnostics(data.diagnostics);
        setStatus('Study complete');
    } catch (error) {
        console.error(error);
        setStatus(`Error: ${error.message}`);
    }
}

function buildStudyRequest() {
    const branchId = document.getElementById('branch-select').value;
    const alpha = parseFloat(document.getElementById('alpha-input').value);
    const rFault = parseFloat(document.getElementById('r-input').value) || 0;
    const xFault = parseFloat(document.getElementById('x-input').value) || 0;
    const faultType = document.getElementById('fault-type').value;
    const referenceSide = document.getElementById('reference-side').value;
    const includeBuses = document.getElementById('bus-scan').checked;
    return {
        branchFaults: [
            {
                id: `UI_${branchId}_${alpha.toFixed(2)}`,
                branchId,
                alpha,
                r: rFault,
                x: xFault,
                connection: 'SERIES',
                faultType,
                referenceSide
            }
        ],
        busFaults: [],
        includeAllBuses: includeBuses
    };
}

function renderLoadFlow(rows) {
    const body = document.getElementById('loadflow-body');
    body.innerHTML = '';
    (rows || []).forEach(row => {
        const tr = document.createElement('tr');
        tr.innerHTML = `
            <td>${row.busId}</td>
            <td>${row.voltagePu.toFixed(4)}</td>
            <td>${row.angleDegrees.toFixed(2)}</td>
        `;
        body.appendChild(tr);
    });
}

function renderShortCircuit(rows) {
    const body = document.getElementById('shortcircuit-body');
    body.innerHTML = '';
    (rows || []).forEach(row => {
        const tr = document.createElement('tr');
        tr.innerHTML = `
            <td>${row.faultId}</td>
            <td>${row.elementId}</td>
            <td>${row.elementType} / ${row.faultType}</td>
            <td>${row.status}</td>
            <td>${Number.isFinite(row.currentKa) ? row.currentKa.toFixed(4) : '—'}</td>
            <td>${Number.isFinite(row.voltageKv) ? row.voltageKv.toFixed(2) : '—'}</td>
            <td>${row.diagnostic ?? ''}</td>
        `;
        body.appendChild(tr);
    });
}

function renderDiagnostics(messages) {
    const container = document.getElementById('diagnostics');
    container.innerHTML = '';
    if (!messages || messages.length === 0) {
        return;
    }
    const list = document.createElement('ul');
    messages.forEach(message => {
        const li = document.createElement('li');
        li.textContent = message;
        list.appendChild(li);
    });
    container.appendChild(list);
}

function setStatus(message) {
    document.getElementById('status-message').textContent = message;
}
