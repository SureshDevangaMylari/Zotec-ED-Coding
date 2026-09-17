(function () {
  const chartTypeEl = document.getElementById("chartType");
  const clientsPanel = document.getElementById("clientsPanel");
  const clientSearch = document.getElementById("clientSearch");
  const selectionMeta = document.getElementById("selectionMeta");
  const startBtn = document.getElementById("startAgent");
  const stopBtn = document.getElementById("stopAgent");
  const statusBar = document.getElementById("statusBar");
  const statusText = document.getElementById("statusText");

  const clients = Array.isArray(window.AGENT_CLIENTS) ? window.AGENT_CLIENTS : [];

  function setStatus(state, message) {
    statusBar.dataset.state = state;
    statusText.textContent = message;
  }

  function selectedClients() {
    return Array.from(clientsPanel.querySelectorAll('input[type="checkbox"]:checked')).map(
      (el) => el.value
    );
  }

  function updateSelectionMeta() {
    const n = selectedClients().length;
    selectionMeta.textContent = n === 1 ? "1 client selected" : n + " clients selected";
  }

  function clearInputs() {
    chartTypeEl.value = "Text";
    clientsPanel.querySelectorAll('input[type="checkbox"]').forEach((el) => {
      el.checked = false;
    });
    clientSearch.value = "";
    filterClients("");
    updateSelectionMeta();
  }

  function renderClients() {
    const frag = document.createDocumentFragment();
    clients.forEach((name, index) => {
      const label = document.createElement("label");
      label.className = "client-row";
      label.dataset.name = name.toLowerCase();

      const input = document.createElement("input");
      input.type = "checkbox";
      input.name = "clients";
      input.value = name;
      input.id = "client-" + index;

      const span = document.createElement("span");
      span.textContent = name;

      label.appendChild(input);
      label.appendChild(span);
      frag.appendChild(label);
    });
    clientsPanel.appendChild(frag);
  }

  function filterClients(query) {
    const q = (query || "").trim().toLowerCase();
    clientsPanel.querySelectorAll(".client-row").forEach((row) => {
      const match = !q || row.dataset.name.includes(q);
      row.classList.toggle("hidden", !match);
    });
  }

  function setRunningUi(running) {
    startBtn.disabled = running;
    stopBtn.disabled = !running;
    chartTypeEl.disabled = running;
    clientsPanel.querySelectorAll('input[type="checkbox"]').forEach((el) => {
      el.disabled = running;
    });
    clientSearch.disabled = running;
    document.getElementById("selectAllClients").disabled = running;
    document.getElementById("clearClients").disabled = running;
  }

  async function startAgent() {
    const chartType = chartTypeEl.value;
    const selected = selectedClients();
    if (!selected.length) {
      setStatus("error", "Select at least one client before starting.");
      return;
    }

    setRunningUi(true);
    setStatus("starting", "Starting agent (" + chartType + ", " + selected.length + " client(s))…");

    const payload = {
      chartType: chartType,
      clients: selected
    };

    try {
      const res = await fetch("/startAgent", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(payload)
      });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) {
        throw new Error(data.error || ("HTTP " + res.status));
      }
      const flow = data.flow || (chartType === "Image" ? "Flow" : "FlowText");
      setStatus(
        "running",
        "Agent running — " + chartType + " (" + flow + ") · " + selected.length + " client(s)"
      );
    } catch (err) {
      console.error(err);
      setRunningUi(false);
      setStatus("error", "Start failed: " + (err.message || err));
    }
  }

  async function stopAgent() {
    setStatus("stopping", "Stopping agent…");
    try {
      const res = await fetch("/stopAgent", { method: "POST" });
      const data = await res.json().catch(() => ({}));
      if (!res.ok) {
        throw new Error(data.error || ("HTTP " + res.status));
      }
    } catch (err) {
      console.error(err);
      setStatus("error", "Stop failed: " + (err.message || err));
    }
    setRunningUi(false);
    clearInputs();
    setStatus("stopped", "Agent stopped — inputs cleared");
  }

  renderClients();
  updateSelectionMeta();

  clientsPanel.addEventListener("change", updateSelectionMeta);
  clientSearch.addEventListener("input", () => filterClients(clientSearch.value));

  document.getElementById("selectAllClients").addEventListener("click", () => {
    clientsPanel.querySelectorAll(".client-row:not(.hidden) input").forEach((el) => {
      el.checked = true;
    });
    updateSelectionMeta();
  });

  document.getElementById("clearClients").addEventListener("click", () => {
    clientsPanel.querySelectorAll('input[type="checkbox"]').forEach((el) => {
      el.checked = false;
    });
    updateSelectionMeta();
  });

  startBtn.addEventListener("click", startAgent);
  stopBtn.addEventListener("click", stopAgent);
})();
