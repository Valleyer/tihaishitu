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

type MarkdownMathNode = {
  type?: string;
  value?: string;
  children?: MarkdownMathNode[];
};

const SIMPLE_DIVISION_ATOM =
  String.raw`(?:\\[A-Za-z]+(?:\{[^{}]*\})?|(?:\d+(?:\.\d+)?|[A-Za-z]+)(?:[_^](?:\{[^{}]*\}|[A-Za-z0-9]+))*|\([^()]*\)|\{[^{}]*\})`;
const SIMPLE_DIVISION_RE = new RegExp(
  `(${SIMPLE_DIVISION_ATOM})\\s*\\/\\s*(${SIMPLE_DIVISION_ATOM})`,
  "g",
);
const PROTECTED_MATH_TEXT_RE = /\\(?:text|mathrm|operatorname)\{[^{}]*\}/g;

function normalizeSimpleMathDivisions(value: string) {
  const protectedSegments: string[] = [];
  const masked = value.replace(PROTECTED_MATH_TEXT_RE, (segment) => {
    const index = protectedSegments.push(segment) - 1;
    return `@@RICH_TEXT_PROTECTED_${index}@@`;
  });

  const normalized = masked.replace(
    SIMPLE_DIVISION_RE,
    (_, numerator: string, denominator: string) =>
      `\\dfrac{${numerator}}{${denominator}}`,
  );

  return normalized.replace(
    /@@RICH_TEXT_PROTECTED_(\d+)@@/g,
    (_, index: string) => protectedSegments[Number(index)] ?? "",
  );
}

/**
 * 只在 remark-math 已识别出的数学节点中，把“简单除法语法”转成上下分式：
 *   $\\pi/4$     -> $\\dfrac{\\pi}{4}$
 *   $a_n/b_n$     -> $\\dfrac{a_n}{b_n}$
 *   $12144/1285.6$ -> $\\dfrac{12144}{1285.6}$
 *
 * 普通 Markdown 文本不会处理；\\text{m/s}、\\mathrm{m/s}、
 * \\operatorname{...} 内的斜杠也保持原样，避免把单位或说明文字误当成除法。
 */
function remarkNormalizeMathDivisions() {
  return (tree: MarkdownMathNode) => {
    const visit = (node: MarkdownMathNode) => {
      if (
        (node.type === "math" || node.type === "inlineMath") &&
        typeof node.value === "string"
      ) {
        node.value = normalizeSimpleMathDivisions(node.value);
      }
      node.children?.forEach(visit);
    };
    visit(tree);
  };
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
 * 仅保留产品明确要求的展示规范：\\frac -> \\dfrac、简单 a/b 除法 -> 上下分式、根号顶部留白、微秒 \\mu s -> us。
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
      remarkPlugins={[remarkMath, remarkNormalizeMathDivisions]}
      rehypePlugins={[[rehypeKatex, {
        strict: false,
        throwOnError: false,
        output: "htmlAndMathml",
        // KaTeX 默认的 \ne / \neq 在当前字体组合下视觉上接近 "/="。
        // 不把 Unicode 字符直接塞进 \\text{}（KaTeX 会把它判成无效输入）；
        // 改用 KaTeX 官方支持的 \\char 十六进制字符写法，再声明为关系运算符，
        // 因此既得到单字符“≠”，也保留数学关系符应有的左右间距。
        macros: {
          "\\ne": "\\mathrel{\\char\"2260}",
          "\\neq": "\\mathrel{\\char\"2260}",
        },
      }]]}
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
