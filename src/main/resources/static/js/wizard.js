/* ============================================================
   Odoo Import Wizard — JavaScript
   ============================================================ */

const state = {
    connectionId: null,
    fileId: null,
    sheetName: null,
    headers: [],
    odooModel: null,
    odooModelLabel: null,
    odooFields: [],
    mappings: [],
    currentJobId: null,
    sseSource: null,
    currentStep: 1,
    // Model cache (loaded once per connection, filtered locally for instant search)
    allModels: null,
    modelsConnId: null,
    modelsLoading: false,
};

// ----------------------------------------------------------------
// Navigation
// ----------------------------------------------------------------

function goToStep(n) {
    for (let i = 1; i <= 5; i++) {
        const panel = document.getElementById('step-' + i);
        const ind   = document.getElementById('step-ind-' + i);
        if (panel) panel.classList.toggle('d-none', i !== n);
        if (ind) {
            ind.classList.remove('active', 'done');
            if (i === n) ind.classList.add('active');
            else if (i < n) ind.classList.add('done');
        }
    }
    // Update connector lines
    document.querySelectorAll('.wizard-step-line').forEach((line, idx) => {
        line.classList.toggle('done', idx + 1 < n);
    });
    state.currentStep = n;
    window.scrollTo(0, 0);
}

async function goToStep2() {
    if (!state.connectionId || !state.fileId || !state.sheetName) return;
    goToStep(2);
    // Start loading all models in background so search is instant
    preloadModels();
}

async function goToStep3() {
    if (!state.odooModel) return;
    goToStep(3);
    await loadOdooFields();
    buildMappingTable();
}

async function goToStep4() {
    buildMappingsFromTable();
    goToStep(4);
    await loadPreview();
}

// ----------------------------------------------------------------
// Step 1 — Connection & File
// ----------------------------------------------------------------

function onConnectionChange() {
    const prev = state.connectionId;
    state.connectionId = document.getElementById('connectionId').value || null;
    // Clear cached models when the connection changes
    if (state.connectionId !== prev) {
        state.allModels = null;
        state.modelsConnId = null;
    }
    checkStep1();
}

async function testConnection() {
    const id = document.getElementById('connectionId').value;
    if (!id) return;
    const btn = document.getElementById('btnTest');
    const result = document.getElementById('connTestResult');
    btn.disabled = true;
    btn.innerHTML = '<span class="spinner-border spinner-border-sm me-1"></span>Test...';
    result.innerHTML = '';
    try {
        const r = await fetch('/api/connections/' + id + '/test', { method: 'POST' });
        const data = await r.json();
        if (data.success) {
            result.innerHTML = '<span class="text-success"><i class="bi bi-check-circle me-1"></i>' + data.message + '</span>';
        } else {
            result.innerHTML = '<span class="text-danger"><i class="bi bi-x-circle me-1"></i>' + data.message + '</span>';
        }
    } catch (e) {
        result.innerHTML = '<span class="text-danger">Erreur réseau: ' + e.message + '</span>';
    }
    btn.disabled = false;
    btn.innerHTML = '<i class="bi bi-wifi me-1"></i>Tester';
}

function onFileSelected(input) {
    const file = input.files[0];
    if (!file) return;
    uploadFile(file);
}

// Drag & drop
document.addEventListener('DOMContentLoaded', () => {
    const zone = document.getElementById('dropZone');
    if (!zone) return;

    zone.addEventListener('dragover', e => {
        e.preventDefault();
        zone.classList.add('drag-over');
    });
    zone.addEventListener('dragleave', () => zone.classList.remove('drag-over'));
    zone.addEventListener('drop', e => {
        e.preventDefault();
        zone.classList.remove('drag-over');
        const file = e.dataTransfer.files[0];
        if (file) uploadFile(file);
    });
});

