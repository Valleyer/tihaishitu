/**
 * 原生 dialog 负责焦点与 Escape；主游戏不滚动，长表单和完整正文允许弹窗内部滚动。
 */
import { useEffect, useRef } from "react";
import type { ReactNode } from "react";
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
    dialog.showModal();
    return () => dialog.close();
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
          <small>{subtitle || "青溪 · 案头文书"}</small>
          <h2>{title}</h2>
        </div>
        <button className="icon-button" aria-label="关闭窗口" onClick={close}>
          ×
        </button>
      </div>
      <div className="modal-body">{children}</div>
    </dialog>
  );
}
