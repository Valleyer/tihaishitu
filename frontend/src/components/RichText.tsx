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
    // 统一使用 display-style 分式，但只影响渲染，不修改数据库原文。
    .replace(/\\frac(?![A-Za-z])/g, "\\dfrac")
    // 历史解析里常见 \\text{ \\mu s} / \\mu s 写法；产品约定最终显示为 us。
    .replace(/\\text\{\s*\\mu\s+s\s*\}/g, "\\text{us}")
    .replace(/\\mu\s+s(?![A-Za-z])/g, "\\mathrm{us}");
}

/**
 * 统一 Markdown + LaTeX 渲染兼容层。
 *
 * 标准 LaTeX（包括 \\ne / \\neq、嵌套公式、operatorname、上下标等）保持原样交给
 * remark-math + KaTeX，不再把数学公式拆成 HTML entity，避免破坏嵌套公式。
 * 仅保留产品明确要求的展示规范：\\frac -> \\dfrac、微秒 \\mu s -> us。
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
      rehypePlugins={[[rehypeKatex, { strict: false, throwOnError: false, output: "htmlAndMathml" }]]}
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