async function uploadFile(file) {
    const zone = document.getElementById('dropZone');
    const content = document.getElementById('dropZoneContent');
    const spinner = document.getElementById('dropZoneProgress');

    content.classList.add('d-none');
    spinner.classList.remove('d-none');
    zone.classList.remove('drag-over');

    const fd = new FormData();
    fd.append('file', file);

    try {
        const r = await fetch('/api/upload', { method: 'POST', body: fd });
        const data = await r.json();

        if (data.error) throw new Error(data.error);

        state.fileId = data.fileId;
        state.sheetName = null;
        state.headers = [];

        // Update dropzone display
        zone.classList.add('uploaded');
        content.classList.remove('d-none');
        content.innerHTML = `
            <i class="bi bi-file-earmark-check fs-1 text-success mb-2"></i>
            <p class="mb-1 fw-medium text-success">${file.name}</p>
            <p class="text-muted small mb-0">${(data.sizeKb / 1024).toFixed(1)} MB — ${data.sheets.length} feuille(s)</p>`;
        spinner.classList.add('d-none');

        // Populate sheet selector
        const sheetSel = document.getElementById('sheetName');
        const sheetSection = document.getElementById('sheetSection');
        sheetSel.innerHTML = data.sheets.map(s =>
            `<option value="${s}">${s}</option>`).join('');
        sheetSection.classList.remove('d-none');

        // Auto-select first sheet
        if (data.sheets.length > 0) {
            sheetSel.value = data.sheets[0];
            state.sheetName = data.sheets[0];
        }

        document.getElementById('fileInfo').textContent =
            `Fichier: ${file.name} (${(data.sizeKb).toLocaleString()} KB)`;

        checkStep1();

    } catch (e) {
        content.classList.remove('d-none');
        spinner.classList.add('d-none');
        content.innerHTML = `<i class="bi bi-exclamation-triangle fs-1 text-danger mb-2"></i>
            <p class="text-danger">${e.message}</p>
            <p class="small text-muted">Cliquer pour réessayer</p>`;
        zone.classList.remove('uploaded');
    }
}

function onSheetChange() {
    state.sheetName = document.getElementById('sheetName').value;
    checkStep1();
}

function checkStep1() {
    const ok = state.connectionId && state.fileId && state.sheetName;
    document.getElementById('btnStep1Next').disabled = !ok;
}

// ----------------------------------------------------------------
// Step 2 — Model search
// ----------------------------------------------------------------

let modelSearchTimer = null;

/** Pre-fetch all models for the current connection into the local cache. */
async function preloadModels() {
    if (!state.connectionId) return;
    if (state.modelsConnId === state.connectionId) return;  // already loaded
    if (state.modelsLoading) return;

    state.modelsLoading = true;
    state.allModels = null;
    try {
        const r = await fetch(`/api/models?connectionId=${state.connectionId}&q=`);
        const data = await r.json();
        if (!data.error && Array.isArray(data.models)) {
            state.allModels    = data.models;
            state.modelsConnId = state.connectionId;
        }
    } catch (e) { /* silent — will fall back to server search */ }
    finally { state.modelsLoading = false; }
}

function searchModels(q) {
    if (!state.connectionId) return;
    const dd = document.getElementById('modelResults');

    if (q.length < 1) {
        dd.classList.add('d-none');
        return;
    }

    // ── Fast path: filter local cache ──────────────────────────────────────
    if (state.allModels) {
        const qLow = q.toLowerCase();
        const hits = state.allModels.filter(m =>
            m.model.toLowerCase().includes(qLow) ||
            m.name.toLowerCase().includes(qLow)
        ).slice(0, 40);
        renderModelDropdown(hits);
        return;
    }

    // ── While cache is still loading, show a spinner ───────────────────────
    if (state.modelsLoading) {
        dd.innerHTML = '<div class="p-3 text-muted small"><span class="spinner-border spinner-border-sm me-2"></span>Chargement des modèles…</div>';
        dd.classList.remove('d-none');
        // Retry once loading completes
        const pendingQ = q;
        const wait = setInterval(() => {
            if (!state.modelsLoading) {
                clearInterval(wait);
                if ((document.getElementById('modelSearch')?.value || '') === pendingQ) {
                    searchModels(pendingQ);
                }
            }
        }, 150);
        return;
    }

    // ── Fallback: server search with debounce (cache not available) ─────────
    clearTimeout(modelSearchTimer);
    modelSearchTimer = setTimeout(async () => {
        if (state.allModels) { searchModels(q); return; }  // cache loaded while waiting
        try {
            const r = await fetch(`/api/models?connectionId=${state.connectionId}&q=${encodeURIComponent(q)}`);
            const data = await r.json();
            if (data.error) return;
            renderModelDropdown(data.models);
        } catch (e) { console.error(e); }
    }, 200);
}

