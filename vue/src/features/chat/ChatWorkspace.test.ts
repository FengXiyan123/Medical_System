import { describe, expect, it } from "vitest";
import source from "./ChatWorkspace.vue?raw";

describe("ChatWorkspace", () => {
  it("presents a first-use empty state and keeps execution mode choices above the composer", () => {
    expect(source).toContain('class="chat-empty-state"');
    expect(source).toContain("创建第一条会话");
    expect(source.indexOf('class="mode-rail"')).toBeLessThan(source.indexOf('class="input-frame"'));
    expect(source).toContain("暂无已发布知识库");
  });

  it("uses one clear new-conversation action and keeps messages inside the workspace scroller", () => {
    expect(source).toContain('class="new-conversation"');
    expect(source).not.toContain('class="icon-action"');
    expect(source).toContain('height:100%; min-height:0;');
    expect(source).toContain('.conversation-items { display:grid; flex:1; min-height:0; align-content:start;');
    expect(source).toContain('grid-auto-rows:max-content;');
  });
});
