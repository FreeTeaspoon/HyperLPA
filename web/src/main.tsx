import React from "react";
import ReactDOM from "react-dom/client";
import { App } from "./App";
import "./index.css";

class ErrorBoundary extends React.Component<{ children: React.ReactNode }, { failed: boolean }> {
  state = { failed: false };
  static getDerivedStateFromError() { return { failed: true }; }
  render() {
    return this.state.failed ? <main className="shell"><h1>Something went wrong</h1><p>Reload the dashboard. Your stored device identity will be kept.</p><button onClick={() => location.reload()}>Reload</button></main> : this.props.children;
  }
}
ReactDOM.createRoot(document.getElementById("root")!).render(<React.StrictMode><ErrorBoundary><App /></ErrorBoundary></React.StrictMode>);