function renderModelDropdown(models) {
    const dropdown = document.getElementById('modelResults');
    if (!models || models.length === 0) {
        dropdown.innerHTML = '<div class="p-3 text-muted small">Aucun modèle trouvé</div>';
    } else {
        dropdown.innerHTML = models.map(m => `
            <div class="model-dropdown-item" onclick="selectModel('${m.model}', '${escHtml(m.name)}')">
                <div class="model-name">${escHtml(m.name)}</div>
                <div class="model-tech">${m.model}</div>
            </div>`).join('');
    }
    dropdown.classList.remove('d-none');
}

function selectModel(model, label) {
    state.odooModel = model;
    state.odooModelLabel = label;
    document.getElementById('modelSearch').value = label;
    document.getElementById('modelResults').classList.add('d-none');
    document.getElementById('modelSelected').classList.remove('d-none');
    document.getElementById('modelSelectedLabel').textContent = label;
    document.getElementById('modelSelectedName').textContent = model;
    document.getElementById('btnStep2Next').disabled = false;
}

function clearModel() {
    state.odooModel = null;
    state.odooModelLabel = null;
    document.getElementById('modelSearch').value = '';
    document.getElementById('modelSelected').classList.add('d-none');
    document.getElementById('btnStep2Next').disabled = true;
}

// Close dropdown on outside click
document.addEventListener('click', e => {
    const dd = document.getElementById('modelResults');
    if (dd && !dd.contains(e.target) && e.target.id !== 'modelSearch') {
        dd.classList.add('d-none');
    }
});

// ----------------------------------------------------------------
// Step 3 — Field mapping
// ----------------------------------------------------------------

async function loadOdooFields() {
    const spinner = document.getElementById('mappingSpinner');
    const table   = document.getElementById('mappingTable');
    spinner.classList.remove('d-none');
    table.classList.add('d-none');

    try {
        const r = await fetch(`/api/fields?connectionId=${state.connectionId}&model=${encodeURIComponent(state.odooModel)}`);
        const data = await r.json();
        if (data.error) throw new Error(data.error);
        state.odooFields = data.fields;
    } catch (e) {
        spinner.innerHTML = `<div class="text-danger p-3"><i class="bi bi-exclamation-triangle me-2"></i>${e.message}</div>`;
        return;
    }

    // Also load headers if not already done
    if (state.headers.length === 0) {
        try {
            const r = await fetch(`/api/preview?fileId=${state.fileId}&sheet=${encodeURIComponent(state.sheetName)}&limit=5`);
            const data = await r.json();
            if (!data.error) {
                state.headers = data.headers;
            }
        } catch (e) { /* ignore */ }
    }

    spinner.classList.add('d-none');
    table.classList.remove('d-none');
}

