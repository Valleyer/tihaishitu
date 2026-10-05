import { useEffect, useState, type AnchorHTMLAttributes, type MouseEvent } from "react";

const INTERNAL_PREFIXES = [
  "/study", "/progress", "/statistics", "/books", "/knowledge/",
  "/questions/", "/wrong-questions", "/practice/", "/account",
];

export function isHubPath(path: string) {
  const pathname = new URL(path, window.location.origin).pathname;
  return pathname === "/" || INTERNAL_PREFIXES.some(prefix =>
    prefix.endsWith("/") ? pathname.startsWith(prefix) : pathname === prefix || pathname.startsWith(`${prefix}/`));
}

export function navigate(path: string) {
  if (!isHubPath(path)) {
    window.location.assign(path);
    return;
  }
  const target = new URL(path, window.location.origin);
  if (`${target.pathname}${target.search}${target.hash}` === `${location.pathname}${location.search}${location.hash}`) return;
  history.pushState(null, "", target);
  window.dispatchEvent(new Event("hub:navigate"));
  window.scrollTo({ top: 0 });
}

export function useCurrentLocation() {
  const current = () => `${window.location.pathname}${window.location.search}${window.location.hash}`;
  const [value, setValue] = useState(current);
  useEffect(() => {
    const sync = () => setValue(current());
    window.addEventListener("popstate", sync);
    window.addEventListener("hub:navigate", sync);
    return () => {
      window.removeEventListener("popstate", sync);
      window.removeEventListener("hub:navigate", sync);
    };
  }, []);
  return value;
}

export function HubLink({ href = "", onClick, ...props }: AnchorHTMLAttributes<HTMLAnchorElement>) {
  const click = (event: MouseEvent<HTMLAnchorElement>) => {
    onClick?.(event);
    if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
    if (href && isHubPath(href)) {
      event.preventDefault();
      navigate(href);
    }
  };
  return <a {...props} href={href} onClick={click} />;
}
