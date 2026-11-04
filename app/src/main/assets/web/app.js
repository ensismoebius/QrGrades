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
        // Bumped when a student's QR code is regenerated, so <img> URLs change and the browser can't show the retired code from cache.
        qrVersion: {},
        suggestions: null,
        loadedAt: 0
    };

    // Data older than this is reloaded when the teacher switches tabs: points are also awarded on the phone
    // (scanning QR codes) while this page stays open.
    var STALE_AFTER_MS = 15000;

    // Feature screens live in their own files (js/*.js) and register themselves here.
    var tabRenderers = {};
    var loadedHooks = [];

    function registerTab(name, render) {
        tabRenderers[name] = render;
    }

    /* ------------------------------- API helper ------------------------------- */

    function api(path, options) {
        options = options || {};
        options.credentials = 'same-origin';
        if (options.body && !(options.body instanceof FormData)) {
            // The charset matters: without it the phone's server reads the body as ASCII and mangles accents ("Participação").
            options.headers = Object.assign({ 'Content-Type': 'application/json; charset=utf-8' }, options.headers || {});
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
        if (Date.now() - state.loadedAt > STALE_AFTER_MS) {
            loadAll().catch(function () { renderTab(tab); }); // loadAll draws the tab itself once it has the fresh data
        } else {
            renderTab(tab);
        }
    }

    function renderTab(tab) {
        var render = tabRenderers[tab];
        if (render) render();
    }

    registerTab('overview', renderOverview);
    registerTab('students', renderStudents);
    registerTab('disciplines', renderDisciplines);
    registerTab('classgroups', renderClassGroups);
    registerTab('goals', renderGoals);
    registerTab('qrcodes', renderQrCodes);
    registerTab('data', renderData);

    /* --------------------------------- Loading --------------------------------- */

    function loadAll() {
        return Promise.all([
            api('/api/disciplines').then(function (d) { state.disciplines = d; }),
            api('/api/classgroups').then(function (g) { state.classGroups = g; }),
            api('/api/students').then(function (s) { state.students = s; }),
            api('/api/overview').then(function (o) { state.overview = o; })
        ]).then(function () {
            state.loadedAt = Date.now();
            ensureDatalists(); // fire and forget: the note/category suggestions are only a convenience
            loadedHooks.forEach(function (hook) { hook(); });
            renderTab(state.tab);
        });
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

    // Lowercased and stripped of accents, so "joao" matches "João".
    function normalizeSearch(text) {
        return (text || '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase();
    }

    function formatDate(ms) {
        return new Date(ms).toLocaleString('pt-BR');
    }

    function formatDay(ms) {
        return new Date(ms).toLocaleDateString('pt-BR');
    }

    // "2026-10-21" -> "21/10/2026" (the day string is already a local calendar day; no time zone maths needed).
    function formatDayString(day) {
        var p = String(day).split('-');
        return p.length === 3 ? p[2] + '/' + p[1] + '/' + p[0] : day;
    }

    // A Date as the "yyyy-MM-dd" day the API and <input type="date"> use (local time, not UTC).
    function dayString(d) {
        function pad(n) { return (n < 10 ? '0' : '') + n; }
        return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate());
    }
    function todayString() {
        return dayString(new Date());
    }

    // The server reports some failures as stable codes; everything else it sends is already a readable sentence.
    var ERROR_MESSAGES = {
        HAS_GROUPS: 'Exclua as turmas desta disciplina primeiro.',
        HAS_STUDENTS: 'Remova os alunos matriculados primeiro.',
        ALREADY_REVERTED: 'Este lançamento já foi estornado.',
        IS_REVERSAL: 'Um estorno não pode ser estornado de novo.',
        CLOSED_PERIOD: 'Este lançamento pertence a um período encerrado. Reabra o período para corrigi-lo.',
        CLASS_CLOSED: 'Esta turma foi encerrada; não é possível alterar a frequência.',
        NOT_FOUND: 'Lançamento não encontrado; talvez tenha sido excluído.',
        FAILED: 'Não foi possível concluir a operação.'
    };

    function errorMessage(err) {
        var message = err && err.message ? err.message : String(err);
        return ERROR_MESSAGES[message] || message;
    }

    /* ---------------------------------- Toasts ---------------------------------- */

    // A short message at the bottom of the screen, optionally with one action button (e.g. "Desfazer").
    function toast(message, options) {
        options = options || {};
        var root = document.getElementById('toast-root');
        var el = document.createElement('div');
        el.className = 'toast' + (options.error ? ' error' : '');
        var text = document.createElement('span');
        text.textContent = message;
        el.appendChild(text);
        function dismiss() { if (el.parentNode) el.parentNode.removeChild(el); }
        if (options.action) {
            var btn = document.createElement('button');
            btn.type = 'button';
            btn.textContent = options.action.label;
            btn.addEventListener('click', function () { dismiss(); options.action.onClick(); });
            el.appendChild(btn);
        }
        root.appendChild(el);
        setTimeout(dismiss, options.duration || (options.action ? 10000 : 4000));
    }

    /* ------------------------- Note / category suggestions ------------------------- */

    // <datalist>s shared by every "note" and "category" input, filled from the notes and categories used before.
    function ensureDatalists() {
        if (document.getElementById('dl-notes')) return Promise.resolve();
        return api('/api/suggestions').then(function (s) {
            state.suggestions = s;
            fillDatalist('dl-notes', s.notes);
            fillDatalist('dl-tags', s.tags);
        }).catch(function () { /* suggestions are a convenience; the inputs work without them */ });
    }

    function fillDatalist(id, values) {
        var list = document.getElementById(id);
        if (!list) {
            list = document.createElement('datalist');
            list.id = id;
            document.body.appendChild(list);
        }
        list.innerHTML = (values || []).map(function (v) { return '<option value="' + escapeHtml(v) + '">'; }).join('');
    }

    // Call after points were given, so a brand-new note or category shows up in the next suggestions.
    function refreshSuggestions() {
        var notes = document.getElementById('dl-notes');
        if (notes) notes.remove();
        var tags = document.getElementById('dl-tags');
        if (tags) tags.remove();
        return ensureDatalists();
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
            '<div class="qr-detail">' +
            '<img class="qr-thumb" data-role="qr-thumb" src="' + qrSrc(student.id) + '" alt="Código QR de ' + escapeHtml(student.name) + '">' +
            '<div><strong>Código QR</strong>' +
            '<div class="muted" data-role="qr-note">' + (student.qrRegenerated ? 'Este código já foi regerado.' : 'Código original.') + '</div>' +
            '<div class="row" style="margin-top:8px">' +
            '<button type="button" class="btn secondary" data-action="qr-print">Imprimir</button>' +
            '<button type="button" class="btn danger" data-action="qr-regenerate">Regerar código</button></div>' +
            '<div class="muted">Regerar invalida o código impresso anterior, útil quando o aluno o perde.</div></div></div>' +
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
                '<input type="text" name="note" placeholder="observação" list="dl-notes" style="width:120px">' +
                '<input type="text" name="tag" placeholder="categoria" list="dl-tags" style="width:110px">' +
                '<button type="submit" class="btn secondary">Lançar</button>' +
                '<button type="button" class="btn danger" data-action="unenroll">Remover</button></form></td>';

            row.querySelector('[data-action="award"]').addEventListener('submit', function (ev) {
                ev.preventDefault();
                var fd = new FormData(ev.target);
                api('/api/enrollments/' + e.id + '/points', {
                    method: 'POST',
                    body: { delta: parseInt(fd.get('delta'), 10), note: fd.get('note') || '', tag: fd.get('tag') || '' }
                }).then(function () { refreshSuggestions(); return loadAll(); })
                    .then(function () { selectTab('students'); })
                    .catch(function (err) { toast(errorMessage(err), { error: true }); });
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

        td.querySelector('[data-action="qr-print"]').addEventListener('click', function () { printQrSheet([student]); });
        td.querySelector('[data-action="qr-regenerate"]').addEventListener('click', function () {
            regenerateQr(student, function () {
                td.querySelector('[data-role="qr-thumb"]').src = qrSrc(student.id);
                td.querySelector('[data-role="qr-note"]').textContent = 'Este código já foi regerado.';
            });
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
            '<label>Nome</label><input name="name" value="' + (d ? escapeHtml(d.name) : '') + '" required>',
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
                    .catch(function (err) { alert(errorMessage(err)); });
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
            '<label>Nome</label><input name="name" value="' + (g ? escapeHtml(g.name) : '') + '" required>',
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
                    .catch(function (err) { alert(errorMessage(err)); });
            } : null);
    }

    /* ----------------------------------- Goals -------------------------------------- */

    // How each goal status reads in the table; "open" is a goal saved before goals had dates.
    var GOAL_STATUS = {
        active: { label: 'Em andamento', cls: '' },
        upcoming: { label: 'Futura', cls: 'excused' },
        ended: { label: 'Encerrada', cls: '' },
        open: { label: 'Sem datas', cls: 'absent' }
    };

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
            '<p class="muted">Toda meta tem data de início e de fim: só contam os pontos lançados entre essas duas datas (inclusive). ' +
            'Depois do fim, a meta guarda o resultado final; antes do início, ainda não conta pontos.</p>' +
            '<div class="table-wrap"><table><thead><tr><th>Nome</th><th>Pontos da meta</th><th>Início</th><th>Fim</th><th>Situação</th></tr></thead>' +
            '<tbody id="goals-body"></tbody></table></div>' +
            '</div>';
        el.dataset.disciplineId = selectedId;

        document.getElementById('goals-discipline').addEventListener('change', function (e) {
            el.dataset.disciplineId = e.target.value;
            renderGoals();
        });

        // The button works at once; the list it is given (to suggest the dates) fills in when the goals arrive.
        var loaded = [];
        document.getElementById('add-goal').addEventListener('click', function () { openGoalModal(null, selectedId, loaded); });

        api('/api/goals?disciplineId=' + encodeURIComponent(selectedId)).then(function (goals) {
            loaded = goals;
            var body = document.getElementById('goals-body');
            if (!body) return;
            goals.forEach(function (g) {
                var status = GOAL_STATUS[g.status] || GOAL_STATUS.open;
                var tr = document.createElement('tr');
                tr.className = 'clickable';
                tr.innerHTML = '<td>' + escapeHtml(g.name) + '</td><td>' + g.targetPoints + '</td>' +
                    '<td>' + (g.startDate ? escapeHtml(formatDayString(g.startDate)) : '—') + '</td>' +
                    '<td>' + (g.endDate ? escapeHtml(formatDayString(g.endDate)) : '—') + '</td>' +
                    '<td><span class="chip ' + status.cls + '">' + status.label + '</span></td>';
                tr.addEventListener('click', function () { openGoalModal(g, selectedId, goals); });
                body.appendChild(tr);
            });
        });
    }

    // The window a goal form opens with: the goal's own; for a new one, that of the goal that ends last if it
    // hasn't ended (goals for the same term share dates), otherwise from today to 30 days ahead.
    function goalWindowFor(goal, goals) {
        if (goal && goal.startDate && goal.endDate) return { start: goal.startDate, end: goal.endDate };
        var today = todayString();
        var latest = null;
        (goals || []).forEach(function (other) {
            if (other.endDate && other.endDate >= today && (!latest || other.endDate > latest.endDate)) latest = other;
        });
        if (latest) return { start: latest.startDate, end: latest.endDate };
        var later = new Date();
        later.setDate(later.getDate() + 30);
        return { start: today, end: dayString(later) };
    }

    function openGoalModal(g, disciplineId, goals) {
        var win = goalWindowFor(g, goals);
        openModal(g ? 'Editar meta' : 'Adicionar meta',
            '<label>Nome</label><input name="name" value="' + (g ? escapeHtml(g.name) : '') + '" required>' +
            '<label>Pontos da meta</label><input type="number" name="targetPoints" value="' + (g ? g.targetPoints : 0) + '" required>' +
            '<label>Início (primeiro dia que conta)</label><input type="date" name="startDate" value="' + win.start + '" required>' +
            '<label>Fim (último dia que conta)</label><input type="date" name="endDate" value="' + win.end + '" min="' + win.start + '" required>' +
            '<p class="muted">Só contam os pontos lançados entre essas duas datas, inclusive.' +
            (g && !g.startDate ? ' Esta meta ainda não tem datas: escolha-as para salvar.' : '') + '</p>',
            function (fd) {
                var startDate = fd.get('startDate');
                var endDate = fd.get('endDate');
                if (!startDate || !endDate) { toast('Informe as datas de início e de fim.', { error: true }); return; }
                if (endDate < startDate) { toast('O fim não pode ser anterior ao início.', { error: true }); return; }
                var body = {
                    disciplineId: disciplineId,
                    name: fd.get('name'),
                    targetPoints: parseInt(fd.get('targetPoints'), 10),
                    startDate: startDate,
                    endDate: endDate
                };
                var call = g ? api('/api/goals/' + g.id, { method: 'PUT', body: body })
                    : api('/api/goals', { method: 'POST', body: body });
                call.then(function () { closeModal(); renderGoals(); })
                    .catch(function (err) { toast(errorMessage(err), { error: true }); });
            },
            g ? function () {
                if (!confirm('Excluir a meta "' + g.name + '"?')) return;
                api('/api/goals/' + g.id, { method: 'DELETE' }).then(function () { closeModal(); renderGoals(); });
            } : null);

        // Starting after the current end would leave an empty window: the end follows the start, and can't go before it.
        var form = document.getElementById('modal-form');
        form.elements.startDate.addEventListener('change', function () {
            var start = form.elements.startDate.value;
            form.elements.endDate.min = start;
            if (start && form.elements.endDate.value < start) form.elements.endDate.value = start;
        });
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

    // Image URL of a student's current QR code; the version changes when it is regenerated.
    function qrSrc(studentId) {
        return '/api/students/' + studentId + '/qrcode?v=' + (state.qrVersion[studentId] || 0);
    }

    // Retires the student's printed code and issues a new one. The old code stops working at once, so ask first.
    function regenerateQr(student, onDone) {
        var question = 'Gerar um novo código QR para ' + student.name + '?\n\n' +
            'O código impresso atual deixará de funcionar imediatamente. Depois, imprima o novo código e entregue ao aluno.';
        if (!confirm(question)) return;
        api('/api/students/' + student.id + '/qrcode/regenerate', { method: 'POST' }).then(function (res) {
            student.qrRegenerated = true;
            state.students.forEach(function (s) { if (s.id === student.id) s.qrRegenerated = true; });
            state.qrVersion[student.id] = Date.now();
            if (onDone) onDone(res.student);
            toast('Novo código gerado para ' + student.name + '. O anterior não funciona mais.', {
                action: { label: 'Imprimir', onClick: function () { printQrSheet([student]); } },
                duration: 12000
            });
        }).catch(function (err) { toast(errorMessage(err), { error: true }); });
    }

    function qrItemHtml(s) {
        return '<div class="qr-item">' +
            '<img src="' + qrSrc(s.id) + '" alt="">' +
            '<div class="qr-name">' + escapeHtml(abbreviateName(s.name)) + '</div>' +
            '</div>';
    }

    // Prints just these students' codes, whatever screen is showing (see body.print-single in the stylesheet).
    function printQrSheet(students) {
        var sheet = document.getElementById('print-sheet');
        if (!sheet) {
            sheet = document.createElement('div');
            sheet.id = 'print-sheet';
            sheet.className = 'qr-grid';
            document.body.appendChild(sheet);
        }
        sheet.innerHTML = students.map(qrItemHtml).join('');
        var images = Array.prototype.slice.call(sheet.querySelectorAll('img'));
        Promise.all(images.map(function (img) {
            return new Promise(function (resolve) {
                if (img.complete) resolve();
                else { img.onload = resolve; img.onerror = resolve; }
            });
        })).then(function () {
            document.body.classList.add('print-single');
            window.onafterprint = function () {
                document.body.classList.remove('print-single');
                sheet.innerHTML = '';
                window.onafterprint = null;
            };
            window.print();
        });
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
            '<div class="table-wrap"><table><thead><tr><th></th><th>Nome</th><th>Prévia da etiqueta</th><th></th></tr></thead>' +
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
                '<td>' + escapeHtml(s.name) + (s.qrRegenerated ? ' <span class="muted">· código regerado</span>' : '') + '</td>' +
                '<td>' + escapeHtml(abbreviateName(s.name)) + '</td>' +
                '<td><button type="button" class="btn secondary" data-action="regenerate">Regerar</button></td>';
            tr.querySelector('input').addEventListener('change', function (e) {
                if (e.target.checked) delete state.qrExcludedIds[s.id];
                else state.qrExcludedIds[s.id] = true;
                updateQrPrintGrid(candidates);
            });
            tr.querySelector('[data-action="regenerate"]').addEventListener('click', function () {
                regenerateQr(s, function () { renderQrCodes(); });
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
        grid.innerHTML = selected.map(qrItemHtml).join('');
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

    // What the feature scripts (js/*.js) may use. They load after this file, so their tabs are registered before anything renders.
    window.QG = {
        state: state,
        api: api,
        escapeHtml: escapeHtml,
        normalizeSearch: normalizeSearch,
        formatDate: formatDate,
        formatDay: formatDay,
        formatDayString: formatDayString,
        todayString: todayString,
        errorMessage: errorMessage,
        toast: toast,
        openModal: openModal,
        closeModal: closeModal,
        disciplineOptions: disciplineOptions,
        disciplineName: disciplineName,
        classGroupName: classGroupName,
        classGroupsForDiscipline: classGroupsForDiscipline,
        abbreviateName: abbreviateName,
        ensureDatalists: ensureDatalists,
        refreshSuggestions: refreshSuggestions,
        loadAll: loadAll,
        selectTab: selectTab,
        renderTab: renderTab,
        currentTab: function () { return state.tab; },
        registerTab: registerTab,
        onLoaded: function (hook) { loadedHooks.push(hook); }
    };

    function bootstrap() {
        api('/api/overview').then(function () {
            showApp();
            loadAll();
        }).catch(function () {
            showLogin();
        });
    }

    // Wait for the feature scripts too: DOMContentLoaded fires after every (non-deferred) script at the end of <body> has run.
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', bootstrap);
    else bootstrap();
})();
