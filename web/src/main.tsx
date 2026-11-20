import React from "react";
import ReactDOM from "react-dom/client";
import { App } from "./App";
import { t } from "./lib/i18n";
import "./index.css";

class ErrorBoundary extends React.Component<{ children: React.ReactNode }, { failed: boolean }> {
  state = { failed: false };
  static getDerivedStateFromError() { return { failed: true }; }
  render() {
    return this.state.failed ? <main className="shell"><h1>{t("Something went wrong")}</h1><p>{t("Reload the dashboard. Your stored device identity will be kept.")}</p><button onClick={() => location.reload()}>{t("Reload")}</button></main> : this.props.children;
  }
}
ReactDOM.createRoot(document.getElementById("root")!).render(<React.StrictMode><ErrorBoundary><App /></ErrorBoundary></React.StrictMode>);
