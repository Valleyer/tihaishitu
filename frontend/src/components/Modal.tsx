/**
 * 原生 dialog 负责焦点与 Escape；主游戏不滚动，长表单和完整正文允许弹窗内部滚动。
 */
import { Component, useEffect, useRef } from "react";
import type { ReactNode } from "react";

class PanelErrorBoundary extends Component<{ children: ReactNode; close: () => void }, { failed: boolean }> {
  state = { failed: false };
  static getDerivedStateFromError() { return { failed: true }; }
  render() {
    if (this.state.failed) return <div className="empty-state"><h3>当前面板暂时无法打开</h3><button onClick={this.props.close}>关闭</button></div>;
    return this.props.children;
  }
}
export function Modal({
  title,
  subtitle,
  close,
  children,
  wide = false,
}: {
  title: string;
  subtitle?: string;
  close: () => void;
  children: ReactNode;
  wide?: boolean;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const dialog = ref.current!;
    if (!dialog.open) dialog.showModal();
    return () => { if (dialog.open) dialog.close(); };
  }, []);
  return (
    <dialog
      ref={ref}
      className={"modal " + (wide ? "wide" : "")}
      onCancel={(e) => {
        e.preventDefault();
        close();
      }}
      onClick={(e) => {
        if (e.target === e.currentTarget) close();
      }}
    >
      <div className="modal-header">
        <div>
          {subtitle && <small>{subtitle}</small>}
          <h2>{title}</h2>
        </div>
        <button className="icon-button" aria-label="关闭窗口" onClick={close}>
          ×
        </button>
      </div>
      <div className="modal-body"><PanelErrorBoundary close={close}>{children}</PanelErrorBoundary></div>
    </dialog>
  );
}
