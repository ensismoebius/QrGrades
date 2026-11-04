/*
 * "Alertas": the week at a glance, and the students who need a look — too many absences, under the
 * discipline's lowest goal, a few points from the next goal, or left out of recent scoring.
 * The lists come from QGInsights (points) and /api/attendance/alerts (absences).
 */
(function () {
    'use strict';

    var QG = window.QG;
    var I = window.QGInsights;
    var esc = QG.escapeHtml;

    var COLLAPSED_ROWS = 10;

    var al = {
        disciplineId: '',
        inactiveDays: 14,
        absenceLimit: 25,
        expanded: {},   // list key -> true once "show all" was clicked
        attendance: null,
        attendanceError: null
    };

    QG.registerTab('alerts', renderAlerts);

    function rowsInScope() {
        return QG.state.overview.filter(function (r) { return !al.disciplineId || r.disciplineId === al.disciplineId; });
    }

    function where(row) {
        return esc(row.discipline) + ' · ' + esc(row.classGroup);
    }

    // "até 30/04/2026 (termina em 5 dias)"; a goal that predates dates has no deadline.
    function deadline(goal) {
        if (!goal.endDate) return '<span class="muted">sem prazo</span>';
        return 'até ' + esc(QG.formatDayString(goal.endDate)) + ' <span class="muted">(' + esc(I.endsIn(goal.daysLeft || 0)) + ')</span>';
    }

    function plural(n, one, many) {
        return n + ' ' + (n === 1 ? one : many);
    }

    function card(title, description, bodyHtml, count) {
        return '<div class="card"><div class="row"><h2 style="flex:1">' + title + '</h2>' +
            (count != null ? '<span class="points-badge">' + count + '</span>' : '') + '</div>' +
            '<p class="muted">' + description + '</p>' + bodyHtml + '</div>';
    }

    // The first rows, with a button for the rest when the list is long.
    function table(key, head, items, rowHtml) {
        var open = al.expanded[key];
        var shown = open ? items : items.slice(0, COLLAPSED_ROWS);
        return '<div class="table-wrap"><table><thead><tr>' + head + '</tr></thead><tbody>' +
            shown.map(rowHtml).join('') + '</tbody></table></div>' +
            (items.length > COLLAPSED_ROWS
                ? '<button type="button" class="btn secondary small" data-more="' + key + '">' +
                  (open ? 'Mostrar menos' : 'Mostrar todos (' + items.length + ')') + '</button>'
                : '');
    }

    /* ---------------------------------- screen ---------------------------------- */

    function renderAlerts() {
        var el = document.getElementById('tab-alerts');
        if (QG.state.disciplines.length === 0) {
            el.innerHTML = '<div class="card"><p>Cadastre uma disciplina primeiro.</p></div>';
            return;
        }
        if (al.disciplineId && !QG.state.disciplines.some(function (d) { return d.id === al.disciplineId; })) al.disciplineId = '';

        el.innerHTML =
            '<div class="card"><div class="row"><h2 style="flex:1">Alertas e resumo da semana</h2>' +
            '<label>Disciplina:</label><select id="al-discipline"><option value="">Todas</option>' +
            QG.state.disciplines.map(function (d) {
                return '<option value="' + esc(d.id) + '"' + (d.id === al.disciplineId ? ' selected' : '') + '>' + esc(d.name) + '</option>';
            }).join('') + '</select>' +
            '<label>Sem pontos há:</label><select id="al-days">' + [7, 14, 30].map(function (n) {
                return '<option value="' + n + '"' + (n === al.inactiveDays ? ' selected' : '') + '>' + n + ' dias</option>';
            }).join('') + '</select>' +
            '<label>Faltas acima de:</label><input type="number" id="al-limit" min="1" max="100" value="' + al.absenceLimit +
            '" style="width:72px"><span>%</span></div></div>' +
            '<div id="al-week"></div><div id="al-attendance"></div><div id="al-below"></div><div id="al-near"></div><div id="al-inactive"></div>';

        document.getElementById('al-discipline').addEventListener('change', function (e) {
            al.disciplineId = e.target.value;
            renderLists();
        });
        document.getElementById('al-days').addEventListener('change', function (e) {
            al.inactiveDays = parseInt(e.target.value, 10);
            renderLists();
        });
        document.getElementById('al-limit').addEventListener('change', function (e) {
            var value = parseInt(e.target.value, 10);
            al.absenceLimit = value >= 1 && value <= 100 ? value : 25;
            e.target.value = al.absenceLimit;
            loadAttendance();
        });
        // `el` outlives every render, so assign (not add) the handler or clicks would pile up one handler per visit.
        el.onclick = onClick;

        renderLists();
        loadAttendance();
    }

    function onClick(e) {
        var more = e.target.closest('button[data-more]');
        if (more) {
            var key = more.getAttribute('data-more');
            al.expanded[key] = !al.expanded[key];
            renderLists();
            renderAttendanceCard();
            return;
        }
        var row = e.target.closest('tr[data-student]');
        if (row) QG.openHistory(row.getAttribute('data-student'));
    }

    function renderLists() {
        var rows = rowsInScope();
        var now = Date.now();
        renderWeek(rows, now);
        renderBelow(rows);
        renderNear(rows);
        renderInactive(rows, now);
        renderAttendanceCard();
    }

    /* -------------------------------- weekly summary -------------------------------- */

    function tile(label, value, cls) {
        return '<div class="stat-tile"><div class="stat-value' + (cls ? ' ' + cls : '') + '">' + value + '</div><div class="stat-label">' + esc(label) + '</div></div>';
    }

    function renderWeek(rows, now) {
        var box = document.getElementById('al-week');
        if (!box) return;
        var w = I.weeklySummary(rows, now);
        var description = 'Os últimos 7 dias, comparados aos 7 anteriores. Lançamentos estornados não entram na conta.';

        if (w.entries === 0) {
            box.innerHTML = card('Resumo da semana', description,
                '<p>Nenhum lançamento de pontos nos últimos 7 dias' + (w.prevEntries ? ' (nos 7 anteriores houve ' + plural(w.prevEntries, 'lançamento', 'lançamentos') + ').' : '.') + '</p>');
            return;
        }

        var change = w.net - w.prevNet;
        var body = '<div class="stat-row">' +
            tile('Pontos lançados', '+' + w.given, 'delta-pos') +
            (w.deducted ? tile('Pontos descontados', '−' + w.deducted, 'delta-neg') : '') +
            tile('Alunos pontuados', w.students) +
            tile('Lançamentos', w.entries) +
            tile('Variação vs. semana anterior', I.signed(change), change < 0 ? 'delta-neg' : (change > 0 ? 'delta-pos' : '')) +
            '</div>';

        if (w.top.length) {
            body += '<div class="chips"><span class="muted">Mais pontos na semana:</span>' + w.top.map(function (s) {
                return '<button type="button" class="chip action" data-open-student="' + esc(s.studentId) + '">' + esc(s.name) +
                    ' <strong class="delta-pos">' + I.signed(s.points) + '</strong></button>';
            }).join('') + '</div>';
        }
        if (w.tags.length) {
            body += '<div class="chips"><span class="muted">Categorias mais usadas:</span>' + w.tags.map(function (t) {
                return '<span class="chip">' + esc(t.tag) + ' <strong>' + t.entries + '×</strong></span>';
            }).join('') + '</div>';
        }
        box.innerHTML = card('Resumo da semana', description, body);

        box.querySelectorAll('button[data-open-student]').forEach(function (btn) {
            btn.addEventListener('click', function () { QG.openHistory(btn.getAttribute('data-open-student')); });
        });
    }

    /* ---------------------------------- points lists ---------------------------------- */

    function renderBelow(rows) {
        var box = document.getElementById('al-below');
        if (!box) return;
        var items = I.belowMinimum(rows);
        var hasGoals = rows.some(function (r) { return r.goals && r.goals.length > 0; });
        var hasRunning = rows.some(function (r) {
            return (r.goals || []).some(function (g) { return !g.state || g.state === 'open' || g.state === 'active'; });
        });
        var description = 'Alunos com menos pontos que a menor meta em andamento da disciplina (a meta mínima), do mais distante ao mais próximo. ' +
            'Contam só os pontos dados entre o início e o fim de cada meta.';
        var body;
        if (!hasGoals) body = '<p>Nenhuma disciplina tem metas cadastradas. Crie-as (com início e fim) na aba Metas para ver quem está abaixo da meta mínima.</p>';
        else if (!hasRunning) body = '<p>Nenhuma meta está em andamento hoje: as que já terminaram têm resultado final e as futuras ainda não começaram.</p>';
        else if (items.length === 0) body = '<p>Todos os alunos já atingiram a meta mínima.</p>';
        else {
            body = table('below', '<th>Aluno</th><th>Disciplina · Turma</th><th class="num">Pontos na meta</th><th>Meta</th><th>Prazo</th><th class="num">Faltam</th>', items, function (i) {
                return '<tr class="clickable" data-student="' + esc(i.row.studentId) + '"><td>' + esc(i.row.name) + '</td><td>' + where(i.row) + '</td>' +
                    '<td class="num">' + I.goalCurrent(i.row, i.goal) + '</td><td>' + esc(i.goal.name) + ' (' + i.goal.target + ')</td>' +
                    '<td>' + deadline(i.goal) + '</td>' +
                    '<td class="num delta-neg">' + i.missing + '</td></tr>';
            });
        }
        box.innerHTML = card('Abaixo da meta mínima', description, body, items.length);
    }

    function renderNear(rows) {
        var box = document.getElementById('al-near');
        if (!box) return;
        var items = I.nearNextGoal(rows);
        var description = 'Alunos a poucos pontos da próxima meta em andamento que ainda não alcançaram: um incentivo pode fazer a diferença.';
        var body = items.length === 0
            ? '<p>Ninguém está a poucos pontos de uma meta no momento.</p>'
            : table('near', '<th>Aluno</th><th>Disciplina · Turma</th><th class="num">Pontos na meta</th><th>Próxima meta</th><th>Prazo</th><th class="num">Faltam</th>', items, function (i) {
                return '<tr class="clickable" data-student="' + esc(i.row.studentId) + '"><td>' + esc(i.row.name) + '</td><td>' + where(i.row) + '</td>' +
                    '<td class="num">' + I.goalCurrent(i.row, i.goal) + '</td><td>' + esc(i.goal.name) + ' (' + i.goal.target + ')</td>' +
                    '<td>' + deadline(i.goal) + '</td>' +
                    '<td class="num delta-pos">' + i.missing + '</td></tr>';
            });
        box.innerHTML = card('Perto da próxima meta', description, body, items.length);
    }

    function renderInactive(rows, now) {
        var box = document.getElementById('al-inactive');
        if (!box) return;
        var items = I.inactive(rows, now, al.inactiveDays);
        var description = 'Alunos sem nenhum ponto nos últimos ' + al.inactiveDays + ' dias, em turmas onde outros alunos pontuaram nesse período. ' +
            'Turmas paradas (sem lançamentos para ninguém) não entram.';
        var body = items.length === 0
            ? '<p>Todos os alunos receberam pontos nesse período.</p>'
            : table('inactive', '<th>Aluno</th><th>Disciplina · Turma</th><th>Último ponto</th>', items, function (i) {
                var last = i.lastAt == null ? '<span class="delta-neg">nunca pontuou</span>'
                    : esc(QG.formatDay(i.lastAt)) + ' <span class="muted">(há ' + plural(I.daysBetween(i.lastAt, now), 'dia', 'dias') + ')</span>';
                return '<tr class="clickable" data-student="' + esc(i.row.studentId) + '"><td>' + esc(i.row.name) + '</td><td>' + where(i.row) + '</td><td>' + last + '</td></tr>';
            });
        box.innerHTML = card('Sem pontos recentes', description, body, items.length);
    }

    /* ----------------------------------- attendance ----------------------------------- */

    function loadAttendance() {
        var limit = al.absenceLimit;
        al.attendance = null;
        al.attendanceError = null;
        renderAttendanceCard();
        QG.api('/api/attendance/alerts?limit=' + limit).then(function (data) {
            if (limit !== al.absenceLimit) return;
            al.attendance = data;
            renderAttendanceCard();
        }).catch(function (err) {
            if (limit !== al.absenceLimit) return;
            al.attendanceError = QG.errorMessage(err);
            renderAttendanceCard();
        });
    }

    function renderAttendanceCard() {
        var box = document.getElementById('al-attendance');
        if (!box) return;
        var description = 'Alunos que já passaram de ' + al.absenceLimit + '% de faltas no período atual (as justificadas contam), ' +
            'considerando turmas com ao menos 3 aulas registradas.';

        if (al.attendanceError) {
            box.innerHTML = card('Faltas em excesso', description, '<div class="summary-banner error">' + esc(al.attendanceError) + '</div>');
            return;
        }
        if (!al.attendance) {
            box.innerHTML = card('Faltas em excesso', description, '<p>Carregando…</p>');
            return;
        }
        var items = al.attendance.alerts.filter(function (a) { return !al.disciplineId || a.disciplineId === al.disciplineId; });
        var body = items.length === 0
            ? '<p>Nenhum aluno acima de ' + al.absenceLimit + '% de faltas.</p>'
            : table('attendance', '<th>Aluno</th><th>Disciplina · Turma</th><th class="num">Aulas</th><th class="num">Faltas</th><th class="num">% de faltas</th>', items, function (a) {
                return '<tr class="clickable" data-student="' + esc(a.studentId) + '"><td>' + esc(a.name) + '</td>' +
                    '<td>' + esc(a.discipline) + ' · ' + esc(a.classGroup) + '</td><td class="num">' + a.total + '</td>' +
                    '<td class="num">' + (a.absent + a.excused) + '</td><td class="num delta-neg">' + I.percent(a.absentPct) + '</td></tr>';
            });
        box.innerHTML = card('Faltas em excesso', description, body, items.length);
    }
})();