function buildMappingTable() {
    const tbody = document.getElementById('mappingBody');
    tbody.innerHTML = '';

    const fieldOptions = `<option value="">— Ignorer —</option>` +
        state.odooFields.map(f =>
            `<option value="${f.name}" data-type="${f.type}" data-relation="${f.relation || ''}" data-required="${f.required}">
                ${escHtml(f.label)} (${f.name})${f.required ? ' *' : ''}
            </option>`
        ).join('');

    state.headers.forEach((header, idx) => {
        // Auto-match: find odoo field with similar name
        const autoMatch = findBestMatch(header, state.odooFields);

        const row = document.createElement('tr');
        row.innerHTML = `
            <td>
                <div class="fw-medium small">${escHtml(header)}</div>
                <div class="text-muted" style="font-size:.7rem">Colonne ${idx + 1}</div>
            </td>
            <td><div class="sample-values" id="sample-${idx}">—</div></td>
            <td>
                <select class="form-select form-select-sm field-select" data-col="${idx}" data-header="${escHtml(header)}"
                        onchange="onFieldChange(this)">
                    ${fieldOptions}
                </select>
            </td>
            <td><span class="badge bg-secondary field-type-badge" id="type-${idx}">—</span></td>
            <td class="text-center">
                <div class="form-check d-flex justify-content-center">
                    <input class="form-check-input create-if-not-found" type="checkbox" id="createIfNotFound-${idx}" disabled/>
                </div>
            </td>`;
        tbody.appendChild(row);

        // Set auto-match
        const sel = row.querySelector('.field-select');
        if (autoMatch) {
            sel.value = autoMatch.name;
            onFieldChange(sel);
        }
    });

    document.getElementById('mappingBadge').textContent = state.headers.length + ' colonnes';
    loadSampleValues();
}

async function loadSampleValues() {
    try {
        const r = await fetch(`/api/preview?fileId=${state.fileId}&sheet=${encodeURIComponent(state.sheetName)}&limit=3`);
        const data = await r.json();
        if (data.error || !data.rows) return;

        state.headers.forEach((header, idx) => {
            const cell = document.getElementById('sample-' + idx);
            if (!cell) return;
            const samples = data.rows.map(row => row[header]).filter(v => v && v.trim()).slice(0, 2);
            cell.textContent = samples.join(', ') || '—';
            cell.title = samples.join('\n');
        });
    } catch (e) { /* ignore */ }
}

function onFieldChange(sel) {
    const idx = sel.dataset.col;
    const selectedOpt = sel.options[sel.selectedIndex];
    const type = selectedOpt.dataset.type || '';
    const relation = selectedOpt.dataset.relation || '';

    // Update type badge
    const badge = document.getElementById('type-' + idx);
    if (badge) {
        badge.textContent = type || '—';
        badge.className = 'badge ' + getTypeBadgeClass(type);
    }

    // Enable "create if not found" only for many2one
    const createChk = document.getElementById('createIfNotFound-' + idx);
    if (createChk) {
        createChk.disabled = type !== 'many2one';
        if (type !== 'many2one') createChk.checked = false;
    }
}

function buildMappingsFromTable() {
    state.mappings = [];
    document.querySelectorAll('.field-select').forEach(sel => {
        if (!sel.value) return;
        const idx = parseInt(sel.dataset.col);
        const selectedOpt = sel.options[sel.selectedIndex];
        const createChk = document.getElementById('createIfNotFound-' + idx);
        state.mappings.push({
            columnIndex: idx,
            columnName: state.headers[idx],
            odooField: sel.value,
            odooFieldType: selectedOpt.dataset.type || 'char',
            odooFieldLabel: selectedOpt.text.split(' (')[0],
            relatedModel: selectedOpt.dataset.relation || '',
            createIfNotFound: createChk ? createChk.checked : false,
        });
    });
}

function findBestMatch(colName, fields) {
    if (!fields || fields.length === 0) return null;
    const normalized = colName.toLowerCase().replace(/[\s_-]/g, '');
    return fields.find(f => {
        const fn = f.name.toLowerCase().replace(/[\s_-]/g, '');
        const fl = f.label.toLowerCase().replace(/[\s_-]/g, '');
        return fn === normalized || fl === normalized;
    }) || null;
}

// ----------------------------------------------------------------
// Step 4 — Preview
// ----------------------------------------------------------------

