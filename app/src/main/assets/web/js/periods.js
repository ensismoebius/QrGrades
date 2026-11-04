/*
 * "Períodos": one discipline's students side by side across its closed periods and the current
 * one, with the change since the last period and the class's average, lowest and highest score
 * for each. Periods closed for different classes at different times are lined up by their label.
 */
(function () {
    'use strict';

    var QG = window.QG;
    var I = window.QGInsights;
    var esc = QG.escapeHtml;

    var ps = {
        disciplineId: '',
        classGroupId: '',
        sort: 'name',
        data: null
    };

    QG.registerTab('periods', renderPeriods);

    function renderPeriods() {
        var el = document.getElementById('tab-periods');
        if (QG.state.disciplines.length === 0) {
            el.innerHTML = '<div class="card"><p>Cadastre uma disciplina primeiro.</p></div>';
            return;
        }
        if (!QG.state.disciplines.some(function (d) { return d.id === ps.disciplineId; })) {
            ps.disciplineId = QG.state.disciplines[0].id;
            ps.classGroupId = '';
        }

        el.innerHTML =
            '<div class="card">' +
            '<div class="row"><h2 style="flex:1">Comparar períodos</h2>' +
            '<label>Disciplina:</label><select id="per-discipline">' + QG.disciplineOptions(ps.disciplineId) + '</select>' +
            '<label>Turma:</label><select id="per-group"><option value="">Todas as turmas</option></select>' +
            '<label>Ordenar por:</label><select id="per-sort">' +
            '<option value="name">Nome</option><option value="current">Pontos atuais</option><option value="delta">Variação</option></select></div>' +
            '<div id="per-body"><p>Carregando…</p></div></div>';

        document.getElementById('per-sort').value = ps.sort;
        document.getElementById('per-discipline').addEventListener('change', function (e) {
            ps.disciplineId = e.target.value;
            ps.classGroupId = '';
            load();
        });
        document.getElementById('per-group').addEventListener('change', function (e) {
            ps.classGroupId = e.target.value;
            load();
        });
        document.getElementById('per-sort').addEventListener('change', function (e) {
            ps.sort = e.target.value;
            renderTable();
        });
        load();
    }

    function load() {
        var discipline = ps.disciplineId, group = ps.classGroupId;
        var body = document.getElementById('per-body');
        if (!body) return;
        body.innerHTML = '<p>Carregando…</p>';
        QG.api('/api/periods/compare?disciplineId=' + encodeURIComponent(discipline) +
            (group ? '&classGroupId=' + encodeURIComponent(group) : '')).then(function (data) {
            if (discipline !== ps.disciplineId || group !== ps.classGroupId) return;
            ps.data = data;
            fillGroups(data.classGroups);
            renderTable();
        }).catch(function (err) {
            if (discipline === ps.disciplineId) body.innerHTML = '<div class="summary-banner error">' + esc(QG.errorMessage(err)) + '</div>';
        });
    }

    function fillGroups(groups) {
        var select = document.getElementById('per-group');
        if (!select) return;
        select.innerHTML = '<option value="">Todas as turmas</option>' + groups.map(function (g) {
            return '<option value="' + esc(g.id) + '">' + esc(g.name) + '</option>';
        }).join('');
        select.value = ps.classGroupId;
    }

    function sortedRows(rows) {
        var list = rows.slice();
        if (ps.sort === 'current') {
            list.sort(function (a, b) { return (b.current == null ? -Infinity : b.current) - (a.current == null ? -Infinity : a.current) || a.name.localeCompare(b.name); });
        } else if (ps.sort === 'delta') {
            list.sort(function (a, b) { return (b.delta == null ? -Infinity : b.delta) - (a.delta == null ? -Infinity : a.delta) || a.name.localeCompare(b.name); });
        } else {
            list.sort(function (a, b) { return a.name.localeCompare(b.name); });
        }
        return list;
    }

    function deltaCell(delta) {
        if (delta == null) return '<td class="num muted">—</td>';
        return '<td class="num ' + (delta < 0 ? 'delta-neg' : (delta > 0 ? 'delta-pos' : 'muted')) + '">' + I.signed(delta) + '</td>';
    }

    function statTile(label, value) {
        return '<div class="stat-tile"><div class="stat-value">' + value + '</div><div class="stat-label">' + esc(label) + '</div></div>';
    }

    function renderTable() {
        var body = document.getElementById('per-body');
        var data = ps.data;
        if (!body || !data) return;

        if (data.columns.length === 0) {
            body.innerHTML = '<p>Esta disciplina ainda não tem períodos encerrados. Quando você encerrar um período, ' +
                'a pontuação de cada aluno nele passa a aparecer aqui, para comparar com os seguintes.</p>';
            return;
        }
        if (data.rows.length === 0) {
            body.innerHTML = '<p>Nenhum aluno com pontuação registrada nesta seleção.</p>';
            return;
        }

        // "Variação" always compares the two latest closed periods: the one in progress is still partial, so it isn't a fair match.
        var n = data.columns.length;
        var last = data.columns[n - 1];
        var previous = n >= 2 ? data.columns[n - 2] : null;
        var lastStats = data.stats[last.key];
        var previousStats = previous ? data.stats[previous.key] : null;
        var currentStats = data.currentStats;
        var averageChange = previousStats && previousStats.count > 0 && lastStats.count > 0
            ? lastStats.average - previousStats.average : null;

        var tiles = '<div class="stat-row">' +
            statTile('Média da turma em “' + last.label + '”', I.decimal(lastStats.average)) +
            (previousStats && previousStats.count > 0 ? statTile('Média em “' + previous.label + '”', I.decimal(previousStats.average)) : '') +
            (averageChange != null
                ? '<div class="stat-tile"><div class="stat-value ' + (averageChange < 0 ? 'delta-neg' : 'delta-pos') + '">' +
                  (averageChange > 0 ? '+' : '') + I.decimal(averageChange) + '</div><div class="stat-label">Variação da média entre esses dois períodos</div></div>'
                : '') +
            (currentStats.count > 0 ? statTile('Média atual (período em andamento)', I.decimal(currentStats.average)) : '') +
            '</div>';

        var head = '<tr><th>Aluno</th><th>Turma</th>' + data.columns.map(function (c) {
            return '<th class="num">' + esc(c.label) + '<div class="muted">' + esc(QG.formatDay(c.closedAt)) + '</div></th>';
        }).join('') + '<th class="num">Atual</th>' +
            (previous ? '<th class="num">Variação<div class="muted">' + esc(previous.label) + ' → ' + esc(last.label) + '</div></th>' : '') + '</tr>';

        var rows = sortedRows(data.rows).map(function (r) {
            return '<tr class="clickable" data-student="' + esc(r.studentId) + '"><td>' + esc(r.name) + '</td><td>' + esc(r.classGroup) + '</td>' +
                data.columns.map(function (c) {
                    var v = r.values[c.key];
                    return '<td class="num">' + (v == null ? '<span class="muted">—</span>' : v) + '</td>';
                }).join('') +
                '<td class="num">' + (r.current == null ? '<span class="muted">—</span>' : '<strong>' + r.current + '</strong>') + '</td>' +
                (previous ? deltaCell(r.delta) : '') + '</tr>';
        }).join('');

        function footRow(label, pick) {
            return '<tr class="foot"><th colspan="2">' + label + '</th>' + data.columns.map(function (c) {
                var s = data.stats[c.key];
                return '<td class="num">' + (s.count > 0 ? pick(s) : '—') + '</td>';
            }).join('') + '<td class="num">' + (currentStats.count > 0 ? pick(currentStats) : '—') + '</td>' + (previous ? '<td></td>' : '') + '</tr>';
        }

        body.innerHTML = tiles +
            '<div class="table-wrap"><table class="compare"><thead>' + head + '</thead><tbody>' + rows + '</tbody><tfoot>' +
            footRow('Média da turma', function (s) { return I.decimal(s.average); }) +
            footRow('Menor pontuação', function (s) { return s.min; }) +
            footRow('Maior pontuação', function (s) { return s.max; }) +
            footRow('Alunos', function (s) { return s.count; }) +
            '</tfoot></table></div>' +
            '<p class="muted">' + (previous
                ? '“Variação” é a pontuação do último período encerrado menos a do período anterior; a coluna “Atual” é a do período ' +
                  'em andamento e fica de fora dessa conta. '
                : 'A coluna “Atual” é a do período em andamento. A variação entre períodos aparece quando houver dois períodos encerrados. ') +
            'Clique em um aluno para ver o histórico dele.</p>';

        body.querySelectorAll('tr[data-student]').forEach(function (tr) {
            tr.addEventListener('click', function () { QG.openHistory(tr.getAttribute('data-student')); });
        });
    }
})();
