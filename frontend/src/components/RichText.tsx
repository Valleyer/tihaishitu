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

type RichSegment =
  | { type: "markdown"; value: string }
  | { type: "table"; header: string[]; rows: string[][] };

function splitTableRow(line: string) {
  const text = line.trim().replace(/^\|/, "").replace(/\|$/, "");
  const cells: string[] = [];
  let current = "";
  for (let index = 0; index < text.length; index += 1) {
    const char = text[index];
    if (char === "|" && text[index - 1] !== "\\") {
      cells.push(current.trim());
      current = "";
    } else {
      current += char;
    }
  }
  cells.push(current.trim());
  return cells;
}

function isTableDivider(line: string) {
  if (!line.includes("|")) return false;
  const cells = splitTableRow(line);
  return cells.length > 1 && cells.every((cell) => /^:?-{3,}:?$/.test(cell.replace(/\s+/g, "")));
}

/**
 * 解析最常用的 GFM pipe table。只识别“表头 + --- 分隔行 + 数据行”，
 * 其它 Markdown 原样交给 react-markdown；单元格仍继续支持 Markdown + LaTeX。
 */
function markdownSegments(value: string): RichSegment[] {
  const lines = value.split("\n");
  const segments: RichSegment[] = [];
  let markdown: string[] = [];
  const flush = () => {
    if (markdown.length) {
      segments.push({ type: "markdown", value: markdown.join("\n") });
      markdown = [];
    }
  };
  for (let index = 0; index < lines.length;) {
    const headerLine = lines[index] ?? "";
    const dividerLine = lines[index + 1] ?? "";
    if (headerLine.includes("|") && isTableDivider(dividerLine)) {
      const header = splitTableRow(headerLine);
      const divider = splitTableRow(dividerLine);
      if (header.length === divider.length) {
        flush();
        const rows: string[][] = [];
        index += 2;
        while (index < lines.length && lines[index].trim() && lines[index].includes("|")) {
          const row = splitTableRow(lines[index]);
          rows.push([...row, ...Array(Math.max(0, header.length - row.length)).fill("")].slice(0, header.length));
          index += 1;
        }
        segments.push({ type: "table", header, rows });
        continue;
      }
    }
    markdown.push(headerLine);
    index += 1;
  }
  flush();
  return segments;
}

function normalizeMarkdownSource(value: string) {
  return value
    // 统一使用 display-style 分式，但只影响渲染，不修改数据库原文。
    .replace(/\\frac(?![A-Za-z])/g, "\\dfrac")
    // 给根式增加一个仅影响高度、不占宽度的 invisible strut。
    // KaTeX 会据此选择稍高的根号，使 x^2 这类上标与根号横线保持更舒服的留白。
    // 同时兼容 \\sqrt{...} 与 \\sqrt[n]{...}，不改变数据库原文。
    .replace(
      /\\sqrt(?:\s*\[[^\]\n]*\])?\s*\{/g,
      (match) => `${match}\\vphantom{\\rule{0pt}{1.15em}}`,
    )
    // 历史解析里常见 \\text{ \\mu s} / \\mu s 写法；产品约定最终显示为 us。
    .replace(/\\text\{\s*\\mu\s+s\s*\}/g, "\\text{us}")
    .replace(/\\mu\s+s(?![A-Za-z])/g, "\\mathrm{us}");
}

/**
 * 统一 Markdown + LaTeX 渲染兼容层。
 *
 * 标准 LaTeX（包括 \\ne / \\neq、嵌套公式、operatorname、上下标等）保持原样交给
 * remark-math + KaTeX，不再把数学公式拆成 HTML entity，避免破坏嵌套公式。
 * 仅保留产品明确要求的展示规范：\\frac -> \\dfrac、根号顶部留白、微秒 \\mu s -> us。
 * 数据库原文和编辑框内容都不修改。
 */

const katexPlugin = [rehypeKatex, {
  strict: false,
  throwOnError: false,
  output: "htmlAndMathml",
  macros: {
    "\\ne": "\\mathrel{\\char\"2260}",
    "\\neq": "\\mathrel{\\char\"2260}",
  },
}] as const;

function MarkdownFragment({ value, inline = false }: { value: string; inline?: boolean }) {
  return <Markdown
    remarkPlugins={[remarkMath]}
    rehypePlugins={[katexPlugin]}
    components={inline ? { p: ({ children }) => <span>{children}</span> } : undefined}
  >{value}</Markdown>;
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
  const normalized = normalizeMarkdownSource(children);
  if (inline) {
    return <span className={`rich-text rich-inline ${className}`}><MarkdownFragment value={normalized} inline /></span>;
  }
  return <div className={`rich-text ${className}`}>{markdownSegments(normalized).map((segment, index) =>
    segment.type === "markdown"
      ? <MarkdownFragment key={`markdown-${index}`} value={segment.value} />
      : <div className="rich-table-scroll" key={`table-${index}`}><table>
          <thead><tr>{segment.header.map((cell, cellIndex) => <th key={cellIndex}><MarkdownFragment value={cell} inline /></th>)}</tr></thead>
          <tbody>{segment.rows.map((row, rowIndex) => <tr key={rowIndex}>{row.map((cell, cellIndex) => <td key={cellIndex}><MarkdownFragment value={cell} inline /></td>)}</tr>)}</tbody>
        </table></div>
  )}</div>;
}