async function loadPreview() {
    const spinner = document.getElementById('previewSpinner');
    const content = document.getElementById('previewContent');
    const info    = document.getElementById('previewInfo');
    spinner.classList.remove('d-none');
    content.classList.add('d-none');

    try {
        const r = await fetch(`/api/preview?fileId=${state.fileId}&sheet=${encodeURIComponent(state.sheetName)}&limit=20`);
        const data = await r.json();
        if (data.error) throw new Error(data.error);

        info.textContent = `${data.totalRows} lignes de données • aperçu des 20 premières`;

        // Build table
        const thead = document.querySelector('#previewTableEl thead');
        const tbody = document.querySelector('#previewTableEl tbody');
        thead.innerHTML = '<tr>' + data.headers.map(h => `<th class="text-nowrap">${escHtml(h)}</th>`).join('') + '</tr>';
        tbody.innerHTML = data.rows.map(row =>
            '<tr>' + data.headers.map(h =>
                `<td class="text-nowrap small">${escHtml(row[h] || '')}</td>`
            ).join('') + '</tr>'
        ).join('');

        spinner.classList.add('d-none');
        content.classList.remove('d-none');
    } catch (e) {
        spinner.innerHTML = `<div class="text-danger p-3"><i class="bi bi-exclamation-triangle me-2"></i>${e.message}</div>`;
    }
}

// ----------------------------------------------------------------
// Step 5 — Import / Progress
// ----------------------------------------------------------------

async function startImport(testMode) {
    if (state.sseSource) { state.sseSource.close(); state.sseSource = null; }

    buildMappingsFromTable();

    const options = {
        matchBy:      document.getElementById('optMatchBy').value,
        onConflict:   document.getElementById('optOnConflict').value,
        batchSize:    parseInt(document.getElementById('optBatchSize').value) || 100,
        skipEmptyLines: document.getElementById('optSkipEmpty').checked,
        stopOnError:  document.getElementById('optStopOnError').checked,
    };

    const payload = {
        connectionId: parseInt(state.connectionId),
        fileId:       state.fileId,
        sheetName:    state.sheetName,
        odooModel:    state.odooModel,
        mappings:     state.mappings,
        options:      options,
        testMode:     testMode,
    };

    goToStep(5);
    resetProgressUI(testMode);

    try {
        const r = await fetch('/api/import', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload),
        });
        const data = await r.json();
        if (data.error) throw new Error(data.error);

        state.currentJobId = data.jobId;
        document.getElementById('viewJobLink').href = '/jobs/' + data.jobId;
        subscribeToProgress(data.jobId, testMode);
    } catch (e) {
        showFatalError(e.message);
    }
}

function subscribeToProgress(jobId, testMode) {
    const es = new EventSource('/api/jobs/' + jobId + '/stream');
    state.sseSource = es;

    es.addEventListener('progress', e => {
        const p = JSON.parse(e.data);
        updateProgressUI(p);
        if (p.done) {
            es.close();
            state.sseSource = null;
            onImportDone(p, testMode);
        }
    });

    es.onerror = () => {
        // SSE error — poll by reloading after a short wait
        es.close();
    };
}

function resetProgressUI(testMode) {
    const title = document.getElementById('progressTitle');
    title.innerHTML = testMode
        ? '<i class="bi bi-flask text-warning me-2"></i>Test en cours...'
        : '<i class="bi bi-cloud-upload text-primary me-2"></i>Import en cours...';

    document.getElementById('statusBadge').textContent = 'EN COURS';
    document.getElementById('statusBadge').className = 'badge bg-primary ms-auto';

    if (testMode) {
        document.getElementById('testModeNotice').classList.remove('d-none');
    } else {
        document.getElementById('testModeNotice').classList.add('d-none');
    }

    setProgress(0, 0, 0, 0, 0, 'Initialisation...');
    document.getElementById('doneActions').classList.add('d-none');
    document.getElementById('cancelSection').classList.remove('d-none');
    document.getElementById('progressBar').className = 'progress-bar progress-bar-striped progress-bar-animated bg-primary';
}

