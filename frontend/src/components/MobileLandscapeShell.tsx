/**
 * PR12 手机端登录后强制横屏兼容层。
 *
 * <p>产品行为固定：认证页（/login、/register）保持当前竖屏；手机窄屏触摸设备在登录后
 * 的 portrait 状态下，把整个已登录应用旋转 90° 按横向画布展示；设备真实 landscape 时
 * 不旋转，直接使用真实横屏 viewport；桌面完全不变。</p>
 *
 * <p>这里只负责「激活判定 + 根节点 class + 稳定 DOM 外壳 + 滚动容器重置」，
 * 不重建 Hub / World / Manage，也不复制第二套页面。旋转只切换 class / CSS，
 * 因此 portrait ↔ landscape 切换不会 remount 业务子树。</p>
 */
import { useEffect, useLayoutEffect, useRef, useState } from "react";
import type { ReactNode } from "react";
import { useCurrentLocation } from "../platform/navigation";
import "../mobile-landscape.css";

/** 认证页不旋转：登录 / 注册在手机上继续使用现有 portrait 响应式布局。 */
const PORTRAIT_ONLY_PATHS = ["/login", "/register"];

/**
 * 手机 portrait 判定：orientation + 窄屏宽度，另需触摸 / coarse pointer。
 * 桌面窗口只是拖窄时 pointer 仍然是 fine，因此不会误触发。
 */
export const PHONE_PORTRAIT_QUERY = "(orientation: portrait) and (max-width: 600px)";

/** 激活时挂在根节点上的 class，所有 forced-landscape 兼容补丁都以它为作用域。 */
export const MOBILE_LANDSCAPE_CLASS = "mobile-landscape-active";

function matchesPhonePortrait() {
  return window.matchMedia(PHONE_PORTRAIT_QUERY).matches
    && (navigator.maxTouchPoints > 0 || window.matchMedia("(pointer: coarse)").matches);
}

/** 订阅手机 portrait 判定：matchMedia change + resize / orientationchange 兜底，不轮询。 */
function usePhonePortrait() {
  const [value, setValue] = useState(matchesPhonePortrait);
  useEffect(() => {
    const sync = () => setValue(matchesPhonePortrait());
    const query = window.matchMedia(PHONE_PORTRAIT_QUERY);
    query.addEventListener("change", sync);
    window.addEventListener("resize", sync);
    window.addEventListener("orientationchange", sync);
    return () => {
      query.removeEventListener("change", sync);
      window.removeEventListener("resize", sync);
      window.removeEventListener("orientationchange", sync);
    };
  }, []);
  return value;
}

export function MobileLandscapeShell({ children }: { children: ReactNode }) {
  const location = useCurrentLocation();
  const pathname = location.split(/[?#]/)[0];
  const frameRef = useRef<HTMLDivElement>(null);

  const active = usePhonePortrait() && !PORTRAIT_ONLY_PATHS.includes(pathname);
  const activeKey = active ? "1" : "0";

  /**
   * 激活时把真正生效的滚动容器（frame）滚回顶部。
   *
   * <p>Hub 的 {@code navigate()} 只调用 {@code window.scrollTo}，而 forced landscape 下
   * 根节点 {@code overflow: hidden}，真正滚动的是 shell frame；不复位会让新页面继承
   * 上一页的 scrollTop。deps 同时覆盖「路由变化」与「旋转状态变化」。</p>
   */
  useLayoutEffect(() => {
    if (activeKey !== "1") return;
    // jsdom 等非浏览器环境没有 Element.scrollTo，缺失时静默跳过。
    frameRef.current?.scrollTo?.({ top: 0, left: 0 });
  }, [location, activeKey]);

  /**
   * 根节点 class 在 useLayoutEffect 中同步切换。
   *
   * <p>不放在 render 阶段：render 里改 document.documentElement 属于 render side effect，
   * 会在 StrictMode 双渲染下重复执行，也破坏 render 的可中断/可丢弃语义。</p>
   *
   * <p>useLayoutEffect 在 DOM 变更后、浏览器 paint 前同步执行，因此第一次 paint 拿到的
   * 就已经是最终几何，不会出现「先竖屏再旋转」的闪烁。cleanup 只负责移除 class，
   * 与下一次 effect 的 toggle 幂等，不会让 class 残留。</p>
   */
  useLayoutEffect(() => {
    const root = document.documentElement;
    root.classList.toggle(MOBILE_LANDSCAPE_CLASS, activeKey === "1");
    return () => root.classList.remove(MOBILE_LANDSCAPE_CLASS);
  }, [activeKey]);

  return (
    <div className="mobile-landscape-frame" ref={frameRef}>
      {children}
    </div>
  );
}
