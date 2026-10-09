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

type MarkdownNode = {
  type?: string;
  value?: string;
  children?: MarkdownNode[];
};

function normalizeMarkdownSource(value: string) {
  return value.replace(
    /\$([^$\n]*?)\\(?:neq|ne)(?![A-Za-z])([^$\n]*?)\$/g,
    (_, left: string, right: string) => `${left}&ne;${right}`,
  );
}

function normalizeMathValue(value: string) {
  return value.replace(/\\frac(?![A-Za-z])/g, "\\dfrac");
}

/**
 * 渲染层兼容：
 * - 原 Markdown 里的 $xxx\ne xxx$ / $xxx\neq xxx$ 在进入 Markdown 解析前，
 *   直接替换成 xxx&ne;xxx；这样由 Markdown 实体解析成普通文本 “≠”，完全绕开 KaTeX。
 * - 已被 remark-math 识别的数学节点中，\frac 按 \dfrac 渲染。
 * 数据库原文和编辑框内容都不修改。
 */
function remarkNormalizeMath() {
  return (tree: MarkdownNode) => {
    const visit = (node: MarkdownNode) => {
      if (
        (node.type === "math" || node.type === "inlineMath") &&
        typeof node.value === "string"
      ) {
        node.value = normalizeMathValue(node.value);
      }
      node.children?.forEach(visit);
    };
    visit(tree);
  };
}

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
      remarkPlugins={[remarkMath, remarkNormalizeMath]}
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
