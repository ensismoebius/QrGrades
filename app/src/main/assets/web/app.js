(function () {
    'use strict';

    var state = {
        disciplines: [],
        classGroups: [],
        students: [],
        overview: [],
        goalsByDiscipline: {},
        bathroomVisits: [],
        indisciplineEvents: [],
        tab: 'overview',
        leaderboardDisciplineId: null,
        studentsDisciplineId: null,
        bathroomStudentId: null
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
                throw new Error('unauthorized');
            }
            return res.json().catch(function () { return {}; }).then(function (data) {
                if (!res.ok) throw new Error(data.error || ('request failed: ' + res.status));
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
        else if (tab === 'disciplines') renderDisciplines();
        else if (tab === 'classgroups') renderClassGroups();
        else if (tab === 'goals') renderGoals();
        else if (tab === 'bathroom') renderBathroom();
        else if (tab === 'data') renderData();
    }

    /* --------------------------------- Loading --------------------------------- */

    function loadAll() {
        return Promise.all([
            api('/api/disciplines').then(function (d) { state.disciplines = d; }),
            api('/api/classgroups').then(function (g) { state.classGroups = g; }),
            api('/api/students').then(function (s) { state.students = s; }),
            api('/api/overview').then(function (o) { state.overview = o; }),
            api('/api/bathroom').then(function (v) { state.bathroomVisits = v; }),
            api('/api/indiscipline').then(function (e) { state.indisciplineEvents = e; })
        ]).then(function () { renderTab(state.tab); });
    }

    function disciplineName(id) {
        var d = state.disciplines.find(function (x) { return x.id === id; });
        return d ? d.name : '';
    }

    function studentName(id) {
        var s = state.students.find(function (x) { return x.id === id; });
        return s ? s.name : '(unknown student)';
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
            (onDelete ? '<button type="button" class="btn danger" id="modal-delete">Delete</button>' : '') +
            '<button type="button" class="btn secondary" id="modal-cancel">Cancel</button>' +
            '<button type="submit" class="btn">Save</button>' +
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
            el.innerHTML = '<div class="card"><p>Add a discipline first.</p></div>';
            return;
        }
        if (state.leaderboardDisciplineId == null ||
            !state.disciplines.some(function (d) { return d.id === state.leaderboardDisciplineId; })) {
            state.leaderboardDisciplineId = state.disciplines[0].id;
        }

        var scoped = state.overview.filter(function (row) { return row.disciplineId === state.leaderboardDisciplineId; });
        var top = scoped.slice().sort(function (a, b) { return b.points - a.points; }).slice(0, 10);

        el.innerHTML =
            '<div class="card"><div class="row"><h2 style="flex:1">Leaderboard</h2><label>Discipline:</label>' +
            '<select id="leaderboard-discipline">' + disciplineOptions(state.leaderboardDisciplineId) + '</select></div>' +
            leaderboardSvg(top) + '</div>' +
            '<div class="card"><h2>Cumulative points over time</h2>' + pointsOverTimeSvg(scoped) + '</div>' +
            '<div class="card"><h2>All enrollments</h2><div class="table-wrap"><table><thead><tr>' +
            '<th>Name</th><th>Discipline</th><th>Class group</th><th>Points</th></tr></thead><tbody>' +
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
        if (rows.length === 0) return '<p>No data yet.</p>';
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
        if (points.length === 0) return '<p>No history yet.</p>';

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
            el.innerHTML = '<div class="card"><p>Add a discipline first.</p></div>';
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
            '<div class="row"><h2 style="flex:1">Students</h2><label>Discipline:</label>' +
            '<select id="students-discipline">' + disciplineOptions(state.studentsDisciplineId) + '</select>' +
            '<button class="btn" id="add-student">Add student</button></div>' +
            '<div class="table-wrap"><table><thead><tr><th></th><th>Name</th><th>Photo</th></tr></thead><tbody id="students-body"></tbody></table></div>' +
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
                '<td>' + escapeHtml(s.name) + '</td><td>' + (s.hasPhoto ? 'Yes' : 'No') + '</td>';
            tr.addEventListener('click', function () { toggleStudentDetail(tr, s); });
            body.appendChild(tr);
        });

        document.getElementById('add-student').addEventListener('click', function () {
            openModal('Add student', '<label>Name</label><input name="name" required>', function (fd) {
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
        detail.innerHTML = '<td colspan="3">Loading…</td>';
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
            '<button class="btn secondary" data-action="edit-student">Rename</button>' +
            '<button class="btn danger" data-action="delete-student">Delete student</button></div>' +
            '<form class="inline-form" data-action="upload-photo"><label>Photo:</label>' +
            '<input type="file" name="photo" accept="image/*" required>' +
            '<button type="submit" class="btn secondary">Upload</button></form>' +
            '<h3 style="font-size:13px;margin-bottom:4px">Enrollments</h3>' +
            '<div class="table-wrap"><table><thead><tr><th>Class group</th><th>Discipline</th><th>Points</th><th></th></tr></thead>' +
            '<tbody data-role="enroll-body"></tbody></table></div>' +
            (available.length ? '<form class="inline-form" data-action="enroll"><select name="classGroupId">' +
                available.map(function (g) { return '<option value="' + g.id + '">' + escapeHtml(g.name) +
                    ' (' + escapeHtml(disciplineName(g.disciplineId)) + ')</option>'; }).join('') +
                '</select><button type="submit" class="btn secondary">Enroll</button></form>' : '');

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
                '<input type="text" name="note" placeholder="note" style="width:100px">' +
                '<button type="submit" class="btn secondary">Award</button>' +
                '<button type="button" class="btn danger" data-action="unenroll">Remove</button></form></td>';

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
            openModal('Rename student', '<label>Name</label><input name="name" value="' + escapeHtml(student.name) + '" required>', function (fd) {
                api('/api/students/' + student.id, { method: 'PUT', body: { name: fd.get('name') } })
                    .then(function () { closeModal(); return loadAll(); });
            });
        });

        td.querySelector('[data-action="delete-student"]').addEventListener('click', function () {
            if (!confirm('Delete ' + student.name + '? This cannot be undone.')) return;
            api('/api/students/' + student.id, { method: 'DELETE' }).then(function () { return loadAll(); });
        });
    }

    /* ------------------------------- Disciplines ----------------------------------- */

    function renderDisciplines() {
        var el = document.getElementById('tab-disciplines');
        el.innerHTML =
            '<div class="card">' +
            '<div class="row"><h2 style="flex:1">Disciplines</h2><button class="btn" id="add-discipline">Add discipline</button></div>' +
            '<div class="table-wrap"><table><thead><tr><th>Name</th></tr></thead><tbody id="disciplines-body"></tbody></table></div>' +
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
        openModal(d ? 'Edit discipline' : 'Add discipline',
            '<label>Name</label><input name="name" value="' + (d ? escapeHtml(d.name) : '') + '" required>',
            function (fd) {
                var body = { name: fd.get('name') };
                var call = d ? api('/api/disciplines/' + d.id, { method: 'PUT', body: body })
                    : api('/api/disciplines', { method: 'POST', body: body });
                call.then(function () { closeModal(); return loadAll(); });
            },
            d ? function () {
                if (!confirm('Delete discipline "' + d.name + '"?')) return;
                api('/api/disciplines/' + d.id, { method: 'DELETE' })
                    .then(function () { closeModal(); return loadAll(); })
                    .catch(function (err) { alert(err.message === 'HAS_GROUPS' ? 'Remove its class groups first.' : err.message); });
            } : null);
    }

    /* ------------------------------- Class groups ----------------------------------- */

    function renderClassGroups() {
        var el = document.getElementById('tab-classgroups');
        el.innerHTML =
            '<div class="card">' +
            '<div class="row"><h2 style="flex:1">Class groups</h2><button class="btn" id="add-classgroup">Add class group</button></div>' +
            '<div class="table-wrap"><table><thead><tr><th>Name</th><th>Discipline</th></tr></thead><tbody id="classgroups-body"></tbody></table></div>' +
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
        if (state.disciplines.length === 0) { alert('Add a discipline first.'); return; }
        openModal(g ? 'Edit class group' : 'Add class group',
            '<label>Discipline</label><select name="disciplineId">' + disciplineOptions(g ? g.disciplineId : state.disciplines[0].id) + '</select>' +
            '<label>Name</label><input name="name" value="' + (g ? escapeHtml(g.name) : '') + '" required>',
            function (fd) {
                var body = { disciplineId: fd.get('disciplineId'), name: fd.get('name') };
                var call = g ? api('/api/classgroups/' + g.id, { method: 'PUT', body: body })
                    : api('/api/classgroups', { method: 'POST', body: body });
                call.then(function () { closeModal(); return loadAll(); });
            },
            g ? function () {
                if (!confirm('Delete class group "' + g.name + '"?')) return;
                api('/api/classgroups/' + g.id, { method: 'DELETE' })
                    .then(function () { closeModal(); return loadAll(); })
                    .catch(function (err) { alert(err.message === 'HAS_STUDENTS' ? 'Remove enrolled students first.' : err.message); });
            } : null);
    }

    /* ----------------------------------- Goals -------------------------------------- */

    function renderGoals() {
        var el = document.getElementById('tab-goals');
        if (state.disciplines.length === 0) {
            el.innerHTML = '<div class="card"><p>Add a discipline first.</p></div>';
            return;
        }
        var selectedId = el.dataset.disciplineId || state.disciplines[0].id;

        el.innerHTML =
            '<div class="card">' +
            '<div class="row"><label>Discipline:</label><select id="goals-discipline">' + disciplineOptions(selectedId) + '</select>' +
            '<div style="flex:1"></div><button class="btn" id="add-goal">Add goal</button></div>' +
            '<div class="table-wrap"><table><thead><tr><th>Name</th><th>Target points</th></tr></thead><tbody id="goals-body"></tbody></table></div>' +
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
        openModal(g ? 'Edit goal' : 'Add goal',
            '<label>Name</label><input name="name" value="' + (g ? escapeHtml(g.name) : '') + '" required>' +
            '<label>Target points</label><input type="number" name="targetPoints" value="' + (g ? g.targetPoints : 0) + '" required>',
            function (fd) {
                var body = { disciplineId: disciplineId, name: fd.get('name'), targetPoints: parseInt(fd.get('targetPoints'), 10) };
                var call = g ? api('/api/goals/' + g.id, { method: 'PUT', body: body })
                    : api('/api/goals', { method: 'POST', body: body });
                call.then(function () { closeModal(); renderGoals(); });
            },
            g ? function () {
                if (!confirm('Delete goal "' + g.name + '"?')) return;
                api('/api/goals/' + g.id, { method: 'DELETE' }).then(function () { closeModal(); renderGoals(); });
            } : null);
    }

    /* --------------------------- Bathroom & indiscipline ------------------------------ */

    function formatDateTime(ms) {
        return ms == null ? '' : new Date(ms).toLocaleString();
    }

    function formatDuration(ms) {
        var totalSeconds = Math.floor(ms / 1000);
        var h = Math.floor(totalSeconds / 3600);
        var m = Math.floor((totalSeconds % 3600) / 60);
        var s = totalSeconds % 60;
        return h + ':' + (m < 10 ? '0' : '') + m + ':' + (s < 10 ? '0' : '') + s;
    }

    function studentOptions(selectedId) {
        return state.students.map(function (s) {
            return '<option value="' + s.id + '"' + (s.id === selectedId ? ' selected' : '') + '>' + escapeHtml(s.name) + '</option>';
        }).join('');
    }

    function renderBathroom() {
        var el = document.getElementById('tab-bathroom');
        if (state.students.length === 0) {
            el.innerHTML = '<div class="card"><p>Add a student first.</p></div>';
            return;
        }

        var openVisits = state.bathroomVisits.filter(function (v) { return v.returnedAt == null; });

        el.innerHTML =
            '<div class="card"><h2>Send to the bathroom</h2>' +
            '<form class="inline-form" id="bathroom-start-form"><select name="studentId">' + studentOptions(null) + '</select>' +
            '<button type="submit" class="btn">Go to bathroom</button></form>' +
            '<div id="bathroom-start-result"></div></div>' +

            '<div class="card"><h2>Currently out (' + openVisits.length + ')</h2>' +
            (openVisits.length === 0 ? '<p>Nobody is out right now.</p>' :
                '<div class="table-wrap"><table><thead><tr><th>Student</th><th>Went at</th><th></th></tr></thead><tbody id="bathroom-open-body"></tbody></table></div>') +
            '</div>' +

            '<div class="card"><h2>Bathroom log</h2>' +
            '<div class="table-wrap"><table><thead><tr><th>Student</th><th>Went at</th><th>Returned at</th><th>Duration</th><th>Evaded</th></tr></thead>' +
            '<tbody>' + state.bathroomVisits.map(function (v) {
                var duration = v.returnedAt != null ? formatDuration(v.returnedAt - v.wentAt) : '—';
                return '<tr' + (v.evaded ? ' class="warning-row"' : '') + '><td>' + escapeHtml(studentName(v.studentId)) + '</td>' +
                    '<td>' + formatDateTime(v.wentAt) + '</td><td>' + formatDateTime(v.returnedAt) + '</td>' +
                    '<td>' + duration + '</td><td>' + (v.evaded ? 'Yes' : 'No') + '</td></tr>';
            }).join('') + '</tbody></table></div></div>' +

            '<div class="card"><h2>Register indiscipline</h2>' +
            '<form class="inline-form" id="indiscipline-form"><select name="studentId">' + studentOptions(null) + '</select>' +
            '<input type="text" name="note" placeholder="note (optional)">' +
            '<button type="submit" class="btn danger">Register</button></form>' +
            '<div id="indiscipline-result"></div>' +
            '<div class="table-wrap"><table><thead><tr><th>Student</th><th>When</th><th>Note</th></tr></thead>' +
            '<tbody>' + state.indisciplineEvents.map(function (e) {
                return '<tr><td>' + escapeHtml(studentName(e.studentId)) + '</td><td>' + formatDateTime(e.createdAt) +
                    '</td><td>' + escapeHtml(e.note) + '</td></tr>';
            }).join('') + '</tbody></table></div></div>';

        var openBody = document.getElementById('bathroom-open-body');
        if (openBody) {
            openVisits.forEach(function (v) {
                var tr = document.createElement('tr');
                tr.innerHTML = '<td>' + escapeHtml(studentName(v.studentId)) + '</td><td>' + formatDateTime(v.wentAt) + '</td>' +
                    '<td><button type="button" class="btn secondary">Mark back</button></td>';
                tr.querySelector('button').addEventListener('click', function () {
                    api('/api/bathroom/' + v.studentId + '/return', { method: 'POST' })
                        .then(function () { return loadAll(); }).then(function () { selectTab('bathroom'); })
                        .catch(function (err) { alert(err.message); });
                });
                openBody.appendChild(tr);
            });
        }

        document.getElementById('bathroom-start-form').addEventListener('submit', function (e) {
            e.preventDefault();
            var fd = new FormData(e.target);
            var resultEl = document.getElementById('bathroom-start-result');
            api('/api/bathroom', { method: 'POST', body: { studentId: fd.get('studentId') } })
                .then(function () { return loadAll(); }).then(function () { selectTab('bathroom'); })
                .catch(function (err) { resultEl.innerHTML = '<div class="summary-banner error">' + escapeHtml(err.message) + '</div>'; });
        });

        document.getElementById('indiscipline-form').addEventListener('submit', function (e) {
            e.preventDefault();
            var fd = new FormData(e.target);
            var resultEl = document.getElementById('indiscipline-result');
            api('/api/indiscipline', { method: 'POST', body: { studentId: fd.get('studentId'), note: fd.get('note') || '' } })
                .then(function () { return loadAll(); }).then(function () { selectTab('bathroom'); })
                .catch(function (err) { resultEl.innerHTML = '<div class="summary-banner error">' + escapeHtml(err.message) + '</div>'; });
        });
    }

    /* ----------------------------- Import / export ----------------------------------- */

    function renderData() {
        var el = document.getElementById('tab-data');
        el.innerHTML =
            '<div class="card"><h2>Export</h2>' +
            '<p>Downloads data for all students currently in the database.</p>' +
            '<div class="row">' +
            '<a class="btn" href="/api/export?format=json">JSON</a>' +
            '<a class="btn" href="/api/export?format=md">Markdown</a>' +
            '<a class="btn" href="/api/export?format=pdf">PDF</a>' +
            '</div></div>' +

            '<div class="card"><h2>Import JSON backup</h2>' +
            '<p class="warning-note">A safety snapshot is taken automatically before importing.</p>' +
            '<form class="inline-form" id="import-json-form"><input type="file" name="file" accept="application/json" required>' +
            '<button type="submit" class="btn">Import</button></form>' +
            '<div id="import-json-result"></div></div>' +

            '<div class="card"><h2>Import CSV roster</h2>' +
            '<p>Columns: name, discipline, class group. A safety snapshot is taken automatically first.</p>' +
            '<form class="inline-form" id="import-csv-form"><input type="file" name="file" accept=".csv,text/csv" required>' +
            '<button type="submit" class="btn">Import</button></form>' +
            '<div id="import-csv-result"></div></div>';

        document.getElementById('import-json-form').addEventListener('submit', function (e) {
            e.preventDefault();
            var fd = new FormData(e.target);
            var resultEl = document.getElementById('import-json-result');
            resultEl.innerHTML = '';
            api('/api/import/json', { method: 'POST', body: fd }).then(function (res) {
                resultEl.innerHTML = '<div class="summary-banner">Imported. New students: ' + res.newCount +
                    ', existing students updated: ' + res.existingCount + '.</div>';
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
                var text = 'Imported. New students: ' + res.newCount + ', matched: ' + res.matchedCount + '.';
                if (res.errors && res.errors.length) {
                    text += '\n' + res.errors.map(function (er) { return 'Line ' + er.line + ': ' + er.reason; }).join('\n');
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
