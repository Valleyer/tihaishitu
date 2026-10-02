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

export function RichText({
  children,
  className = "",
  inline = false,
}: {
  children: string;
  className?: string;
  inline?: boolean;
}) {
  return (
    <div className={`rich-text ${inline ? "rich-inline" : ""} ${className}`}>
      <Markdown remarkPlugins={[remarkMath]} rehypePlugins={[rehypeKatex]}>
        {children}
      </Markdown>
    </div>
  );
}
