(() => {
    if (window.cytoscape && window.cytoscapeDagre) {
        window.cytoscape.use(window.cytoscapeDagre);
    }
    const clone = (value) => JSON.parse(JSON.stringify(value));
    const nodeId = (value) => String(value || "").normalize("NFKD").replace(/[\u0300-\u036f]/g, "")
        .trim().toUpperCase().replace(/[^A-Z0-9]+/g, "_").replace(/^_+|_+$/g, "");
    const stateId = (label, group) => {
        const state = nodeId(label) || "STATE";
        const normalizedGroup = nodeId(group);
        return normalizedGroup ? normalizedGroup + "/" + state : state;
    };
    const yamlValue = (value) => {
        const text = String(value ?? "");
        return /^[A-Za-z0-9_./-]+$/.test(text) ? text : JSON.stringify(text);
    };
    const line = (lines, depth, value) => lines.push("  ".repeat(depth) + value);
    const graphErrors = (workflow) => {
        const ids = workflow.states.map((state) => state.id);
        const connected = new Set();
        const seen = new Set();
        const errors = [];
        if (!ids.length) errors.push("Add at least one state.");
        if (new Set(ids).size !== ids.length || ids.some((id) => !id)) errors.push("Every state needs a unique ID.");
        if (!ids.includes(workflow.initialState)) errors.push("Choose an initial state that remains in the workflow.");
        workflow.transitions.forEach((transition) => (transition.to || []).forEach((target) => {
            const key = transition.from + "→" + target;
            if (!ids.includes(transition.from) || !ids.includes(target)) errors.push("A transition references an unknown state.");
            if (transition.from === target) errors.push("A state cannot transition to itself.");
            if (seen.has(key)) errors.push("Duplicate transition " + transition.from + " → " + target + ".");
            seen.add(key); connected.add(transition.from); connected.add(target);
        }));
        ids.filter((id) => !connected.has(id)).forEach((id) => errors.push("State " + id + " needs at least one transition."));
        const taxonomyHasLeaves = (values) => (values || []).some((value) => !value.children?.length || taxonomyHasLeaves(value.children));
        workflow.states.forEach((state) => {
            const requires = state.requires || {};
            const rule = requires.exclusionCriteria || requires.exclusionCriterion || requires.inclusionCriteria;
            if (rule && !taxonomyHasLeaves(workflow.taxonomies?.[rule.taxonomy]?.values)) {
                errors.push("State " + state.label + " requires criteria from a taxonomy with no selectable values.");
            }
        });
        return [...new Set(errors)];
    };
    const yamlFromWorkflow = (workflow) => {
        const lines = ["version: 2", "", "initial_state: " + workflow.initialState];
        const positions = workflow.layout?.nodes || {};
        if (Object.keys(positions).length) {
            lines.push("", "layout:", "  nodes:");
            Object.entries(positions).forEach(([id, position]) => {
                line(lines, 2, id + ":"); line(lines, 3, "x: " + position.x); line(lines, 3, "y: " + position.y);
            });
        }
        lines.push("", "states:");
        workflow.states.forEach((state) => {
            line(lines, 1, "- id: " + state.id); line(lines, 2, "label: " + yamlValue(state.label || state.id));
            if (state.group) line(lines, 2, "group: " + state.group);
            if (state.terminal) line(lines, 2, "terminal: true");
            const requires = state.requires || {};
            if (requires.exclusionCriteria || requires.exclusionCriterion || requires.inclusionCriteria || requires.exclusionNotes || requires.inclusionNotes) {
                line(lines, 2, "requires:");
                const exclusion = requires.exclusionCriteria || requires.exclusionCriterion;
                if (exclusion) { line(lines, 3, "exclusion_criteria:"); line(lines, 4, "taxonomy: " + exclusion.taxonomy); line(lines, 4, "min: " + (exclusion.min || exclusion.exactly || 1)); }
                if (requires.inclusionCriteria) { line(lines, 3, "inclusion_criteria:"); line(lines, 4, "taxonomy: " + requires.inclusionCriteria.taxonomy); line(lines, 4, "min: " + (requires.inclusionCriteria.min || 1)); }
                if (requires.exclusionNotes) line(lines, 3, "exclusion_notes: optional");
                if (requires.inclusionNotes) line(lines, 3, "inclusion_notes: optional");
            }
            if (state.report?.prismaBucket) { line(lines, 2, "report:"); line(lines, 3, "prisma_bucket: " + state.report.prismaBucket); }
        });
        lines.push("", "transitions:");
        workflow.transitions.forEach((transition) => { line(lines, 1, "- from: " + transition.from); line(lines, 2, "to:"); transition.to.forEach((target) => line(lines, 3, "- " + target)); });
        const appendCriteria = (criteria, depth) => (criteria || []).forEach((criterion) => {
            line(lines, depth, "- id: " + criterion.id); line(lines, depth + 1, "label: " + yamlValue(criterion.label || criterion.id));
            if (criterion.description) line(lines, depth + 1, "description: " + yamlValue(criterion.description));
            if (criterion.children?.length) { line(lines, depth + 1, "children:"); appendCriteria(criterion.children, depth + 2); }
        });
        if (Object.keys(workflow.taxonomies || {}).length) {
            lines.push("", "taxonomies:");
            Object.entries(workflow.taxonomies).forEach(([id, taxonomy]) => {
                line(lines, 1, id + ":"); if (taxonomy.label) line(lines, 2, "label: " + yamlValue(taxonomy.label)); line(lines, 2, "values:"); appendCriteria(taxonomy.values, 3);
            });
        }
        return lines.join("\n");
    };
    const pointerMessage = (event, message) => {
        const notice = document.createElement("div"); notice.className = "workflow-pointer-message"; notice.textContent = message;
        notice.style.left = Math.min(window.innerWidth - 300, (event?.clientX || 24) + 12) + "px";
        notice.style.top = Math.min(window.innerHeight - 80, (event?.clientY || 24) + 12) + "px";
        document.body.appendChild(notice); window.setTimeout(() => notice.remove(), 2600);
    };
    const migrationDialog = document.getElementById("workflow-migration-dialog");
    const migrationList = document.getElementById("workflow-migration-list");
    const migrationError = document.getElementById("workflow-migration-error");
    const migrationConfirm = document.getElementById("workflow-migration-confirm");
    const migrationCancel = document.getElementById("workflow-migration-cancel");
    let pendingWorkflowSave = null;
    const criterionLeaves = (values) => (values || []).flatMap((value) => value.children?.length
        ? criterionLeaves(value.children) : [{ id: value.id, label: value.label || value.id }]);
    const migrationRequirementFields = (row, target, workflow) => {
        const holder = row.querySelector("[data-migration-criteria]");
        holder.replaceChildren();
        const requires = target?.requires || {};
        [["exclusion", requires.exclusionCriteria || requires.exclusionCriterion], ["inclusion", requires.inclusionCriteria]]
            .filter(([, rule]) => rule)
            .forEach(([kind, rule]) => {
                const fieldset = document.createElement("fieldset");
                fieldset.dataset.migrationCriteriaKind = kind;
                fieldset.dataset.migrationCriteriaMin = String(rule.min || rule.exactly || 1);
                const legend = document.createElement("legend");
                legend.textContent = (kind === "exclusion" ? "Exclusion" : "Inclusion")
                    + " criteria (choose at least " + fieldset.dataset.migrationCriteriaMin + ")";
                fieldset.appendChild(legend);
                criterionLeaves(workflow.taxonomies?.[rule.taxonomy]?.values).forEach((criterion) => {
                    const label = document.createElement("label");
                    const checkbox = document.createElement("input");
                    checkbox.type = "checkbox";
                    checkbox.value = criterion.id;
                    label.append(checkbox, document.createTextNode(" " + criterion.label));
                    fieldset.appendChild(label);
                });
                holder.appendChild(fieldset);
            });
    };
    migrationCancel?.addEventListener("click", () => { pendingWorkflowSave = null; migrationDialog.close(); });
    migrationConfirm?.addEventListener("click", () => {
        if (!pendingWorkflowSave) return;
        const mappings = Array.from(migrationList.querySelectorAll("select[data-migration-from]"));
        if (mappings.some((select) => !select.value)) {
            migrationError.textContent = "Choose a destination for every removed state.";
            migrationError.hidden = false;
            return;
        }
        const { form } = pendingWorkflowSave;
        form.querySelectorAll("input[data-workflow-migration]").forEach((input) => input.remove());
        for (const select of mappings) {
            const source = select.dataset.migrationFrom;
            const criteria = select.closest(".workflow-migration-row").querySelectorAll("fieldset[data-migration-criteria-kind]");
            for (const fieldset of criteria) {
                const selected = Array.from(fieldset.querySelectorAll("input:checked"));
                if (selected.length < Number(fieldset.dataset.migrationCriteriaMin || "1")) {
                    migrationError.textContent = "Choose the required criteria for every migration.";
                    migrationError.hidden = false;
                    return;
                }
                const name = fieldset.dataset.migrationCriteriaKind === "exclusion"
                    ? "migrationExclusionCriteria" : "migrationInclusionCriteria";
                selected.forEach((checkbox) => {
                    const input = document.createElement("input");
                    input.type = "hidden"; input.name = name; input.value = source + "|" + checkbox.value;
                    input.dataset.workflowMigration = "true"; form.appendChild(input);
                });
            }
            [["migrationFrom", source], ["migrationTo", select.value]].forEach(([name, value]) => {
                const input = document.createElement("input");
                input.type = "hidden"; input.name = name; input.value = value;
                input.dataset.workflowMigration = "true"; form.appendChild(input);
            });
        }
        pendingWorkflowSave = null;
        migrationDialog.close();
        form.submit();
    });

    document.querySelectorAll("[data-workflow-designer]").forEach((designer) => {
        const form = designer.closest("form");
        const yamlField = form?.querySelector("textarea[name='workflowStates']");
        const canvas = designer.querySelector("[data-workflow-canvas]");
        const status = designer.querySelector("[data-workflow-status]");
        const inspector = designer.querySelector("[data-workflow-inspector]");
        const initialSelect = designer.querySelector("[data-workflow-initial-state]");
        if (!form || !yamlField || !canvas || !status || !inspector || !initialSelect || !window.cytoscape) return;
        let workflow;
        try { workflow = clone(JSON.parse(form.dataset.workflowConfig || "{}")); } catch { return; }
        workflow.states = Array.isArray(workflow.states) ? workflow.states : [];
        workflow.transitions = Array.isArray(workflow.transitions) ? workflow.transitions : [];
        workflow.taxonomies = workflow.taxonomies || {};
        workflow.layout = workflow.layout || { nodes: {} }; workflow.layout.nodes = workflow.layout.nodes || {};
        workflow.states.forEach((state) => { state.requires = state.requires || {}; state.report = state.report || {}; });
        let selectedStateId = null, selectedEdge = null, edgeStartId = null, bypassSaveValidation = false;
        const fields = Object.fromEntries(["label", "group", "terminal", "requirementKind", "requirementTaxonomy", "requirementCount", "requirementValues", "requirementNotes", "reportBucket"].map((name) => [name, designer.querySelector("[data-workflow-state-" + name.replace(/[A-Z]/g, (letter) => "-" + letter.toLowerCase()) + "]") || designer.querySelector("[data-workflow-" + name.replace(/[A-Z]/g, (letter) => "-" + letter.toLowerCase()) + "]")]));
        const menu = document.createElement("div"); menu.className = "workflow-context-menu"; menu.hidden = true; document.body.appendChild(menu);
        const closeMenu = () => { menu.hidden = true; menu.replaceChildren(); };
        const openMenu = (event, actions) => {
            event?.preventDefault?.(); menu.replaceChildren();
            actions.forEach(({ label, run }) => { const button = document.createElement("button"); button.type = "button"; button.textContent = label; button.onclick = () => { closeMenu(); run(); }; menu.appendChild(button); });
            menu.style.left = Math.min(window.innerWidth - 210, event?.clientX || 24) + "px"; menu.style.top = Math.min(window.innerHeight - 150, event?.clientY || 24) + "px"; menu.hidden = false;
        };
        document.addEventListener("pointerdown", (event) => { if (!menu.contains(event.target)) closeMenu(); });
        const stateById = (id) => workflow.states.find((state) => state.id === id) || null;
        const edgeList = () => workflow.transitions.flatMap((transition) => transition.to.map((target) => ({ from: transition.from, to: target })));
        const edgeId = (from, to) => from + "→" + to;
        const captureLayout = () => { if (!cy) return; workflow.layout.nodes = Object.fromEntries(cy.nodes().map((node) => [node.id(), node.position()])); };
        const graphStatus = () => { const errors = graphErrors(workflow); status.textContent = edgeStartId ? "Select a destination state for " + edgeStartId + "." : errors[0] || "Workflow is valid. Save to apply it."; status.classList.toggle("error", Boolean(errors.length)); return errors; };
        const syncYaml = () => { captureLayout(); yamlField.value = yamlFromWorkflow(workflow); graphStatus(); };
        const elements = () => [
            ...workflow.states.map((state, index) => ({ group: "nodes", data: { id: state.id, label: state.label || state.id, initial: state.id === workflow.initialState ? "true" : "false" }, position: workflow.layout.nodes[state.id] || { x: 110 + (index % 4) * 210, y: 80 + Math.floor(index / 4) * 125 } })),
            ...edgeList().map((edge) => ({ group: "edges", data: { id: edgeId(edge.from, edge.to), source: edge.from, target: edge.to } }))
        ];
        const cy = cytoscape({ container: canvas, elements: elements(), style: [
            { selector: "node", style: { "background-color": "#fff", "border-color": "#a39182", "border-width": 2, width: 150, height: 60, shape: "round-rectangle", label: "data(label)", color: "#30251d", "font-family": "Georgia, serif", "font-size": 12, "text-wrap": "wrap", "text-max-width": 130, "text-valign": "center", "text-halign": "center" } },
            { selector: "node:selected", style: { "background-color": "#fff0e9", "border-color": "#b34f2e", "border-width": 4 } },
            { selector: "node[initial = 'true']", style: { "border-style": "dashed" } },
            { selector: "edge", style: { width: 2.5, "line-color": "#8b7564", "target-arrow-color": "#8b7564", "target-arrow-shape": "triangle", "curve-style": "bezier" } },
            { selector: "edge:selected", style: { width: 4, "line-color": "#b34f2e", "target-arrow-color": "#b34f2e" } }
        ], layout: { name: "preset" }, minZoom: 0.2, maxZoom: 3 });
        const taxonomyLeafLabels = (values) => (values || []).flatMap((value) => value.children?.length ? taxonomyLeafLabels(value.children) : [value.label || value.id]);
        const renderInspector = () => { const state = stateById(selectedStateId); inspector.hidden = !state; if (!state) return; const requires = state.requires || {}; const exclusion = requires.exclusionCriteria || requires.exclusionCriterion; const criterion = exclusion || requires.inclusionCriteria; designer.querySelector("[data-workflow-inspector-title]").textContent = state.label || state.id; fields.label.value = state.label || ""; fields.group.value = state.group || ""; fields.terminal.checked = !!state.terminal; fields.requirementKind.value = exclusion ? "exclusion" : requires.inclusionCriteria ? "inclusion" : ""; fields.requirementTaxonomy.value = criterion?.taxonomy || ""; fields.requirementCount.value = criterion?.min || criterion?.exactly || 1; if (fields.requirementValues) fields.requirementValues.value = criterion ? taxonomyLeafLabels(workflow.taxonomies[criterion.taxonomy]?.values).join("\n") : ""; fields.requirementNotes.checked = false; fields.reportBucket.value = state.report?.prismaBucket || ""; };
        const render = () => { captureLayout(); cy.elements().remove(); cy.add(elements()); initialSelect.replaceChildren(); workflow.states.forEach((state) => { const option = new Option(state.label + " (" + state.id + ")", state.id, false, state.id === workflow.initialState); initialSelect.add(option); }); if (selectedStateId) cy.$id(selectedStateId).select(); if (selectedEdge) cy.$id(selectedEdge).select(); renderInspector(); syncYaml(); };
        const addEdge = (from, to) => { if (from === to) return "A state cannot transition to itself."; if (edgeList().some((edge) => edge.from === from && edge.to === to)) return "That transition already exists."; let transition = workflow.transitions.find((item) => item.from === from); if (!transition) { transition = { from, to: [] }; workflow.transitions.push(transition); } transition.to.push(to); return ""; };
        const removeEdge = (from, to) => { const transition = workflow.transitions.find((item) => item.from === from); if (!transition) return; transition.to = transition.to.filter((target) => target !== to); workflow.transitions = workflow.transitions.filter((item) => item.to.length); };
        const addState = (position) => { const label = "New state"; const base = stateId(label, null); let id = base, suffix = 2; while (stateById(id)) id = base + "_" + suffix++; workflow.states.push({ id, label, group: null, terminal: false, requires: {}, report: {} }); workflow.layout.nodes[id] = position || { x: 120, y: 90 }; selectedStateId = id; selectedEdge = null; render(); };
        const deleteState = (id, event) => { if (workflow.initialState === id && workflow.states.length > 1) return pointerMessage(event, "Choose a replacement initial state before deleting this state."); workflow.states = workflow.states.filter((state) => state.id !== id); workflow.transitions = workflow.transitions.filter((transition) => transition.from !== id).map((transition) => ({ ...transition, to: transition.to.filter((target) => target !== id) })).filter((transition) => transition.to.length); delete workflow.layout.nodes[id]; selectedStateId = null; selectedEdge = null; render(); };
        const autoLayout = () => { cy.layout({ name: "dagre", rankDir: "LR", nodeSep: 70, rankSep: 110, edgeSep: 30, padding: 40, fit: true, animate: false }).run(); requestAnimationFrame(syncYaml); };
        const updateState = () => { const state = stateById(selectedStateId); if (!state) return; const oldId = state.id, label = fields.label.value.trim() || "Untitled state", group = nodeId(fields.group.value) || null, base = stateId(label, group); let nextId = base, suffix = 2; while (nextId !== oldId && stateById(nextId)) nextId = base + "_" + suffix++; if (nextId !== oldId) { state.id = nextId; workflow.transitions.forEach((transition) => { if (transition.from === oldId) transition.from = nextId; transition.to = transition.to.map((target) => target === oldId ? nextId : target); }); if (workflow.initialState === oldId) workflow.initialState = nextId; workflow.layout.nodes[nextId] = workflow.layout.nodes[oldId]; delete workflow.layout.nodes[oldId]; selectedStateId = nextId; } state.label = label; state.group = group; state.terminal = fields.terminal.checked; const kind = fields.requirementKind.value, taxonomy = nodeId(fields.requirementTaxonomy.value) || (kind === "exclusion" ? "EXCLUSION" : "INCLUSION"), count = Math.max(1, Number.parseInt(fields.requirementCount.value || "1", 10) || 1); state.requires = {}; if (kind === "exclusion") state.requires.exclusionCriteria = { taxonomy, min: count }; if (kind === "inclusion") state.requires.inclusionCriteria = { taxonomy, min: count }; const listedCriteria = fields.requirementValues?.value.trim(); if (kind && listedCriteria) { const usedIds = new Set(); workflow.taxonomies[taxonomy] = { label: workflow.taxonomies[taxonomy]?.label || taxonomy, values: listedCriteria.split(/\r?\n/).map((item) => item.trim()).filter(Boolean).filter((item) => { const id = nodeId(item); if (!id || usedIds.has(id)) return false; usedIds.add(id); return true; }).map((label) => ({ id: nodeId(label), label })) }; } else if (kind && !workflow.taxonomies[taxonomy]) { workflow.taxonomies[taxonomy] = { label: taxonomy, values: [] }; } state.report = fields.reportBucket.value.trim() ? { prismaBucket: nodeId(fields.reportBucket.value) } : {}; render(); };
        cy.on("tap", "node", (event) => { const id = event.target.id(); if (edgeStartId && edgeStartId !== id) { const reason = addEdge(edgeStartId, id); if (reason) pointerMessage(event.originalEvent, reason); edgeStartId = null; render(); return; } selectedStateId = id; selectedEdge = null; renderInspector(); syncYaml(); });
        cy.on("tap", "edge", (event) => { selectedEdge = event.target.id(); selectedStateId = null; edgeStartId = null; renderInspector(); syncYaml(); });
        cy.on("dragfree", "node", syncYaml);
        cy.on("cxttap", "node", (event) => { const id = event.target.id(); selectedStateId = id; selectedEdge = null; openMenu(event.originalEvent, [{ label: "Create transition from here", run: () => { edgeStartId = id; graphStatus(); } }, { label: "Delete state", run: () => deleteState(id, event.originalEvent) }]); });
        cy.on("cxttap", "edge", (event) => { const edge = event.target; const from = edge.source().id(), to = edge.target().id(); selectedEdge = edge.id(); selectedStateId = null; openMenu(event.originalEvent, [{ label: "Reverse transition", run: () => { removeEdge(from, to); const reason = addEdge(to, from); if (reason) { addEdge(from, to); pointerMessage(event.originalEvent, reason); } selectedEdge = null; render(); } }, { label: "Delete transition", run: () => { removeEdge(from, to); selectedEdge = null; render(); } }]); });
        cy.on("cxttap", (event) => { if (event.target === cy) openMenu(event.originalEvent, [{ label: "Add state", run: () => addState(event.position) }]); });
        // Updating on commit avoids rebuilding the inspector while an administrator is typing.
        Object.values(fields).forEach((field) => field?.addEventListener("change", updateState));
        initialSelect.addEventListener("change", () => { workflow.initialState = initialSelect.value; render(); });
        designer.querySelector("[data-workflow-auto-layout]")?.addEventListener("click", autoLayout);
        designer.querySelector("[data-workflow-copy-yaml]")?.addEventListener("click", async () => { await navigator.clipboard?.writeText(yamlField.value); status.textContent = "YAML copied."; });
        designer.querySelector("[data-workflow-load-yaml]")?.addEventListener("click", async () => { try { const response = await fetch("/logical-feeds/" + encodeURIComponent(form.dataset.logicalFeedId) + "/workflow/validate", { method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8", Accept: "application/json" }, body: new URLSearchParams({ workflowStates: yamlField.value }) }); const payload = await response.json(); if (!response.ok) throw new Error(payload.error || "Invalid workflow YAML."); workflow = clone(payload.workflow); workflow.layout = workflow.layout || { nodes: {} }; workflow.layout.nodes = workflow.layout.nodes || {}; const needsLayout = !Object.keys(workflow.layout.nodes).length; workflow.states.forEach((state) => { state.requires ||= {}; state.report ||= {}; }); selectedStateId = null; selectedEdge = null; render(); if (needsLayout) autoLayout(); } catch (error) { status.textContent = error.message || "Could not load workflow YAML."; status.classList.add("error"); } });
        form.addEventListener("submit", async (event) => { if (bypassSaveValidation) return; event.preventDefault(); syncYaml(); try { const response = await fetch("/logical-feeds/" + encodeURIComponent(form.dataset.logicalFeedId) + "/workflow/validate", { method: "POST", headers: { "Content-Type": "application/x-www-form-urlencoded;charset=UTF-8", Accept: "application/json" }, body: new URLSearchParams({ workflowStates: yamlField.value }) }); const payload = await response.json(); if (!response.ok) throw new Error(payload.error || "Workflow validation failed."); yamlField.value = payload.yaml; if (!payload.removedStates?.length) { bypassSaveValidation = true; form.submit(); return; } pendingWorkflowSave = { form, payload }; migrationList.replaceChildren(); migrationError.hidden = true; payload.removedStates.forEach((removed) => { const row = document.createElement("div"); row.className = "workflow-migration-row"; const source = document.createElement("span"); source.textContent = removed.label + " (" + removed.paperCount + " paper" + (removed.paperCount === 1 ? "" : "s") + ")"; const select = document.createElement("select"); select.dataset.migrationFrom = removed.id; select.add(new Option("Choose destination", "")); payload.migrationTargets.forEach((target) => select.add(new Option(target.label + " (" + target.id + ")", target.id))); const criteria = document.createElement("div"); criteria.dataset.migrationCriteria = "true"; select.addEventListener("change", () => migrationRequirementFields(row, payload.workflow.states.find((state) => state.id === select.value), payload.workflow)); row.append(source, select, criteria); migrationList.appendChild(row); }); migrationDialog.showModal(); } catch (error) { status.textContent = error.message || "Workflow validation failed."; status.classList.add("error"); } });
        const needsInitialLayout = !Object.keys(workflow.layout.nodes).length;
        render(); if (needsInitialLayout) autoLayout(); else cy.fit(cy.elements(), 40);
    });
})();