function updateProgressUI(p) {
    setProgress(p.totalRows, p.processedRows, p.successRows, p.errorRows, p.skippedRows, p.message);
}

function setProgress(total, processed, success, errors, skipped, msg) {
    const pct = total > 0 ? Math.min(100, Math.round(processed * 100 / total)) : 0;
    document.getElementById('progressBar').style.width = pct + '%';
    document.getElementById('progressPct').textContent = pct + '%';
    document.getElementById('progressLabel').textContent = msg || '';
    document.getElementById('statTotal').textContent = total;
    document.getElementById('statSuccess').textContent = success;
    document.getElementById('statErrors').textContent = errors;
    document.getElementById('statSkipped').textContent = skipped;
}

function onImportDone(p, testMode) {
    const badge = document.getElementById('statusBadge');
    const bar   = document.getElementById('progressBar');
    const title = document.getElementById('progressTitle');

    document.getElementById('cancelSection').classList.add('d-none');
    document.getElementById('doneActions').classList.remove('d-none');

    const btnLaunchReal = document.getElementById('btnLaunchReal');

    if (p.status === 'COMPLETED') {
        badge.className = 'badge bg-success ms-auto';
        badge.textContent = testMode ? 'TEST OK' : 'TERMINÉ';
        bar.className = 'progress-bar bg-success';
        bar.style.width = '100%';
        title.innerHTML = testMode
            ? '<i class="bi bi-flask text-success me-2"></i>Test terminé'
            : '<i class="bi bi-check-circle text-success me-2"></i>Import terminé';
        if (testMode && btnLaunchReal) btnLaunchReal.style.display = '';
    } else if (p.status === 'FAILED') {
        badge.className = 'badge bg-danger ms-auto';
        badge.textContent = 'ÉCHEC';
        bar.className = 'progress-bar bg-danger';
        title.innerHTML = '<i class="bi bi-x-circle text-danger me-2"></i>Import échoué';
    } else if (p.status === 'CANCELLED') {
        badge.className = 'badge bg-secondary ms-auto';
        badge.textContent = 'ANNULÉ';
        bar.className = 'progress-bar bg-secondary';
        title.innerHTML = '<i class="bi bi-x-circle text-secondary me-2"></i>Import annulé';
    }
}

function showFatalError(msg) {
    document.getElementById('progressTitle').innerHTML =
        '<i class="bi bi-exclamation-triangle text-danger me-2"></i>Erreur';
    document.getElementById('progressLabel').textContent = msg;
    document.getElementById('statusBadge').className = 'badge bg-danger ms-auto';
    document.getElementById('statusBadge').textContent = 'ERREUR';
    document.getElementById('cancelSection').classList.add('d-none');
    document.getElementById('doneActions').classList.remove('d-none');
}

async function cancelImport() {
    if (!state.currentJobId) return;
    if (!confirm('Annuler l\'import en cours ?')) return;
    if (state.sseSource) { state.sseSource.close(); state.sseSource = null; }
    try {
        await fetch('/jobs/' + state.currentJobId + '/cancel', { method: 'POST' });
    } catch (e) { /* ignore */ }
}

// ----------------------------------------------------------------
// Utilities
// ----------------------------------------------------------------

function escHtml(str) {
    if (!str) return '';
    return String(str)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}

function getTypeBadgeClass(type) {
    return {
        'char':      'bg-secondary',
        'text':      'bg-secondary',
        'integer':   'bg-info text-dark',
        'float':     'bg-info text-dark',
        'monetary':  'bg-info text-dark',
        'boolean':   'bg-warning text-dark',
        'date':      'bg-primary',
        'datetime':  'bg-primary',
        'many2one':  'bg-success',
        'many2many': 'bg-success',
        'one2many':  'bg-success',
        'selection': 'bg-warning text-dark',
    }[type] || 'bg-secondary';
}
