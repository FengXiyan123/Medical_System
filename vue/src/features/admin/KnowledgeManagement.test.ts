import { describe, expect, it } from "vitest";
import source from "./KnowledgeManagement.vue?raw";

describe("KnowledgeManagement", () => {
  it("explains the publication handoff after a completed ingestion", () => {
    expect(source).toContain('class="publication-guide"');
    expect(source).toContain("发布版本");
    expect(source).toContain("选择一个可发布版本");
    expect(source).toContain("立即处理此文档");
  });

  it("keeps document selection and long chunk previews in bounded scrollers", () => {
    expect(source).toContain('height:min(500px,52vh)');
    expect(source).toContain('max-height:min(520px,60vh)');
  });
});
