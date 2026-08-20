(function () {
  var state = { path: null, busy: false, reportUrl: null };

  var folderField = document.getElementById("folder-field");
  var folderValue = document.getElementById("folder-value");
  var analyzeButton = document.getElementById("analyze");
  var statusLine = document.getElementById("status");
  var resultPanel = document.getElementById("result");
  var resultStats = document.getElementById("result-stats");
  var openReportButton = document.getElementById("open-report");

  function setStatus(message, tone) {
    statusLine.textContent = message || "";
    statusLine.className = "status" + (tone ? " is-" + tone : "");
  }

  function setBusy(busy, label) {
    state.busy = busy;
    folderField.disabled = busy;
    analyzeButton.disabled = busy || !state.path;
    analyzeButton.classList.toggle("is-busy", busy);
    analyzeButton.textContent = busy && label ? label : "Analyze";
  }

  function readJson(response) {
    return response.json().then(function (payload) {
      if (!response.ok) throw new Error(payload.error || "The request failed");
      return payload;
    });
  }

  function statCard(value, label, tone) {
    var card = document.createElement("div");
    card.className = "stat" + (tone ? " tone-" + tone : "");
    var valueNode = document.createElement("div");
    valueNode.className = "value";
    valueNode.textContent = String(value);
    var labelNode = document.createElement("div");
    labelNode.className = "label";
    labelNode.textContent = label;
    card.appendChild(valueNode);
    card.appendChild(labelNode);
    return card;
  }

  function gradeTone(grade) {
    if (grade === "A" || grade === "B") return "good";
    if (grade === "C" || grade === "D") return null;
    return "bad";
  }

  function countTone(count) {
    return count > 0 ? "bad" : "good";
  }

  function renderResult(result) {
    resultStats.innerHTML = "";
    resultStats.appendChild(statCard(result.riskGrade, "Risk grade", gradeTone(result.riskGrade)));
    resultStats.appendChild(statCard(result.declaredPermissionCount, "Declared", null));
    resultStats.appendChild(statCard(result.usedPermissionCount, "Declared & used", null));
    resultStats.appendChild(statCard(result.unusedPermissionCount, "Declared, never used", countTone(result.unusedPermissionCount)));
    resultStats.appendChild(statCard(result.undeclaredPermissionCount, "Missing from manifest", countTone(result.undeclaredPermissionCount)));
    resultStats.appendChild(statCard(result.findingsCount, "Findings", countTone(result.findingsCount)));
    state.reportUrl = result.reportUrl;
    resultPanel.hidden = false;
  }

  function pickFolder() {
    if (state.busy) return;
    setBusy(true, null);
    setStatus("Waiting for the folder dialog...", "busy");

    fetch("/api/pick-folder", { method: "POST" })
      .then(readJson)
      .then(function (payload) {
        if (!payload.path) {
          setStatus(state.path ? "" : "No folder selected yet.");
          return;
        }
        state.path = payload.path;
        folderValue.textContent = payload.path;
        folderField.classList.remove("is-empty");
        setStatus("");
      })
      .catch(function (error) {
        setStatus(error.message, "error");
      })
      .then(function () {
        setBusy(false, null);
      });
  }

  function analyze() {
    if (state.busy || !state.path) return;
    setBusy(true, "Analyzing...");
    setStatus("Analyzing " + state.path + " ...", "busy");
    resultPanel.hidden = true;

    fetch("/api/analyze", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ path: state.path, includeTests: false })
    })
      .then(readJson)
      .then(function (result) {
        renderResult(result);
        setStatus("Analysis finished in " + result.analysisDurationMs + " ms.", "done");
      })
      .catch(function (error) {
        setStatus(error.message, "error");
      })
      .then(function () {
        setBusy(false, null);
      });
  }

  folderField.addEventListener("click", pickFolder);
  analyzeButton.addEventListener("click", analyze);

  openReportButton.addEventListener("click", function () {
    if (state.reportUrl) window.open(state.reportUrl, "_blank", "noopener");
  });
})();
