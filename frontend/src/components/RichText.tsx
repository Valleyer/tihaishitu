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

const inequalityCommand = /\\(?:neq|ne)(?![A-Za-z])/g;

function normalizeMathValue(value: string) {
  return value.replace(/\\frac(?![A-Za-z])/g, "\\dfrac");
}

/**
 * 仅规范 remark-math 已识别出的数学节点：
 * - \frac 按 \dfrac 渲染，统一题干 / 解析 / 选项的分式尺寸；
 * - inline math 中的 \ne / \neq 不再交给 KaTeX 画组合符号，而是把数学节点拆开，
 *   在两个数学片段之间插入普通文本 Unicode “≠”。这样最终使用页面正文字体的
 *   单字符不等号，不会再显示成视觉上类似 “/=” 的 KaTeX 组合字形。
 * 不改数据库原文，也不触碰代码块或普通 Markdown 文本。
 */
function remarkNormalizeMath() {
  return (tree: MarkdownNode) => {
    const visit = (node: MarkdownNode) => {
      if (!node.children) {
        if (
          node.type === "math" &&
          typeof node.value === "string"
        ) {
          node.value = normalizeMathValue(node.value);
        }
        return;
      }

      const nextChildren: MarkdownNode[] = [];
      for (const child of node.children) {
        if (
          child.type === "inlineMath" &&
          typeof child.value === "string" &&
          inequalityCommand.test(child.value)
        ) {
          inequalityCommand.lastIndex = 0;
          const parts = child.value.split(inequalityCommand);
          parts.forEach((part, index) => {
            if (part) {
              nextChildren.push({
                type: "inlineMath",
                value: normalizeMathValue(part),
              });
            }
            if (index < parts.length - 1) {
              nextChildren.push({ type: "text", value: " ≠ " });
            }
          });
          continue;
        }

        inequalityCommand.lastIndex = 0;
        if (
          (child.type === "math" || child.type === "inlineMath") &&
          typeof child.value === "string"
        ) {
          child.value = normalizeMathValue(child.value);
        }
        visit(child);
        nextChildren.push(child);
      }
      node.children = nextChildren;
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
