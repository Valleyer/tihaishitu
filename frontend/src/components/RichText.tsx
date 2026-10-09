/**
 * 统一卷面富文本渲染器。
 *
 * 题库与后端只保存 Markdown + LaTeX 文本；这里负责展示。react-markdown
 * 默认不执行原始 HTML，因此用户导入的题目不能借题干注入脚本。
 */
import Markdown from "react-markdown";
import remarkMath from "remark-math";
import rehypeKatex from "rehype-katex";
import "katex/dist/katex.min.css";

function normalizeMarkdownSource(value: string) {
  return value
    // 先在原始 Markdown 上直接把 \frac 改成 \dfrac，确保 remark-math / KaTeX
    // 从一开始拿到的就是 \dfrac，而不是依赖后续 AST 再改写。
    .replace(/\\frac(?![A-Za-z])/g, "\\dfrac")
    .replace(
      /\$([^$\n]*?)\\(?:neq|ne)(?![A-Za-z])([^$\n]*?)\$/g,
      (_, left: string, right: string) => `${left}&ne;${right}`,
    );
}

/**
 * 渲染层兼容：
 * - 原 Markdown 里的 \frac 在进入 Markdown / LaTeX 解析前直接改为 \dfrac；
 * - $xxx\ne xxx$ / $xxx\neq xxx$ 在进入 Markdown 解析前直接改成 xxx&ne;xxx。
 * 数据库原文和编辑框内容都不修改。
 */

export function RichText({
  children,
  className = "",
  inline = false,
}: {
  children: string;
  className?: string;
  inline?: boolean;
}) {
  const content = (
    <Markdown
      remarkPlugins={[remarkMath]}
      rehypePlugins={[rehypeKatex]}
      components={inline ? { p: ({ children }) => <span>{children}</span> } : undefined}
    >
      {normalizeMarkdownSource(children)}
    </Markdown>
  );
  if (inline) return <span className={`rich-text rich-inline ${className}`}>{content}</span>;
  return (
    <div className={`rich-text ${className}`}>{content}</div>
  );
}
