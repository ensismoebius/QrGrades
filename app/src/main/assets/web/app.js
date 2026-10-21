(function () {
    'use strict';

    var state = {
        disciplines: [],
        classGroups: [],
        students: [],
        overview: [],
        goalsByDiscipline: {},
        tab: 'overview',
        leaderboardDisciplineId: null,
        studentsDisciplineId: null,
        qrDisciplineId: null,
        qrExcludedIds: {},
        historyQuery: '',
        historyStudentId: null,
        historyDisciplineId: '',
        historyPeriod: 'all'
    };

    /* ------------------------------- API helper ------------------------------- */

    function api(path, options) {
        options = options || {};
        options.credentials = 'same-origin';
        if (options.body && !(options.body instanceof FormData)) {
            options.headers = Object.assign({ 'Content-Type': 'application/json' }, options.headers || {});
            options.body = JSON.stringify(options.body);
        }
        return fetch(path, options).then(function (res) {
            if (res.status === 401) {
                showLogin();
                return res.json().catch(function () { return {}; }).then(function (data) {
                    throw new Error(data.error || 'não autorizado');
                });
            }
            return res.json().catch(function () { return {}; }).then(function (data) {
                if (!res.ok) throw new Error(data.error || ('falha na requisição: ' + res.status));
                return data;
            });
        });
    }

    /* --------------------------------- Login ---------------------------------- */

    function showLogin() {
        document.getElementById('app-shell').classList.add('hidden');
        document.getElementById('login-screen').classList.remove('hidden');
    }

    function showApp() {
        document.getElementById('login-screen').classList.add('hidden');
        document.getElementById('app-shell').classList.remove('hidden');
    }

    document.getElementById('login-card').addEventListener('submit', function (e) {
        e.preventDefault();
        var password = document.getElementById('login-password').value;
        var errorEl = document.getElementById('login-error');
        errorEl.textContent = '';
        api('/api/login', { method: 'POST', body: { password: password } }).then(function () {
            document.getElementById('login-password').value = '';
            showApp();
            loadAll();
        }).catch(function (err) {
            errorEl.textContent = err.message;
        });
    });

    document.getElementById('logout-btn').addEventListener('click', function () {
        api('/api/logout', { method: 'POST' }).then(showLogin).catch(function () { showLogin(); });
    });

    /* ---------------------------------- Tabs ----------------------------------- */

    document.getElementById('tab-nav').addEventListener('click', function (e) {
        var btn = e.target.closest('button[data-tab]');
        if (!btn) return;
        selectTab(btn.getAttribute('data-tab'));
    });

    function selectTab(tab) {
        state.tab = tab;
        document.querySelectorAll('#tab-nav button').forEach(function (b) {
            b.classList.toggle('active', b.getAttribute('data-tab') === tab);
        });
        document.querySelectorAll('section.tab').forEach(function (s) {
            s.classList.toggle('active', s.id === 'tab-' + tab);
        });
        renderTab(tab);
    }

    function renderTab(tab) {
        if (tab === 'overview') renderOverview();
        else if (tab === 'students') renderStudents();
        else if (tab === 'history') renderHistory();
        else if (tab === 'disciplines') renderDisciplines();
        else if (tab === 'classgroups') renderClassGroups();
        else if (tab === 'goals') renderGoals();
        else if (tab === 'qrcodes') renderQrCodes();
        else if (tab === 'data') renderData();
    }

    /* --------------------------------- Loading --------------------------------- */

    function loadAll() {
        return Promise.all([
            api('/api/disciplines').then(function (d) { state.disciplines = d; }),
            api('/api/classgroups').then(function (g) { state.classGroups = g; }),
            api('/api/students').then(function (s) { state.students = s; }),
            api('/api/overview').then(function (o) { state.overview = o; })
        ]).then(function () { renderTab(state.tab); });
    }

    function disciplineName(id) {
        var d = state.disciplines.find(function (x) { return x.id === id; });
        return d ? d.name : '';
    }

    function studentName(id) {
        var s = state.students.find(function (x) { return x.id === id; });
        return s ? s.name : '(aluno desconhecido)';
    }

    function classGroupName(id) {
        var g = state.classGroups.find(function (x) { return x.id === id; });
        return g ? g.name : '';
    }

    function classGroupsForDiscipline(disciplineId) {
        return state.classGroups.filter(function (g) { return g.disciplineId === disciplineId; });
    }

    function disciplineNameForClassGroup(classGroupId) {
        var g = state.classGroups.find(function (x) { return x.id === classGroupId; });
        return g ? disciplineName(g.disciplineId) : '';
    }

    /* --------------------------------- Modal ----------------------------------- */

    function openModal(title, fieldsHtml, onSubmit, onDelete) {
        var root = document.getElementById('modal-root');
        root.innerHTML =
            '<div class="modal-backdrop"><div class="modal">' +
            '<h2>' + escapeHtml(title) + '</h2>' +
            '<form id="modal-form">' + fieldsHtml +
            '<div class="modal-actions">' +
            (onDelete ? '<button type="button" class="btn danger" id="modal-delete">Excluir</button>' : '') +
            '<button type="button" class="btn secondary" id="modal-cancel">Cancelar</button>' +
            '<button type="submit" class="btn">Salvar</button>' +
            '</div></form></div></div>';

        root.querySelector('#modal-cancel').addEventListener('click', closeModal);
        root.querySelector('.modal-backdrop').addEventListener('click', function (e) {
            if (e.target === e.currentTarget) closeModal();
        });
        if (onDelete) root.querySelector('#modal-delete').addEventListener('click', function () {
            onDelete();
        });
        root.querySelector('#modal-form').addEventListener('submit', function (e) {
            e.preventDefault();
            onSubmit(new FormData(e.target));
        });
    }

    function closeModal() {
        document.getElementById('modal-root').innerHTML = '';
    }

    function escapeHtml(value) {
        var div = document.createElement('div');
        div.textContent = value == null ? '' : String(value);
        return div.innerHTML;
    }

    /* -------------------------------- Overview ---------------------------------- */

    function renderOverview() {
        var el = document.getElementById('tab-overview');

        if (state.disciplines.length === 0) {
            el.innerHTML = '<div class="card"><p>Cadastre uma disciplina primeiro.</p></div>';
            return;
        }
        if (state.leaderboardDisciplineId == null ||
            !state.disciplines.some(function (d) { return d.id === state.leaderboardDisciplineId; })) {
            state.leaderboardDisciplineId = state.disciplines[0].id;
        }

        var scoped = state.overview.filter(function (row) { return row.disciplineId === state.leaderboardDisciplineId; });
        var top = scoped.slice().sort(function (a, b) { return b.points - a.points; }).slice(0, 10);

        el.innerHTML =
            '<div class="card"><div class="row"><h2 style="flex:1">Ranking</h2><label>Disciplina:</label>' +
            '<select id="leaderboard-discipline">' + disciplineOptions(state.leaderboardDisciplineId) + '</select></div>' +
            leaderboardSvg(top) + '</div>' +
            '<div class="card"><h2>Pontos acumulados ao longo do tempo</h2>' + pointsOverTimeSvg(scoped) + '</div>' +
            '<div class="card"><h2>Todas as matrículas</h2><div class="table-wrap"><table><thead><tr>' +
            '<th>Nome</th><th>Disciplina</th><th>Turma</th><th>Pontos</th></tr></thead><tbody>' +
            state.overview.map(function (row) {
                return '<tr><td>' + escapeHtml(row.name) + '</td><td>' + escapeHtml(row.discipline) +
                    '</td><td>' + escapeHtml(row.classGroup) + '</td><td>' + row.points + '</td></tr>';
            }).join('') +
            '</tbody></table></div></div>';

        document.getElementById('leaderboard-discipline').addEventListener('change', function (e) {
            state.leaderboardDisciplineId = e.target.value;
            renderOverview();
        });
    }

    function leaderboardSvg(rows) {
        if (rows.length === 0) return '<p>Ainda não há dados.</p>';
        var width = 600, barHeight = 28, gap = 8, labelWidth = 140;
        var max = Math.max.apply(null, rows.map(function (r) { return r.points; }).concat([1]));
        var height = rows.length * (barHeight + gap);
        var chartWidth = width - labelWidth - 50;

        var bars = rows.map(function (r, i) {
            var y = i * (barHeight + gap);
            var w = Math.max(2, (r.points / max) * chartWidth);
            return '<text x="0" y="' + (y + barHeight * 0.7) + '" font-size="12" fill="var(--md-on-surface)">' +
                escapeHtml(truncate(r.name, 16)) + '</text>' +
                '<rect x="' + labelWidth + '" y="' + y + '" width="' + w + '" height="' + barHeight +
                '" rx="6" fill="var(--md-primary)"></rect>' +
                '<text x="' + (labelWidth + w + 6) + '" y="' + (y + barHeight * 0.7) + '" font-size="12" fill="var(--md-on-surface)">' +
                r.points + '</text>';
        }).join('');

        return '<svg class="chart-svg" viewBox="0 0 ' + width + ' ' + height + '" xmlns="http://www.w3.org/2000/svg">' + bars + '</svg>';
    }

    function pointsOverTimeSvg(rows) {
        var points = [];
        rows.forEach(function (r) {
            (r.history || []).forEach(function (h) { points.push(h); });
        });
        if (points.length === 0) return '<p>Ainda não há histórico.</p>';

        points.sort(function (a, b) { return a.timestamp - b.timestamp; });
        var cumulative = 0;
        var series = points.map(function (h) {
            cumulative += h.points;
            return { t: h.timestamp, v: cumulative };
        });

        var width = 600, height = 220, padding = 30;
        var minT = series[0].t, maxT = series[series.length - 1].t;
        var maxV = Math.max.apply(null, series.map(function (s) { return s.v; }).concat([1]));
        var minV = Math.min(0, Math.min.apply(null, series.map(function (s) { return s.v; })));
        var spanT = Math.max(1, maxT - minT);
        var spanV = Math.max(1, maxV - minV);

        function x(t) { return padding + ((t - minT) / spanT) * (width - padding * 2); }
        function y(v) { return height - padding - ((v - minV) / spanV) * (height - padding * 2); }

        var path = series.map(function (s, i) {
            return (i === 0 ? 'M' : 'L') + x(s.t).toFixed(1) + ' ' + y(s.v).toFixed(1);
        }).join(' ');

        return '<svg class="chart-svg" viewBox="0 0 ' + width + ' ' + height + '" xmlns="http://www.w3.org/2000/svg">' +
            '<line x1="' + padding + '" y1="' + y(0) + '" x2="' + (width - padding) + '" y2="' + y(0) +
            '" stroke="var(--md-outline)" stroke-width="1"></line>' +
            '<path d="' + path + '" fill="none" stroke="var(--md-primary)" stroke-width="2"></path>' +
            '</svg>';
    }

    function truncate(text, max) {
        text = text || '';
        return text.length > max ? text.slice(0, max - 1) + '…' : text;
    }

    /* -------------------------------- Students ----------------------------------- */

    function renderStudents() {
        var el = document.getElementById('tab-students');

        if (state.disciplines.length === 0) {
            el.innerHTML = '<div class="card"><p>Cadastre uma disciplina primeiro.</p></div>';
            return;
        }
        if (state.studentsDisciplineId == null ||
            !state.disciplines.some(function (d) { return d.id === state.studentsDisciplineId; })) {
            state.studentsDisciplineId = state.disciplines[0].id;
        }

        var enrolledIds = {};
        state.overview.forEach(function (row) {
            if (row.disciplineId === state.studentsDisciplineId) enrolledIds[row.studentId] = true;
        });
        var scopedStudents = state.students.filter(function (s) { return enrolledIds[s.id]; });

        el.innerHTML =
            '<div class="card">' +
            '<div class="row"><h2 style="flex:1">Alunos</h2><label>Disciplina:</label>' +
            '<select id="students-discipline">' + disciplineOptions(state.studentsDisciplineId) + '</select>' +
            '<button class="btn" id="add-student">Adicionar aluno</button></div>' +
            '<div class="table-wrap"><table><thead><tr><th></th><th>Nome</th><th>Foto</th></tr></thead><tbody id="students-body"></tbody></table></div>' +
            '</div>';

        document.getElementById('students-discipline').addEventListener('change', function (e) {
            state.studentsDisciplineId = e.target.value;
            renderStudents();
        });

        var body = document.getElementById('students-body');
        scopedStudents.forEach(function (s) {
            var tr = document.createElement('tr');
            tr.className = 'clickable';
            tr.innerHTML = '<td><img class="avatar" src="' + (s.hasPhoto ? '/api/students/' + s.id + '/photo' : '') +
                '" onerror="this.style.visibility=\'hidden\'"></td>' +
                '<td>' + escapeHtml(s.name) + '</td><td>' + (s.hasPhoto ? 'Sim' : 'Não') + '</td>';
            tr.addEventListener('click', function () { toggleStudentDetail(tr, s); });
            body.appendChild(tr);
        });

        document.getElementById('add-student').addEventListener('click', function () {
            openModal('Adicionar aluno', '<label>Nome</label><input name="name" required>', function (fd) {
                api('/api/students', { method: 'POST', body: { name: fd.get('name') } })
                    .then(function () { closeModal(); return loadAll(); });
            });
        });
    }

    function toggleStudentDetail(tr, student) {
        var next = tr.nextElementSibling;
        if (next && next.classList.contains('detail-row')) { next.remove(); return; }
        document.querySelectorAll('.detail-row').forEach(function (r) { r.remove(); });

        var detail = document.createElement('tr');
        detail.className = 'detail-row';
        detail.innerHTML = '<td colspan="3">Carregando…</td>';
        tr.after(detail);

        Promise.all([
            api('/api/enrollments?studentId=' + encodeURIComponent(student.id)),
        ]).then(function (results) {
            renderStudentDetail(detail, student, results[0]);
        });
    }

    function renderStudentDetail(detail, student, enrollments) {
        var enrolledIds = enrollments.map(function (e) { return e.classGroupId; });
        var available = state.classGroups.filter(function (g) { return enrolledIds.indexOf(g.id) === -1; });

        var td = document.createElement('td');
        td.colSpan = 3;
        td.innerHTML =
            '<div class="row"><strong>' + escapeHtml(student.name) + '</strong>' +
            '<button class="btn secondary" data-action="edit-student">Renomear</button>' +
            '<button class="btn danger" data-action="delete-student">Excluir aluno</button></div>' +
            '<form class="inline-form" data-action="upload-photo"><label>Foto:</label>' +
            '<input type="file" name="photo" accept="image/*" required>' +
            '<button type="submit" class="btn secondary">Enviar</button></form>' +
            '<h3 style="font-size:13px;margin-bottom:4px">Matrículas</h3>' +
            '<div class="table-wrap"><table><thead><tr><th>Turma</th><th>Disciplina</th><th>Pontos</th><th></th></tr></thead>' +
            '<tbody data-role="enroll-body"></tbody></table></div>' +
            (available.length ? '<form class="inline-form" data-action="enroll"><select name="classGroupId">' +
                available.map(function (g) { return '<option value="' + g.id + '">' + escapeHtml(g.name) +
                    ' (' + escapeHtml(disciplineName(g.disciplineId)) + ')</option>'; }).join('') +
                '</select><button type="submit" class="btn secondary">Matricular</button></form>' : '');

        detail.innerHTML = '';
        detail.appendChild(td);

        var enrollBody = td.querySelector('[data-role="enroll-body"]');
        enrollments.forEach(function (e) {
            var row = document.createElement('tr');
            row.innerHTML = '<td>' + escapeHtml(classGroupName(e.classGroupId)) + '</td>' +
                '<td>' + escapeHtml(disciplineNameForClassGroup(e.classGroupId)) + '</td>' +
                '<td>' + e.grades + '</td>' +
                '<td><form class="inline-form" data-action="award">' +
                '<input type="number" name="delta" placeholder="+/-" style="width:70px" required>' +
                '<input type="text" name="note" placeholder="observação" style="width:100px">' +
                '<button type="submit" class="btn secondary">Lançar</button>' +
                '<button type="button" class="btn danger" data-action="unenroll">Remover</button></form></td>';

            row.querySelector('[data-action="award"]').addEventListener('submit', function (ev) {
                ev.preventDefault();
                var fd = new FormData(ev.target);
                api('/api/enrollments/' + e.id + '/points', {
                    method: 'POST',
                    body: { delta: parseInt(fd.get('delta'), 10), note: fd.get('note') || '' }
                }).then(function () { return loadAll(); }).then(function () { selectTab('students'); });
            });
            row.querySelector('[data-action="unenroll"]').addEventListener('click', function () {
                api('/api/enrollments/' + e.id, { method: 'DELETE' })
                    .then(function () { return loadAll(); }).then(function () { selectTab('students'); });
            });
            enrollBody.appendChild(row);
        });

        var enrollForm = td.querySelector('[data-action="enroll"]');
        if (enrollForm) enrollForm.addEventListener('submit', function (e) {
            e.preventDefault();
            var fd = new FormData(e.target);
            api('/api/enrollments', { method: 'POST', body: { studentId: student.id, classGroupId: fd.get('classGroupId') } })
                .then(function () { return loadAll(); }).then(function () { selectTab('students'); });
        });

        td.querySelector('[data-action="upload-photo"]').addEventListener('submit', function (e) {
            e.preventDefault();
            var fd = new FormData(e.target);
            api('/api/students/' + student.id + '/photo', { method: 'POST', body: fd })
                .then(function () { return loadAll(); }).then(function () { selectTab('students'); });
        });

        td.querySelector('[data-action="edit-student"]').addEventListener('click', function () {
            openModal('Renomear aluno', '<label>Nome</label><input name="name" value="' + escapeHtml(student.name) + '" required>', function (fd) {
                api('/api/students/' + student.id, { method: 'PUT', body: { name: fd.get('name') } })
                    .then(function () { closeModal(); return loadAll(); });
            });
        });

        td.querySelector('[data-action="delete-student"]').addEventListener('click', function () {
            if (!confirm('Excluir ' + student.name + '? Esta ação não pode ser desfeita.')) return;
            api('/api/students/' + student.id, { method: 'DELETE' }).then(function () { return loadAll(); });
        });
    }

    /* --------------------------------- History ------------------------------------ */

    // Lowercased and stripped of accents, so "joao" matches "João".
    function normalizeSearch(text) {
        return (text || '').normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase();
    }

    function formatDate(ms) {
        return new Date(ms).toLocaleString('pt-BR');
    }

    function periodTitle(p) {
        return p.label ? p.label : 'Encerrado em ' + new Date(p.closedAt).toLocaleDateString('pt-BR');
    }

    function renderHistory() {
        var el = document.getElementById('tab-history');
        if (state.students.length === 0) {
            el.innerHTML = '<div class="card"><p>Cadastre um aluno primeiro.</p></div>';
            return;
        }

        el.innerHTML =
            '<div class="card">' +
            '<div class="row"><h2 style="flex:1">Histórico do aluno</h2>' +
            '<input type="search" id="history-search" class="search-input" placeholder="Pesquisar aluno pelo nome…" value="' +
            escapeHtml(state.historyQuery) + '"></div>' +
            '<div class="table-wrap history-results"><table><tbody id="history-results"></tbody></table></div>' +
            '</div>' +
            '<div id="history-detail"></div>';

        var search = document.getElementById('history-search');
        search.addEventListener('input', function () {
            state.historyQuery = search.value;
            renderHistoryResults();
        });
        renderHistoryResults();
        if (state.historyStudentId) loadStudentHistory(state.historyStudentId);
    }

    function renderHistoryResults() {
        var body = document.getElementById('history-results');
        if (!body) return;
        var query = normalizeSearch(state.historyQuery.trim());
        var matches = state.students.filter(function (s) {
            return !query || normalizeSearch(s.name).indexOf(query) !== -1;
        }).sort(function (a, b) { return a.name.localeCompare(b.name); });

        body.innerHTML = '';
        if (matches.length === 0) {
            body.innerHTML = '<tr><td>Nenhum aluno corresponde a "' + escapeHtml(state.historyQuery) + '".</td></tr>';
            return;
        }
        matches.forEach(function (s) {
            var tr = document.createElement('tr');
            tr.className = 'clickable' + (s.id === state.historyStudentId ? ' selected' : '');
            tr.innerHTML = '<td><div class="row" style="margin:0;flex-wrap:nowrap">' +
                (s.hasPhoto ? '<img class="avatar" src="/api/students/' + s.id + '/photo" onerror="this.remove()">' : '') +
                '<span>' + escapeHtml(s.name) + '</span></div></td>';
            tr.addEventListener('click', function () {
                state.historyStudentId = s.id;
                state.historyDisciplineId = '';
                state.historyPeriod = 'all';
                renderHistoryResults();
                loadStudentHistory(s.id);
            });
            body.appendChild(tr);
        });
    }

    function loadStudentHistory(studentId) {
        var detail = document.getElementById('history-detail');
        detail.innerHTML = '<div class="card"><p>Carregando…</p></div>';
        api('/api/students/' + encodeURIComponent(studentId) + '/history').then(function (data) {
            if (state.historyStudentId !== studentId) return;
            renderStudentHistory(data);
        }).catch(function (err) {
            detail.innerHTML = '<div class="card"><div class="summary-banner error">' + escapeHtml(err.message) + '</div></div>';
        });
    }

    function renderStudentHistory(data) {
        var detail = document.getElementById('history-detail');
        if (!detail) return;
        var periods = data.periods.slice().sort(function (a, b) { return b.closedAt - a.closedAt; });

        // Discipline choices come from both live enrollments and past periods, so a discipline
        // the student has since left is still selectable.
        var disciplines = {};
        data.current.concat(periods).forEach(function (r) { if (r.disciplineId) disciplines[r.disciplineId] = r.discipline; });
        var byDiscipline = function (r) { return !state.historyDisciplineId || r.disciplineId === state.historyDisciplineId; };
        var current = data.current.filter(byDiscipline);
        var scopedPeriods = periods.filter(byDiscipline);
        if (state.historyPeriod !== 'all' && state.historyPeriod !== 'current' &&
            !scopedPeriods.some(function (p) { return p.periodId === state.historyPeriod; })) {
            state.historyPeriod = 'all';
        }

        var showCurrent = state.historyPeriod === 'all' || state.historyPeriod === 'current';
        var shownPeriods = scopedPeriods.filter(function (p) {
            return state.historyPeriod === 'all' || p.periodId === state.historyPeriod;
        });

        var currentPoints = current.reduce(function (sum, r) { return sum + r.points; }, 0);
        var closedPoints = scopedPeriods.reduce(function (sum, p) { return sum + p.points; }, 0);

        var html =
            '<div class="card">' +
            '<h2>' + escapeHtml(data.student.name) + '</h2>' +
            '<div class="history-filters"><label>Disciplina<select id="history-discipline"><option value="">Todas as disciplinas</option>' +
            Object.keys(disciplines).map(function (id) {
                return '<option value="' + id + '"' + (id === state.historyDisciplineId ? ' selected' : '') + '>' +
                    escapeHtml(disciplines[id]) + '</option>';
            }).join('') + '</select></label>' +
            '<label>Período<select id="history-period">' +
            '<option value="all"' + (state.historyPeriod === 'all' ? ' selected' : '') + '>Todos os períodos</option>' +
            '<option value="current"' + (state.historyPeriod === 'current' ? ' selected' : '') + '>Período atual</option>' +
            scopedPeriods.map(function (p) {
                return '<option value="' + p.periodId + '"' + (p.periodId === state.historyPeriod ? ' selected' : '') + '>' +
                    escapeHtml(periodTitle(p) + ' — ' + p.discipline) + '</option>';
            }).join('') + '</select></label></div>' +
            '<div class="stat-row">' +
            statTile('Pontos no período atual', currentPoints) +
            statTile('Períodos encerrados', scopedPeriods.length) +
            statTile('Pontos em períodos encerrados', closedPoints) +
            '</div></div>';

        if (showCurrent) {
            html += '<div class="card"><h2>Período atual</h2>';
            if (current.length === 0) {
                html += '<p>Não está matriculado(a) em nenhuma turma' + (state.historyDisciplineId ? ' desta disciplina' : '') + '.</p>';
            } else {
                html += current.map(function (r) {
                    return historyBlock(r.discipline + ' · ' + r.classGroup + (r.classGroupClosed ? ' (encerrada)' : ''), r.points, r.history);
                }).join('');
            }
            html += '</div>';
        }

        if (state.historyPeriod !== 'current') {
            if (shownPeriods.length === 0 && state.historyPeriod === 'all') {
                html += '<div class="card"><h2>Períodos encerrados</h2><p>Nenhum período encerrado ainda.</p></div>';
            }
            shownPeriods.forEach(function (p) {
                html += '<div class="card"><h2>' + escapeHtml(periodTitle(p)) +
                    ' <span class="muted">· encerrado em ' + escapeHtml(formatDate(p.closedAt)) + '</span></h2>' +
                    historyBlock(p.discipline + ' · ' + p.classGroup + (p.classClose ? ' (turma encerrada)' : ''), p.points, p.history) +
                    '</div>';
            });
        }

        detail.innerHTML = html;

        document.getElementById('history-discipline').addEventListener('change', function (e) {
            state.historyDisciplineId = e.target.value;
            renderStudentHistory(data);
        });
        document.getElementById('history-period').addEventListener('change', function (e) {
            state.historyPeriod = e.target.value;
            renderStudentHistory(data);
        });
    }

    function statTile(label, value) {
        return '<div class="stat-tile"><div class="stat-value">' + value + '</div><div class="stat-label">' +
            escapeHtml(label) + '</div></div>';
    }

    function historyBlock(title, points, history) {
        var entries = history.slice().sort(function (a, b) { return b.createdAt - a.createdAt; });
        return '<div class="history-block"><div class="row"><strong style="flex:1">' + escapeHtml(title) + '</strong>' +
            '<span class="points-badge">' + points + ' pts</span></div>' +
            (entries.length === 0 ? '<p class="muted">Nenhum lançamento de pontos registrado.</p>' :
                '<div class="table-wrap"><table><thead><tr><th>Data</th><th>Pontos</th><th>Observação</th></tr></thead><tbody>' +
                entries.map(function (h) {
                    return '<tr><td>' + escapeHtml(formatDate(h.createdAt)) + '</td>' +
                        '<td class="' + (h.pointsDelta < 0 ? 'delta-neg' : 'delta-pos') + '">' +
                        (h.pointsDelta > 0 ? '+' : '') + h.pointsDelta + '</td>' +
                        '<td>' + escapeHtml(h.note) + '</td></tr>';
                }).join('') + '</tbody></table></div>') +
            '</div>';
    }

    /* ------------------------------- Disciplines ----------------------------------- */

    function renderDisciplines() {
        var el = document.getElementById('tab-disciplines');
        el.innerHTML =
            '<div class="card">' +
            '<div class="row"><h2 style="flex:1">Disciplinas</h2><button class="btn" id="add-discipline">Adicionar disciplina</button></div>' +
            '<div class="table-wrap"><table><thead><tr><th>Nome</th></tr></thead><tbody id="disciplines-body"></tbody></table></div>' +
            '</div>';

        var body = document.getElementById('disciplines-body');
        state.disciplines.forEach(function (d) {
            var tr = document.createElement('tr');
            tr.className = 'clickable';
            tr.innerHTML = '<td>' + escapeHtml(d.name) + '</td>';
            tr.addEventListener('click', function () { openDisciplineModal(d); });
            body.appendChild(tr);
        });

        document.getElementById('add-discipline').addEventListener('click', function () { openDisciplineModal(null); });
    }

    function openDisciplineModal(d) {
        openModal(d ? 'Editar disciplina' : 'Adicionar disciplina',
            '<label>Name</label><input name="name" value="' + (d ? escapeHtml(d.name) : '') + '" required>',
            function (fd) {
                var body = { name: fd.get('name') };
                var call = d ? api('/api/disciplines/' + d.id, { method: 'PUT', body: body })
                    : api('/api/disciplines', { method: 'POST', body: body });
                call.then(function () { closeModal(); return loadAll(); });
            },
            d ? function () {
                if (!confirm('Excluir a disciplina "' + d.name + '"?')) return;
                api('/api/disciplines/' + d.id, { method: 'DELETE' })
                    .then(function () { closeModal(); return loadAll(); })
                    .catch(function (err) { alert(err.message === 'HAS_GROUPS' ? 'Exclua as turmas desta disciplina primeiro.' : err.message); });
            } : null);
    }

    /* ------------------------------- Class groups ----------------------------------- */

    function renderClassGroups() {
        var el = document.getElementById('tab-classgroups');
        el.innerHTML =
            '<div class="card">' +
            '<div class="row"><h2 style="flex:1">Turmas</h2><button class="btn" id="add-classgroup">Adicionar turma</button></div>' +
            '<div class="table-wrap"><table><thead><tr><th>Nome</th><th>Disciplina</th></tr></thead><tbody id="classgroups-body"></tbody></table></div>' +
            '</div>';

        var body = document.getElementById('classgroups-body');
        state.classGroups.forEach(function (g) {
            var tr = document.createElement('tr');
            tr.className = 'clickable';
            tr.innerHTML = '<td>' + escapeHtml(g.name) + '</td><td>' + escapeHtml(disciplineName(g.disciplineId)) + '</td>';
            tr.addEventListener('click', function () { openClassGroupModal(g); });
            body.appendChild(tr);
        });

        document.getElementById('add-classgroup').addEventListener('click', function () { openClassGroupModal(null); });
    }

    function disciplineOptions(selectedId) {
        return state.disciplines.map(function (d) {
            return '<option value="' + d.id + '"' + (d.id === selectedId ? ' selected' : '') + '>' + escapeHtml(d.name) + '</option>';
        }).join('');
    }

    function openClassGroupModal(g) {
        if (state.disciplines.length === 0) { alert('Cadastre uma disciplina primeiro.'); return; }
        openModal(g ? 'Editar turma' : 'Adicionar turma',
            '<label>Disciplina</label><select name="disciplineId">' + disciplineOptions(g ? g.disciplineId : state.disciplines[0].id) + '</select>' +
            '<label>Name</label><input name="name" value="' + (g ? escapeHtml(g.name) : '') + '" required>',
            function (fd) {
                var body = { disciplineId: fd.get('disciplineId'), name: fd.get('name') };
                var call = g ? api('/api/classgroups/' + g.id, { method: 'PUT', body: body })
                    : api('/api/classgroups', { method: 'POST', body: body });
                call.then(function () { closeModal(); return loadAll(); });
            },
            g ? function () {
                if (!confirm('Excluir a turma "' + g.name + '"?')) return;
                api('/api/classgroups/' + g.id, { method: 'DELETE' })
                    .then(function () { closeModal(); return loadAll(); })
                    .catch(function (err) { alert(err.message === 'HAS_STUDENTS' ? 'Remova os alunos matriculados primeiro.' : err.message); });
            } : null);
    }

    /* ----------------------------------- Goals -------------------------------------- */

    function renderGoals() {
        var el = document.getElementById('tab-goals');
        if (state.disciplines.length === 0) {
            el.innerHTML = '<div class="card"><p>Cadastre uma disciplina primeiro.</p></div>';
            return;
        }
        var selectedId = el.dataset.disciplineId || state.disciplines[0].id;

        el.innerHTML =
            '<div class="card">' +
            '<div class="row"><label>Disciplina:</label><select id="goals-discipline">' + disciplineOptions(selectedId) + '</select>' +
            '<div style="flex:1"></div><button class="btn" id="add-goal">Adicionar meta</button></div>' +
            '<div class="table-wrap"><table><thead><tr><th>Nome</th><th>Pontos da meta</th></tr></thead><tbody id="goals-body"></tbody></table></div>' +
            '</div>';
        el.dataset.disciplineId = selectedId;

        document.getElementById('goals-discipline').addEventListener('change', function (e) {
            el.dataset.disciplineId = e.target.value;
            renderGoals();
        });
        document.getElementById('add-goal').addEventListener('click', function () { openGoalModal(null, selectedId); });

        api('/api/goals?disciplineId=' + encodeURIComponent(selectedId)).then(function (goals) {
            var body = document.getElementById('goals-body');
            if (!body) return;
            goals.forEach(function (g) {
                var tr = document.createElement('tr');
                tr.className = 'clickable';
                tr.innerHTML = '<td>' + escapeHtml(g.name) + '</td><td>' + g.targetPoints + '</td>';
                tr.addEventListener('click', function () { openGoalModal(g, selectedId); });
                body.appendChild(tr);
            });
        });
    }

    function openGoalModal(g, disciplineId) {
        openModal(g ? 'Editar meta' : 'Adicionar meta',
            '<label>Name</label><input name="name" value="' + (g ? escapeHtml(g.name) : '') + '" required>' +
            '<label>Pontos da meta</label><input type="number" name="targetPoints" value="' + (g ? g.targetPoints : 0) + '" required>',
            function (fd) {
                var body = { disciplineId: disciplineId, name: fd.get('name'), targetPoints: parseInt(fd.get('targetPoints'), 10) };
                var call = g ? api('/api/goals/' + g.id, { method: 'PUT', body: body })
                    : api('/api/goals', { method: 'POST', body: body });
                call.then(function () { closeModal(); renderGoals(); });
            },
            g ? function () {
                if (!confirm('Excluir a meta "' + g.name + '"?')) return;
                api('/api/goals/' + g.id, { method: 'DELETE' }).then(function () { closeModal(); renderGoals(); });
            } : null);
    }

    /* ---------------------------------- QR codes --------------------------------------- */

    // "João Pedro Silva" -> "João P. S." — first name kept in full, every other name reduced
    // to its initial, as requested for the printable QR code sheet.
    function abbreviateName(name) {
        var parts = (name || '').trim().split(/\s+/).filter(Boolean);
        if (parts.length <= 1) return parts.join('');
        return parts[0] + ' ' + parts.slice(1).map(function (p) {
            return p.charAt(0).toUpperCase() + '.';
        }).join(' ');
    }

    function renderQrCodes() {
        var el = document.getElementById('tab-qrcodes');
        if (state.students.length === 0) {
            el.innerHTML = '<div class="card"><p>Cadastre um aluno primeiro.</p></div>';
            return;
        }

        var enrolledIds = null;
        if (state.qrDisciplineId) {
            enrolledIds = {};
            state.overview.forEach(function (row) {
                if (row.disciplineId === state.qrDisciplineId) enrolledIds[row.studentId] = true;
            });
        }
        var candidates = enrolledIds ? state.students.filter(function (s) { return enrolledIds[s.id]; }) : state.students;

        el.innerHTML =
            '<div class="card no-print">' +
            '<div class="row"><h2 style="flex:1">Imprimir códigos QR</h2>' +
            '<label>Disciplina:</label><select id="qr-discipline"><option value="">Todos os alunos</option>' +
            disciplineOptions(state.qrDisciplineId) + '</select>' +
            '<button type="button" class="btn secondary" id="qr-select-all">Selecionar todos</button>' +
            '<button type="button" class="btn secondary" id="qr-select-none">Desmarcar todos</button>' +
            '<button type="button" class="btn" id="qr-print-btn">Imprimir</button></div>' +
            '<p>Cada código é impresso com o primeiro nome do aluno por extenso e o restante do nome abreviado em iniciais.</p>' +
            '<div class="table-wrap"><table><thead><tr><th></th><th>Nome</th><th>Prévia da etiqueta</th></tr></thead>' +
            '<tbody id="qr-select-body"></tbody></table></div>' +
            '</div>' +
            '<div id="qr-print-grid" class="qr-grid"></div>';

        document.getElementById('qr-discipline').value = state.qrDisciplineId || '';
        document.getElementById('qr-discipline').addEventListener('change', function (e) {
            state.qrDisciplineId = e.target.value || null;
            renderQrCodes();
        });

        var body = document.getElementById('qr-select-body');
        candidates.forEach(function (s) {
            var tr = document.createElement('tr');
            var checked = !state.qrExcludedIds[s.id];
            tr.innerHTML = '<td><input type="checkbox" data-student-id="' + s.id + '"' + (checked ? ' checked' : '') + '></td>' +
                '<td>' + escapeHtml(s.name) + '</td><td>' + escapeHtml(abbreviateName(s.name)) + '</td>';
            tr.querySelector('input').addEventListener('change', function (e) {
                if (e.target.checked) delete state.qrExcludedIds[s.id];
                else state.qrExcludedIds[s.id] = true;
                updateQrPrintGrid(candidates);
            });
            body.appendChild(tr);
        });

        document.getElementById('qr-select-all').addEventListener('click', function () {
            candidates.forEach(function (s) { delete state.qrExcludedIds[s.id]; });
            renderQrCodes();
        });
        document.getElementById('qr-select-none').addEventListener('click', function () {
            candidates.forEach(function (s) { state.qrExcludedIds[s.id] = true; });
            renderQrCodes();
        });
        document.getElementById('qr-print-btn').addEventListener('click', function () {
            window.print();
        });

        updateQrPrintGrid(candidates);
    }

    function updateQrPrintGrid(candidates) {
        var grid = document.getElementById('qr-print-grid');
        if (!grid) return;
        var selected = candidates.filter(function (s) { return !state.qrExcludedIds[s.id]; });
        grid.innerHTML = selected.map(function (s) {
            return '<div class="qr-item">' +
                '<img src="/api/students/' + s.id + '/qrcode" alt="">' +
                '<div class="qr-name">' + escapeHtml(abbreviateName(s.name)) + '</div>' +
                '</div>';
        }).join('');
    }

    /* ----------------------------- Import / export ----------------------------------- */

    function renderData() {
        var el = document.getElementById('tab-data');
        el.innerHTML =
            '<div class="card"><h2>Exportar</h2>' +
            '<p>Baixa os dados de todos os alunos cadastrados no momento.</p>' +
            '<div class="row">' +
            '<a class="btn" href="/api/export?format=json">JSON</a>' +
            '<a class="btn" href="/api/export?format=md">Markdown</a>' +
            '<a class="btn" href="/api/export?format=pdf">PDF</a>' +
            '</div></div>' +

            '<div class="card"><h2>Importar backup JSON</h2>' +
            '<p class="warning-note">Uma cópia de segurança é feita automaticamente antes de importar.</p>' +
            '<form class="inline-form" id="import-json-form"><input type="file" name="file" accept="application/json" required>' +
            '<button type="submit" class="btn">Importar</button></form>' +
            '<div id="import-json-result"></div></div>' +

            '<div class="card"><h2>Importar lista de alunos (CSV)</h2>' +
            '<p>O cabeçalho do arquivo deve ter as colunas <code>name</code>, <code>discipline</code> e <code>classGroup</code> (nome, disciplina e turma). Uma cópia de segurança é feita automaticamente antes.</p>' +
            '<form class="inline-form" id="import-csv-form"><input type="file" name="file" accept=".csv,text/csv" required>' +
            '<button type="submit" class="btn">Importar</button></form>' +
            '<div id="import-csv-result"></div></div>';

        document.getElementById('import-json-form').addEventListener('submit', function (e) {
            e.preventDefault();
            var fd = new FormData(e.target);
            var resultEl = document.getElementById('import-json-result');
            resultEl.innerHTML = '';
            api('/api/import/json', { method: 'POST', body: fd }).then(function (res) {
                resultEl.innerHTML = '<div class="summary-banner">Importação concluída. Alunos novos: ' + res.newCount +
                    ', alunos existentes atualizados: ' + res.existingCount + '.</div>';
                return loadAll();
            }).catch(function (err) {
                resultEl.innerHTML = '<div class="summary-banner error">' + escapeHtml(err.message) + '</div>';
            });
        });

        document.getElementById('import-csv-form').addEventListener('submit', function (e) {
            e.preventDefault();
            var fd = new FormData(e.target);
            var resultEl = document.getElementById('import-csv-result');
            resultEl.innerHTML = '';
            api('/api/import/csv', { method: 'POST', body: fd }).then(function (res) {
                var text = 'Importação concluída. Alunos novos: ' + res.newCount + ', encontrados: ' + res.matchedCount + '.';
                if (res.newDisciplineCount || res.newClassGroupCount) {
                    text += ' Disciplinas novas: ' + res.newDisciplineCount + ', turmas novas: ' + res.newClassGroupCount + '.';
                }
                if (res.errors && res.errors.length) {
                    text += '\n' + res.errors.map(function (er) { return 'Linha ' + er.line + ': ' + er.reason; }).join('\n');
                }
                resultEl.innerHTML = '<div class="summary-banner' + (res.errors && res.errors.length ? ' error' : '') + '">' +
                    escapeHtml(text) + '</div>';
                return loadAll();
            }).catch(function (err) {
                resultEl.innerHTML = '<div class="summary-banner error">' + escapeHtml(err.message) + '</div>';
            });
        });
    }

    /* ---------------------------------- Bootstrap ------------------------------------- */

    api('/api/overview').then(function () {
        showApp();
        loadAll();
    }).catch(function () {
        showLogin();
    });
})();
