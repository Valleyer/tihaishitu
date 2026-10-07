// @vitest-environment jsdom

import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { QuestionPanel } from "./QuestionPanel";
import { examMetadataView } from "../utils/examMetadata";
import type { Attempt } from "../domain/types";

const point = (id: string, name: string) => ({
  id, name, subject: "数学一", category: "", description: `${name}说明`,
  explanation: `${name}解析`, prerequisites: [], tags: [],
});

const attempt = (): Attempt => ({
  id: "attempt",
  targetKnowledgePointId: "k1",
  targetKnowledgePointName: "数列极限计算",
  question: {
    id: "q1", subject: "数学一", category: "real_exam", chapter: "2022年全国硕士研究生招生考试数学一",
    type: "true_false", presentationType: "true_false", gradingMode: "auto",
    question: "题干", options: { true: "正确", false: "错误" },
    difficulty: 2, frequency: 3, tags: [], enabled: true,
    knowledgePointIds: ["k1", "k2"],
    knowledgePoints: [point("k1", "数列极限计算"), point("k2", "函数奇偶性、周期性与单调性")],
    // 后端在发题时冻结进 attempt snapshot 的题面 metadata（Hub 与 World 共用同一结构）。
    examMetadata: {
      subjectName: "数学一",
      sourceName: "2022年全国硕士研究生招生考试数学一",
      examYear: 2022,
      questionNumber: "2022-3",
      displayQuestionNumber: "3",
      examLabel: "2022年考研数学一真题",
      knowledgePoints: [
        { id: "k1", name: "数列极限计算", role: "core" },
        { id: "k2", name: "函数奇偶性、周期性与单调性", role: "auxiliary" },
      ],
    },
  },
  scene: {
    id: "scene", title: "读书", location: "青溪县", speaker: "先生", role: "师长",
    text: "开卷", dialogue: "读", task: "读书", npcId: "npc", success: "", failure: "",
  },
  result: null,
  review: false,
});

afterEach(cleanup);

const noop = () => undefined;

describe("World question panel exam metadata", () => {
  it("renders the exam label and the display question number", () => {
    const view = render(
      <QuestionPanel attempt={attempt()} busy={false} submit={noop} reveal={noop} assess={noop}
        next={noop} note="" showNote={noop} eventPending={false} reviewOnly={false}
        setReview={noop} onEvent={noop} />,
    );
    expect(screen.getByText("2022年考研数学一真题")).toBeTruthy();
    expect(screen.getByText("第3题")).toBeTruthy();
    // displayQuestionNumber 已剥离年份前缀，不能再出现 `第2022-3题`。
    expect(screen.queryByText(/第2022-3题/)).toBeNull();
    expect(view.container.querySelector(".exam-source-row .exam-source")?.textContent)
      .toBe("2022年全国硕士研究生招生考试数学一");
    expect(view.container.querySelectorAll(".exam-source-row .exam-label")).toHaveLength(1);
  });

  it("renders every knowledge point tag with its role", () => {
    const view = render(
      <QuestionPanel attempt={attempt()} busy={false} submit={noop} reveal={noop} assess={noop}
        next={noop} note="" showNote={noop} eventPending={false} reviewOnly={false}
        setReview={noop} onEvent={noop} />,
    );
    const tags = view.container.querySelectorAll(".knowledge-ribbon button.knowledge-tag");
    expect(tags).toHaveLength(2);
    const core = screen.getByText("数列极限计算");
    const auxiliary = screen.getByText("函数奇偶性、周期性与单调性");
    expect(core.classList.contains("core")).toBe(true);
    expect(auxiliary.classList.contains("auxiliary")).toBe(true);
  });

  it("keeps the panel usable when metadata is absent", () => {
    const bare = attempt();
    delete (bare.question as { examMetadata?: unknown }).examMetadata;
    const view = render(
      <QuestionPanel attempt={bare} busy={false} submit={noop} reveal={noop} assess={noop}
        next={noop} note="" showNote={noop} eventPending={false} reviewOnly={false}
        setReview={noop} onEvent={noop} />,
    );
    expect(view.container.querySelector(".exam-source-row")).toBeNull();
    expect(screen.getByText("题干")).toBeTruthy();
  });

  it("shows solution content as one reference analysis without a separate standard answer", () => {
    const solution = attempt();
    solution.question.type = "solution";
    solution.question.presentationType = "self_assessment";
    solution.question.gradingMode = "self_assessment";
    solution.question.options = {};
    solution.reveal = { explanation: "## 答案与解析\n\n完整过程", knowledgePoints: solution.question.knowledgePoints };
    render(
      <QuestionPanel attempt={solution} busy={false} submit={noop} reveal={noop} assess={noop}
        next={noop} note="" showNote={noop} eventPending={false} reviewOnly={false}
        setReview={noop} onEvent={noop} />,
    );
    expect(screen.getByText("参考解析")).toBeTruthy();
    expect(screen.queryByText("参考解答")).toBeNull();
    expect(screen.queryByText("解题分析")).toBeNull();
  });

  it("builds the title from displayQuestionNumber instead of the raw value", () => {
    expect(examMetadataView({
      examLabel: "2014年408考研真题", displayQuestionNumber: "1",
      questionNumber: "2014-1", sourceName: "2014年…",
    })?.examTitle).toBe("2014年408考研真题 · 第1题");
    expect(examMetadataView({ examLabel: "2022年考研数学一真题" })?.examTitle)
      .toBe("2022年考研数学一真题");
    expect(examMetadataView({ displayQuestionNumber: "3" })?.examTitle).toBe("第3题");
    expect(examMetadataView(undefined)).toBeUndefined();
  });
});
