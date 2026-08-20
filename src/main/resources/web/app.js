(function () {
  var data = JSON.parse(document.getElementById("apum-data").textContent);

  var statusLabels = {
    USED: "Declared and used",
    DECLARED_UNUSED: "Declared, never used",
    UNDECLARED_USAGE: "Missing from manifest",
    REQUESTED_ONLY: "Requested only",
    LIBRARY_ONLY: "Library only",
    WEAK_SIGNAL: "Weak signal",
    ALTERNATIVE_COVERED: "Covered by alternative"
  };

  var statusTips = {
    USED: "Declared in the manifest and backed by real API usage in the code",
    DECLARED_UNUSED: "Declared in the manifest but no usage was found in the code, safe candidate for removal",
    UNDECLARED_USAGE: "The code uses this permission but the manifest does not declare it, this will fail at runtime",
    REQUESTED_ONLY: "Only a runtime request or permission check was found, no protected API call uses it yet",
    LIBRARY_ONLY: "Only a bundled library appears to need this permission, the app code itself does not use it",
    WEAK_SIGNAL: "Only a low confidence signal matched, verify this one manually before acting on it",
    ALTERNATIVE_COVERED: "Another permission from the same group is already declared and covers this usage"
  };

  var kindLabels = {
    API_CALL: "API call",
    REQUEST: "Runtime request",
    CHECK: "Permission check",
    PERMISSION_STRING: "Permission constant",
    MANIFEST: "Manifest"
  };

  var protectionTips = {
    DANGEROUS: "Dangerous permission, Android requires an explicit runtime grant from the user",
    NORMAL: "Normal permission, granted automatically at install time",
    SPECIAL: "Special access, granted through a dedicated system settings screen and reviewed by Google Play",
    SIGNATURE: "Signature level permission, granted only to apps signed with the same certificate"
  };

  function el(tag, className, text, tip) {
    var node = document.createElement(tag);
    if (className) node.className = className;
    if (text !== undefined && text !== null) node.textContent = String(text);
    if (tip) node.title = tip;
    return node;
  }

  function shortSymbol(symbol) {
    var parts = String(symbol).split(".");
    return parts.length > 2 ? parts.slice(-2).join(".") : symbol;
  }

  function renderProject() {
    document.getElementById("project-name").textContent = data.project.name;
    var meta = [];
    if (data.project.applicationId) meta.push(data.project.applicationId);
    meta.push("minSdk " + (data.project.minSdk || "-") + " / targetSdk " + (data.project.targetSdk || "-"));
    meta.push(
      data.project.kotlinFileCount + " Kotlin · " +
      data.project.javaFileCount + " Java · " +
      data.project.dartFileCount + " Dart files"
    );
    document.getElementById("project-meta").textContent = meta.join("   ·   ");
    document.getElementById("generated-at").textContent = "Generated " + data.generatedAt;
  }

  function renderStats() {
    var summary = data.summary;
    var container = document.getElementById("summary-stats");
    var grade = summary.riskGrade;
    var stats = [
      {
        label: "Risk grade",
        value: grade + " · " + summary.riskScore,
        tip: "Overall grade from A to F, derived from the weighted severity of all findings (score " + summary.riskScore + " of 100)",
        tone: grade === "A" || grade === "B" ? "tone-good" : grade === "C" ? "tone-warn" : "tone-bad"
      },
      { label: "Declared", value: summary.declaredPermissionCount, tip: "Permissions declared with uses-permission in the manifest" },
      { label: "Declared & used", value: summary.usedPermissionCount, tip: "Declared in the manifest and backed by real API usage in the code", tone: "tone-good" },
      { label: "Declared, never used", value: summary.unusedPermissionCount, tip: "Declared in the manifest but no usage was found in the code, safe candidates for removal", tone: summary.unusedPermissionCount > 0 ? "tone-warn" : "" },
      { label: "Missing from manifest", value: summary.undeclaredPermissionCount, tip: "The code uses these permissions but the manifest does not declare them, they will fail at runtime", tone: summary.undeclaredPermissionCount > 0 ? "tone-bad" : "" },
      { label: "Findings", value: data.findings.length, tip: "Total number of issues detected in this run" }
    ];
    stats.forEach(function (item) {
      var node = el("div", "stat " + (item.tone || ""), null, item.tip);
      node.appendChild(el("div", "value", item.value));
      node.appendChild(el("div", "label", item.label));
      container.appendChild(node);
    });
  }

  function evidenceRow(usage) {
    var row = el("div", "row", null, kindLabels[usage.kind] + " detected with " + usage.confidence.toLowerCase() + " confidence");
    row.appendChild(el("div", "where", usage.file + ":" + usage.line, "File and line where this evidence was found"));
    var what = el("div", "what");
    what.appendChild(el("div", null, (kindLabels[usage.kind] || usage.kind) + " · " + usage.evidence));
    if (usage.snippet) what.appendChild(el("code", null, usage.snippet, "Source line as written in the project"));
    row.appendChild(what);
    return row;
  }

  function declarationRow(declaration) {
    var row = el("div", "row", null, "uses-permission entry found in the manifest");
    row.appendChild(el("div", "where", declaration.file + ":" + declaration.line, "Manifest file and line of this declaration"));
    var what = el("div", "what");
    what.appendChild(el("div", null, declaration.maxSdkVersion ? "maxSdkVersion " + declaration.maxSdkVersion : "No maxSdkVersion limit"));
    if (declaration.snippet) what.appendChild(el("code", null, declaration.snippet));
    row.appendChild(what);
    return row;
  }

  function pathRow(path) {
    var row = el("div", "path", null, "Call path from the entry point down to the code that uses the permission");
    row.appendChild(el("span", "tag", path.entryKind, "Type of the entry point that reaches this permission"));
    path.nodes.forEach(function (node, index) {
      if (index > 0) row.appendChild(el("span", "arrow", "→"));
      row.appendChild(el("span", "node", shortSymbol(node.symbol) + ":" + node.line, node.file + ":" + node.line));
    });
    return row;
  }

  function block(title, tip, items, builder) {
    if (!items || items.length === 0) return null;
    var section = el("div", "block");
    section.appendChild(el("h3", null, title + " (" + items.length + ")", tip));
    items.forEach(function (item) { section.appendChild(builder(item)); });
    return section;
  }

  function permissionNode(permission) {
    var wrapper = el("div", "permission");
    var header = el("div", "permission-header", null, "Click to expand the evidence collected for this permission");

    var left = el("div");
    var title = el("div", "permission-title");
    title.appendChild(el("span", "dot risk-" + permission.riskLevel, null, permission.riskLevel + " risk"));
    title.appendChild(el("strong", null, permission.shortName, permission.permission));
    title.appendChild(el("span", "tag risk-" + permission.riskLevel, statusLabels[permission.status] || permission.status, statusTips[permission.status] || ""));
    title.appendChild(el("span", "tag", permission.protection, protectionTips[permission.protection] || ""));
    if (!permission.declared) title.appendChild(el("span", "tag risk-HIGH", "not in manifest", "This permission is missing from AndroidManifest.xml"));
    left.appendChild(title);
    left.appendChild(el("div", "permission-sub", permission.description, permission.permission));
    header.appendChild(left);

    var counts = el("div", "counts");
    counts.appendChild(el("span", null, permission.usages.length + " evidence", "Number of code locations that point to this permission"));
    counts.appendChild(el("span", null, permission.runtimeRequests.length + " requests", "Number of runtime permission requests found for this permission"));
    counts.appendChild(el("span", null, permission.callPaths.length + " paths", "Number of call paths from an entry point to this permission"));
    header.appendChild(counts);

    var body = el("div", "permission-body");

    if (permission.policyNote) {
      var note = el("div", "block");
      note.appendChild(el("h3", null, "Play policy", "What Google Play expects for this permission"));
      note.appendChild(el("div", "muted", permission.policyNote));
      body.appendChild(note);
    }

    var sections = [
      block("Manifest declarations", "Where this permission is declared in the manifest", permission.declarations, declarationRow),
      block("Code evidence", "API calls and permission constants that require this permission", permission.usages, evidenceRow),
      block("Runtime requests", "Places where the app asks the user to grant this permission", permission.runtimeRequests, evidenceRow),
      block("Permission checks", "Places where the app verifies the permission before using it", permission.permissionChecks, evidenceRow),
      block("Call paths", "How execution reaches the permission from a screen, service or worker", permission.callPaths, pathRow)
    ];
    var added = 0;
    sections.forEach(function (section) { if (section) { body.appendChild(section); added++; } });
    if (added === 0) body.appendChild(el("div", "empty", "No usage evidence was found for this permission."));

    header.addEventListener("click", function () { wrapper.classList.toggle("open"); });
    wrapper.appendChild(header);
    wrapper.appendChild(body);
    return wrapper;
  }

  function matchesFilters(permission) {
    var query = document.getElementById("search").value.trim().toLowerCase();
    var status = document.getElementById("status-filter").value;
    var risk = document.getElementById("risk-filter").value;

    if (status && permission.status !== status) return false;
    if (risk && permission.riskLevel !== risk) return false;
    if (!query) return true;

    var haystack = [permission.permission, permission.description, permission.group]
      .concat(permission.touchedFiles)
      .concat(permission.usages.map(function (usage) { return usage.enclosing + " " + usage.evidence; }))
      .join(" ")
      .toLowerCase();
    return haystack.indexOf(query) !== -1;
  }

  function renderPermissions() {
    var container = document.getElementById("permission-list");
    container.innerHTML = "";
    var visible = data.permissions.filter(matchesFilters);
    if (visible.length === 0) {
      container.appendChild(el("div", "empty", "No permission matches the current filters."));
      return;
    }
    visible.forEach(function (permission) { container.appendChild(permissionNode(permission)); });
  }

  function renderFindings() {
    var container = document.getElementById("finding-list");
    if (data.findings.length === 0) {
      container.appendChild(el("div", "empty", "No issues were found."));
      return;
    }
    data.findings.forEach(function (finding) {
      var wrapper = el("div", "finding sev-" + finding.severity, null, "Rule " + finding.rule);
      var title = el("div", "finding-title");
      title.appendChild(el("span", "tag risk-" + (finding.severity === "LOW" ? "LOW" : finding.severity === "MEDIUM" ? "MEDIUM" : "HIGH"), finding.severity, "Severity of this finding"));
      title.appendChild(el("span", null, finding.title));
      wrapper.appendChild(title);
      wrapper.appendChild(el("p", null, finding.detail, "Why this was reported"));
      wrapper.appendChild(el("p", null, "Fix: " + finding.recommendation, "Recommended action"));
      finding.locations.forEach(function (location) {
        wrapper.appendChild(el("div", "where", location.file + ":" + location.line, "Location related to this finding"));
      });
      container.appendChild(wrapper);
    });
    var counts = data.summary.findingCountBySeverity || {};
    document.getElementById("finding-caption").textContent = Object.keys(counts)
      .map(function (key) { return key + " " + counts[key]; })
      .join("   ·   ");
  }

  function renderEntries() {
    var container = document.getElementById("entry-list");
    if (!data.entryPoints || data.entryPoints.length === 0) {
      container.appendChild(el("div", "empty", "No entry point reached a permission."));
      return;
    }
    data.entryPoints.forEach(function (entry) {
      var item = el("div", "plain-item", null, "Entry point of type " + entry.kind);
      item.appendChild(el("div", null, shortSymbol(entry.symbol)));
      item.appendChild(el("span", "muted", entry.file + ":" + entry.line, "Where this entry point is defined"));
      var pills = el("div", "pills");
      entry.reachedPermissions.forEach(function (permission) {
        pills.appendChild(el("span", "tag", permission.split(".").pop(), permission + " is reachable from here"));
      });
      item.appendChild(pills);
      container.appendChild(item);
    });
  }

  function renderComponents() {
    var container = document.getElementById("component-list");
    if (!data.components || data.components.length === 0) {
      container.appendChild(el("div", "empty", "No manifest component was found."));
      return;
    }
    data.components.forEach(function (component) {
      var item = el("div", "plain-item", null, "Component declared in " + component.file);
      item.appendChild(el("div", null, component.name));
      var details = [component.type];
      details.push("exported " + (component.exported === null || component.exported === undefined ? "unset" : component.exported));
      if (component.permission) details.push("guarded by " + component.permission);
      if (component.hasIntentFilter) details.push("intent-filter");
      item.appendChild(el("span", "muted", details.join(" · "), "Component type, export state and protection"));
      item.appendChild(el("span", "muted", component.file + ":" + component.line));
      container.appendChild(item);
    });
  }

  function renderProjectChips() {
    var container = document.getElementById("project-info");
    var chips = [
      { label: "Application id", value: data.project.applicationId || "-", tip: "Application id read from the Gradle configuration" },
      { label: "compileSdk", value: data.project.compileSdk || "-", tip: "compileSdk declared in the Gradle configuration" },
      { label: "Lines scanned", value: data.project.analyzedLineCount, tip: "Total number of source lines that were read" },
      { label: "Modules", value: (data.project.modules || []).length, tip: "Number of source modules discovered in the project" },
      { label: "Manifests", value: (data.project.manifestFiles || []).length, tip: "Number of AndroidManifest.xml files that were parsed" },
      { label: "Dependencies", value: (data.project.dependencies || []).length, tip: "Number of Gradle dependencies detected" },
      { label: "Duration", value: data.summary.analysisDurationMs + " ms", tip: "Time this analysis run took" }
    ];
    chips.forEach(function (chip) {
      var node = el("div", "chip", null, chip.tip);
      node.appendChild(el("b", null, chip.value + " "));
      node.appendChild(el("span", null, chip.label));
      container.appendChild(node);
    });
  }

  function bindControls() {
    ["search", "status-filter", "risk-filter"].forEach(function (id) {
      var control = document.getElementById(id);
      control.addEventListener("input", renderPermissions);
      control.addEventListener("change", renderPermissions);
    });

    document.getElementById("theme-toggle").addEventListener("click", function () {
      document.body.dataset.theme = document.body.dataset.theme === "dark" ? "light" : "dark";
    });

    document.getElementById("export-json").addEventListener("click", function () {
      var blob = new Blob([JSON.stringify(data, null, 2)], { type: "application/json" });
      var url = URL.createObjectURL(blob);
      var link = document.createElement("a");
      link.href = url;
      link.download = "permission-map.json";
      link.click();
      URL.revokeObjectURL(url);
    });
  }

  renderProject();
  renderStats();
  renderPermissions();
  renderFindings();
  renderEntries();
  renderComponents();
  renderProjectChips();
  bindControls();
})();
