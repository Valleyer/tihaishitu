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

function normalizeMathValue(value: string) {
  return value
    .replace(/\\frac(?![A-Za-z])/g, "\\dfrac")
    .replace(/\\ne(?!q|g|arrow)/g, "\\neq ");
}

/**
 * 仅规范 remark-math 已识别出的数学节点：
 * - \frac 按 \dfrac 渲染，统一题干 / 解析 / 选项的分式尺寸；
 * - 历史题库里的 \ne 兼容成 \neq，视觉上渲染为 ≠。
 * 不改数据库原文，也不触碰代码块或普通 Markdown 文本。
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
      {children}
    </Markdown>
  );
  if (inline) return <span className={`rich-text rich-inline ${className}`}>{content}</span>;
  return (
    <div className={`rich-text ${className}`}>{content}</div>
  );
}
