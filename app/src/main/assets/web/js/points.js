/*
 * "Lançar pontos": give (or take) the same points, with the same note and category, to several
 * students of a class at once — "everyone handed in the assignment". The whole batch is saved
 * together or not at all, and can be undone as a batch (each entry is cancelled, not erased).
 */
(function () {
    'use strict';

    var QG = window.QG;
    var I = window.QGInsights;
    var esc = QG.escapeHtml;

    var PRESETS = [1, 2, 5, 10, -1, -5];

    var ps = {
        classGroupId: '',
        selectedFor: null, // the class group `selected` belongs to; a different one starts with everybody ticked
        selected: {},      // enrollment id -> true
        filter: '',
        delta: '',
        note: '',
        tag: '',
        busy: false,
        batches: []        // this session's batches, newest first
    };

    QG.registerTab('points', renderPoints);

    function activeGroups() {
        return QG.state.classGroups.filter(function (g) { return !g.closed; });
    }

    // Enrollments of the chosen class, by name.
    function rowsOfGroup() {
        return QG.state.overview.filter(function (r) {
            return r.classGroupId === ps.classGroupId && !r.classGroupClosed;
        }).sort(function (a, b) { return a.name.localeCompare(b.name); });
    }

    // "Disciplina · Turma", so two classes with the same name in different disciplines can be told apart in the closed select.
    function groupOptions(groups) {
        var html = '';
        QG.state.disciplines.forEach(function (d) {
            groups.filter(function (g) { return g.disciplineId === d.id; }).forEach(function (g) {
                html += '<option value="' + esc(g.id) + '"' + (g.id === ps.classGroupId ? ' selected' : '') + '>' +
                    esc(d.name) + ' · ' + esc(g.name) + '</option>';
            });
        });
        return html;
    }

    function selectAll(rows) {
        ps.selected = {};
        rows.forEach(function (r) { ps.selected[r.enrollmentId] = true; });
        ps.selectedFor = ps.classGroupId;
    }

    function renderPoints() {
        var el = document.getElementById('tab-points');
        var groups = activeGroups();
        if (groups.length === 0) {
            el.innerHTML = '<div class="card"><p>Cadastre uma turma e matricule alunos primeiro.</p></div>';
            return;
        }
        if (!groups.some(function (g) { return g.id === ps.classGroupId; })) ps.classGroupId = groups[0].id;

        var rows = rowsOfGroup();
        if (ps.selectedFor !== ps.classGroupId) selectAll(rows);

        QG.ensureDatalists();
        el.innerHTML =
            '<div class="card">' +
            '<div class="row"><h2 style="flex:1">Lançar pontos para vários alunos</h2>' +
            '<label>Turma:</label><select id="pts-group">' + groupOptions(groups) + '</select></div>' +
            '<form id="pts-form" autocomplete="off">' +
            '<div class="batch-fields">' +
            '<label>Pontos<input type="number" name="delta" required placeholder="+/−" value="' + esc(ps.delta) + '"></label>' +
            '<label>Observação<input name="note" list="dl-notes" placeholder="ex.: entregou o trabalho" value="' + esc(ps.note) + '"></label>' +
            '<label>Categoria<input name="tag" list="dl-tags" placeholder="opcional" value="' + esc(ps.tag) + '"></label>' +
            '</div>' +
            '<div class="chips presets">' + PRESETS.map(function (n) {
                return '<button type="button" class="chip action" data-delta="' + n + '">' + I.signed(n) + '</button>';
            }).join('') + '</div>' +
            '<div class="row selection-tools">' +
            '<button type="button" class="btn secondary small" data-sel="all">Todos</button>' +
            '<button type="button" class="btn secondary small" data-sel="none">Nenhum</button>' +
            '<button type="button" class="btn secondary small" data-sel="invert">Inverter</button>' +
            '<button type="button" class="btn secondary small" data-sel="present" title="Marca só quem está presente na chamada de hoje">Presentes hoje</button>' +
            '<input type="search" id="pts-filter" class="search-input" placeholder="Filtrar por nome…" value="' + esc(ps.filter) + '">' +
            '</div>' +
            '<div class="table-wrap history-results"><table><thead><tr><th></th><th>Aluno</th><th>Pontos</th></tr></thead>' +
            '<tbody id="pts-body"></tbody></table></div>' +
            '<div class="row" style="margin-top:12px"><span id="pts-preview" class="muted" style="flex:1"></span>' +
            '<button type="submit" class="btn" id="pts-submit">Lançar</button></div>' +
            '</form></div>' +
            '<div id="pts-batches"></div>';

        var form = document.getElementById('pts-form');

        document.getElementById('pts-group').addEventListener('change', function (e) {
            ps.classGroupId = e.target.value;
            ps.filter = '';
            renderPoints();
        });

        form.addEventListener('input', function (e) {
            if (e.target.name === 'delta') ps.delta = e.target.value;
            else if (e.target.name === 'note') ps.note = e.target.value;
            else if (e.target.name === 'tag') ps.tag = e.target.value;
            updatePreview();
        });

        form.querySelector('.presets').addEventListener('click', function (e) {
            var btn = e.target.closest('button[data-delta]');
            if (!btn) return;
            form.elements.delta.value = btn.getAttribute('data-delta');
            ps.delta = form.elements.delta.value;
            updatePreview();
        });

        form.querySelector('.selection-tools').addEventListener('click', function (e) {
            var btn = e.target.closest('button[data-sel]');
            if (!btn) return;
            applySelection(btn.getAttribute('data-sel'), rows);
        });

        document.getElementById('pts-filter').addEventListener('input', function (e) {
            ps.filter = e.target.value;
            renderList(rows);
        });

        document.getElementById('pts-body').addEventListener('change', function (e) {
            var box = e.target.closest('input[type="checkbox"][data-id]');
            if (!box) return;
            if (box.checked) ps.selected[box.getAttribute('data-id')] = true;
            else delete ps.selected[box.getAttribute('data-id')];
            updatePreview();
        });

        form.addEventListener('submit', function (e) {
            e.preventDefault();
            submitBatch(rows);
        });

        renderList(rows);
        renderBatches();
    }

    function renderList(rows) {
        var body = document.getElementById('pts-body');
        if (!body) return;
        var query = QG.normalizeSearch(ps.filter.trim());
        var visible = rows.filter(function (r) { return !query || QG.normalizeSearch(r.name).indexOf(query) !== -1; });

        if (rows.length === 0) {
            body.innerHTML = '<tr><td colspan="3">Esta turma ainda não tem alunos matriculados.</td></tr>';
        } else if (visible.length === 0) {
            body.innerHTML = '<tr><td colspan="3">Nenhum aluno corresponde a "' + esc(ps.filter) + '".</td></tr>';
        } else {
            body.innerHTML = visible.map(function (r) {
                return '<tr><td><input type="checkbox" data-id="' + esc(r.enrollmentId) + '"' +
                    (ps.selected[r.enrollmentId] ? ' checked' : '') + ' aria-label="Selecionar ' + esc(r.name) + '"></td>' +
                    '<td>' + esc(r.name) + '</td><td>' + r.points + '</td></tr>';
            }).join('');
        }
        updatePreview();
    }

    function applySelection(mode, rows) {
        if (mode === 'present') {
            QG.api('/api/attendance?classGroupId=' + encodeURIComponent(ps.classGroupId) + '&day=' + QG.todayString())
                .then(function (day) {
                    if (!day.started) {
                        QG.toast('Não há chamada registrada hoje para esta turma.', { error: true });
                        return;
                    }
                    var presentIds = {};
                    day.students.forEach(function (s) { if (s.status === 1) presentIds[s.studentId] = true; });
                    ps.selected = {};
                    rows.forEach(function (r) { if (presentIds[r.studentId]) ps.selected[r.enrollmentId] = true; });
                    renderList(rows);
                })
                .catch(function (err) { QG.toast(QG.errorMessage(err), { error: true }); });
            return;
        }
        rows.forEach(function (r) {
            if (mode === 'all') ps.selected[r.enrollmentId] = true;
            else if (mode === 'none') delete ps.selected[r.enrollmentId];
            else if (mode === 'invert') {
                if (ps.selected[r.enrollmentId]) delete ps.selected[r.enrollmentId];
                else ps.selected[r.enrollmentId] = true;
            }
        });
        renderList(rows);
    }

    function selectedIds(rows) {
        return rows.filter(function (r) { return ps.selected[r.enrollmentId]; }).map(function (r) { return r.enrollmentId; });
    }

    function updatePreview() {
        var preview = document.getElementById('pts-preview');
        if (!preview) return;
        var rows = rowsOfGroup();
        var count = selectedIds(rows).length;
        var delta = parseInt(ps.delta, 10);
        var who = count + ' de ' + rows.length + (rows.length === 1 ? ' aluno selecionado' : ' alunos selecionados');
        preview.textContent = delta
            ? I.signed(delta) + (Math.abs(delta) === 1 ? ' ponto' : ' pontos') + ' para cada um · ' + who
            : who;
    }

    /* ------------------------------------ saving ------------------------------------ */

    function submitBatch(rows) {
        if (ps.busy) return; // a double click must not give the points twice
        var delta = parseInt(ps.delta, 10);
        var ids = selectedIds(rows);
        if (!delta) {
            QG.toast('Informe a quantidade de pontos (diferente de zero).', { error: true });
            return;
        }
        if (ids.length === 0) {
            QG.toast('Selecione ao menos um aluno.', { error: true });
            return;
        }

        var note = ps.note.trim();
        var tag = ps.tag.trim();
        ps.busy = true;
        document.getElementById('pts-submit').disabled = true;

        QG.api('/api/points/batch', {
            method: 'POST',
            body: { enrollmentIds: ids, delta: delta, note: note, tag: tag }
        }).then(function (res) {
            var batch = {
                at: Date.now(),
                classGroupId: ps.classGroupId,
                delta: delta,
                note: note,
                tag: tag,
                count: res.count,
                historyIds: res.entries.map(function (e) { return e.historyId; }),
                undone: false
            };
            ps.batches.unshift(batch);
            ps.delta = '';
            ps.note = '';
            ps.tag = '';
            ps.selectedFor = null; // next render ticks everybody again
            QG.toast(I.signed(delta) + (Math.abs(delta) === 1 ? ' ponto' : ' pontos') + ' lançados para ' + res.count +
                (res.count === 1 ? ' aluno.' : ' alunos.'), {
                action: { label: 'Desfazer', onClick: function () { undoBatch(batch, true); } }
            });
            QG.refreshSuggestions();
            return QG.loadAll();
        }).catch(function (err) {
            QG.toast(QG.errorMessage(err), { error: true });
        }).then(function () {
            ps.busy = false;
            var submit = document.getElementById('pts-submit');
            if (submit) submit.disabled = false;
        });
    }

    /* ----------------------------- this session's batches ----------------------------- */

    function renderBatches() {
        var el = document.getElementById('pts-batches');
        if (!el) return;
        if (ps.batches.length === 0) {
            el.innerHTML = '';
            return;
        }
        el.innerHTML = '<div class="card"><h2>Lançamentos em lote desta sessão</h2>' +
            '<div class="table-wrap"><table><thead><tr><th>Hora</th><th>Turma</th><th>Pontos</th><th>Detalhes</th><th></th></tr></thead><tbody>' +
            ps.batches.map(function (b, i) {
                var details = [b.count + (b.count === 1 ? ' aluno' : ' alunos')];
                if (b.tag) details.push(b.tag);
                if (b.note) details.push('“' + b.note + '”');
                return '<tr class="' + (b.undone ? 'reverted' : '') + '"><td>' +
                    esc(new Date(b.at).toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' })) + '</td>' +
                    '<td>' + esc(QG.classGroupName(b.classGroupId)) + '</td>' +
                    '<td class="' + (b.delta < 0 ? 'delta-neg' : 'delta-pos') + '">' + I.signed(b.delta) + '</td>' +
                    '<td>' + esc(details.join(' · ')) + '</td>' +
                    '<td class="actions">' + (b.undone
                        ? '<span class="chip">desfeito</span>'
                        : '<button type="button" class="btn secondary small" data-undo="' + i + '">Desfazer lote</button>') + '</td></tr>';
            }).join('') + '</tbody></table></div>' +
            '<p class="muted">Desfazer cancela cada lançamento do lote; eles continuam no histórico dos alunos, marcados como estornados.</p></div>';

        el.querySelectorAll('button[data-undo]').forEach(function (btn) {
            btn.addEventListener('click', function () { undoBatch(ps.batches[parseInt(btn.getAttribute('data-undo'), 10)], false); });
        });
    }

    function undoBatch(batch, fromToast) {
        if (!batch || batch.undone) return;
        if (!fromToast) {
            var question = 'Desfazer o lote de ' + I.signed(batch.delta) + ' ponto(s) para ' + batch.count + ' aluno(s)?';
            if (!confirm(question)) return;
        }
        QG.api('/api/history/revert-batch', { method: 'POST', body: { ids: batch.historyIds } }).then(function (res) {
            // Entries that were already cancelled elsewhere count as done; anything else that failed stays in the batch.
            var stillPending = res.failed.filter(function (f) { return f.error !== 'ALREADY_REVERTED'; });
            batch.historyIds = stillPending.map(function (f) { return f.id; });
            batch.undone = stillPending.length === 0;
            if (batch.undone) {
                QG.toast('Lote desfeito.');
            } else {
                var why = QG.errorMessage(new Error(stillPending[0].error));
                QG.toast(stillPending.length + ' lançamento(s) não puderam ser desfeitos. ' + why, { error: true });
            }
            return QG.loadAll();
        }).catch(function (err) {
            QG.toast(QG.errorMessage(err), { error: true });
        });
    }
})();
