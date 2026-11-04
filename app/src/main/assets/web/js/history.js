/*
 * "Histórico": look a student up and see their points, goals, categories and attendance, period by
 * period (the open one and every closed one). Entries of the open period can be cancelled or
 * corrected here; the report card (boletim) opens as a PDF.
 */
(function () {
    'use strict';

    var QG = window.QG;
    var I = window.QGInsights;
    var esc = QG.escapeHtml;

    var hs = {
        query: '',
        studentId: null,
        disciplineId: '',
        period: 'all',
        data: null,
        entries: {} // history id -> entry, for the buttons on each row
    };

    QG.registerTab('history', renderHistory);

    // Lets other screens link to a student ("see their history").
    QG.openHistory = function (studentId) {
        hs.studentId = studentId;
        hs.disciplineId = '';
        hs.period = 'all';
        hs.data = null;
        QG.selectTab('history');
    };

    function periodTitle(p) {
        return p.label ? p.label : 'Encerrado em ' + QG.formatDay(p.closedAt);
    }

    /* --------------------------------- search --------------------------------- */

    function renderHistory() {
        var el = document.getElementById('tab-history');
        if (QG.state.students.length === 0) {
            el.innerHTML = '<div class="card"><p>Cadastre um aluno primeiro.</p></div>';
            return;
        }
        if (hs.studentId && !QG.state.students.some(function (s) { return s.id === hs.studentId; })) {
            hs.studentId = null; // the student was deleted meanwhile
        }

        el.innerHTML =
            '<div class="card no-print">' +
            '<div class="row"><h2 style="flex:1">Histórico do aluno</h2>' +
            '<input type="search" id="history-search" class="search-input" placeholder="Pesquisar aluno pelo nome…" value="' +
            esc(hs.query) + '"></div>' +
            '<div class="table-wrap history-results"><table><tbody id="history-results"></tbody></table></div>' +
            '</div>' +
            '<div id="history-detail"></div>';

        var search = document.getElementById('history-search');
        search.addEventListener('input', function () {
            hs.query = search.value;
            renderResults();
        });
        document.getElementById('history-detail').addEventListener('click', onDetailClick);

        QG.ensureDatalists();
        renderResults();
        if (hs.studentId) loadStudentHistory(hs.studentId);
    }

    function renderResults() {
        var body = document.getElementById('history-results');
        if (!body) return;
        var query = QG.normalizeSearch(hs.query.trim());
        var matches = QG.state.students.filter(function (s) {
            return !query || QG.normalizeSearch(s.name).indexOf(query) !== -1;
        }).sort(function (a, b) { return a.name.localeCompare(b.name); });

        body.innerHTML = '';
        if (matches.length === 0) {
            body.innerHTML = '<tr><td>Nenhum aluno corresponde a "' + esc(hs.query) + '".</td></tr>';
            return;
        }
        matches.forEach(function (s) {
            var tr = document.createElement('tr');
            tr.className = 'clickable' + (s.id === hs.studentId ? ' selected' : '');
            tr.innerHTML = '<td><div class="row" style="margin:0;flex-wrap:nowrap">' +
                (s.hasPhoto ? '<img class="avatar" src="/api/students/' + s.id + '/photo" onerror="this.remove()">' : '') +
                '<span>' + esc(s.name) + '</span></div></td>';
            tr.addEventListener('click', function () {
                hs.studentId = s.id;
                hs.disciplineId = '';
                hs.period = 'all';
                renderResults();
                loadStudentHistory(s.id);
            });
            body.appendChild(tr);
        });
    }

    /* -------------------------------- one student -------------------------------- */

    function loadStudentHistory(studentId) {
        var detail = document.getElementById('history-detail');
        detail.innerHTML = '<div class="card"><p>Carregando…</p></div>';
        QG.api('/api/students/' + encodeURIComponent(studentId) + '/history').then(function (data) {
            if (hs.studentId !== studentId) return; // the teacher already picked someone else
            hs.data = data;
            renderStudentHistory();
        }).catch(function (err) {
            detail.innerHTML = '<div class="card"><div class="summary-banner error">' + esc(QG.errorMessage(err)) + '</div></div>';
        });
    }

    function statTile(label, value) {
        return '<div class="stat-tile"><div class="stat-value">' + value + '</div><div class="stat-label">' +
            esc(label) + '</div></div>';
    }

    function renderStudentHistory() {
        var detail = document.getElementById('history-detail');
        var data = hs.data;
        if (!detail || !data) return;
        hs.entries = {};

        var periods = data.periods.slice().sort(function (a, b) { return b.closedAt - a.closedAt; });

        // Discipline choices come from both live enrollments and past periods, so a discipline
        // the student has since left is still selectable.
        var disciplines = {};
        data.current.concat(periods).forEach(function (r) { if (r.disciplineId) disciplines[r.disciplineId] = r.discipline; });
        var byDiscipline = function (r) { return !hs.disciplineId || r.disciplineId === hs.disciplineId; };
        var current = data.current.filter(byDiscipline);
        var scopedPeriods = periods.filter(byDiscipline);
        if (hs.period !== 'all' && hs.period !== 'current' &&
            !scopedPeriods.some(function (p) { return p.periodId === hs.period; })) {
            hs.period = 'all';
        }

        var showCurrent = hs.period === 'all' || hs.period === 'current';
        var shownPeriods = scopedPeriods.filter(function (p) { return hs.period === 'all' || p.periodId === hs.period; });

        var currentPoints = current.reduce(function (sum, r) { return sum + r.points; }, 0);
        var closedPoints = scopedPeriods.reduce(function (sum, p) { return sum + p.points; }, 0);
        var present = 0, lessons = 0;
        current.forEach(function (r) { present += r.attendance.present; lessons += r.attendance.total; });

        // One option per closed period (a student in two classes of a discipline has two blocks with the same period).
        var seen = {};
        var periodOptions = scopedPeriods.filter(function (p) {
            if (seen[p.periodId]) return false;
            seen[p.periodId] = true;
            return true;
        });

        var student = data.student;
        var html =
            '<div class="card">' +
            '<div class="row" style="flex-wrap:nowrap">' +
            (student.hasPhoto ? '<img class="avatar" src="/api/students/' + student.id + '/photo" onerror="this.remove()">' : '') +
            '<h2 style="flex:1;margin:0">' + esc(student.name) + '</h2>' +
            '<a class="btn secondary no-print" href="/api/students/' + encodeURIComponent(student.id) +
            '/report.pdf" target="_blank" rel="noopener">Boletim (PDF)</a>' +
            '<button type="button" class="btn secondary no-print" data-action="print">Imprimir</button></div>' +
            '<div class="history-filters no-print"><label>Disciplina<select id="history-discipline"><option value="">Todas as disciplinas</option>' +
            Object.keys(disciplines).map(function (id) {
                return '<option value="' + esc(id) + '"' + (id === hs.disciplineId ? ' selected' : '') + '>' +
                    esc(disciplines[id]) + '</option>';
            }).join('') + '</select></label>' +
            '<label>Período<select id="history-period">' +
            '<option value="all"' + (hs.period === 'all' ? ' selected' : '') + '>Todos os períodos</option>' +
            '<option value="current"' + (hs.period === 'current' ? ' selected' : '') + '>Período atual</option>' +
            periodOptions.map(function (p) {
                return '<option value="' + esc(p.periodId) + '"' + (p.periodId === hs.period ? ' selected' : '') + '>' +
                    esc(periodTitle(p) + ' — ' + p.discipline) + '</option>';
            }).join('') + '</select></label></div>' +
            '<div class="stat-row">' +
            statTile('Pontos no período atual', currentPoints) +
            (lessons > 0 ? statTile('Presença no período atual (' + lessons + (lessons === 1 ? ' aula' : ' aulas') + ')', I.percent(present / lessons * 100)) : '') +
            statTile('Períodos encerrados', scopedPeriods.length) +
            statTile('Pontos em períodos encerrados', closedPoints) +
            '</div></div>';

        if (showCurrent) {
            html += '<div class="card"><h2>Período atual</h2>';
            if (current.length === 0) {
                html += '<p>Não está matriculado(a) em nenhuma turma' + (hs.disciplineId ? ' desta disciplina' : '') + '.</p>';
            } else {
                html += current.map(function (r) {
                    return bucketHtml(r, r.discipline + ' · ' + r.classGroup + (r.classGroupClosed ? ' (encerrada)' : ''));
                }).join('');
            }
            html += '</div>';
        }

        if (hs.period !== 'current') {
            if (shownPeriods.length === 0 && hs.period === 'all') {
                html += '<div class="card"><h2>Períodos encerrados</h2><p>Nenhum período encerrado ainda.</p></div>';
            }
            shownPeriods.forEach(function (p) {
                html += '<div class="card"><h2>' + esc(periodTitle(p)) +
                    ' <span class="muted">· encerrado em ' + esc(QG.formatDate(p.closedAt)) + '</span></h2>' +
                    bucketHtml(p, p.discipline + ' · ' + p.classGroup + (p.classClose ? ' (turma encerrada)' : '')) +
                    '</div>';
            });
        }

        detail.innerHTML = html;

        document.getElementById('history-discipline').addEventListener('change', function (e) {
            hs.disciplineId = e.target.value;
            renderStudentHistory();
        });
        document.getElementById('history-period').addEventListener('change', function (e) {
            hs.period = e.target.value;
            renderStudentHistory();
        });
    }

    /* ---------------------------- one enrollment, one period ---------------------------- */

    function bucketHtml(b, title) {
        var entries = b.history.slice().sort(function (x, y) { return y.createdAt - x.createdAt; });
        entries.forEach(function (h) { hs.entries[h.id] = h; });
        return '<div class="history-block"><div class="row"><strong style="flex:1">' + esc(title) + '</strong>' +
            '<span class="points-badge">' + b.points + ' pts</span></div>' +
            goalsHtml(b) + tagsHtml(b) + attendanceHtml(b) + entriesHtml(entries) + '</div>';
    }

    // Each goal counts only the points given inside its window, so the bar and the figures use `current`, not the block's total.
    // The server sends them running first, then upcoming, then finished.
    function goalsHtml(b) {
        if (!b.goals || b.goals.length === 0) return '';
        return '<div class="goal-list">' + b.goals.map(function (g) {
            var current = g.current != null ? g.current : b.points;
            var pct = g.target > 0 ? Math.max(0, Math.min(100, Math.round(current / g.target * 100))) : 100;
            var missed = g.state === 'ended' && !g.achieved;
            var status = g.achieved ? 'alcançada'
                : missed ? 'não alcançada'
                : g.state === 'upcoming' ? 'ainda não começou'
                : 'faltam ' + g.remaining;
            var windowText = I.windowText(g);
            return '<div class="goal' + (g.achieved ? ' achieved' : '') + (missed ? ' missed' : '') + (g.state === 'upcoming' ? ' upcoming' : '') + '">' +
                '<div class="goal-head"><span>' + (g.achieved ? '✓ ' : '') + esc(g.name) + '</span>' +
                '<span class="muted">' + status + ' · ' + current + '/' + g.target + '</span></div>' +
                (windowText ? '<div class="goal-window muted">' + esc(windowText) + '</div>' : '') +
                '<div class="bar"><span style="width:' + pct + '%"></span></div></div>';
        }).join('') + '</div>';
    }

    // Only worth a line when at least one category is actually in use; otherwise it would just repeat the total.
    function tagsHtml(b) {
        var tags = b.byTag || [];
        if (!tags.some(function (t) { return t.tag; })) return '';
        return '<div class="chips"><span class="muted">Por categoria:</span>' + tags.map(function (t) {
            return '<span class="chip">' + (t.tag ? esc(t.tag) : '<em>sem categoria</em>') + ' <strong class="' +
                (t.points < 0 ? 'delta-neg' : 'delta-pos') + '">' + I.signed(t.points) + '</strong></span>';
        }).join('') + '</div>';
    }

    function attendanceHtml(b) {
        var a = b.attendance;
        if (!a || a.total === 0) return '';
        var missed = (b.attendanceDays || []).filter(function (d) { return d.status !== 1; })
            .sort(function (x, y) { return x.day < y.day ? 1 : -1; })
            .map(function (d) {
                return '<span class="chip ' + (d.status === 2 ? 'excused' : 'absent') + '">' + esc(QG.formatDayString(d.day)) +
                    (d.status === 2 ? ' · justificada' : ' · falta') + '</span>';
            });
        return '<div class="attendance-line"><strong>Frequência:</strong> ' + a.total + (a.total === 1 ? ' aula' : ' aulas') +
            ' · presente em ' + a.present + ' (' + I.percent(a.presentPct) + ') · ' +
            a.absent + (a.absent === 1 ? ' falta' : ' faltas') + ' · ' + a.excused + (a.excused === 1 ? ' justificada' : ' justificadas') +
            (missed.length ? '<div class="chips">' + missed.join('') + '</div>' : '') + '</div>';
    }

    function entriesHtml(entries) {
        if (entries.length === 0) return '<p class="muted">Nenhum lançamento de pontos registrado.</p>';
        var hasActions = entries.some(function (h) { return h.canRevert; });
        return '<div class="table-wrap"><table class="entries"><thead><tr><th>Data</th><th>Pontos</th><th>Categoria</th><th>Observação</th>' +
            (hasActions ? '<th class="no-print"></th>' : '') + '</tr></thead><tbody>' +
            entries.map(function (h) {
                var rowClass = h.isReversal ? 'reversal' : (h.reverted ? 'reverted' : '');
                var status = h.isReversal ? ' <span class="chip">estorno</span>' : (h.reverted ? ' <span class="chip">estornado</span>' : '');
                var actions = h.canRevert
                    ? '<button type="button" class="btn secondary small" data-action="correct" data-id="' + esc(h.id) + '">Corrigir</button> ' +
                      '<button type="button" class="btn secondary small" data-action="revert" data-id="' + esc(h.id) + '">Estornar</button>'
                    : '';
                return '<tr class="' + rowClass + '"><td>' + esc(QG.formatDate(h.createdAt)) + '</td>' +
                    '<td class="' + (h.pointsDelta < 0 ? 'delta-neg' : 'delta-pos') + '">' + I.signed(h.pointsDelta) + '</td>' +
                    '<td>' + esc(h.tag) + '</td>' +
                    '<td><span class="note">' + esc(h.note) + '</span>' + status + '</td>' +
                    (hasActions ? '<td class="no-print actions">' + actions + '</td>' : '') + '</tr>';
            }).join('') + '</tbody></table></div>';
    }

    /* ------------------------------ cancel / correct ------------------------------ */

    function onDetailClick(e) {
        var btn = e.target.closest('button[data-action]');
        if (!btn) return;
        var action = btn.getAttribute('data-action');
        if (action === 'print') {
            window.print();
            return;
        }
        var entry = hs.entries[btn.getAttribute('data-id')];
        if (!entry) return;
        if (action === 'revert') revertEntry(entry);
        else if (action === 'correct') correctEntry(entry);
    }

    function describe(entry) {
        return I.signed(entry.pointsDelta) + (Math.abs(entry.pointsDelta) === 1 ? ' ponto' : ' pontos') +
            (entry.note ? ' (“' + entry.note + '”)' : '');
    }

    // Whatever happened, show the server's current state afterwards: someone else (or the phone) may have changed it first.
    function failed(err) {
        QG.toast(QG.errorMessage(err), { error: true });
        QG.closeModal();
        return QG.loadAll();
    }

    function revertEntry(entry) {
        var question = 'Estornar o lançamento de ' + describe(entry) + '?\n\n' +
            'Os pontos voltam ao que eram. O lançamento continua no histórico, marcado como estornado.';
        if (!confirm(question)) return;
        QG.api('/api/history/' + encodeURIComponent(entry.id) + '/revert', { method: 'POST', body: {} })
            .then(function () {
                QG.toast('Lançamento estornado.');
                return QG.loadAll();
            })
            .catch(failed);
    }

    function correctEntry(entry) {
        QG.ensureDatalists();
        QG.openModal('Corrigir lançamento',
            '<label>Pontos</label><input type="number" name="delta" value="' + entry.pointsDelta + '" required>' +
            '<label>Observação</label><input name="note" list="dl-notes" value="' + esc(entry.note) + '">' +
            '<label>Categoria</label><input name="tag" list="dl-tags" value="' + esc(entry.tag) + '">' +
            '<div class="muted" style="margin-top:8px">O lançamento original é estornado e o corrigido é registrado em seguida; ' +
            'os dois ficam no histórico.</div>',
            function (fd) {
                var delta = parseInt(fd.get('delta'), 10);
                if (!delta) {
                    QG.toast('Informe uma quantidade de pontos diferente de zero.', { error: true });
                    return;
                }
                QG.api('/api/history/' + encodeURIComponent(entry.id) + '/correct', {
                    method: 'POST',
                    body: { delta: delta, note: fd.get('note') || '', tag: fd.get('tag') || '' }
                }).then(function () {
                    QG.closeModal();
                    QG.toast('Lançamento corrigido.');
                    QG.refreshSuggestions();
                    return QG.loadAll();
                }).catch(failed);
            });
    }
})();
